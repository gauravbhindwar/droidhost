package dockerapi

import (
	"bytes"
	"encoding/binary"
	"io"
	"strings"
)

// decodeLogStream decodes Docker's multiplexed log stream format. When a
// container is started without a TTY, the engine frames stdout/stderr with an
// 8-byte header: [stream][000][size uint32 big-endian]. When a TTY is attached
// the stream is raw. This handles both.
func decodeLogStream(r io.Reader) (string, error) {
	data, err := io.ReadAll(io.LimitReader(r, 8<<20))
	if err != nil {
		return "", err
	}
	if !looksMultiplexed(data) {
		return string(data), nil
	}

	var out bytes.Buffer
	for len(data) >= 8 {
		size := binary.BigEndian.Uint32(data[4:8])
		data = data[8:]
		if int(size) > len(data) {
			out.Write(data)
			break
		}
		out.Write(data[:size])
		data = data[size:]
	}
	return out.String(), nil
}

// looksMultiplexed heuristically detects the 8-byte frame header. The first
// byte is a stream type (0,1,2) and bytes 1-3 are zero padding.
func looksMultiplexed(data []byte) bool {
	if len(data) < 8 {
		return false
	}
	if data[0] > 2 || data[1] != 0 || data[2] != 0 || data[3] != 0 {
		return false
	}
	// Sanity: printable content should not usually start this way.
	return !strings.HasPrefix(string(data[:1]), "{")
}
