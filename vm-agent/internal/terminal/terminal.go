package terminal

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/creack/pty"
	"github.com/gorilla/websocket"
)

type SessionInfo struct {
	ID        string `json:"id"`
	Shell     string `json:"shell"`
	Title     string `json:"title"`
	CreatedAt int64  `json:"createdAt"`
	Active    bool   `json:"active"`
}

type Session struct {
	ID         string
	Shell      string
	Title      string
	CreatedAt  time.Time
	LastActive time.Time

	cmd  *exec.Cmd
	ptmx *os.File

	mu         sync.Mutex
	closed     bool
	conns      map[*websocket.Conn]bool
	history    []byte
	historyMax int
}

func (sess *Session) Close() {
	sess.mu.Lock()
	if sess.closed {
		sess.mu.Unlock()
		return
	}
	sess.closed = true
	for conn := range sess.conns {
		_ = conn.Close()
	}
	sess.conns = make(map[*websocket.Conn]bool)
	if sess.ptmx != nil {
		_ = sess.ptmx.Close()
	}
	if sess.cmd != nil && sess.cmd.Process != nil {
		_ = sess.cmd.Process.Kill()
	}
	sess.mu.Unlock()
}

type Server struct {
	defaultShell string
	upgrader     websocket.Upgrader

	mu       sync.RWMutex
	sessions map[string]*Session
	seq      int
}

func NewServer(defaultShell string) *Server {
	s := &Server{
		defaultShell: defaultShell,
		upgrader: websocket.Upgrader{
			CheckOrigin: func(*http.Request) bool { return true },
		},
		sessions: make(map[string]*Session),
	}
	// Background reaper: reap closed or inactive sessions older than 30 minutes
	go s.reaper(10 * time.Minute)
	return s
}

func (s *Server) reaper(interval time.Duration) {
	ticker := time.NewTicker(interval)
	for range ticker.C {
		s.mu.Lock()
		now := time.Now()
		for id, sess := range s.sessions {
			sess.mu.Lock()
			isIdle := len(sess.conns) == 0 && now.Sub(sess.LastActive) > 30*time.Minute
			isClosed := sess.closed
			sess.mu.Unlock()
			if isIdle || isClosed {
				sess.Close()
				delete(s.sessions, id)
			}
		}
		s.mu.Unlock()
	}
}

func (s *Server) GetOrCreateSession(id string, shell string, title string) (*Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if id == "" {
		id = "1"
	}

	if sess, ok := s.sessions[id]; ok {
		sess.mu.Lock()
		closed := sess.closed
		sess.mu.Unlock()
		if !closed {
			return sess, nil
		}
	}

	if shell == "" {
		shell = s.defaultShell
	}
	if title == "" {
		title = fmt.Sprintf("Terminal %s", id)
	}

	sess, err := s.startSession(id, shell, title)
	if err != nil {
		return nil, err
	}
	s.sessions[id] = sess
	return sess, nil
}

func (s *Server) startSession(id string, shell string, title string) (*Session, error) {
	var cmd *exec.Cmd
	if strings.HasSuffix(shell, ".sh") {
		cmd = exec.Command("/system/bin/sh", shell, "-i")
	} else {
		parts := strings.Fields(shell)
		if len(parts) > 1 {
			cmd = exec.Command(parts[0], append(parts[1:], "-i")...)
		} else {
			cmd = exec.Command(shell, "-i")
		}
	}

	env := []string{
		"TERM=xterm-256color",
		"HOME=/root",
		"USER=root",
		"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
	}
	// Never pass BASH_ENV or ENV to /bin/sh to prevent syntax errors on ash
	if strings.Contains(shell, "bash") {
		env = append(env, "BASH_ENV=/etc/bash/bashrc")
	}
	cmd.Env = append(cmd.Environ(), env...)

	ptmx, err := pty.Start(cmd)
	if err != nil {
		return nil, err
	}

	sess := &Session{
		ID:         id,
		Shell:      shell,
		Title:      title,
		CreatedAt:  time.Now(),
		LastActive: time.Now(),
		cmd:        cmd,
		ptmx:       ptmx,
		conns:      make(map[*websocket.Conn]bool),
		historyMax: 64 * 1024, // 64KB history ring buffer
	}

	// Read PTY output and broadcast to all connected WebSocket clients
	go func() {
		buf := make([]byte, 8192)
		for {
			n, rErr := ptmx.Read(buf)
			if n > 0 {
				chunk := buf[:n]
				sess.mu.Lock()
				// Append to history buffer
				sess.history = append(sess.history, chunk...)
				if len(sess.history) > sess.historyMax {
					sess.history = sess.history[len(sess.history)-sess.historyMax:]
				}
				sess.LastActive = time.Now()

				// Broadcast to all active WebSocket connections
				for conn := range sess.conns {
					_ = conn.WriteMessage(websocket.BinaryMessage, chunk)
				}
				sess.mu.Unlock()
			}
			if rErr != nil {
				sess.Close()
				return
			}
		}
	}()

	return sess, nil
}

