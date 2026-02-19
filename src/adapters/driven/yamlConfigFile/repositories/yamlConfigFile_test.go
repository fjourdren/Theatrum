package repositories

import (
	"os"
	"path/filepath"
	"testing"

	yamlConfigFileEntities "Theatrum/adapters/driven/yamlConfigFile/entities"
	"Theatrum/domain/models"
)

func newValidator() *YamlConfigFile {
	return &YamlConfigFile{}
}

func validStream() yamlConfigFileEntities.Stream {
	return yamlConfigFileEntities.Stream{
		Type: string(models.StreamTypeVideoEncoded),
		Path: "videos/{name}",
		Qualities: map[string]yamlConfigFileEntities.Quality{
			"low": {Width: 640, Height: 360, Framerate: 30, Bitrate: "800k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "64k", Codec: "aac"}},
		},
		Distribution: yamlConfigFileEntities.Distribution{
			Hls: yamlConfigFileEntities.Hls{SegmentDuration: 6, WindowSize: 3},
		},
	}
}

func validLiveStream() yamlConfigFileEntities.Stream {
	return yamlConfigFileEntities.Stream{
		Type: string(models.StreamTypeLive),
		Path: "live/{username}",
		Qualities: map[string]yamlConfigFileEntities.Quality{
			"low": {Width: 640, Height: 360, Framerate: 30, Bitrate: "800k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "64k", Codec: "aac"}},
		},
		Distribution: yamlConfigFileEntities.Distribution{
			Hls: yamlConfigFileEntities.Hls{SegmentDuration: 2, WindowSize: 3},
		},
		LiveStreamKey:     "secret",
		AuthTokenTemplate: "{username}",
	}
}

func TestYamlConfigFile_validateConfig(t *testing.T) {
	v := newValidator()

	t.Run("valid config", func(t *testing.T) {
		config := &yamlConfigFileEntities.Config{
			Server: yamlConfigFileEntities.Server{HTTPPort: 8080, RTMPPort: 1935},
			Channels: map[string]yamlConfigFileEntities.Channel{
				"/vod/{name}": {Stream: validStream()},
			},
		}
		if err := v.validateConfig(config); err != nil {
			t.Errorf("unexpected error: %v", err)
		}
	})

	t.Run("invalid HTTP port", func(t *testing.T) {
		config := &yamlConfigFileEntities.Config{
			Server:   yamlConfigFileEntities.Server{HTTPPort: 0, RTMPPort: 1935},
			Channels: map[string]yamlConfigFileEntities.Channel{},
		}
		if err := v.validateConfig(config); err == nil {
			t.Error("expected error for invalid HTTP port")
		}
	})

	t.Run("invalid RTMP port", func(t *testing.T) {
		config := &yamlConfigFileEntities.Config{
			Server:   yamlConfigFileEntities.Server{HTTPPort: 8080, RTMPPort: -1},
			Channels: map[string]yamlConfigFileEntities.Channel{},
		}
		if err := v.validateConfig(config); err == nil {
			t.Error("expected error for invalid RTMP port")
		}
	})

	t.Run("invalid channel name", func(t *testing.T) {
		config := &yamlConfigFileEntities.Config{
			Server: yamlConfigFileEntities.Server{HTTPPort: 8080, RTMPPort: 1935},
			Channels: map[string]yamlConfigFileEntities.Channel{
				"/": {Stream: validStream()},
			},
		}
		if err := v.validateConfig(config); err == nil {
			t.Error("expected error for invalid channel name '/'")
		}
	})
}

func TestYamlConfigFile_validateStream(t *testing.T) {
	v := newValidator()

	t.Run("empty type", func(t *testing.T) {
		stream := validStream()
		stream.Type = ""
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for empty type")
		}
	})

	t.Run("invalid type", func(t *testing.T) {
		stream := validStream()
		stream.Type = "invalid_type"
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for invalid type")
		}
	})

	t.Run("empty path", func(t *testing.T) {
		stream := validStream()
		stream.Path = ""
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for empty path")
		}
	})

	t.Run("path traversal", func(t *testing.T) {
		stream := validStream()
		stream.Path = "videos/../etc/passwd"
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for path traversal")
		}
	})

	t.Run("viewers on non-live", func(t *testing.T) {
		stream := validStream()
		stream.Viewers = yamlConfigFileEntities.Viewers{Enabled: true, Window: 30}
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for viewers on non-live stream")
		}
	})

	t.Run("missing live_stream_key", func(t *testing.T) {
		stream := validLiveStream()
		stream.LiveStreamKey = ""
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for missing live_stream_key")
		}
	})

	t.Run("missing auth_token_template", func(t *testing.T) {
		stream := validLiveStream()
		stream.AuthTokenTemplate = ""
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for missing auth_token_template")
		}
	})

	t.Run("video_unencoded missing input_path", func(t *testing.T) {
		stream := yamlConfigFileEntities.Stream{
			Type: string(models.StreamTypeVideoUnEncoded),
			Path: "encoded/{name}",
			Qualities: map[string]yamlConfigFileEntities.Quality{
				"low": {Width: 640, Height: 360, Framerate: 30, Bitrate: "800k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "64k", Codec: "aac"}},
			},
			Distribution: yamlConfigFileEntities.Distribution{
				Hls: yamlConfigFileEntities.Hls{SegmentDuration: 6, WindowSize: 3},
			},
		}
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for missing video_input_path")
		}
	})

	t.Run("record on video_encoded", func(t *testing.T) {
		stream := validStream()
		stream.Record = yamlConfigFileEntities.Record{Enabled: true}
		if err := v.validateStream(stream, "test"); err == nil {
			t.Error("expected error for record on video_encoded")
		}
	})
}

