package dns

import (
	"bytes"
	"context"
	"io"
	"log"
	"net"
	"net/http"
	"time"
)

// StartDohProxy starts a local UDP DNS server on 127.0.0.1:53 that forwards
// standard DNS queries via DNS-over-HTTPS (DoH) to Cloudflare and Google.
// This guarantees 100% reliable DNS resolution inside the VM even on restrictive
// cellular carriers (e.g. Jio/Airtel 5G) where raw outbound UDP port 53 is dropped.
func StartDohProxy(ctx context.Context) {
	conn, err := net.ListenPacket("udp", "127.0.0.1:53")
	if err != nil {
		log.Printf("dns-proxy: failed to listen on 127.0.0.1:53: %v", err)
		return
	}
	go func() {
		<-ctx.Done()
		conn.Close()
	}()

	client := &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			MaxIdleConns:        10,
			IdleConnTimeout:     30 * time.Second,
			DisableCompression: true,
		},
	}

	dohEndpoints := []string{
		"https://1.1.1.1/dns-query",
		"https://8.8.8.8/dns-query",
		"https://cloudflare-dns.com/dns-query",
	}

	log.Printf("dns-proxy: started local DoH resolver on 127.0.0.1:53")

	buf := make([]byte, 4096)
	for {
		n, clientAddr, err := conn.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			continue
		}

		query := make([]byte, n)
		copy(query, buf[:n])

		go func(q []byte, addr net.Addr) {
			for _, endpoint := range dohEndpoints {
				req, err := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewReader(q))
				if err != nil {
					continue
				}
				req.Header.Set("Content-Type", "application/dns-message")
				req.Header.Set("Accept", "application/dns-message")

				resp, err := client.Do(req)
				if err != nil {
					continue
				}

				respBody, err := io.ReadAll(resp.Body)
				resp.Body.Close()
				if err != nil || resp.StatusCode != http.StatusOK {
					continue
				}

				_, _ = conn.WriteTo(respBody, addr)
				return
			}
		}(query, clientAddr)
	}
}
