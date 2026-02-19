//go:build e2e

package e2e

import (
	"bufio"
	"context"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	fileAccessRepository "Theatrum/adapters/driven/fileAccess/repositories"
	"Theatrum/adapters/driven/metrics"
	yamlConfigFileRepository "Theatrum/adapters/driven/yamlConfigFile/repositories"
	httpAdapter "Theatrum/adapters/driver/http"
	"Theatrum/adapters/driver/ports"
	rtmpAdapter "Theatrum/adapters/driver/rtmp"
	"Theatrum/constants"
	"Theatrum/domain/services"
)

// TestConfig holds the configuration for a test server instance.
type TestConfig struct {
	// Channels is the YAML channels block content (indented under "channels:")
	Channels string
	// EnableAllStreamsPlaylist enables the all_streams playlist feature
	EnableAllStreamsPlaylist bool
	// ReconnectDelay overrides the RTMP reconnect delay (default: 1)
	ReconnectDelay int
	// CleanupDelay overrides the RTMP cleanup delay (default: 1)
	CleanupDelay int
}

// TestServer holds references to a running test server.
type TestServer struct {
	HTTPAddr string
	RTMPAddr string
	HTTPPort int
	RTMPPort int
	DataDir  string

	AppService      *services.ApplicationService
	ViewerTracker   *services.ViewerTracker
	HTTPServer      ports.HttpPort
	RTMPServer      ports.RtmpPort
}

// setupTestServer creates and starts a full server stack for E2E testing.
func setupTestServer(t *testing.T, cfg TestConfig) *TestServer {
	t.Helper()

	// Get random ports
	httpPort := getRandomPort(t)
	rtmpPort := getRandomPort(t)

	// Set defaults
	if cfg.ReconnectDelay == 0 {
		cfg.ReconnectDelay = 1
	}
	if cfg.CleanupDelay == 0 {
		cfg.CleanupDelay = 1
	}

	// Write config file
	configPath := writeTestConfig(t, cfg, httpPort, rtmpPort)

	// Load config via real YAML adapter
	configRepo := yamlConfigFileRepository.NewYamlConfigFile()
	application, server, channels, err := configRepo.Load(configPath)
	if err != nil {
		t.Fatalf("Failed to load config: %v", err)
	}

	// Wire real services
	storage := fileAccessRepository.NewFileAccess()
	templateService := services.NewPathTemplateService()
	appService := services.NewApplicationService(application, server, channels, storage, templateService)
	streamService := services.NewStreamService(templateService)
	rtmpAuthService := services.NewRtmpAuthService(appService.GetChannels(), templateService)
	registry := services.NewLiveStreamRegistry()
	viewerTracker := services.NewViewerTracker(storage)
	m := metrics.NewTestableMetrics()

	// Create servers
	httpServer := httpAdapter.NewHttpServer(appService, streamService, templateService, registry, viewerTracker, m)
	rtmpServer := rtmpAdapter.NewRtmpServer(appService, streamService, rtmpAuthService, templateService, registry, viewerTracker, m)

	// Start HTTP server
	go func() {
		if err := httpServer.StartHttpServer(); err != nil && err != http.ErrServerClosed {
			// Only log, don't fail - server may shut down normally
			t.Logf("HTTP server stopped: %v", err)
		}
	}()

	// Start RTMP server
	go func() {
		if err := rtmpServer.StartRtmpServer(); err != nil {
			t.Logf("RTMP server stopped: %v", err)
		}
	}()

	// Wait for servers to be ready
	httpAddr := fmt.Sprintf("127.0.0.1:%d", httpPort)
	rtmpAddr := fmt.Sprintf("127.0.0.1:%d", rtmpPort)

	waitForTCP(t, httpAddr, 5*time.Second)
	waitForTCP(t, rtmpAddr, 5*time.Second)

	ts := &TestServer{
		HTTPAddr:      httpAddr,
		RTMPAddr:      rtmpAddr,
		HTTPPort:      httpPort,
		RTMPPort:      rtmpPort,
		DataDir:       constants.VideoDir,
		AppService:    appService,
		ViewerTracker: viewerTracker,
		HTTPServer:    httpServer,
		RTMPServer:    rtmpServer,
	}

	// Register cleanup
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		httpServer.Shutdown(ctx)
		rtmpServer.ShutdownRtmpServer()
	})

	return ts
}

// writeTestConfig generates a YAML config file and returns its path.
func writeTestConfig(t *testing.T, cfg TestConfig, httpPort, rtmpPort int) string {
	t.Helper()

	allStreamsEnabled := "false"
	allStreamsPath := ""
	if cfg.EnableAllStreamsPlaylist {
		allStreamsEnabled = "true"
		allStreamsPath = "all_streams.m3u8"
	}

	config := fmt.Sprintf(`application:
  public_path: "http://127.0.0.1:%d"
  all_streams_playlist:
    enabled: %s
    path: "%s"

server:
  http: %d
  rtmp: %d
  rtmp_config:
    reconnect_delay: %d
    cleanup_delay: %d

channels:
%s
`, httpPort, allStreamsEnabled, allStreamsPath, httpPort, rtmpPort, cfg.ReconnectDelay, cfg.CleanupDelay, cfg.Channels)

	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(config), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}
	return configPath
}

// getRandomPort finds a free TCP port.
func getRandomPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to get random port: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return port
}

// computeXORToken replicates the XOR authentication logic from rtmpAuthService.
func computeXORToken(liveStreamKey, input string) string {
	inputBytes := []byte(input)
	result := make([]byte, len(inputBytes))
	for i := 0; i < len(inputBytes); i++ {
		result[i] = inputBytes[i] ^ liveStreamKey[i%len(liveStreamKey)]
	}
	return hex.EncodeToString(result)
}

