//go:build e2e

package e2e

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"Theatrum/constants"
)

func TestHTTP_ServePreExistingPlaylist(t *testing.T) {
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
        hls:
          segment_duration: 4
`,
	})

	// Create a pre-existing master playlist and segment on disk
	streamDir := filepath.Join(ts.DataDir, "videos", "testvideo")
	qualityDir := filepath.Join(streamDir, "low")
	if err := os.MkdirAll(qualityDir, 0755); err != nil {
		t.Fatalf("Failed to create stream dir: %v", err)
	}

	masterContent := `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
low/playlist.m3u8
`
	if err := os.WriteFile(filepath.Join(streamDir, constants.MasterPlaylist), []byte(masterContent), 0644); err != nil {
		t.Fatalf("Failed to write master playlist: %v", err)
	}

	playlistContent := `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:4.000,
segment_000.ts
#EXT-X-ENDLIST
`
	if err := os.WriteFile(filepath.Join(qualityDir, constants.SubPlaylist), []byte(playlistContent), 0644); err != nil {
		t.Fatalf("Failed to write sub playlist: %v", err)
	}

	segmentContent := []byte("fake-ts-segment-data")
	if err := os.WriteFile(filepath.Join(qualityDir, "segment_000.ts"), segmentContent, 0644); err != nil {
		t.Fatalf("Failed to write segment: %v", err)
	}

	// Test: fetch master playlist
	masterURL := fmt.Sprintf("http://%s/vod/testvideo/%s", ts.HTTPAddr, constants.MasterPlaylist)
	status, body := httpGetBody(t, masterURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for master playlist, got %d", status)
	}

	m3u8 := parseM3U8(body)
	if !m3u8.IsMaster {
		t.Error("Expected master playlist to be identified as master")
	}
	if len(m3u8.StreamPlaylists) != 1 {
		t.Errorf("Expected 1 stream playlist reference, got %d", len(m3u8.StreamPlaylists))
	}

	// Test: fetch sub playlist
	subURL := fmt.Sprintf("http://%s/vod/testvideo/low/%s", ts.HTTPAddr, constants.SubPlaylist)
	status, body = httpGetBody(t, subURL)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 for sub playlist, got %d", status)
	}

	subM3U8 := parseM3U8(body)
	if !subM3U8.IsVOD {
		t.Error("Expected sub playlist to be VOD (have #EXT-X-ENDLIST)")
	}
	if subM3U8.SegmentCount != 1 {
		t.Errorf("Expected 1 segment, got %d", subM3U8.SegmentCount)
	}

	// Test: fetch segment
	segURL := fmt.Sprintf("http://%s/vod/testvideo/low/segment_000.ts", ts.HTTPAddr)
	resp := httpGet(t, segURL)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("Expected 200 for segment, got %d", resp.StatusCode)
	}
	segBody, _ := io.ReadAll(resp.Body)
	if string(segBody) != string(segmentContent) {
		t.Error("Segment content mismatch")
	}
}

func TestHTTP_404ForMissingStream(t *testing.T) {
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
        hls:
          segment_duration: 4
`,
	})

	url := fmt.Sprintf("http://%s/vod/nonexistent/%s", ts.HTTPAddr, constants.MasterPlaylist)
	resp := httpGet(t, url)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("Expected 404 for missing stream, got %d", resp.StatusCode)
	}
}

func TestHTTP_CORSHeaders(t *testing.T) {
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
        hls:
          segment_duration: 4
`,
	})

	// Create a playlist to have a successful response
	streamDir := filepath.Join(ts.DataDir, "videos", "corstest")
	if err := os.MkdirAll(streamDir, 0755); err != nil {
		t.Fatalf("Failed to create dir: %v", err)
	}
	masterContent := `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
low/playlist.m3u8
`
	if err := os.WriteFile(filepath.Join(streamDir, constants.MasterPlaylist), []byte(masterContent), 0644); err != nil {
		t.Fatalf("Failed to write playlist: %v", err)
	}

	url := fmt.Sprintf("http://%s/vod/corstest/%s", ts.HTTPAddr, constants.MasterPlaylist)
	resp := httpGet(t, url)
	resp.Body.Close()

	// Check that CORS headers are present
	acao := resp.Header.Get("Access-Control-Allow-Origin")
	if acao == "" {
		t.Error("Expected Access-Control-Allow-Origin header to be set")
	}
}
