package mappers

import (
	"testing"

	"Theatrum/adapters/driven/yamlConfigFile/entities"
	"Theatrum/domain/models"
)

func TestToDomainServer(t *testing.T) {
	t.Run("all fields mapped", func(t *testing.T) {
		server := entities.Server{
			HTTPPort: 8080,
			RTMPPort: 1935,
			RTMP: entities.RTMP{
				ReconnectDelay: 60,
				CleanupDelay:   45,
			},
		}

		result := ToDomainServer(server)
		if result.HTTPPort != 8080 {
			t.Errorf("expected HTTPPort 8080, got %d", result.HTTPPort)
		}
		if result.RTMPPort != 1935 {
			t.Errorf("expected RTMPPort 1935, got %d", result.RTMPPort)
		}
		if result.RTMP.ReconnectDelay != 60 {
			t.Errorf("expected ReconnectDelay 60, got %d", result.RTMP.ReconnectDelay)
		}
		if result.RTMP.CleanupDelay != 45 {
			t.Errorf("expected CleanupDelay 45, got %d", result.RTMP.CleanupDelay)
		}
	})

	t.Run("RTMP defaults when zero", func(t *testing.T) {
		server := entities.Server{
			HTTPPort: 8080,
			RTMPPort: 1935,
			RTMP:     entities.RTMP{},
		}

		result := ToDomainServer(server)
		if result.RTMP.ReconnectDelay != 30 {
			t.Errorf("expected default ReconnectDelay 30, got %d", result.RTMP.ReconnectDelay)
		}
		if result.RTMP.CleanupDelay != 30 {
			t.Errorf("expected default CleanupDelay 30, got %d", result.RTMP.CleanupDelay)
		}
	})

	t.Run("negative values default to 30", func(t *testing.T) {
		server := entities.Server{
			HTTPPort: 8080,
			RTMPPort: 1935,
			RTMP: entities.RTMP{
				ReconnectDelay: -1,
				CleanupDelay:   -5,
			},
		}

		result := ToDomainServer(server)
		if result.RTMP.ReconnectDelay != 30 {
			t.Errorf("expected default ReconnectDelay 30, got %d", result.RTMP.ReconnectDelay)
		}
		if result.RTMP.CleanupDelay != 30 {
			t.Errorf("expected default CleanupDelay 30, got %d", result.RTMP.CleanupDelay)
		}
	})
}

func TestToDomainQuality(t *testing.T) {
	quality := entities.Quality{
		Width:     1920,
		Height:    1080,
		Framerate: 30,
		Bitrate:   "5000k",
		Codec:     "libx264",
		Audio: entities.Audio{
			Bitrate: "128k",
			Codec:   "aac",
		},
	}

	result := ToDomainQuality(quality)
	if result.Width != 1920 {
		t.Errorf("expected Width 1920, got %d", result.Width)
	}
	if result.Height != 1080 {
		t.Errorf("expected Height 1080, got %d", result.Height)
	}
	if result.Framerate != 30 {
		t.Errorf("expected Framerate 30, got %d", result.Framerate)
	}
	if result.Bitrate != "5000k" {
		t.Errorf("expected Bitrate 5000k, got %q", result.Bitrate)
	}
	if result.Codec != "libx264" {
		t.Errorf("expected Codec libx264, got %q", result.Codec)
	}
	if result.Audio.Bitrate != "128k" {
		t.Errorf("expected Audio Bitrate 128k, got %q", result.Audio.Bitrate)
	}
	if result.Audio.Codec != "aac" {
		t.Errorf("expected Audio Codec aac, got %q", result.Audio.Codec)
	}
}

