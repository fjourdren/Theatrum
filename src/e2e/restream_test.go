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

func TestRestream_HLSPassthrough(t *testing.T) {
	testVideoPath := testVideoPath(t)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/restream/mystream":
    stream:
      type: restream
      source_url: "%s"
      path: "restream/mystream"
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
`, testVideoPath),
	})

	// Wait for the playlist to appear on disk
	// Passthrough streams go into {path}/default/
	playlistPath := filepath.Join(ts.DataDir, "restream", "mystream", constants.DefaultQuality, constants.SubPlaylist)
	waitForFile(t, playlistPath, 20*time.Second)

	// Wait for at least one segment
	segmentPath := filepath.Join(ts.DataDir, "restream", "mystream", constants.DefaultQuality, "segment_000.ts")
	waitForFile(t, segmentPath, 20*time.Second)

	// Fetch the master playlist via HTTP
	masterURL := fmt.Sprintf("http://%s/restream/mystream/%s", ts.HTTPAddr, constants.MasterPlaylist)
	resp := waitForHTTP(t, masterURL, 15*time.Second)
	resp.Body.Close()

	status, body := httpGetBody(t, masterURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for master playlist, got %d", status)
	}
	m3u8 := parseM3U8(body)
	if !m3u8.IsMaster {
		t.Error("Expected master playlist")
	}

	// Fetch the sub playlist via HTTP
	subPlaylistURL := fmt.Sprintf("http://%s/restream/mystream/%s/%s", ts.HTTPAddr, constants.DefaultQuality, constants.SubPlaylist)
	status, body = httpGetBody(t, subPlaylistURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for sub playlist, got %d", status)
	}
	m3u8 = parseM3U8(body)
	if m3u8.SegmentCount == 0 {
		t.Error("Expected at least one segment in playlist")
	}

	// Verify live cache headers (restream is treated as live for caching)
	resp = httpGet(t, subPlaylistURL)
	defer resp.Body.Close()
	cacheControl := resp.Header.Get("Cache-Control")
	if cacheControl != "no-cache, no-store, must-revalidate" {
		t.Errorf("Expected no-cache for restream playlist, got %q", cacheControl)
	}
}

func TestRestream_HLSMultiQuality(t *testing.T) {
	testVideoPath := testVideoPath(t)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/restream/premium":
    stream:
      type: restream
      source_url: "%s"
      path: "restream/premium"
      qualities:
        low:
          width: 640
          height: 360
          framerate: 24
          bitrate: "800k"
          codec: "libx264"
          audio:
            bitrate: "96k"
            codec: "aac"
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
`, testVideoPath),
	})

	// Wait for master.m3u8 to appear (multi-quality creates it via FFmpeg)
	masterPath := filepath.Join(ts.DataDir, "restream", "premium", constants.MasterPlaylist)
	waitForFile(t, masterPath, 30*time.Second)

	// Wait for at least one quality subdir to appear
	lowPlaylistPath := filepath.Join(ts.DataDir, "restream", "premium", "low", constants.SubPlaylist)
	waitForFile(t, lowPlaylistPath, 30*time.Second)

	// Fetch master playlist via HTTP
	masterURL := fmt.Sprintf("http://%s/restream/premium/%s", ts.HTTPAddr, constants.MasterPlaylist)
	status, body := httpGetBody(t, masterURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for master playlist, got %d", status)
	}
	m3u8 := parseM3U8(body)
	if !m3u8.IsMaster {
		t.Error("Expected master playlist")
	}
	if len(m3u8.StreamPlaylists) == 0 {
		t.Error("Expected at least one stream playlist in master")
	}
}

func TestRestream_DASHPassthrough(t *testing.T) {
	testVideoPath := testVideoPath(t)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/restream/dash":
    stream:
      type: restream
      source_url: "%s"
      path: "restream/dash"
      distribution:
        dash:
          segment_duration: 2
          window_size: 5
`, testVideoPath),
	})

	// Wait for manifest.mpd to appear on disk
	manifestPath := filepath.Join(ts.DataDir, "restream", "dash", constants.DashManifest)
	waitForFile(t, manifestPath, 20*time.Second)

	// Fetch manifest via HTTP
	manifestURL := fmt.Sprintf("http://%s/restream/dash/%s", ts.HTTPAddr, constants.DashManifest)
	resp := waitForHTTP(t, manifestURL, 15*time.Second)
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("Expected 200 for manifest, got %d", resp.StatusCode)
	}

	contentType := resp.Header.Get("Content-Type")
	if contentType != "application/dash+xml" {
		t.Errorf("Expected Content-Type application/dash+xml, got %q", contentType)
	}
}

func TestRestream_ViewerTracking(t *testing.T) {
	testVideoPath := testVideoPath(t)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/restream/tracked":
    stream:
      type: restream
      source_url: "%s"
      path: "restream/tracked"
      distribution:
        hls:
          segment_duration: 2
          window_size: 5
      viewers:
        enabled: true
        window: 1
      views:
        enabled: true
        window: 1
`, testVideoPath),
	})

	// Wait for the playlist to appear
	playlistPath := filepath.Join(ts.DataDir, "restream", "tracked", constants.DefaultQuality, constants.SubPlaylist)
	waitForFile(t, playlistPath, 20*time.Second)

	// Check that viewers.txt endpoint works
	viewersURL := fmt.Sprintf("http://%s/restream/tracked/%s", ts.HTTPAddr, constants.ViewersFile)
	status, body := httpGetBody(t, viewersURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for viewers.txt, got %d", status)
	}
	if body != "0" {
		t.Errorf("Expected 0 viewers initially, got %q", body)
	}

	// Check that views.txt endpoint works
	viewsURL := fmt.Sprintf("http://%s/restream/tracked/%s", ts.HTTPAddr, constants.ViewsFile)
	status, body = httpGetBody(t, viewsURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for views.txt, got %d", status)
	}
	if body != "0" {
		t.Errorf("Expected 0 views initially, got %q", body)
	}
}
