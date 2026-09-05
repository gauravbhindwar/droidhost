// Package dockerapi is a small, dependency-light client for the Docker Engine
// HTTP API. It speaks directly to the engine over a unix socket (or tcp) so the
// vm-agent can manage real containers, images, volumes and networks running
// inside the VM without pulling in the full Docker SDK.
package dockerapi

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client talks to a Docker Engine endpoint.
type Client struct {
	http    *http.Client
	baseURL string
	host    string
}

// New creates a client for the given docker host. Supported forms:
//
//	unix:///var/run/docker.sock
//	tcp://127.0.0.1:2375
//	npipe:// (unsupported, treated as unix)
func New(host string) *Client {
	c := &Client{host: host}
	switch {
	case strings.HasPrefix(host, "unix://"):
		socket := strings.TrimPrefix(host, "unix://")
		c.baseURL = "http://docker"
		c.http = &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
					var d net.Dialer
					return d.DialContext(ctx, "unix", socket)
				},
			},
		}
	case strings.HasPrefix(host, "tcp://"):
		c.baseURL = "http://" + strings.TrimPrefix(host, "tcp://")
		c.http = &http.Client{Timeout: 30 * time.Second}
	default:
		// Fall back to treating the value as a unix socket path.
		c.baseURL = "http://docker"
		c.http = &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
					var d net.Dialer
					return d.DialContext(ctx, "unix", host)
				},
			},
		}
	}
	return c
}

// Host returns the configured docker host string.
func (c *Client) Host() string { return c.host }

func (c *Client) do(ctx context.Context, method, path string, query url.Values, body io.Reader) (*http.Response, error) {
	u := c.baseURL + path
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("docker request failed: %w", err)
	}
	return resp, nil
}

// getJSON performs a GET and decodes the JSON body into out.
func (c *Client) getJSON(ctx context.Context, path string, query url.Values, out any) error {
	resp, err := c.do(ctx, http.MethodGet, path, query, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if err := checkResponse(resp); err != nil {
		return err
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// post performs a POST and discards the body, returning an error for non-2xx.
func (c *Client) post(ctx context.Context, path string, query url.Values) error {
	resp, err := c.do(ctx, http.MethodPost, path, query, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return checkResponse(resp)
}

// delete performs a DELETE and discards the body, returning an error for non-2xx.
func (c *Client) delete(ctx context.Context, path string, query url.Values) error {
	resp, err := c.do(ctx, http.MethodDelete, path, query, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return checkResponse(resp)
}

func checkResponse(resp *http.Response) error {
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<10))
	msg := strings.TrimSpace(string(data))
	// Docker error bodies are usually {"message":"..."}.
	var e struct {
		Message string `json:"message"`
	}
	if json.Unmarshal(data, &e) == nil && e.Message != "" {
		msg = e.Message
	}
	return &APIError{Status: resp.StatusCode, Message: msg}
}

// APIError represents a non-2xx response from the Docker Engine.
type APIError struct {
	Status  int
	Message string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("docker api error (%d): %s", e.Status, e.Message)
}

// NotFound reports whether the error is a 404 from the engine.
func NotFound(err error) bool {
	var e *APIError
	if ok := asAPIError(err, &e); ok {
		return e.Status == http.StatusNotFound
	}
	return false
}

func asAPIError(err error, target **APIError) bool {
	for err != nil {
		if e, ok := err.(*APIError); ok {
			*target = e
			return true
		}
		type unwrap interface{ Unwrap() error }
		if u, ok := err.(unwrap); ok {
			err = u.Unwrap()
		} else {
			return false
		}
	}
	return false
}

// streamLines reads an http body line by line, invoking fn for each line until
// the context is cancelled or the stream ends.
func streamLines(ctx context.Context, r io.Reader, fn func([]byte)) error {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, 64<<10), 1<<20)
	for scanner.Scan() {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		fn(scanner.Bytes())
	}
	return scanner.Err()
}
