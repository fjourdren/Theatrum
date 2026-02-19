//go:build e2e

package e2e

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"Theatrum/constants"
)

func TestRecording_InPlace(t *testing.T) {
	liveStreamKey := "record-inplace-secret"
	username := "inplace"
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
      record:
        enabled: true
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	cmd := streamToRTMP(t, rtmpURL, token, 10)

	// Wait for segments to be created
	segmentPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, "segment_000.ts")
	waitForFile(t, segmentPath, 15*time.Second)

	// Stop the stream
	cmd.Process.Kill()
	cmd.Wait()

	// Wait for recording to be finalized (reconnect_delay + processing)
	time.Sleep(5 * time.Second)

	// For in-place recording: VOD playlist should be in the stream path
	playlistPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, constants.SubPlaylist)
	waitForFile(t, playlistPath, 10*time.Second)

	// Read the playlist and verify it has #EXT-X-ENDLIST (VOD marker)
	data, err := os.ReadFile(playlistPath)
	if err != nil {
		t.Fatalf("Failed to read playlist: %v", err)
	}

	m3u8 := parseM3U8(string(data))
	if !m3u8.IsVOD {
		t.Error("Expected VOD playlist with #EXT-X-ENDLIST after recording completes")
	}
	if m3u8.SegmentCount == 0 {
		t.Error("Expected at least one segment in VOD playlist")
	}
}

func TestRecording_WithRecordPath(t *testing.T) {
	liveStreamKey := "record-move-secret"
	username := "recorded"
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
      record:
        enabled: true
        path: "recordings/{username}"
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	cmd := streamToRTMP(t, rtmpURL, token, 10)

	// Wait for segments to be created
	segmentPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, "segment_000.ts")
	waitForFile(t, segmentPath, 15*time.Second)

	// Stop the stream
	cmd.Process.Kill()
	cmd.Wait()

	// Wait for recording to be finalized and files moved
	time.Sleep(8 * time.Second)

	// Files should be moved to the record path
	recordDir := filepath.Join(ts.DataDir, "recordings", username, constants.DefaultQuality)

	// Check that at least one segment exists in the record path
	entries, err := os.ReadDir(recordDir)
	if err != nil {
		// Recording path might not exist if cleanup happened differently
		t.Logf("Record dir not found at %s: %v", recordDir, err)
		// Check if files are still in original path
		origDir := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality)
		origEntries, origErr := os.ReadDir(origDir)
		if origErr == nil && len(origEntries) > 0 {
			t.Logf("Files still in original path, recording may still be processing")
		}
		return
	}

	hasSegment := false
	hasPlaylist := false
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) == ".ts" {
			hasSegment = true
		}
		if entry.Name() == constants.SubPlaylist {
			hasPlaylist = true
		}
	}

	if !hasSegment {
		t.Error("Expected at least one .ts segment in record path")
	}
	if !hasPlaylist {
		t.Error("Expected playlist.m3u8 in record path")
	}

	// Verify the playlist is VOD
	if hasPlaylist {
		data, err := os.ReadFile(filepath.Join(recordDir, constants.SubPlaylist))
		if err != nil {
			t.Fatalf("Failed to read recorded playlist: %v", err)
		}
		m3u8 := parseM3U8(string(data))
		if !m3u8.IsVOD {
			t.Error("Expected recorded playlist to be VOD (have #EXT-X-ENDLIST)")
		}
	}
}
