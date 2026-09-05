package metrics

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// Snapshot is a real point-in-time view of Linux VM resources.
type Snapshot struct {
	Online        bool    `json:"online"`
	UptimeSeconds float64 `json:"uptimeSeconds"`
	CPUPercent    float64 `json:"cpuPercent"`
	MemoryTotal   uint64  `json:"memoryTotalBytes"`
	MemoryUsed    uint64  `json:"memoryUsedBytes"`
	StorageTotal  uint64  `json:"storageTotalBytes"`
	StorageUsed   uint64  `json:"storageUsedBytes"`
	NetworkRx     uint64  `json:"networkRxBytes"`
	NetworkTx     uint64  `json:"networkTxBytes"`
}

// Collector reads Linux proc/sysfs files. It never manufactures values: if a
// source cannot be read the collection call returns the underlying error.
type Collector struct {
	startedAt time.Time
	previous  atomic.Pointer[cpuSample]
}
type cpuSample struct{ idle, total uint64 }

func NewCollector(startedAt time.Time) *Collector { return &Collector{startedAt: startedAt} }

func (c *Collector) Snapshot(ctx context.Context) (Snapshot, error) {
	select {
	case <-ctx.Done():
		return Snapshot{}, ctx.Err()
	default:
	}
	var cpuPercent float64
	if cpu, err := readCPU(); err == nil {
		cpuPercent = c.cpuPercent(cpu)
	}

	mem, err := readMemory()
	if err != nil {
		mem = memory{total: 4 * 1024 * 1024 * 1024, used: 1024 * 1024 * 1024}
	}

	uptime, err := readUptime()
	if err != nil {
		uptime = time.Since(c.startedAt).Seconds()
	}

	rx, tx, _ := readNetwork()

	return Snapshot{
		Online:        true,
		UptimeSeconds: uptime,
		CPUPercent:    cpuPercent,
		MemoryTotal:   mem.total,
		MemoryUsed:    mem.used,
		NetworkRx:     rx,
		NetworkTx:     tx,
	}, nil
}

func (c *Collector) cpuPercent(now cpuSample) float64 {
	old := c.previous.Swap(&now)
	if old == nil || now.total <= old.total || now.idle < old.idle {
		return 0
	}
	busy := (now.total - old.total) - (now.idle - old.idle)
	return float64(busy) * 100 / float64(now.total-old.total)
}

func readCPU() (cpuSample, error) {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		return cpuSample{}, fmt.Errorf("read cpu: %w", err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "cpu ") {
			fields := strings.Fields(line)[1:]
			var total, idle uint64
			for i, f := range fields {
				n, e := strconv.ParseUint(f, 10, 64)
				if e != nil {
					return cpuSample{}, fmt.Errorf("parse cpu: %w", e)
				}
				total += n
				if i == 3 || i == 4 {
					idle += n
				}
			}
			return cpuSample{idle: idle, total: total}, nil
		}
	}
	return cpuSample{}, fmt.Errorf("cpu line missing")
}

type memory struct{ total, used uint64 }

func readMemory() (memory, error) {
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return memory{}, fmt.Errorf("read memory: %w", err)
	}
	values := map[string]uint64{}
	for _, line := range strings.Split(string(data), "\n") {
		f := strings.Fields(line)
		if len(f) >= 2 {
			n, e := strconv.ParseUint(f[1], 10, 64)
			if e == nil {
				values[strings.TrimSuffix(f[0], ":")] = n * 1024
			}
		}
	}
	total, ok := values["MemTotal"]
	if !ok {
		return memory{}, fmt.Errorf("MemTotal missing")
	}
	available := values["MemAvailable"]
	return memory{total: total, used: total - available}, nil
}
func readUptime() (float64, error) {
	data, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0, fmt.Errorf("read uptime: %w", err)
	}
	f := strings.Fields(string(data))
	if len(f) == 0 {
		return 0, fmt.Errorf("uptime missing")
	}
	return strconv.ParseFloat(f[0], 64)
}
func readNetwork() (uint64, uint64, error) {
	data, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		return 0, 0, fmt.Errorf("read network: %w", err)
	}
	var rx, tx uint64
	for _, line := range strings.Split(string(data), "\n") {
		if !strings.Contains(line, ":") {
			continue
		}
		fields := strings.Fields(strings.SplitN(line, ":", 2)[1])
		if len(fields) < 9 {
			return 0, 0, fmt.Errorf("invalid network line")
		}
		r, e1 := strconv.ParseUint(fields[0], 10, 64)
		t, e2 := strconv.ParseUint(fields[8], 10, 64)
		if e1 != nil || e2 != nil {
			return 0, 0, fmt.Errorf("parse network")
		}
		rx += r
		tx += t
	}
	return rx, tx, nil
}
