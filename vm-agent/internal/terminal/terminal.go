package terminal

import (
	"context"
	"net/http"
	"os/exec"
	"strings"
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
	return &Server{shell: shell, upgrader: websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}}
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
	var cmd *exec.Cmd
	if strings.HasSuffix(s.shell, ".sh") {
		cmd = exec.CommandContext(ctx, "/system/bin/sh", s.shell, "-i")
	} else {
		parts := strings.Fields(s.shell)
		if len(parts) > 1 {
			cmd = exec.CommandContext(ctx, parts[0], append(parts[1:], "-i")...)
		} else {
			cmd = exec.CommandContext(ctx, s.shell, "-i")
		}
	}
	cmd.Env = append(cmd.Environ(),
		"TERM=xterm-256color",
		"HOME=/root",
		"USER=root",
		"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin",
		"BASH_ENV=/etc/bash/bashrc",
		"ENV=/etc/bash/bashrc",
	)
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
