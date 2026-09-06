package dockerapi

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Container is a normalized view of a Docker container suitable for the app.
type Container struct {
	ID      string            `json:"id"`
	Names   []string          `json:"names"`
	Image   string            `json:"image"`
	ImageID string            `json:"imageId"`
	Command string            `json:"command"`
	Created int64             `json:"created"`
	State   string            `json:"state"`
	Status  string            `json:"status"`
	Ports   []Port            `json:"ports"`
	Labels  map[string]string `json:"labels"`
}

// Port describes a published or exposed container port.
type Port struct {
	IP          string `json:"ip,omitempty"`
	PrivatePort int    `json:"privatePort"`
	PublicPort  int    `json:"publicPort,omitempty"`
	Type        string `json:"type"`
}

// ListContainers returns all containers (running and stopped when all=true).
func (c *Client) ListContainers(ctx context.Context, all bool) ([]Container, error) {
	q := url.Values{}
	if all {
		q.Set("all", "1")
	}
	var raw []struct {
		ID      string            `json:"Id"`
		Names   []string          `json:"Names"`
		Image   string            `json:"Image"`
		ImageID string            `json:"ImageID"`
		Command string            `json:"Command"`
		Created int64             `json:"Created"`
		State   string            `json:"State"`
		Status  string            `json:"Status"`
		Labels  map[string]string `json:"Labels"`
		Ports   []struct {
			IP          string `json:"IP"`
			PrivatePort int    `json:"PrivatePort"`
			PublicPort  int    `json:"PublicPort"`
			Type        string `json:"Type"`
		} `json:"Ports"`
	}
	if err := c.getJSON(ctx, "/containers/json", q, &raw); err != nil {
		return nil, err
	}
	out := make([]Container, 0, len(raw))
	for _, r := range raw {
		ports := make([]Port, 0, len(r.Ports))
		for _, p := range r.Ports {
			ports = append(ports, Port{IP: p.IP, PrivatePort: p.PrivatePort, PublicPort: p.PublicPort, Type: p.Type})
		}
		out = append(out, Container{
			ID:      r.ID,
			Names:   cleanNames(r.Names),
			Image:   r.Image,
			ImageID: r.ImageID,
			Command: r.Command,
			Created: r.Created,
			State:   r.State,
			Status:  r.Status,
			Ports:   ports,
			Labels:  r.Labels,
		})
	}
	return out, nil
}

// ContainerDetail is the full inspect payload trimmed to fields the app shows.
type ContainerDetail struct {
	ID         string            `json:"id"`
	Name       string            `json:"name"`
	Image      string            `json:"image"`
	State      string            `json:"state"`
	Status     string            `json:"status"`
	Created    string            `json:"created"`
	StartedAt  string            `json:"startedAt"`
	RestartCnt int               `json:"restartCount"`
	Command    []string          `json:"command"`
	Ports      []Port            `json:"ports"`
	Mounts     []Mount           `json:"mounts"`
	Networks   map[string]NetEP  `json:"networks"`
	EnvKeys    []string          `json:"envKeys"`
	Labels     map[string]string `json:"labels"`
}

// Mount is a bind or volume mount attached to a container.
type Mount struct {
	Type        string `json:"type"`
	Source      string `json:"source"`
	Destination string `json:"destination"`
	Mode        string `json:"mode"`
	RW          bool   `json:"rw"`
}

// NetEP is a container's endpoint on a docker network.
type NetEP struct {
	IPAddress string `json:"ipAddress"`
	Gateway   string `json:"gateway"`
	MacAddr   string `json:"macAddress"`
}