func TestToDomainStream(t *testing.T) {
	t.Run("all fields mapped", func(t *testing.T) {
		stream := entities.Stream{
			Type: "live",
			Path: "live/{username}",
			Qualities: map[string]entities.Quality{
				"low": {Width: 640, Height: 360, Framerate: 30, Bitrate: "800k", Codec: "libx264", Audio: entities.Audio{Bitrate: "64k", Codec: "aac"}},
			},
			Distribution: entities.Distribution{
				Hls: &entities.Hls{SegmentDuration: 4, WindowSize: 5},
			},
			LiveStreamKey:     "secret",
			AuthTokenTemplate: "{username}",
			Record:            entities.Record{Enabled: true, Path: "recordings/{username}"},
			Viewers:           entities.Viewers{Enabled: true, Window: 30},
			Views:             entities.Views{Enabled: true, Window: 15},
		}

		result := ToDomainStream(stream)
		if result.Type != models.StreamTypeLive {
			t.Errorf("expected StreamTypeLive, got %s", result.Type)
		}
		if result.Path != "live/{username}" {
			t.Errorf("expected path live/{username}, got %q", result.Path)
		}
		if len(result.Qualities) != 1 {
			t.Errorf("expected 1 quality, got %d", len(result.Qualities))
		}
		if result.Distribution.Hls.SegmentDuration != 4 {
			t.Errorf("expected SegmentDuration 4, got %d", result.Distribution.Hls.SegmentDuration)
		}
		if result.Distribution.Hls.WindowSize != 5 {
			t.Errorf("expected WindowSize 5, got %d", result.Distribution.Hls.WindowSize)
		}
		if result.LiveStreamKey != "secret" {
			t.Errorf("expected LiveStreamKey secret, got %q", result.LiveStreamKey)
		}
		if result.Record.Enabled != true {
			t.Error("expected Record.Enabled true")
		}
		if result.Viewers.Window != 30 {
			t.Errorf("expected Viewers.Window 30, got %d", result.Viewers.Window)
		}
		if result.Views.Window != 15 {
			t.Errorf("expected Views.Window 15, got %d", result.Views.Window)
		}
	})

	t.Run("window_size default when zero", func(t *testing.T) {
		stream := entities.Stream{
			Type: "video_encoded",
			Path: "videos/{name}",
			Distribution: entities.Distribution{
				Hls: &entities.Hls{SegmentDuration: 6, WindowSize: 0},
			},
		}

		result := ToDomainStream(stream)
		if result.Distribution.Hls.WindowSize != 3 {
			t.Errorf("expected default WindowSize 3, got %d", result.Distribution.Hls.WindowSize)
		}
	})

	t.Run("dash only distribution mapped", func(t *testing.T) {
		stream := entities.Stream{
			Type: "live",
			Path: "live/{username}",
			Distribution: entities.Distribution{
				Dash: &entities.Dash{SegmentDuration: 4, WindowSize: 5},
			},
			LiveStreamKey:     "secret",
			AuthTokenTemplate: "{username}",
		}

		result := ToDomainStream(stream)
		if result.Distribution.Hls != nil {
			t.Error("expected Hls to be nil for dash-only stream")
		}
		if result.Distribution.Dash == nil {
			t.Fatal("expected Dash to be non-nil")
		}
		if result.Distribution.Dash.SegmentDuration != 4 {
			t.Errorf("expected Dash SegmentDuration 4, got %d", result.Distribution.Dash.SegmentDuration)
		}
		if result.Distribution.Dash.WindowSize != 5 {
			t.Errorf("expected Dash WindowSize 5, got %d", result.Distribution.Dash.WindowSize)
		}
	})

	t.Run("dash window_size default when zero", func(t *testing.T) {
		stream := entities.Stream{
			Type: "live",
			Path: "live/{username}",
			Distribution: entities.Distribution{
				Dash: &entities.Dash{SegmentDuration: 4, WindowSize: 0},
			},
			LiveStreamKey:     "secret",
			AuthTokenTemplate: "{username}",
		}

		result := ToDomainStream(stream)
		if result.Distribution.Dash.WindowSize != 3 {
			t.Errorf("expected default Dash WindowSize 3, got %d", result.Distribution.Dash.WindowSize)
		}
	})

	t.Run("dual mode distribution mapped", func(t *testing.T) {
		stream := entities.Stream{
			Type: "live",
			Path: "live/{username}",
			Distribution: entities.Distribution{
				Hls:  &entities.Hls{SegmentDuration: 2, WindowSize: 3},
				Dash: &entities.Dash{SegmentDuration: 2, WindowSize: 3},
			},
			LiveStreamKey:     "secret",
			AuthTokenTemplate: "{username}",
		}

		result := ToDomainStream(stream)
		if result.Distribution.Hls == nil {
			t.Fatal("expected Hls to be non-nil in dual mode")
		}
		if result.Distribution.Dash == nil {
			t.Fatal("expected Dash to be non-nil in dual mode")
		}
		if result.Distribution.Hls.SegmentDuration != 2 {
			t.Errorf("expected HLS SegmentDuration 2, got %d", result.Distribution.Hls.SegmentDuration)
		}
		if result.Distribution.Dash.SegmentDuration != 2 {
			t.Errorf("expected DASH SegmentDuration 2, got %d", result.Distribution.Dash.SegmentDuration)
		}
	})
}

