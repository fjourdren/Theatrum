//go:build e2e

package e2e

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	yamlConfigFileRepository "Theatrum/adapters/driven/yamlConfigFile/repositories"
	"Theatrum/constants"
)

// --- Config loading tests for DASH ---

func TestConfigLoading_DashOnly(t *testing.T) {
	configContent := `
application:
  public_path: "http://localhost:8080"
  all_streams_playlist:
    enabled: false
    path: ""

server:
  http: 8080
  rtmp: 1935

channels:
  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "secret"
      auth_token_template: "{username}"
      distribution:
        dash:
          segment_duration: 4
          window_size: 5
`
	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	repo := yamlConfigFileRepository.NewYamlConfigFile()
	app, server, channels, err := repo.Load(configPath)
	if err != nil {
		t.Fatalf("Expected valid DASH config to load, got error: %v", err)
	}

	if app.PublicPath != "http://localhost:8080" {
		t.Errorf("Expected public_path 'http://localhost:8080', got %q", app.PublicPath)
	}
	if server.HTTPPort != 8080 {
		t.Errorf("Expected HTTP port 8080, got %d", server.HTTPPort)
	}
	ch := (*channels)["/user/{username}"]
	if !ch.Distribution.DashEnabled() {
		t.Error("Expected DASH to be enabled")
	}
	if ch.Distribution.HlsEnabled() {
		t.Error("Expected HLS to be disabled in DASH-only mode")
	}
}

func TestConfigLoading_DualMode(t *testing.T) {
	configContent := `
application:
  public_path: "http://localhost:8080"
  all_streams_playlist:
    enabled: false
    path: ""

server:
  http: 8080
  rtmp: 1935

channels:
  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "secret"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
        dash:
          segment_duration: 2
          window_size: 5
`
	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	repo := yamlConfigFileRepository.NewYamlConfigFile()
	_, _, channels, err := repo.Load(configPath)
	if err != nil {
		t.Fatalf("Expected valid dual-mode config to load, got error: %v", err)
	}

	ch := (*channels)["/user/{username}"]
	if !ch.Distribution.IsDualMode() {
		t.Error("Expected dual mode (both HLS and DASH enabled)")
	}
}

func TestConfigLoading_DualModeMismatchedSegmentDuration(t *testing.T) {
	configContent := `
application:
  public_path: "http://localhost:8080"
  all_streams_playlist:
    enabled: false
    path: ""

server:
  http: 8080
  rtmp: 1935

channels:
  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "secret"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
        dash:
          segment_duration: 4
`
	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	repo := yamlConfigFileRepository.NewYamlConfigFile()
	_, _, _, err := repo.Load(configPath)
	if err == nil {
		t.Fatal("Expected error for mismatched segment_duration in dual mode, got nil")
	}
}

// --- HTTP serving tests for DASH ---

