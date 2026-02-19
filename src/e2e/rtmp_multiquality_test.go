//go:build e2e

package e2e

import (
	"fmt"
	"net/http"
	"path/filepath"
	"testing"
	"time"

	"Theatrum/constants"
)

func TestRTMP_MultiQualityTranscoding(t *testing.T) {
	liveStreamKey := "multi-secret"
	username := "multiquality"
	token := computeXORToken(liveStreamKey, username)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/premium/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "%s"
      auth_token_template: "{username}"
      qualities:
        low:
          width: 320
          height: 240
          framerate: 15
          bitrate: "200k"
          codec: "libx264"
          audio:
            bitrate: "64k"
            codec: "aac"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/premium/%s", ts.RTMPPort, username)
	streamToRTMP(t, rtmpURL, token, 25)

	// Multi-quality: expect master.m3u8 at stream root
	masterPath := filepath.Join(ts.DataDir, "live", username, constants.MasterPlaylist)
	waitForFile(t, masterPath, 20*time.Second)

	// Expect quality subdirectory with playlist
	lowPlaylistPath := filepath.Join(ts.DataDir, "live", username, "low", constants.SubPlaylist)
	waitForFile(t, lowPlaylistPath, 20*time.Second)

	// Expect at least one segment in the quality dir
	lowSegmentPath := filepath.Join(ts.DataDir, "live", username, "low", "segment_000.ts")
	waitForFile(t, lowSegmentPath, 25*time.Second)

	// Fetch master playlist via HTTP
	masterURL := fmt.Sprintf("http://%s/premium/%s/%s", ts.HTTPAddr, username, constants.MasterPlaylist)
	resp := waitForHTTP(t, masterURL, 20*time.Second)
	resp.Body.Close()

	status, body := httpGetBody(t, masterURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for master playlist, got %d", status)
	}

	m3u8 := parseM3U8(body)
	if !m3u8.IsMaster {
		t.Error("Expected master playlist")
	}
	if len(m3u8.StreamPlaylists) == 0 {
		t.Error("Expected at least one quality variant in master playlist")
	}

	// Fetch quality sub playlist
	lowPlaylistURL := fmt.Sprintf("http://%s/premium/%s/low/%s", ts.HTTPAddr, username, constants.SubPlaylist)
	status, body = httpGetBody(t, lowPlaylistURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for low quality playlist, got %d", status)
	}

	subM3U8 := parseM3U8(body)
	if subM3U8.SegmentCount == 0 {
		t.Error("Expected at least one segment in low quality playlist")
	}
}
