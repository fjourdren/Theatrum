//go:build e2e

package e2e

import (
	"os"
	"path/filepath"
	"testing"

	yamlConfigFileRepository "Theatrum/adapters/driven/yamlConfigFile/repositories"
)

func TestConfigLoading_ValidConfig(t *testing.T) {
	configContent := `
application:
  public_path: "http://localhost:8080"
  all_streams_playlist:
    enabled: false
    path: ""

server:
  http: 8080
  rtmp: 1935
  rtmp_config:
    reconnect_delay: 1
    cleanup_delay: 1

channels:
  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "test-secret"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
`
	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	repo := yamlConfigFileRepository.NewYamlConfigFile()
	app, server, channels, err := repo.Load(configPath)
	if err != nil {
		t.Fatalf("Expected valid config to load, got error: %v", err)
	}

	if app.PublicPath != "http://localhost:8080" {
		t.Errorf("Expected public_path 'http://localhost:8080', got %q", app.PublicPath)
	}
	if server.HTTPPort != 8080 {
		t.Errorf("Expected HTTP port 8080, got %d", server.HTTPPort)
	}
	if server.RTMPPort != 1935 {
		t.Errorf("Expected RTMP port 1935, got %d", server.RTMPPort)
	}
	if len(*channels) != 1 {
		t.Errorf("Expected 1 channel, got %d", len(*channels))
	}
}

func TestConfigLoading_InvalidYAML(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte("{{invalid yaml"), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	repo := yamlConfigFileRepository.NewYamlConfigFile()
	_, _, _, err := repo.Load(configPath)
	if err == nil {
		t.Fatal("Expected error for invalid YAML, got nil")
	}
}

func TestConfigLoading_MissingAuthFields(t *testing.T) {
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
      live_stream_key: "test-secret"
      distribution:
        hls:
          segment_duration: 2
`
	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	repo := yamlConfigFileRepository.NewYamlConfigFile()
	_, _, _, err := repo.Load(configPath)
	if err == nil {
		t.Fatal("Expected error for missing auth_token_template, got nil")
	}
}

func TestConfigLoading_PathTraversal(t *testing.T) {
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
      path: "../../../etc/passwd"
      live_stream_key: "test-secret"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
`
	configPath := filepath.Join(t.TempDir(), "config.yml")
	if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
		t.Fatalf("Failed to write config: %v", err)
	}

	repo := yamlConfigFileRepository.NewYamlConfigFile()
	_, _, _, err := repo.Load(configPath)
	if err == nil {
		t.Fatal("Expected error for path traversal, got nil")
	}
}