func TestYamlConfigFile_validateQuality(t *testing.T) {
	v := newValidator()

	tests := []struct {
		name    string
		quality yamlConfigFileEntities.Quality
		wantErr bool
	}{
		{
			"valid",
			yamlConfigFileEntities.Quality{Width: 1920, Height: 1080, Framerate: 30, Bitrate: "5000k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "128k", Codec: "aac"}},
			false,
		},
		{"invalid width", yamlConfigFileEntities.Quality{Width: 0, Height: 1080, Framerate: 30, Bitrate: "5000k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "128k", Codec: "aac"}}, true},
		{"invalid height", yamlConfigFileEntities.Quality{Width: 1920, Height: 0, Framerate: 30, Bitrate: "5000k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "128k", Codec: "aac"}}, true},
		{"invalid framerate", yamlConfigFileEntities.Quality{Width: 1920, Height: 1080, Framerate: -1, Bitrate: "5000k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "128k", Codec: "aac"}}, true},
		{"empty bitrate", yamlConfigFileEntities.Quality{Width: 1920, Height: 1080, Framerate: 30, Bitrate: "", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "128k", Codec: "aac"}}, true},
		{"empty codec", yamlConfigFileEntities.Quality{Width: 1920, Height: 1080, Framerate: 30, Bitrate: "5000k", Codec: "", Audio: yamlConfigFileEntities.Audio{Bitrate: "128k", Codec: "aac"}}, true},
		{"empty audio bitrate", yamlConfigFileEntities.Quality{Width: 1920, Height: 1080, Framerate: 30, Bitrate: "5000k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "", Codec: "aac"}}, true},
		{"empty audio codec", yamlConfigFileEntities.Quality{Width: 1920, Height: 1080, Framerate: 30, Bitrate: "5000k", Codec: "libx264", Audio: yamlConfigFileEntities.Audio{Bitrate: "128k", Codec: ""}}, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := v.validateQuality(tt.quality, "test")
			if (err != nil) != tt.wantErr {
				t.Errorf("validateQuality() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestYamlConfigFile_validateDistribution(t *testing.T) {
	v := newValidator()

	tests := []struct {
		name    string
		dist    yamlConfigFileEntities.Distribution
		wantErr bool
	}{
		{
			"valid",
			yamlConfigFileEntities.Distribution{Hls: yamlConfigFileEntities.Hls{SegmentDuration: 4, WindowSize: 3}},
			false,
		},
		{
			"invalid segment_duration",
			yamlConfigFileEntities.Distribution{Hls: yamlConfigFileEntities.Hls{SegmentDuration: 0, WindowSize: 3}},
			true,
		},
		{
			"negative window_size",
			yamlConfigFileEntities.Distribution{Hls: yamlConfigFileEntities.Hls{SegmentDuration: 4, WindowSize: -1}},
			true,
		},
		{
			"zero window_size is valid",
			yamlConfigFileEntities.Distribution{Hls: yamlConfigFileEntities.Hls{SegmentDuration: 4, WindowSize: 0}},
			false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := v.validateDistribution(tt.dist, "live", "test")
			if (err != nil) != tt.wantErr {
				t.Errorf("validateDistribution() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestYamlConfigFile_validateAuthTokenTemplate(t *testing.T) {
	v := newValidator()

	tests := []struct {
		name        string
		channelName string
		stream      yamlConfigFileEntities.Stream
		wantErr     bool
	}{
		{
			"variable exists in pattern",
			"/user/{username}",
			yamlConfigFileEntities.Stream{AuthTokenTemplate: "{username}"},
			false,
		},
		{
			"variable missing from pattern",
			"/user/{username}",
			yamlConfigFileEntities.Stream{AuthTokenTemplate: "{room_id}"},
			true,
		},
		{
			"no variables in template",
			"/user/{username}",
			yamlConfigFileEntities.Stream{AuthTokenTemplate: "static_token"},
			true,
		},
		{
			"multiple variables all present",
			"/room/{room_id}/{username}",
			yamlConfigFileEntities.Stream{AuthTokenTemplate: "{room_id}{username}"},
			false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := v.validateAuthTokenTemplate(tt.channelName, tt.stream)
			if (err != nil) != tt.wantErr {
				t.Errorf("validateAuthTokenTemplate() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestYamlConfigFile_validatePath(t *testing.T) {
	v := newValidator()

	tests := []struct {
		name    string
		path    string
		wantErr bool
	}{
		{"valid path", "videos/stream1", false},
		{"valid path with placeholder", "videos/{name}", false},
		{"path traversal", "videos/../etc", true},
		{"absolute path unix", "/etc/passwd", true},
		{"absolute path windows", "\\windows\\system32", true},
		{"windows drive", "C:\\data", true},
		{"empty segments", "videos//stream1", true},
		{"dangerous null byte", "videos/%00test", true},
		{"dangerous pipe", "videos/test|cmd", true},
		{"dangerous redirect", "videos/test>file", true},
		{"dangerous wildcard", "videos/test*", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := v.validatePath(tt.path, "test")
			if (err != nil) != tt.wantErr {
				t.Errorf("validatePath(%q) error = %v, wantErr %v", tt.path, err, tt.wantErr)
			}
		})
	}
}

func TestYamlConfigFile_Load(t *testing.T) {
	t.Run("full integration with temp YAML file", func(t *testing.T) {
		configContent := `
application:
  public_path: "http://localhost:8080"
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
      qualities:
        low:
          width: 640
          height: 360
          framerate: 30
          bitrate: "800k"
          codec: "libx264"
          audio:
            bitrate: "64k"
            codec: "aac"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
`
		tmpDir := t.TempDir()
		configPath := filepath.Join(tmpDir, "config.yml")
		if err := os.WriteFile(configPath, []byte(configContent), 0644); err != nil {
			t.Fatalf("failed to write config file: %v", err)
		}

		loader := NewYamlConfigFile()
		app, server, channels, err := loader.Load(configPath)
		if err != nil {
			t.Fatalf("Load() error: %v", err)
		}

		if app.PublicPath != "http://localhost:8080" {
			t.Errorf("expected PublicPath http://localhost:8080, got %q", app.PublicPath)
		}
		if server.HTTPPort != 8080 {
			t.Errorf("expected HTTPPort 8080, got %d", server.HTTPPort)
		}
		if server.RTMPPort != 1935 {
			t.Errorf("expected RTMPPort 1935, got %d", server.RTMPPort)
		}
		if len(*channels) != 1 {
			t.Fatalf("expected 1 channel, got %d", len(*channels))
		}
		ch := (*channels)["/user/{username}"]
		if ch.Type != models.StreamTypeLive {
			t.Errorf("expected StreamTypeLive, got %s", ch.Type)
		}
		if ch.LiveStreamKey != "secret" {
			t.Errorf("expected LiveStreamKey secret, got %q", ch.LiveStreamKey)
		}
	})

	t.Run("file not found", func(t *testing.T) {
		loader := NewYamlConfigFile()
		_, _, _, err := loader.Load("/nonexistent/config.yml")
		if err == nil {
			t.Error("expected error for nonexistent file")
		}
	})

	t.Run("invalid YAML", func(t *testing.T) {
		tmpDir := t.TempDir()
		configPath := filepath.Join(tmpDir, "config.yml")
		if err := os.WriteFile(configPath, []byte("{{invalid yaml"), 0644); err != nil {
			t.Fatalf("failed to write config file: %v", err)
		}

		loader := NewYamlConfigFile()
		_, _, _, err := loader.Load(configPath)
		if err == nil {
			t.Error("expected error for invalid YAML")
		}
	})
}