// InspectContainer returns detailed info. Environment values are intentionally
// stripped: only the variable KEYS are returned so the UI never leaks secrets.
func (c *Client) InspectContainer(ctx context.Context, id string) (*ContainerDetail, error) {
	var raw struct {
		ID      string `json:"Id"`
		Name    string `json:"Name"`
		Created string `json:"Created"`
		State   struct {
			Status     string `json:"Status"`
			StartedAt  string `json:"StartedAt"`
			RestartCnt int    `json:"RestartCount"`
		} `json:"State"`
		Config struct {
			Image  string            `json:"Image"`
			Cmd    []string          `json:"Cmd"`
			Env    []string          `json:"Env"`
			Labels map[string]string `json:"Labels"`
		} `json:"Config"`
		RestartCount    int `json:"RestartCount"`
		NetworkSettings struct {
			Ports map[string][]struct {
				HostIP   string `json:"HostIp"`
				HostPort string `json:"HostPort"`
			} `json:"Ports"`
			Networks map[string]struct {
				IPAddress  string `json:"IPAddress"`
				Gateway    string `json:"Gateway"`
				MacAddress string `json:"MacAddress"`
			} `json:"Networks"`
		} `json:"NetworkSettings"`
		Mounts []struct {
			Type        string `json:"Type"`
			Source      string `json:"Source"`
			Destination string `json:"Destination"`
			Mode        string `json:"Mode"`
			RW          bool   `json:"RW"`
		} `json:"Mounts"`
	}
	if err := c.getJSON(ctx, "/containers/"+id+"/json", nil, &raw); err != nil {
		return nil, err
	}

	ports := make([]Port, 0)
	for portProto, bindings := range raw.NetworkSettings.Ports {
		priv, proto := splitPortProto(portProto)
		if len(bindings) == 0 {
			ports = append(ports, Port{PrivatePort: priv, Type: proto})
			continue
		}
		for _, b := range bindings {
			pub, _ := strconv.Atoi(b.HostPort)
			ports = append(ports, Port{IP: b.HostIP, PrivatePort: priv, PublicPort: pub, Type: proto})
		}
	}

	mounts := make([]Mount, 0, len(raw.Mounts))
	for _, m := range raw.Mounts {
		mounts = append(mounts, Mount{Type: m.Type, Source: m.Source, Destination: m.Destination, Mode: m.Mode, RW: m.RW})
	}

	nets := make(map[string]NetEP, len(raw.NetworkSettings.Networks))
	for name, n := range raw.NetworkSettings.Networks {
		nets[name] = NetEP{IPAddress: n.IPAddress, Gateway: n.Gateway, MacAddr: n.MacAddress}
	}

	envKeys := make([]string, 0, len(raw.Config.Env))
	for _, e := range raw.Config.Env {
		if i := strings.IndexByte(e, '='); i > 0 {
			envKeys = append(envKeys, e[:i])
		} else {
			envKeys = append(envKeys, e)
		}
	}

	restart := raw.RestartCount
	if restart == 0 {
		restart = raw.State.RestartCnt
	}

	return &ContainerDetail{
		ID:         raw.ID,
		Name:       strings.TrimPrefix(raw.Name, "/"),
		Image:      raw.Config.Image,
		State:      raw.State.Status,
		Status:     raw.State.Status,
		Created:    raw.Created,
		StartedAt:  raw.State.StartedAt,
		RestartCnt: restart,
		Command:    raw.Config.Cmd,
		Ports:      ports,
		Mounts:     mounts,
		Networks:   nets,
		EnvKeys:    envKeys,
		Labels:     raw.Config.Labels,
	}, nil
}

// StartContainer starts a stopped container.
func (c *Client) StartContainer(ctx context.Context, id string) error {
	return c.post(ctx, "/containers/"+id+"/start", nil)
}

// StopContainer stops a running container, allowing timeout seconds to stop.
func (c *Client) StopContainer(ctx context.Context, id string, timeout int) error {
	q := url.Values{}
	if timeout > 0 {
		q.Set("t", strconv.Itoa(timeout))
	}
	return c.post(ctx, "/containers/"+id+"/stop", q)
}

// RestartContainer restarts a container.
func (c *Client) RestartContainer(ctx context.Context, id string, timeout int) error {
	q := url.Values{}
	if timeout > 0 {
		q.Set("t", strconv.Itoa(timeout))
	}
	return c.post(ctx, "/containers/"+id+"/restart", q)
}

// PauseContainer pauses a running container.
func (c *Client) PauseContainer(ctx context.Context, id string) error {
	return c.post(ctx, "/containers/"+id+"/pause", nil)
}

// UnpauseContainer unpauses a paused container.
func (c *Client) UnpauseContainer(ctx context.Context, id string) error {
	return c.post(ctx, "/containers/"+id+"/unpause", nil)
}

