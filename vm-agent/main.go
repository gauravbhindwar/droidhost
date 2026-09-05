// Package main starts the droidhost vm-agent, the in-VM control service that the
// Android application communicates with. It exposes a small authenticated HTTP +
// WebSocket API over which the app manages Docker workloads, reads live system
// metrics, and opens interactive PTY terminal sessions.
//
// The agent is intended to run INSIDE the ARM64 Linux VM. It never touches the
// Android host directly; the VM is the security boundary for user workloads.
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/droidhost/vm-agent/internal/api"
	"github.com/droidhost/vm-agent/internal/dockerapi"
	"github.com/droidhost/vm-agent/internal/metrics"
	"github.com/droidhost/vm-agent/internal/terminal"
)

func main() {
	var (
		addr        = flag.String("addr", envOr("VM_AGENT_ADDR", "0.0.0.0:8899"), "listen address")
		token       = flag.String("token", os.Getenv("VM_AGENT_TOKEN"), "bearer token required for all requests")
		dockerHost  = flag.String("docker-host", envOr("DOCKER_HOST", "unix:///var/run/docker.sock"), "docker engine endpoint")
		shell       = flag.String("shell", envOr("VM_AGENT_SHELL", "/bin/sh"), "shell used for PTY terminal sessions")
		startedAt   = time.Now()
		readTimeout = 15 * time.Second
	)
	flag.Parse()

	if *token == "" {
		log.Fatal("vm-agent: a bearer token is required (set --token or VM_AGENT_TOKEN); refusing to start unauthenticated")
	}

	docker := dockerapi.New(*dockerHost)
	collector := metrics.NewCollector(startedAt)
	term := terminal.NewServer(*shell)

	server := api.NewServer(api.Config{
		Token:     *token,
		Docker:    docker,
		Metrics:   collector,
		Terminal:  term,
		StartedAt: startedAt,
	})

	httpServer := &http.Server{
		Addr:              *addr,
		Handler:           server.Handler(),
		ReadHeaderTimeout: readTimeout,
	}

	go func() {
		log.Printf("vm-agent: listening on %s (docker=%s shell=%s)", *addr, *dockerHost, *shell)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("vm-agent: server error: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	log.Print("vm-agent: shutting down")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("vm-agent: graceful shutdown failed: %v", err)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