// streamToRTMP spawns FFmpeg as an RTMP client that streams the test video.
// Returns the exec.Cmd so the caller can wait or kill it.
func streamToRTMP(t *testing.T, rtmpURL, publishName string, durationSec int) *exec.Cmd {
	t.Helper()

	testVideoPath := testVideoPath(t)

	fullURL := fmt.Sprintf("%s/%s", rtmpURL, publishName)

	args := []string{
		"-re",
		"-stream_loop", "-1",
		"-t", fmt.Sprintf("%d", durationSec),
		"-i", testVideoPath,
		"-c", "copy",
		"-f", "flv",
		fullURL,
	}

	cmd := exec.Command("ffmpeg", args...)
	// Capture stderr for debugging
	cmd.Stderr = io.Discard

	if err := cmd.Start(); err != nil {
		t.Fatalf("Failed to start FFmpeg RTMP client: %v", err)
	}

	t.Cleanup(func() {
		if cmd.Process != nil {
			cmd.Process.Kill()
			cmd.Wait()
		}
	})

	return cmd
}

// testVideoPath returns the absolute path to the test video.
func testVideoPath(t *testing.T) string {
	t.Helper()
	// Find the test video relative to this test file
	path := filepath.Join(getTestdataDir(t), "test.mp4")
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("Test video not found at %s. Run generate_test_video.sh first.", path)
	}
	return path
}

// getTestdataDir returns the absolute path to the testdata directory.
func getTestdataDir(t *testing.T) string {
	t.Helper()
	// Walk up from working directory to find src/e2e/testdata
	wd, err := os.Getwd()
	if err != nil {
		t.Fatalf("Failed to get working directory: %v", err)
	}

	// Try current directory first (when run from src/e2e/)
	candidate := filepath.Join(wd, "testdata")
	if _, err := os.Stat(candidate); err == nil {
		return candidate
	}

	// Try from src/
	candidate = filepath.Join(wd, "e2e", "testdata")
	if _, err := os.Stat(candidate); err == nil {
		return candidate
	}

	t.Fatalf("Cannot find testdata directory from %s", wd)
	return ""
}

// waitForTCP polls until a TCP port is accepting connections.
func waitForTCP(t *testing.T, addr string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err == nil {
			conn.Close()
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("Timed out waiting for TCP %s", addr)
}

// waitForFile polls until a file exists on disk.
func waitForFile(t *testing.T, path string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(path); err == nil {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("Timed out waiting for file: %s", path)
}

// waitForFileAbsent polls until a file no longer exists on disk.
func waitForFileAbsent(t *testing.T, path string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(path); os.IsNotExist(err) {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("Timed out waiting for file to be removed: %s", path)
}

// waitForHTTP polls until an HTTP URL returns 200.
func waitForHTTP(t *testing.T, url string, timeout time.Duration) *http.Response {
	t.Helper()
	client := &http.Client{Timeout: 2 * time.Second}
	deadline := time.Now().Add(timeout)
	var lastErr error
	for time.Now().Before(deadline) {
		resp, err := client.Get(url)
		if err == nil && resp.StatusCode == http.StatusOK {
			return resp
		}
		if err != nil {
			lastErr = err
		} else {
			lastErr = fmt.Errorf("status %d", resp.StatusCode)
			resp.Body.Close()
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("Timed out waiting for HTTP 200 at %s: %v", url, lastErr)
	return nil
}

// httpGet performs an HTTP GET and returns the response.
func httpGet(t *testing.T, url string) *http.Response {
	t.Helper()
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		t.Fatalf("HTTP GET %s failed: %v", url, err)
	}
	return resp
}

// httpGetBody performs an HTTP GET and returns the response body as a string.
func httpGetBody(t *testing.T, url string) (int, string) {
	t.Helper()
	resp := httpGet(t, url)
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("Failed to read response body: %v", err)
	}
	return resp.StatusCode, string(body)
}

// httpGetWithHeader performs an HTTP GET with a custom header.
func httpGetWithHeader(t *testing.T, url, headerKey, headerValue string) *http.Response {
	t.Helper()
	client := &http.Client{Timeout: 5 * time.Second}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		t.Fatalf("Failed to create request: %v", err)
	}
	req.Header.Set(headerKey, headerValue)
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("HTTP GET %s failed: %v", url, err)
	}
	return resp
}

// M3U8Info holds parsed information about an M3U8 playlist.
type M3U8Info struct {
	IsVOD             bool
	SegmentCount      int
	Segments          []string
	StreamPlaylists   []string // For master playlists: referenced variant playlists
	IsMaster          bool
	RawContent        string
}

// parseM3U8 parses an M3U8 playlist content and extracts key information.
func parseM3U8(content string) M3U8Info {
	info := M3U8Info{RawContent: content}

	scanner := bufio.NewScanner(strings.NewReader(content))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())

		if line == "#EXT-X-ENDLIST" {
			info.IsVOD = true
		}

		if strings.HasPrefix(line, "#EXT-X-STREAM-INF:") {
			info.IsMaster = true
		}

		// Non-comment, non-empty lines are either segment or playlist references
		if line != "" && !strings.HasPrefix(line, "#") {
			if strings.HasSuffix(line, ".ts") {
				info.SegmentCount++
				info.Segments = append(info.Segments, line)
			} else if strings.HasSuffix(line, ".m3u8") {
				info.StreamPlaylists = append(info.StreamPlaylists, line)
			}
		}
	}

	return info
}