func TestHTTP_ServeDashManifest(t *testing.T) {
	ts := setupTestServer(t, TestConfig{
		Channels: `  "/vod/{name}":
    stream:
      type: video_encoded
      path: "videos/{name}"
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
        dash:
          segment_duration: 4
`,
	})

	// Create a pre-existing DASH manifest and init segment on disk
	streamDir := filepath.Join(ts.DataDir, "videos", "dashtest")
	if err := os.MkdirAll(streamDir, 0755); err != nil {
		t.Fatalf("Failed to create stream dir: %v", err)
	}

	manifestContent := `<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
  <Period>
    <AdaptationSet mimeType="video/mp4">
      <Representation id="0" bandwidth="800000" width="640" height="360">
        <SegmentTemplate initialization="init-stream0.m4s" media="chunk-stream0-$Number%05d$.m4s"/>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
`
	if err := os.WriteFile(filepath.Join(streamDir, constants.DashManifest), []byte(manifestContent), 0644); err != nil {
		t.Fatalf("Failed to write DASH manifest: %v", err)
	}

	// Create a fake init segment
	initContent := []byte("fake-init-segment")
	if err := os.WriteFile(filepath.Join(streamDir, "init-stream0.m4s"), initContent, 0644); err != nil {
		t.Fatalf("Failed to write init segment: %v", err)
	}

	// Create a fake media segment
	chunkContent := []byte("fake-chunk-segment")
	if err := os.WriteFile(filepath.Join(streamDir, "chunk-stream0-00001.m4s"), chunkContent, 0644); err != nil {
		t.Fatalf("Failed to write chunk segment: %v", err)
	}

	// Test: fetch DASH manifest
	manifestURL := fmt.Sprintf("http://%s/vod/dashtest/%s", ts.HTTPAddr, constants.DashManifest)
	status, body := httpGetBody(t, manifestURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for DASH manifest, got %d", status)
	}
	if !strings.Contains(body, "MPD") {
		t.Errorf("Expected DASH manifest to contain MPD, got: %s", body)
	}

	// Test: verify Content-Type for .mpd
	resp := httpGet(t, manifestURL)
	ct := resp.Header.Get("Content-Type")
	resp.Body.Close()
	if ct != "application/dash+xml" {
		t.Errorf("Expected Content-Type application/dash+xml for .mpd, got %q", ct)
	}

	// Test: fetch init segment (.m4s)
	initURL := fmt.Sprintf("http://%s/vod/dashtest/init-stream0.m4s", ts.HTTPAddr)
	resp = httpGet(t, initURL)
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		t.Fatalf("Expected 200 for init segment, got %d", resp.StatusCode)
	}
	initBody, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(initBody) != string(initContent) {
		t.Error("Init segment content mismatch")
	}

	// Test: verify Content-Type for .m4s
	m4sResp := httpGet(t, initURL)
	m4sCT := m4sResp.Header.Get("Content-Type")
	m4sResp.Body.Close()
	if m4sCT != "video/iso.segment" {
		t.Errorf("Expected Content-Type video/iso.segment for .m4s, got %q", m4sCT)
	}

	// Test: fetch chunk segment (.m4s)
	chunkURL := fmt.Sprintf("http://%s/vod/dashtest/chunk-stream0-00001.m4s", ts.HTTPAddr)
	resp2 := httpGet(t, chunkURL)
	if resp2.StatusCode != http.StatusOK {
		resp2.Body.Close()
		t.Fatalf("Expected 200 for chunk segment, got %d", resp2.StatusCode)
	}
	chunkBody, _ := io.ReadAll(resp2.Body)
	resp2.Body.Close()
	if string(chunkBody) != string(chunkContent) {
		t.Error("Chunk segment content mismatch")
	}
}

func TestHTTP_DashCacheHeaders_VOD(t *testing.T) {
	ts := setupTestServer(t, TestConfig{
		Channels: `  "/vod/{name}":
    stream:
      type: video_encoded
      path: "videos/{name}"
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
        dash:
          segment_duration: 4
`,
	})

	streamDir := filepath.Join(ts.DataDir, "videos", "cachetest")
	if err := os.MkdirAll(streamDir, 0755); err != nil {
		t.Fatalf("Failed to create stream dir: %v", err)
	}

	// Write manifest
	if err := os.WriteFile(filepath.Join(streamDir, constants.DashManifest), []byte(`<?xml version="1.0"?><MPD/>`), 0644); err != nil {
		t.Fatalf("Failed to write manifest: %v", err)
	}

	// Write m4s segment
	if err := os.WriteFile(filepath.Join(streamDir, "chunk-stream0-00001.m4s"), []byte("segment"), 0644); err != nil {
		t.Fatalf("Failed to write segment: %v", err)
	}

	// VOD manifest should be cacheable
	mpdURL := fmt.Sprintf("http://%s/vod/cachetest/%s", ts.HTTPAddr, constants.DashManifest)
	resp := httpGet(t, mpdURL)
	cc := resp.Header.Get("Cache-Control")
	resp.Body.Close()
	if !strings.Contains(cc, "public") || !strings.Contains(cc, "max-age=600") {
		t.Errorf("Expected VOD manifest Cache-Control 'public, max-age=600', got %q", cc)
	}

	// VOD segment should be long-cached
	segURL := fmt.Sprintf("http://%s/vod/cachetest/chunk-stream0-00001.m4s", ts.HTTPAddr)
	resp = httpGet(t, segURL)
	cc = resp.Header.Get("Cache-Control")
	resp.Body.Close()
	if !strings.Contains(cc, "public") || !strings.Contains(cc, "max-age=86400") {
		t.Errorf("Expected VOD segment Cache-Control 'public, max-age=86400', got %q", cc)
	}
}

