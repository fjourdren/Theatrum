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

func TestRTMPAuth_ValidToken(t *testing.T) {
	liveStreamKey := "auth-test-secret"
	username := "validuser"
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

	// Start RTMP stream with valid token
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	streamToRTMP(t, rtmpURL, token, 10)

	// Verify that HLS files are created (auth succeeded)
	playlistPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, constants.SubPlaylist)
	waitForFile(t, playlistPath, 15*time.Second)

	t.Log("Valid auth token: stream started successfully")
}

func TestRTMPAuth_InvalidToken(t *testing.T) {
	liveStreamKey := "auth-test-secret"
	username := "baduser"

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

	// Start RTMP stream with invalid token
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	cmd := streamToRTMP(t, rtmpURL, "invalid-token", 5)

	// FFmpeg should exit with error since authentication fails
	done := make(chan error, 1)
	go func() {
		done <- cmd.Wait()
	}()

	select {
	case err := <-done:
		// FFmpeg should fail (auth rejected)
		if err == nil {
			t.Log("FFmpeg exited cleanly - auth may have silently failed")
		} else {
			t.Logf("FFmpeg exited with error (expected): %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Log("FFmpeg did not exit quickly, auth may have failed at different stage")
		cmd.Process.Kill()
	}

	// Verify no HLS files were created
	time.Sleep(2 * time.Second)
	playlistPath := filepath.Join(ts.DataDir, "live", username, constants.DefaultQuality, constants.SubPlaylist)
	if _, err := os.Stat(playlistPath); err == nil {
		t.Error("Expected no HLS files for invalid token, but playlist was created")
	}
}

func TestRTMPAuth_UnknownChannel(t *testing.T) {
	liveStreamKey := "auth-test-secret"

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

	// Try to connect to a channel that doesn't exist
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/nonexistent/channel", ts.RTMPPort)
	cmd := streamToRTMP(t, rtmpURL, "anytoken", 5)

	// Wait for FFmpeg to exit (should fail)
	done := make(chan error, 1)
	go func() {
		done <- cmd.Wait()
	}()

	select {
	case <-done:
		// Expected: FFmpeg exits because server rejects the unknown channel
	case <-time.After(10 * time.Second):
		cmd.Process.Kill()
	}

	// Verify no files in data dir for this path
	time.Sleep(1 * time.Second)
	unknownDir := filepath.Join(ts.DataDir, "nonexistent")
	if _, err := os.Stat(unknownDir); err == nil {
		t.Error("Expected no files for unknown channel")
	}
}
