package dockerapi

import (
	"bytes"
	"encoding/binary"
	"io"
	"testing"
)

func TestDecodeLogStream(t *testing.T) {
	var b bytes.Buffer
	b.Write([]byte{1, 0, 0, 0, 0, 0, 0, 5})
	b.WriteString("hello")
	got, err := decodeLogStream(&b)
	if err != nil || got != "hello" {
		t.Fatalf("got %q err %v", got, err)
	}
}
func TestDecodeRawLogs(t *testing.T) {
	got, err := decodeLogStream(bytes.NewBufferString("plain\ntext"))
	if err != nil || got != "plain\ntext" {
		t.Fatalf("got %q err %v", got, err)
	}
}
func TestDecodeShortFrame(t *testing.T) {
	_, err := decodeLogStream(bytes.NewBuffer([]byte{1, 2}))
	if err != nil {
		t.Fatal(err)
	}
}
func TestMultiplexFrameSizeBigEndian(t *testing.T) {
	data := make([]byte, 8)
	data[0] = 1
	binary.BigEndian.PutUint32(data[4:], 2)
	if !looksMultiplexed(data) {
		t.Fatal("expected multiplexed")
	}
	if _, err := io.ReadAll(bytes.NewReader(data)); err != nil {
		t.Fatal(err)
	}
}