// PullImage pulls a Docker image from a registry.
func (c *Client) PullImage(ctx context.Context, image string) error {
	q := url.Values{}
	q.Set("fromImage", image)
	resp, err := c.do(ctx, http.MethodPost, "/images/create", q, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if err := checkResponse(resp); err != nil {
		return err
	}
	// Drain response stream to ensure pull completion
	_, _ = io.Copy(io.Discard, resp.Body)
	return nil
}

// DeploySpec specifies parameters for single-container deployment.
type DeploySpec struct {
	Name          string            `json:"name"`
	Image         string            `json:"image"`
	HostPort      int               `json:"hostPort"`
	ContainerPort int               `json:"containerPort"`
	Protocol      string            `json:"protocol"`
	Env           map[string]string `json:"env"`
	Volumes       []string          `json:"volumes"`
	RestartPolicy string            `json:"restartPolicy"`
	MemoryBytes   int64             `json:"memoryBytes"`
}

// DeployContainer pulls the required image, checks port conflicts, creates, and starts the container.
func (c *Client) DeployContainer(ctx context.Context, spec DeploySpec) (*ContainerDetail, error) {
	if spec.Image == "" {
		return nil, fmt.Errorf("image is required")
	}
	if spec.Protocol == "" {
		spec.Protocol = "tcp"
	}
	if spec.RestartPolicy == "" {
		spec.RestartPolicy = "unless-stopped"
	}

	// 1. Port conflict check if hostPort is specified
	if spec.HostPort > 0 {
		existing, err := c.ListContainers(ctx, true)
		if err == nil {
			for _, cont := range existing {
				for _, p := range cont.Ports {
					if p.PublicPort == spec.HostPort {
						return nil, fmt.Errorf("port conflict: host port %d is already occupied by container %s", spec.HostPort, cont.Names)
					}
				}
			}
		}
	}

	// 2. Pull image
	if err := c.PullImage(ctx, spec.Image); err != nil {
		return nil, fmt.Errorf("failed to pull image %s: %w", spec.Image, err)
	}

	// 3. Prepare container config
	envList := make([]string, 0, len(spec.Env))
	for k, v := range spec.Env {
		envList = append(envList, fmt.Sprintf("%s=%s", k, v))
	}

	exposedPorts := make(map[string]struct{})
	portBindings := make(map[string][]map[string]string)
	if spec.ContainerPort > 0 {
		portKey := fmt.Sprintf("%d/%s", spec.ContainerPort, spec.Protocol)
		exposedPorts[portKey] = struct{}{}
		if spec.HostPort > 0 {
			portBindings[portKey] = []map[string]string{
				{
					"HostPort": strconv.Itoa(spec.HostPort),
					"HostIp":   "0.0.0.0",
				},
			}
		}
	}

	hostConfig := map[string]any{
		"RestartPolicy": map[string]string{
			"Name": spec.RestartPolicy,
		},
		"PortBindings": portBindings,
	}
	if len(spec.Volumes) > 0 {
		hostConfig["Binds"] = spec.Volumes
	}
	if spec.MemoryBytes > 0 {
		hostConfig["Memory"] = spec.MemoryBytes
	}

	createPayload := map[string]any{
		"Image":        spec.Image,
		"Env":          envList,
		"ExposedPorts": exposedPorts,
		"HostConfig":   hostConfig,
	}

	q := url.Values{}
	if spec.Name != "" {
		q.Set("name", spec.Name)
	}

	var createResp struct {
		ID       string   `json:"Id"`
		Warnings []string `json:"Warnings"`
	}
	if err := c.postJSON(ctx, "/containers/create", q, createPayload, &createResp); err != nil {
		return nil, fmt.Errorf("failed to create container: %w", err)
	}

	// 4. Start container
	if err := c.StartContainer(ctx, createResp.ID); err != nil {
		return nil, fmt.Errorf("failed to start container %s: %w", createResp.ID, err)
	}

	return c.InspectContainer(ctx, createResp.ID)
}

// RemoveContainer removes a container, optionally forcing and removing volumes.
func (c *Client) RemoveContainer(ctx context.Context, id string, force, volumes bool) error {
	q := url.Values{}
	if force {
		q.Set("force", "1")
	}
	if volumes {
		q.Set("v", "1")
	}
	return c.delete(ctx, "/containers/"+id, q)
}

// ContainerLogs returns recent logs (stdout+stderr) as a single decoded string.
// tail limits the number of trailing lines.
func (c *Client) ContainerLogs(ctx context.Context, id string, tail int) (string, error) {
	q := url.Values{}
	q.Set("stdout", "1")
	q.Set("stderr", "1")
	q.Set("timestamps", "0")
	if tail > 0 {
		q.Set("tail", strconv.Itoa(tail))
	} else {
		q.Set("tail", "500")
	}
	resp, err := c.do(ctx, "GET", "/containers/"+id+"/logs", q, nil)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if err := checkResponse(resp); err != nil {
		return "", err
	}
	return decodeLogStream(resp.Body)
}

func splitPortProto(s string) (int, string) {
	proto := "tcp"
	if i := strings.IndexByte(s, '/'); i >= 0 {
		proto = s[i+1:]
		s = s[:i]
	}
	n, _ := strconv.Atoi(s)
	return n, proto
}

func cleanNames(names []string) []string {
	out := make([]string, 0, len(names))
	for _, n := range names {
		out = append(out, strings.TrimPrefix(n, "/"))
	}
	return out
}

// used by stats.go for parsing timestamps consistently
var _ = time.Now
