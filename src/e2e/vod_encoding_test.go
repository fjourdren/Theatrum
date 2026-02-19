//go:build e2e

package e2e

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	fileAccessRepository "Theatrum/adapters/driven/fileAccess/repositories"
	"Theatrum/adapters/driven/metrics"
	yamlConfigFileRepository "Theatrum/adapters/driven/yamlConfigFile/repositories"
	ffmpegEncoderRepository "Theatrum/adapters/driven/ffmpegEncoder/repositories"
	"Theatrum/constants"
	"Theatrum/domain/jobs"
	"Theatrum/domain/services"
)

func TestVOD_UnencodedDetectionAndEncoding(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping VOD encoding test in short mode")
	}

	// Create a unique temp config for this test
	httpPort := getRandomPort(t)
	rtmpPort := getRandomPort(t)

	channelsYAML := `  "/videos/{name}":
    stream:
      type: video_unencoded
      path: "encoded/{name}"
      video_input_path: "raw/{name}"
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
          segment_duration: 4
`

	configContent := fmt.Sprintf(`application:
  public_path: "http://127.0.0.1:%d"
  all_streams_playlist:
    enabled: false
    path: ""

server:
  http: %d
  rtmp: %d
  rtmp_config:
    reconnect_delay: 1
    cleanup_delay: 1

channels:
%s
`, httpPort, httpPort, rtmpPort, channelsYAML)

	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	// Place test video in the raw input path
	rawDir := filepath.Join(constants.VideoDir, "raw", "testvideo")
	if err := os.MkdirAll(rawDir, 0755); err != nil {
		t.Fatalf("Failed to create raw dir: %v", err)
	}

	testVideo := testVideoPath(t)
	input, err := os.ReadFile(testVideo)
	if err != nil {
		t.Fatalf("Failed to read test video: %v", err)
	}
	rawVideoPath := filepath.Join(rawDir, "test.mp4")
	if err := os.WriteFile(rawVideoPath, input, 0644); err != nil {
		t.Fatalf("Failed to write raw video: %v", err)
	}

	// Load config
	configRepo := yamlConfigFileRepository.NewYamlConfigFile()
	application, server, channels, err := configRepo.Load(configPath)
	if err != nil {
		t.Fatalf("Failed to load config: %v", err)
	}

	// Wire services
	storage := fileAccessRepository.NewFileAccess()
	templateService := services.NewPathTemplateService()
	appService := services.NewApplicationService(application, server, channels, storage, templateService)
	encoder := ffmpegEncoderRepository.NewFfmpegEncoder()
	encodeService := services.NewEncodeService(encoder)
	m := metrics.NewTestableMetrics()
	encodeMetrics := metrics.NewEncodeMetricsAdapter(m)
	encodeQueue := jobs.NewEncodeJobQueue(encodeService, storage, encodeMetrics)
	videoDetector := jobs.NewVideoUnencodedDetector(appService, encodeQueue, storage, templateService)

	// Start the encode queue
	encodeQueue.Start()
	defer encodeQueue.Stop()

	// Run detection
	if err := videoDetector.DetectAndQueueVideos(); err != nil {
		t.Fatalf("Detection failed: %v", err)
	}

	// Wait for encoding to complete
	// This will take some time as FFmpeg needs to encode
	encodedDir := filepath.Join(constants.VideoDir, "encoded", "testvideo")
	lowDir := filepath.Join(encodedDir, "low")

	// Wait for the low quality playlist to appear
	lowPlaylistPath := filepath.Join(lowDir, constants.SubPlaylist)
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(lowPlaylistPath); err == nil {
			break
		}
		time.Sleep(1 * time.Second)
	}

	if _, err := os.Stat(lowPlaylistPath); err != nil {
		t.Fatalf("Encoded playlist not found at %s after timeout", lowPlaylistPath)
	}

	// Verify the encoded output
	data, err := os.ReadFile(lowPlaylistPath)
	if err != nil {
		t.Fatalf("Failed to read encoded playlist: %v", err)
	}

	m3u8 := parseM3U8(string(data))
	if !m3u8.IsVOD {
		t.Error("Expected VOD playlist (with #EXT-X-ENDLIST)")
	}
	if m3u8.SegmentCount == 0 {
		t.Error("Expected at least one encoded segment")
	}

	// Verify segments exist on disk
	for _, seg := range m3u8.Segments {
		segPath := filepath.Join(lowDir, seg)
		if _, err := os.Stat(segPath); err != nil {
			t.Errorf("Expected segment file %s to exist: %v", segPath, err)
		}
	}
}