func (s *Server) ListSessions() []SessionInfo {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]SessionInfo, 0, len(s.sessions))
	for _, sess := range s.sessions {
		sess.mu.Lock()
		active := !sess.closed && len(sess.conns) > 0
		info := SessionInfo{
			ID:        sess.ID,
			Shell:     sess.Shell,
			Title:     sess.Title,
			CreatedAt: sess.CreatedAt.UnixMilli(),
			Active:    active,
		}
		sess.mu.Unlock()
		result = append(result, info)
	}
	return result
}

func (s *Server) CloseSession(id string) bool {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	if ok {
		delete(s.sessions, id)
	}
	s.mu.Unlock()

	if ok {
		sess.Close()
		return true
	}
	return false
}

// Handler handles the WebSocket connection for a terminal session.
// Closing the WebSocket client does NOT terminate the PTY session.
func (s *Server) Handler(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
	sessionID := r.URL.Query().Get("session")
	if sessionID == "" {
		sessionID = "1"
	}

	sess, err := s.GetOrCreateSession(sessionID, s.defaultShell, "")
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return err
	}

	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return err
	}
	defer conn.Close()

	// Register connection and replay history buffer so reconnecting gets full terminal output
	sess.mu.Lock()
	sess.conns[conn] = true
	sess.LastActive = time.Now()
	if len(sess.history) > 0 {
		_ = conn.WriteMessage(websocket.BinaryMessage, sess.history)
	}
	sess.mu.Unlock()

	defer func() {
		sess.mu.Lock()
		delete(sess.conns, conn)
		sess.LastActive = time.Now()
		sess.mu.Unlock()
	}()

	// Read input from client and forward to PTY
	for {
		msgType, data, readErr := conn.ReadMessage()
		if readErr != nil {
			break
		}
		if msgType == websocket.TextMessage || msgType == websocket.BinaryMessage {
			sess.mu.Lock()
			if !sess.closed && sess.ptmx != nil {
				_, _ = sess.ptmx.Write(data)
			}
			sess.mu.Unlock()
		}
	}

	return nil
}

// ServeSessionsHTTP serves the REST endpoints for sessions:
// GET /v1/terminal/sessions -> list sessions
// POST /v1/terminal/sessions -> create session
func (s *Server) ServeSessionsHTTP(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		sessions := s.ListSessions()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(sessions)
	case http.MethodPost:
		var req struct {
			ID    string `json:"id"`
			Shell string `json:"shell"`
			Title string `json:"title"`
		}
		if r.Body != nil {
			_ = json.NewDecoder(r.Body).Decode(&req)
		}
		if req.ID == "" {
			s.mu.Lock()
			s.seq++
			req.ID = fmt.Sprintf("%d", s.seq)
			s.mu.Unlock()
		}
		sess, err := s.GetOrCreateSession(req.ID, req.Shell, req.Title)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		info := SessionInfo{
			ID:        sess.ID,
			Shell:     sess.Shell,
			Title:     sess.Title,
			CreatedAt: sess.CreatedAt.UnixMilli(),
			Active:    true,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(info)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

// ServeSessionActionHTTP handles DELETE /v1/terminal/sessions/{id}
func (s *Server) ServeSessionActionHTTP(w http.ResponseWriter, r *http.Request, id string) {
	if r.Method == http.MethodDelete {
		if s.CloseSession(id) {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"status":"ok"}`)
			return
		}
		http.NotFound(w, r)
		return
	}
	http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
}
