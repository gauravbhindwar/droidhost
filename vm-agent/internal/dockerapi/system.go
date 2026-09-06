package dockerapi

import (
	"context"
	"net/url"
	"syscall"
)

// StorageBreakdown aggregates disk space used by Docker objects and the rootfs filesystem.
type StorageBreakdown struct {
	VmDiskTotalBytes     int64 `json:"vmDiskTotalBytes"`
	VmDiskUsedBytes      int64 `json:"vmDiskUsedBytes"`
	DockerImagesBytes    int64 `json:"dockerImagesBytes"`
	DockerContainersBytes int64 `json:"dockerContainersBytes"`
	DockerVolumesBytes   int64 `json:"dockerVolumesBytes"`
	DockerBuildCacheBytes int64 `json:"dockerBuildCacheBytes"`
}

// PruneResult reports the outcome of a prune operation.
type PruneResult struct {
	ImagesDeleted     int   `json:"imagesDeleted"`
	ContainersDeleted int   `json:"containersDeleted"`
	VolumesDeleted    int   `json:"volumesDeleted"`
	SpaceReclaimed    int64 `json:"spaceReclaimed"`
}

// SystemDf returns storage utilization for VM rootfs and Docker resources.
func (c *Client) SystemDf(ctx context.Context) (*StorageBreakdown, error) {
	var raw struct {
		LayersSize int64 `json:"LayersSize"`
		Images     []struct {
			Size        int64 `json:"Size"`
			VirtualSize int64 `json:"VirtualSize"`
		} `json:"Images"`
		Containers []struct {
			SizeRw     int64 `json:"SizeRw"`
			SizeRootFs int64 `json:"SizeRootFs"`
		} `json:"Containers"`
		Volumes []struct {
			UsageData struct {
				Size int64 `json:"Size"`
			} `json:"UsageData"`
		} `json:"Volumes"`
		BuildCache []struct {
			Size int64 `json:"Size"`
		} `json:"BuildCache"`
	}

	_ = c.getJSON(ctx, "/system/df", nil, &raw)

	var imgBytes, contBytes, volBytes, bcBytes int64
	for _, img := range raw.Images {
		if img.Size > 0 {
			imgBytes += img.Size
		} else {
			imgBytes += img.VirtualSize
		}
	}
	for _, cont := range raw.Containers {
		contBytes += cont.SizeRw
	}
	for _, vol := range raw.Volumes {
		volBytes += vol.UsageData.Size
	}
	for _, bc := range raw.BuildCache {
		bcBytes += bc.Size
	}

	var stat syscall.Statfs_t
	var totalBytes, usedBytes int64
	if err := syscall.Statfs("/", &stat); err == nil {
		totalBytes = int64(stat.Blocks) * int64(stat.Bsize)
		freeBytes := int64(stat.Bavail) * int64(stat.Bsize)
		usedBytes = totalBytes - freeBytes
	}

	return &StorageBreakdown{
		VmDiskTotalBytes:      totalBytes,
		VmDiskUsedBytes:       usedBytes,
		DockerImagesBytes:     imgBytes,
		DockerContainersBytes: contBytes,
		DockerVolumesBytes:    volBytes,
		DockerBuildCacheBytes: bcBytes,
	}, nil
}

// Prune cleans up unused Docker resources according to pruneType ("images", "containers", "volumes", "all").
func (c *Client) Prune(ctx context.Context, pruneType string) (*PruneResult, error) {
	result := &PruneResult{}

	if pruneType == "images" || pruneType == "all" {
		var imgResp struct {
			ImagesDeleted  []struct{ Deleted string } `json:"ImagesDeleted"`
			SpaceReclaimed int64                      `json:"SpaceReclaimed"`
		}
		q := url.Values{}
		q.Set("filters", `{"dangling":["false"]}`)
		if err := c.postJSON(ctx, "/images/prune", q, nil, &imgResp); err == nil {
			result.ImagesDeleted += len(imgResp.ImagesDeleted)
			result.SpaceReclaimed += imgResp.SpaceReclaimed
		}
	}

	if pruneType == "containers" || pruneType == "all" {
		var contResp struct {
			ContainersDeleted []string `json:"ContainersDeleted"`
			SpaceReclaimed    int64    `json:"SpaceReclaimed"`
		}
		if err := c.postJSON(ctx, "/containers/prune", nil, nil, &contResp); err == nil {
			result.ContainersDeleted += len(contResp.ContainersDeleted)
			result.SpaceReclaimed += contResp.SpaceReclaimed
		}
	}

	if pruneType == "volumes" || pruneType == "all" {
		var volResp struct {
			VolumesDeleted []string `json:"VolumesDeleted"`
			SpaceReclaimed int64    `json:"SpaceReclaimed"`
		}
		if err := c.postJSON(ctx, "/volumes/prune", nil, nil, &volResp); err == nil {
			result.VolumesDeleted += len(volResp.VolumesDeleted)
			result.SpaceReclaimed += volResp.SpaceReclaimed
		}
	}

	return result, nil
}
