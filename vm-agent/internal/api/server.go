package api

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"os/exec"
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
	s.mux.HandleFunc("/v1/containers/deploy", s.deployContainer)
	s.mux.HandleFunc("/v1/containers/", s.container)
	s.mux.HandleFunc("/v1/system/df", s.systemDf)
	s.mux.HandleFunc("/v1/system/prune", s.systemPrune)
	s.mux.HandleFunc("/v1/compose/projects", s.composeProjects)
	s.mux.HandleFunc("/v1/compose/projects/", s.composeProjectAction)
	s.mux.HandleFunc("/v1/images", s.images)
	s.mux.HandleFunc("/v1/volumes", s.volumes)
	s.mux.HandleFunc("/v1/networks", s.networks)
	s.mux.HandleFunc("/v1/network/diagnostics", s.networkDiagnostics)
	s.mux.HandleFunc("/v1/terminal/sessions", s.terminalSessions)
	s.mux.HandleFunc("/v1/terminal/sessions/", s.terminalSessionAction)
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
func (s *Server) deployContainer(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	var spec dockerapi.DeploySpec
	if err := json.NewDecoder(r.Body).Decode(&spec); err != nil {
		http.Error(w, "invalid request body: "+err.Error(), http.StatusBadRequest)
		return
	}
	res, err := s.config.Docker.DeployContainer(r.Context(), spec)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
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
	case "pause":
		err = s.config.Docker.PauseContainer(r.Context(), id)
	case "unpause":
		err = s.config.Docker.UnpauseContainer(r.Context(), id)
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

func (s *Server) systemDf(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	breakdown, err := s.config.Docker.SystemDf(r.Context())
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, breakdown)
}

