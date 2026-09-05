package terminal

import (
	"context"
	"net/http"
	"os/exec"
	"sync"

	"github.com/creack/pty"
	"github.com/gorilla/websocket"
)

// Server bridges a WebSocket to a shell attached to a real Linux PTY.
type Server struct {
	shell    string
	upgrader websocket.Upgrader
}

func NewServer(shell string) *Server {
	return &Server{shell: shell, upgrader: websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return false }}}
}

// Handler starts a shell inside the VM. The websocket protocol is intentionally
// tiny: client text/binary messages are stdin; server binary messages are PTY
// output. Closing either side terminates the child process.
func (s *Server) Handler(ctx context.Context, w http.ResponseWriter, r *http.Request) error {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return err
	}
	defer conn.Close()
	cmd := exec.CommandContext(ctx, s.shell, "-i")
	cmd.Env = append(cmd.Env, "TERM=xterm-256color")
	ptmx, err := pty.Start(cmd)
	if err != nil {
		return err
	}
	defer ptmx.Close()
	var once sync.Once
	closeAll := func() { once.Do(func() { _ = cmd.Process.Kill(); _ = conn.Close(); _ = ptmx.Close() }) }
	defer closeAll()
	errCh := make(chan error, 2)
	go func() {
		for {
			typ, data, e := conn.ReadMessage()
			if e != nil {
				errCh <- e
				return
			}
			if typ == websocket.TextMessage || typ == websocket.BinaryMessage {
				if _, e = ptmx.Write(data); e != nil {
					errCh <- e
					return
				}
			}
		}
	}()
	go func() {
		buf := make([]byte, 32<<10)
		for {
			n, e := ptmx.Read(buf)
			if n > 0 {
				if e = conn.WriteMessage(websocket.BinaryMessage, buf[:n]); e != nil {
					errCh <- e
					return
				}
			}
			if e != nil {
				errCh <- e
				return
			}
		}
	}()
	<-errCh
	return nil
}
