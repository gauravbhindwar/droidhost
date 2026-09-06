package api

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/droidhost/vm-agent/internal/dockerapi"
	"github.com/droidhost/vm-agent/internal/metrics"
	"github.com/droidhost/vm-agent/internal/terminal"
)

func newTestServer(token string) *Server {
	return NewServer(Config{
		Token:     token,
		Docker:    dockerapi.New("unix:///dev/null"),
		Metrics:   metrics.NewCollector(time.Now()),
		Terminal:  terminal.NewServer("/bin/sh"),
		StartedAt: time.Now(),
	})
}

func TestHealthUnauthenticated(t *testing.T) {
	s := newTestServer("secret-token-12345")
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()

	s.Handler().ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", w.Code)
	}
}

func TestAuthEnforcement(t *testing.T) {
	token := "secure-test-token-777"
	s := newTestServer(token)

	// 1. Missing Authorization header
	req := httptest.NewRequest(http.MethodGet, "/v1/metrics", nil)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized for missing token, got %d", w.Code)
	}

	// 2. Invalid token
	req = httptest.NewRequest(http.MethodGet, "/v1/metrics", nil)
	req.Header.Set("Authorization", "Bearer wrong-token")
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized for wrong token, got %d", w.Code)
	}

	// 3. Valid token
	req = httptest.NewRequest(http.MethodGet, "/v1/metrics", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for valid token, got %d", w.Code)
	}
}

func TestDeployPayloadValidation(t *testing.T) {
	token := "valid-token"
	s := newTestServer(token)

	// Malformed JSON should return 400 Bad Request
	req := httptest.NewRequest(http.MethodPost, "/v1/containers/deploy", bytes.NewReader([]byte("{invalid-json")))
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request for malformed JSON, got %d", w.Code)
	}
}

func TestComposeRoutes(t *testing.T) {
	token := "valid-token"
	s := newTestServer(token)

	// GET /v1/compose/projects
	req := httptest.NewRequest(http.MethodGet, "/v1/compose/projects", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for compose projects list, got %d", w.Code)
	}
}
