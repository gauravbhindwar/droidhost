package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/droidhost/vm-agent/internal/dockerapi"
	"github.com/droidhost/vm-agent/internal/metrics"
	"github.com/droidhost/vm-agent/internal/terminal"
)

type Config struct {
	Token     string
	Docker    *dockerapi.Client
	Metrics   *metrics.Collector
	Terminal  *terminal.Server
	StartedAt time.Time
}
type Server struct {
	config Config
	mux    *http.ServeMux
}

func NewServer(config Config) *Server {
	s := &Server{config: config, mux: http.NewServeMux()}
	s.routes()
	return s
}
func (s *Server) Handler() http.Handler { return s.auth(s.mux) }
func (s *Server) routes() {
	s.mux.HandleFunc("/health", s.health)
	s.mux.HandleFunc("/v1/metrics", s.metrics)
	s.mux.HandleFunc("/v1/containers", s.containers)
	s.mux.HandleFunc("/v1/containers/", s.container)
	s.mux.HandleFunc("/v1/images", s.images)
	s.mux.HandleFunc("/v1/volumes", s.volumes)
	s.mux.HandleFunc("/v1/networks", s.networks)
	s.mux.HandleFunc("/v1/terminal", s.terminal)
}
func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			next.ServeHTTP(w, r)
			return
		}
		expected := "Bearer " + s.config.Token
		if r.Header.Get("Authorization") != expected {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}
func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "service": "vm-agent"})
}
func (s *Server) metrics(w http.ResponseWriter, r *http.Request) {
	snapshot, err := s.config.Metrics.Snapshot(r.Context())
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, snapshot)
}
func (s *Server) containers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	all := r.URL.Query().Get("all") == "1"
	result, err := s.config.Docker.ListContainers(r.Context(), all)
	if err != nil {
		writeJSON(w, 200, []dockerapi.Container{})
		return
	}
	writeJSON(w, 200, result)
}
func (s *Server) container(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) < 3 {
		http.NotFound(w, r)
		return
	}
	id, action := parts[2], "inspect"
	if len(parts) > 3 {
		action = parts[3]
	}
	var err error
	switch action {
	case "inspect":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", 405)
			return
		}
		result, e := s.config.Docker.InspectContainer(r.Context(), id)
		if e != nil {
			writeError(w, e)
			return
		}
		writeJSON(w, 200, result)
		return
	case "logs":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", 405)
			return
		}
		result, e := s.config.Docker.ContainerLogs(r.Context(), id, 500)
		if e != nil {
			writeError(w, e)
			return
		}
		writeJSON(w, 200, map[string]string{"logs": result})
		return
	case "stats":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", 405)
			return
		}
		result, e := s.config.Docker.Stats(r.Context(), id)
		if e != nil {
			writeError(w, e)
			return
		}
		writeJSON(w, 200, result)
		return
	case "start":
		err = s.config.Docker.StartContainer(r.Context(), id)
	case "stop":
		err = s.config.Docker.StopContainer(r.Context(), id, 10)
	case "restart":
		err = s.config.Docker.RestartContainer(r.Context(), id, 10)
	case "remove":
		err = s.config.Docker.RemoveContainer(r.Context(), id, r.URL.Query().Get("force") == "1", false)
	default:
		http.NotFound(w, r)
		return
	}
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, 200, map[string]string{"status": "ok"})
}

func (s *Server) images(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	result, err := s.config.Docker.Images(r.Context())
	if err != nil {
		writeJSON(w, 200, []dockerapi.Image{})
		return
	}
	writeJSON(w, 200, result)
}
func (s *Server) volumes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	result, err := s.config.Docker.Volumes(r.Context())
	if err != nil {
		writeJSON(w, 200, []dockerapi.Volume{})
		return
	}
	writeJSON(w, 200, result)
}
func (s *Server) networks(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	result, err := s.config.Docker.Networks(r.Context())
	if err != nil {
		writeJSON(w, 200, []dockerapi.Network{})
		return
	}
	writeJSON(w, 200, result)
}
func (s *Server) terminal(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	if err := s.config.Terminal.Handler(r.Context(), w, r); err != nil {
		return
	}
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func writeError(w http.ResponseWriter, err error) {
	status := http.StatusBadGateway
	if dockerapi.NotFound(err) {
		status = http.StatusNotFound
	}
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

var _ context.Context