func TestHTTP_404ForMissingDashManifest(t *testing.T) {
	ts := setupTestServer(t, TestConfig{
		Channels: `  "/vod/{name}":
    stream:
      type: video_encoded
      path: "videos/{name}"
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
        dash:
          segment_duration: 4
`,
	})

	url := fmt.Sprintf("http://%s/vod/nonexistent/%s", ts.HTTPAddr, constants.DashManifest)
	resp := httpGet(t, url)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("Expected 404 for missing DASH manifest, got %d", resp.StatusCode)
	}
}

// --- RTMP live stream tests for DASH ---

func TestRTMP_DashPassthrough(t *testing.T) {
	liveStreamKey := "dash-e2e-secret"
	username := "dashalice"
	token := computeXORToken(liveStreamKey, username)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "%s"
      auth_token_template: "{username}"
      distribution:
        dash:
          segment_duration: 2
          window_size: 3
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	cmd := streamToRTMP(t, rtmpURL, token, 15)

	// Wait for the DASH manifest to appear on disk
	// DASH passthrough: flat layout at {path}/
	manifestPath := filepath.Join(ts.DataDir, "live", username, constants.DashManifest)
	waitForFile(t, manifestPath, 15*time.Second)

	// Wait for at least one init segment
	initPath := filepath.Join(ts.DataDir, "live", username, "init-stream0.m4s")
	waitForFile(t, initPath, 15*time.Second)

	// Fetch the DASH manifest via HTTP
	manifestURL := fmt.Sprintf("http://%s/user/%s/%s", ts.HTTPAddr, username, constants.DashManifest)
	resp := waitForHTTP(t, manifestURL, 15*time.Second)
	resp.Body.Close()

	status, body := httpGetBody(t, manifestURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for DASH manifest, got %d", status)
	}
	if !strings.Contains(body, "MPD") {
		t.Errorf("Expected DASH manifest to contain MPD element")
	}

	// Verify Content-Type
	respCT := httpGet(t, manifestURL)
	ct := respCT.Header.Get("Content-Type")
	respCT.Body.Close()
	if ct != "application/dash+xml" {
		t.Errorf("Expected Content-Type application/dash+xml, got %q", ct)
	}

	// Stop the FFmpeg stream
	cmd.Process.Kill()
	cmd.Wait()

	// Wait for cleanup
	time.Sleep(4 * time.Second)
	waitForFileAbsent(t, manifestPath, 10*time.Second)
}

func TestRTMP_DashMultiQuality(t *testing.T) {
	liveStreamKey := "dash-multi-secret"
	username := "dashmulti"
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
        dash:
          segment_duration: 2
          window_size: 3
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/premium/%s", ts.RTMPPort, username)
	streamToRTMP(t, rtmpURL, token, 25)

	// Multi-quality DASH: expect manifest.mpd at stream root
	manifestPath := filepath.Join(ts.DataDir, "live", username, constants.DashManifest)
	waitForFile(t, manifestPath, 20*time.Second)

	// Fetch DASH manifest via HTTP
	manifestURL := fmt.Sprintf("http://%s/premium/%s/%s", ts.HTTPAddr, username, constants.DashManifest)
	resp := waitForHTTP(t, manifestURL, 20*time.Second)
	resp.Body.Close()

	status, body := httpGetBody(t, manifestURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for DASH manifest, got %d", status)
	}
	if !strings.Contains(body, "MPD") {
		t.Errorf("Expected DASH manifest to contain MPD")
	}
}