func TestToDomainStream_SourceURL(t *testing.T) {
	stream := entities.Stream{
		Type:      "restream",
		Path:      "restream/mystream",
		SourceURL: "rtmp://external-server/live/stream_key",
		Distribution: entities.Distribution{
			Hls: &entities.Hls{SegmentDuration: 2, WindowSize: 3},
		},
	}

	result := ToDomainStream(stream)
	if result.Type != models.StreamTypeRestream {
		t.Errorf("expected StreamTypeRestream, got %s", result.Type)
	}
	if result.SourceURL != "rtmp://external-server/live/stream_key" {
		t.Errorf("expected SourceURL rtmp://external-server/live/stream_key, got %q", result.SourceURL)
	}
}

func TestToDomainChannels(t *testing.T) {
	channels := map[string]entities.Channel{
		"/user/{username}": {
			Stream: entities.Stream{
				Type: "live",
				Path: "live/{username}",
				Distribution: entities.Distribution{
					Hls: &entities.Hls{SegmentDuration: 2, WindowSize: 3},
				},
			},
		},
		"/vod/{name}": {
			Stream: entities.Stream{
				Type: "video_encoded",
				Path: "videos/{name}",
				Distribution: entities.Distribution{
					Hls: &entities.Hls{SegmentDuration: 6},
				},
			},
		},
	}

	result := ToDomainChannels(channels)
	if len(result) != 2 {
		t.Fatalf("expected 2 channels, got %d", len(result))
	}
	if result["/user/{username}"].Type != models.StreamTypeLive {
		t.Error("expected /user/{username} to be live")
	}
	if result["/vod/{name}"].Type != models.StreamTypeVideoEncoded {
		t.Error("expected /vod/{name} to be video_encoded")
	}
}

func TestToDomainApplication(t *testing.T) {
	t.Run("public path mapped", func(t *testing.T) {
		app := entities.Application{PublicPath: "http://localhost:8080"}
		result := ToDomainApplication(app)
		if result.PublicPath != "http://localhost:8080" {
			t.Errorf("expected PublicPath http://localhost:8080, got %q", result.PublicPath)
		}
	})

	t.Run("all_streams_playlist enabled", func(t *testing.T) {
		app := entities.Application{
			AllStreamsPlaylist: entities.AllStreamsPlaylist{Enabled: true, Path: "/all.m3u8"},
		}
		result := ToDomainApplication(app)
		if !result.AllStreamsPlaylist.Enabled {
			t.Error("expected AllStreamsPlaylist.Enabled true")
		}
		if result.AllStreamsPlaylist.Path != "/all.m3u8" {
			t.Errorf("expected path /all.m3u8, got %q", result.AllStreamsPlaylist.Path)
		}
	})

	t.Run("all_streams_playlist disabled", func(t *testing.T) {
		app := entities.Application{
			AllStreamsPlaylist: entities.AllStreamsPlaylist{Enabled: false},
		}
		result := ToDomainApplication(app)
		if result.AllStreamsPlaylist.Enabled {
			t.Error("expected AllStreamsPlaylist.Enabled false")
		}
	})
}
