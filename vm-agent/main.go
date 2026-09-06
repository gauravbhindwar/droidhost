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
	"strings"
	"syscall"
	"time"

	"github.com/droidhost/vm-agent/internal/api"
	"github.com/droidhost/vm-agent/internal/dns"
	"github.com/droidhost/vm-agent/internal/dockerapi"
	"github.com/droidhost/vm-agent/internal/metrics"
	"github.com/droidhost/vm-agent/internal/terminal"
)

func main() {
	defaultShell := "/bin/sh"
	if _, err := os.Stat("/bin/bash"); err == nil {
		defaultShell = "/bin/bash"
	}
	var (
		addr        = flag.String("addr", envOr("VM_AGENT_ADDR", "0.0.0.0:8899"), "listen address")
		tokenFlag   = flag.String("token", "", "bearer token required for all requests")
		dockerHost  = flag.String("docker-host", envOr("DOCKER_HOST", "unix:///var/run/docker.sock"), "docker engine endpoint")
		shell       = flag.String("shell", envOr("VM_AGENT_SHELL", defaultShell), "shell used for PTY terminal sessions")
		startedAt   = time.Now()
		readTimeout = 60 * time.Second
	)
	flag.Parse()

	token := resolveToken(*tokenFlag)
	if token == "" {
		log.Fatal("vm-agent: no bearer token provided; refusing to start unauthenticated (configure via QEMU fw_cfg, cmdline, /etc/droidhost/agent-token, or VM_AGENT_TOKEN)")
	}

	appCtx, cancelApp := context.WithCancel(context.Background())
	defer cancelApp()

	// Start built-in DNS-over-HTTPS (DoH) resolver on 127.0.0.1:53
	go dns.StartDohProxy(appCtx)

	docker := dockerapi.New(*dockerHost)
	collector := metrics.NewCollector(startedAt)
	term := terminal.NewServer(*shell)

	server := api.NewServer(api.Config{
		Token:     token,
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
		log.Printf("vm-agent: listening on %s (docker=%s, auth=bearer, token_len=%d)", *addr, *dockerHost, len(token))
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

func resolveToken(flagVal string) string {
	if flagVal = strings.TrimSpace(flagVal); flagVal != "" {
		return flagVal
	}
	if envVal := strings.TrimSpace(os.Getenv("VM_AGENT_TOKEN")); envVal != "" {
		return envVal
	}
	// QEMU fw_cfg injected value: -fw_cfg name=opt/droidhost/token,string=...
	if fwData, err := os.ReadFile("/sys/firmware/qemu_fw_cfg/by_name/opt/droidhost/token/raw"); err == nil {
		if t := strings.TrimSpace(string(fwData)); t != "" {
			return t
		}
	}
	// Kernel cmdline parameter: droidhost.token=<token>
	if cmdline, err := os.ReadFile("/proc/cmdline"); err == nil {
		fields := strings.Fields(string(cmdline))
		for _, f := range fields {
			if strings.HasPrefix(f, "droidhost.token=") {
				if t := strings.TrimSpace(strings.TrimPrefix(f, "droidhost.token=")); t != "" {
					return t
				}
			}
		}
	}
	// Token file in /etc/droidhost/agent-token
	if fileData, err := os.ReadFile("/etc/droidhost/agent-token"); err == nil {
		if t := strings.TrimSpace(string(fileData)); t != "" {
			return t
		}
	}
	return ""
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
