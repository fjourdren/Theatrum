//go:build e2e

package e2e

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"Theatrum/constants"
)

func TestRTMP_PassthroughPipeline(t *testing.T) {
	liveStreamKey := "e2e-secret-key"
	username := "alice"
	token := computeXORToken(liveStreamKey, username)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "%s"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	cmd := streamToRTMP(t, rtmpURL, token, 15)

	// Wait for the playlist to appear on disk
	// Passthrough streams go into {path}/default/
	playlistPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, constants.SubPlaylist)
	waitForFile(t, playlistPath, 15*time.Second)

	// Wait for at least one segment
	segmentPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, "segment_000.ts")
	waitForFile(t, segmentPath, 15*time.Second)

	// Fetch the sub playlist via HTTP
	subPlaylistURL := fmt.Sprintf("http://%s/user/%s/%s/%s", ts.HTTPAddr, username, constants.DefaultQuality, constants.SubPlaylist)
	resp := waitForHTTP(t, subPlaylistURL, 15*time.Second)
	resp.Body.Close()

	// Verify it's a live playlist (no #EXT-X-ENDLIST)
	status, body := httpGetBody(t, subPlaylistURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for sub playlist, got %d", status)
	}
	m3u8 := parseM3U8(body)
	if m3u8.IsVOD {
		t.Error("Expected live playlist (no #EXT-X-ENDLIST)")
	}
	if m3u8.SegmentCount == 0 {
		t.Error("Expected at least one segment in playlist")
	}

	// Stop the FFmpeg stream
	cmd.Process.Kill()
	cmd.Wait()

	// Wait for cleanup (reconnect_delay=1 + cleanup_delay=1)
	// The stream files should be deleted after cleanup
	time.Sleep(4 * time.Second)

	// Verify cleanup: the stream directory or files should be removed
	waitForFileAbsent(t, segmentPath, 10*time.Second)
}

func TestRTMP_PassthroughServesSegmentsViaHTTP(t *testing.T) {
	liveStreamKey := "e2e-secret-key2"
	username := "bob"
	token := computeXORToken(liveStreamKey, username)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "%s"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	streamToRTMP(t, rtmpURL, token, 12)

	// Wait for segments to appear
	segmentPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, "segment_000.ts")
	waitForFile(t, segmentPath, 15*time.Second)

	// Fetch a segment via HTTP
	segURL := fmt.Sprintf("http://%s/user/%s/%s/segment_000.ts", ts.HTTPAddr, username, constants.DefaultQuality)
	resp := waitForHTTP(t, segURL, 10*time.Second)
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("Expected 200 for segment, got %d", resp.StatusCode)
	}

	// Verify segment has content
	stat, err := os.Stat(segmentPath)
	if err != nil {
		t.Fatalf("Failed to stat segment: %v", err)
	}
	if stat.Size() == 0 {
		t.Error("Segment file is empty")
	}
}
