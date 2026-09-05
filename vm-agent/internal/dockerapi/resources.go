package dockerapi

import "context"

type Image struct {
	ID       string   `json:"id"`
	RepoTags []string `json:"repoTags"`
	Size     uint64   `json:"size"`
	Created  int64    `json:"created"`
}
type Volume struct {
	Name       string `json:"name"`
	Driver     string `json:"driver"`
	Mountpoint string `json:"mountpoint"`
}
type Network struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Driver string `json:"driver"`
	Scope  string `json:"scope"`
}

func (c *Client) Images(ctx context.Context) ([]Image, error) {
	var raw []struct {
		ID       string   `json:"Id"`
		RepoTags []string `json:"RepoTags"`
		Size     uint64   `json:"Size"`
		Created  int64    `json:"Created"`
	}
	if err := c.getJSON(ctx, "/images/json", nil, &raw); err != nil {
		return nil, err
	}
	out := make([]Image, 0, len(raw))
	for _, v := range raw {
		out = append(out, Image{ID: v.ID, RepoTags: v.RepoTags, Size: v.Size, Created: v.Created})
	}
	return out, nil
}
func (c *Client) Volumes(ctx context.Context) ([]Volume, error) {
	var raw struct {
		Volumes []struct {
			Name       string `json:"Name"`
			Driver     string `json:"Driver"`
			Mountpoint string `json:"Mountpoint"`
		} `json:"Volumes"`
	}
	if err := c.getJSON(ctx, "/volumes", nil, &raw); err != nil {
		return nil, err
	}
	out := make([]Volume, 0, len(raw.Volumes))
	for _, v := range raw.Volumes {
		out = append(out, Volume{Name: v.Name, Driver: v.Driver, Mountpoint: v.Mountpoint})
	}
	return out, nil
}
func (c *Client) Networks(ctx context.Context) ([]Network, error) {
	var raw []struct {
		ID     string `json:"Id"`
		Name   string `json:"Name"`
		Driver string `json:"Driver"`
		Scope  string `json:"Scope"`
	}
	if err := c.getJSON(ctx, "/networks", nil, &raw); err != nil {
		return nil, err
	}
	out := make([]Network, 0, len(raw))
	for _, v := range raw {
		out = append(out, Network{ID: v.ID, Name: v.Name, Driver: v.Driver, Scope: v.Scope})
	}
	return out, nil
}