func TestRTMP_DualModePassthrough(t *testing.T) {
	liveStreamKey := "dual-e2e-secret"
	username := "dualalice"
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
        dash:
          segment_duration: 2
          window_size: 3
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	cmd := streamToRTMP(t, rtmpURL, token, 15)

	// In dual mode, both DASH manifest and HLS master playlist should be generated
	manifestPath := filepath.Join(ts.DataDir, "live", username, constants.DashManifest)
	waitForFile(t, manifestPath, 15*time.Second)

	masterPath := filepath.Join(ts.DataDir, "live", username, constants.MasterPlaylist)
	waitForFile(t, masterPath, 15*time.Second)

	// Fetch DASH manifest via HTTP
	manifestURL := fmt.Sprintf("http://%s/user/%s/%s", ts.HTTPAddr, username, constants.DashManifest)
	status, body := httpGetBody(t, manifestURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for DASH manifest, got %d", status)
	}
	if !strings.Contains(body, "MPD") {
		t.Errorf("Expected DASH manifest content")
	}

	// Fetch HLS master playlist via HTTP
	masterURL := fmt.Sprintf("http://%s/user/%s/%s", ts.HTTPAddr, username, constants.MasterPlaylist)
	status, body = httpGetBody(t, masterURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for HLS master playlist, got %d", status)
	}
	if !strings.Contains(body, "EXTM3U") {
		t.Errorf("Expected HLS playlist content")
	}

	// Stop the FFmpeg stream
	cmd.Process.Kill()
	cmd.Wait()

	// Wait for cleanup
	time.Sleep(4 * time.Second)
	waitForFileAbsent(t, manifestPath, 10*time.Second)
}

// --- Recording tests for DASH ---

func TestRecording_DashInPlace(t *testing.T) {
	liveStreamKey := "dash-rec-inplace"
	username := "dashrec"
	token := computeXORToken(liveStreamKey, username)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "%s"
      auth_token_template: "{username}"
      distribution:
        dash:
          segment_duration: 2
          window_size: 3
      record:
        enabled: true
`, liveStreamKey),
	})

	// Start RTMP stream
	rtmpURL := fmt.Sprintf("rtmp://127.0.0.1:%d/user/%s", ts.RTMPPort, username)
	cmd := streamToRTMP(t, rtmpURL, token, 10)

	// Wait for DASH manifest to appear
	manifestPath := filepath.Join(ts.DataDir, "live", username, constants.DashManifest)
	waitForFile(t, manifestPath, 15*time.Second)

	// Stop the stream
	cmd.Process.Kill()
	cmd.Wait()

	// Wait for recording to be finalized
	time.Sleep(5 * time.Second)

	// For in-place DASH recording: manifest.mpd should remain in the stream path
	waitForFile(t, manifestPath, 10*time.Second)

	// Read the manifest and verify it's present
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatalf("Failed to read DASH manifest: %v", err)
	}
	if !strings.Contains(string(data), "MPD") {
		t.Error("Expected DASH manifest to contain MPD")
	}
}

func TestRecording_DashWithRecordPath(t *testing.T) {
	liveStreamKey := "dash-rec-move"
	username := "dashmoved"
	token := computeXORToken(liveStreamKey, username)

	ts := setupTestServer(t, TestConfig{
		Channels: fmt.Sprintf(`  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "%s"
      auth_token_template: "{username}"
      distribution:
        dash:
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

	// Wait for DASH manifest to appear during streaming
	manifestPath := filepath.Join(ts.DataDir, "live", username, constants.DashManifest)
	waitForFile(t, manifestPath, 15*time.Second)

	// Stop the stream
	cmd.Process.Kill()
	cmd.Wait()

	// Wait for recording to be finalized and files moved
	time.Sleep(8 * time.Second)

	// Files should be moved to the record path
	recordDir := filepath.Join(ts.DataDir, "recordings", username)

	entries, err := os.ReadDir(recordDir)
	if err != nil {
		t.Logf("Record dir not found at %s: %v", recordDir, err)
		return
	}

	hasManifest := false
	hasM4S := false
	for _, entry := range entries {
		if entry.Name() == constants.DashManifest {
			hasManifest = true
		}
		if strings.HasSuffix(entry.Name(), ".m4s") {
			hasM4S = true
		}
	}

	if !hasManifest {
		t.Error("Expected manifest.mpd in record path")
	}
	if !hasM4S {
		t.Error("Expected at least one .m4s segment in record path")
	}
}
