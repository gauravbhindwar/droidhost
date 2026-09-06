package dockerapi

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
)

var projectNameRegex = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)

const defaultProjectsDir = "/var/droidhost/projects"

// ComposeProject represents a deployed Compose application stack.
type ComposeProject struct {
	Name        string           `json:"name"`
	Status      string           `json:"status"`
	ConfigFile  string           `json:"configFile"`
	Services    []ComposeService `json:"services"`
}

// ComposeService represents a service within a Compose stack.
type ComposeService struct {
	Name    string `json:"name"`
	Image   string `json:"image"`
	State   string `json:"state"`
	Status  string `json:"status"`
	Ports   string `json:"ports"`
}

// DeployCompose writes the compose YAML, validates it with `docker compose config`, and runs `docker compose up -d`.
func (c *Client) DeployCompose(ctx context.Context, name, yamlContent string) (*ComposeProject, error) {
	name = strings.TrimSpace(name)
	if !projectNameRegex.MatchString(name) {
		return nil, fmt.Errorf("invalid project name %q: must match [a-zA-Z0-9_-]+ (max 64 chars)", name)
	}
	if strings.TrimSpace(yamlContent) == "" {
		return nil, fmt.Errorf("compose yaml content cannot be empty")
	}

	projectDir := filepath.Join(defaultProjectsDir, name)
	if err := os.MkdirAll(projectDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create project directory: %w", err)
	}

	composeFile := filepath.Join(projectDir, "compose.yaml")
	if err := os.WriteFile(composeFile, []byte(yamlContent), 0644); err != nil {
		return nil, fmt.Errorf("failed to write compose.yaml: %w", err)
	}

	// 1. Validate syntax via `docker compose config`
	cmdConfig := exec.CommandContext(ctx, "docker", "compose", "-f", composeFile, "-p", name, "config")
	if out, err := cmdConfig.CombinedOutput(); err != nil {
		return nil, fmt.Errorf("invalid compose configuration: %s: %w", string(out), err)
	}

	// 2. Deploy with `docker compose up -d`
	cmdUp := exec.CommandContext(ctx, "docker", "compose", "-f", composeFile, "-p", name, "up", "-d", "--remove-orphans")
	if out, err := cmdUp.CombinedOutput(); err != nil {
		return nil, fmt.Errorf("docker compose up failed: %s: %w", string(out), err)
	}

	return c.InspectCompose(ctx, name)
}

// InspectCompose inspects a Compose project and returns its service statuses.
func (c *Client) InspectCompose(ctx context.Context, name string) (*ComposeProject, error) {
	name = strings.TrimSpace(name)
	if !projectNameRegex.MatchString(name) {
		return nil, fmt.Errorf("invalid project name %q", name)
	}

	projectDir := filepath.Join(defaultProjectsDir, name)
	composeFile := filepath.Join(projectDir, "compose.yaml")
	if _, err := os.Stat(composeFile); err != nil {
		return nil, fmt.Errorf("project %s does not exist", name)
	}

	cmdPs := exec.CommandContext(ctx, "docker", "compose", "-f", composeFile, "-p", name, "ps", "--format", "json")
	out, err := cmdPs.CombinedOutput()
	services := make([]ComposeService, 0)
	status := "running"

	if err == nil && len(out) > 0 {
		lines := strings.Split(strings.TrimSpace(string(out)), "\n")
		for _, line := range lines {
			if strings.TrimSpace(line) == "" {
				continue
			}
			var item struct {
				Service string `json:"Service"`
				Image   string `json:"Image"`
				State   string `json:"State"`
				Status  string `json:"Status"`
				Publishers []struct {
					URL           string `json:"URL"`
					TargetPort    int    `json:"TargetPort"`
					PublishedPort int    `json:"PublishedPort"`
					Protocol      string `json:"Protocol"`
				} `json:"Publishers"`
			}
			if json.Unmarshal([]byte(line), &item) == nil {
				portsList := make([]string, 0)
				for _, p := range item.Publishers {
					if p.PublishedPort > 0 {
						portsList = append(portsList, fmt.Sprintf("%d:%d", p.PublishedPort, p.TargetPort))
					}
				}
				services = append(services, ComposeService{
					Name:   item.Service,
					Image:  item.Image,
					State:  item.State,
					Status: item.Status,
					Ports:  strings.Join(portsList, ", "),
				})
			}
		}
	}

	if len(services) == 0 {
		status = "stopped"
	}

	return &ComposeProject{
		Name:       name,
		Status:     status,
		ConfigFile: composeFile,
		Services:   services,
	}, nil
}

// ListComposeProjects scans the projects directory and returns all Compose stacks.
func (c *Client) ListComposeProjects(ctx context.Context) ([]ComposeProject, error) {
	entries, err := os.ReadDir(defaultProjectsDir)
	if err != nil {
		return []ComposeProject{}, nil
	}

	projects := make([]ComposeProject, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() || !projectNameRegex.MatchString(e.Name()) {
			continue
		}
		proj, err := c.InspectCompose(ctx, e.Name())
		if err == nil && proj != nil {
			projects = append(projects, *proj)
		}
	}

	return projects, nil
}

// DownCompose stops and tears down the Compose project.
func (c *Client) DownCompose(ctx context.Context, name string) error {
	name = strings.TrimSpace(name)
	if !projectNameRegex.MatchString(name) {
		return fmt.Errorf("invalid project name %q", name)
	}

	projectDir := filepath.Join(defaultProjectsDir, name)
	composeFile := filepath.Join(projectDir, "compose.yaml")
	if _, err := os.Stat(composeFile); err != nil {
		return fmt.Errorf("project %s not found", name)
	}

	cmdDown := exec.CommandContext(ctx, "docker", "compose", "-f", composeFile, "-p", name, "down")
	if out, err := cmdDown.CombinedOutput(); err != nil {
		return fmt.Errorf("docker compose down failed: %s: %w", string(out), err)
	}

	return nil
}
