package dockerapi

import (
	"context"
	"encoding/json"
	"net/url"
)

type ContainerStats struct {
	CPUPercent    float64 `json:"cpuPercent"`
	MemoryUsed    uint64  `json:"memoryUsedBytes"`
	MemoryLimit   uint64  `json:"memoryLimitBytes"`
	MemoryPercent float64 `json:"memoryPercent"`
	NetworkRx     uint64  `json:"networkRxBytes"`
	NetworkTx     uint64  `json:"networkTxBytes"`
}

func (c *Client) Stats(ctx context.Context, id string) (ContainerStats, error) {
	q := url.Values{}
	q.Set("stream", "0")
	q.Set("one-shot", "1")
	resp, err := c.do(ctx, "GET", "/containers/"+id+"/stats", q, nil)
	if err != nil {
		return ContainerStats{}, err
	}
	defer resp.Body.Close()
	if err = checkResponse(resp); err != nil {
		return ContainerStats{}, err
	}
	var raw struct {
		CPUStats struct {
			CPUUsage struct {
				TotalUsage  uint64   `json:"total_usage"`
				PercpuUsage []uint64 `json:"percpu_usage"`
			} `json:"cpu_usage"`
			SystemUsage uint64 `json:"system_cpu_usage"`
			OnlineCPUs  uint64 `json:"online_cpus"`
		} `json:"cpu_stats"`
		PreCPUStats struct {
			CPUUsage struct {
				TotalUsage uint64 `json:"total_usage"`
			} `json:"cpu_usage"`
			SystemUsage uint64 `json:"system_cpu_usage"`
		} `json:"precpu_stats"`
		MemoryStats struct {
			Usage uint64 `json:"usage"`
			Limit uint64 `json:"limit"`
		} `json:"memory_stats"`
		Networks map[string]struct {
			RxBytes uint64 `json:"rx_bytes"`
			TxBytes uint64 `json:"tx_bytes"`
		} `json:"networks"`
	}
	if err = json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return ContainerStats{}, err
	}
	cpuDelta := raw.CPUStats.CPUUsage.TotalUsage - raw.PreCPUStats.CPUUsage.TotalUsage
	sysDelta := raw.CPUStats.SystemUsage - raw.PreCPUStats.SystemUsage
	cpus := raw.CPUStats.OnlineCPUs
	if cpus == 0 {
		cpus = uint64(len(raw.CPUStats.CPUUsage.PercpuUsage))
	}
	var percent float64
	if sysDelta > 0 {
		percent = float64(cpuDelta) / float64(sysDelta) * float64(cpus) * 100
	}
	var rx, tx uint64
	for _, n := range raw.Networks {
		rx += n.RxBytes
		tx += n.TxBytes
	}
	memPercent := float64(0)
	if raw.MemoryStats.Limit > 0 {
		memPercent = float64(raw.MemoryStats.Usage) / float64(raw.MemoryStats.Limit) * 100
	}
	return ContainerStats{CPUPercent: percent, MemoryUsed: raw.MemoryStats.Usage, MemoryLimit: raw.MemoryStats.Limit, MemoryPercent: memPercent, NetworkRx: rx, NetworkTx: tx}, nil
}