func (s *Server) systemPrune(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	pruneType := r.URL.Query().Get("type")
	if pruneType == "" {
		pruneType = "all"
	}
	res, err := s.config.Docker.Prune(r.Context(), pruneType)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) composeProjects(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		projects, err := s.config.Docker.ListComposeProjects(r.Context())
		if err != nil {
			writeJSON(w, http.StatusOK, []dockerapi.ComposeProject{})
			return
		}
		writeJSON(w, http.StatusOK, projects)
	case http.MethodPost:
		var req struct {
			Name string `json:"name"`
			Yaml string `json:"yaml"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid request body: "+err.Error(), http.StatusBadRequest)
			return
		}
		proj, err := s.config.Docker.DeployCompose(r.Context(), req.Name, req.Yaml)
		if err != nil {
			writeError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, proj)
	default:
		http.Error(w, "method not allowed", 405)
	}
}

func (s *Server) composeProjectAction(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	// /v1/compose/projects/<name>/down
	if len(parts) < 4 {
		http.NotFound(w, r)
		return
	}
	name, action := parts[3], parts[len(parts)-1]
	if action == "down" {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", 405)
			return
		}
		if err := s.config.Docker.DownCompose(r.Context(), name); err != nil {
			writeError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
		return
	}
	http.NotFound(w, r)
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
type NetworkDiagnostics struct {
	InterfaceUp             bool   `json:"interfaceUp"`
	GuestAddress            string `json:"guestAddress,omitempty"`
	DefaultRoute            bool   `json:"defaultRoute"`
	GatewayReachable        bool   `json:"gatewayReachable"`
	DNSReachable            bool   `json:"dnsReachable"`
	DNSResolution           bool   `json:"dnsResolution"`
	HTTPSReachable          bool   `json:"httpsReachable"`
	DockerRegistryReachable bool   `json:"dockerRegistryReachable"`
	DockerPullTest          bool   `json:"dockerPullTest"`
	LatencyMs               int64  `json:"latencyMs,omitempty"`
	Error                   string `json:"error,omitempty"`
	Details                 string `json:"details,omitempty"`
}

func (s *Server) networkDiagnostics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", 405)
		return
	}
	diag := NetworkDiagnostics{}

	// 1. Interface & Guest IP
	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp != 0 && iface.Flags&net.FlagLoopback == 0 {
				addrs, _ := iface.Addrs()
				for _, addr := range addrs {
					if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() && ipnet.IP.To4() != nil {
						diag.InterfaceUp = true
						diag.GuestAddress = ipnet.IP.String()
						break
					}
				}
				if diag.InterfaceUp {
					break
				}
			}
		}
	}

	// 2. Default Route
	routeData, err := os.ReadFile("/proc/net/route")
	if err == nil {
		for _, line := range strings.Split(string(routeData), "\n") {
			fields := strings.Fields(line)
			if len(fields) >= 2 && fields[1] == "00000000" {
				diag.DefaultRoute = true
				break
			}
		}
	}

	// 3. Gateway Reachable (QEMU 10.0.2.2 ICMP ping or route)
	ctxPing, cancelPing := context.WithTimeout(r.Context(), 1500*time.Millisecond)
	if err := exec.CommandContext(ctxPing, "ping", "-c", "1", "-W", "1", "10.0.2.2").Run(); err == nil {
		diag.GatewayReachable = true
	} else if diag.DefaultRoute {
		diag.GatewayReachable = true
	}
	cancelPing()

	// 4. DNS Port Reachable (UDP 10.0.2.3:53)
	dnsConn, err := net.DialTimeout("udp", "10.0.2.3:53", 2*time.Second)
	if err == nil {
		diag.DNSReachable = true
		dnsConn.Close()
	}

	// 5. DNS Resolution (System resolver -> DoH fallback on 127.0.0.1:53)
	ctxDns, cancelDns := context.WithTimeout(r.Context(), 5*time.Second)
	ips, err := net.DefaultResolver.LookupHost(ctxDns, "cloudflare.com")
	cancelDns()
	if err == nil && len(ips) > 0 {
		diag.DNSResolution = true
	} else {
		// Fallback to in-guest DoH proxy on 127.0.0.1:53
		dohResolver := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "udp", "127.0.0.1:53")
			},
		}
		ctxDoh, cancelDoh := context.WithTimeout(r.Context(), 5*time.Second)
		ipsDoh, errDoh := dohResolver.LookupHost(ctxDoh, "cloudflare.com")
		cancelDoh()
		if errDoh == nil && len(ipsDoh) > 0 {
			diag.DNSResolution = true
		}
	}

	// 6. HTTPS Reachable & Latency
	startHttp := time.Now()
	httpClient := &http.Client{
		Timeout: 6 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	resp, err := httpClient.Get("https://1.1.1.1")
	if err == nil {
		diag.HTTPSReachable = true
		diag.LatencyMs = time.Since(startHttp).Milliseconds()
		resp.Body.Close()
	} else if diag.DNSResolution {
		resp, err = httpClient.Get("https://cloudflare.com")
		if err == nil {
			diag.HTTPSReachable = true
			diag.LatencyMs = time.Since(startHttp).Milliseconds()
			resp.Body.Close()
		}
	}

	// 7. Docker Registry Reachable
	regClient := &http.Client{Timeout: 8 * time.Second}
	resp, err = regClient.Get("https://registry-1.docker.io/v2/")
	if err == nil {
		diag.DockerRegistryReachable = true
		resp.Body.Close()
	} else {
		diag.Details = "reg err: " + err.Error()
	}

	// 8. Docker Pull Operation
	ctxDoc, cancelDoc := context.WithTimeout(r.Context(), 6*time.Second)
	defer cancelDoc()
	hasBusybox := false
	if imgs, err := s.config.Docker.Images(ctxDoc); err == nil {
		for _, img := range imgs {
			for _, tag := range img.RepoTags {
				if strings.Contains(tag, "busybox") {
					hasBusybox = true
					break
				}
			}
			if hasBusybox {
				break
			}
		}
	}
	if hasBusybox {
		diag.DockerPullTest = true
	} else if err := s.config.Docker.PullImage(ctxDoc, "busybox:latest"); err == nil {
		diag.DockerPullTest = true
	} else {
		diag.Error = "docker pull failed: " + err.Error()
	}

	writeJSON(w, http.StatusOK, diag)
}

func (s *Server) terminalSessions(w http.ResponseWriter, r *http.Request) {
	s.config.Terminal.ServeSessionsHTTP(w, r)
}

func (s *Server) terminalSessionAction(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) < 4 {
		http.NotFound(w, r)
		return
	}
	id := parts[3]
	s.config.Terminal.ServeSessionActionHTTP(w, r, id)
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

