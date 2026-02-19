//go:build !e2e

package stream

import (
	"context"
	"strings"
	"testing"

	"Theatrum/constants"
	"Theatrum/domain/models"
)

func TestDetermineOutputMode(t *testing.T) {
	tests := []struct {
		name     string
		dist     models.Distribution
		expected OutputMode
	}{
		{
			"hls only",
			models.Distribution{Hls: &models.Hls{SegmentDuration: 2}},
			OutputModeHLS,
		},
		{
			"dash only",
			models.Distribution{Dash: &models.Dash{SegmentDuration: 4}},
			OutputModeDASH,
		},
		{
			"dual mode",
			models.Distribution{
				Hls:  &models.Hls{SegmentDuration: 2},
				Dash: &models.Dash{SegmentDuration: 2},
			},
			OutputModeDual,
		},
		{
			"neither defaults to hls",
			models.Distribution{},
			OutputModeHLS,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := DetermineOutputMode(tt.dist)
			if got != tt.expected {
				t.Errorf("DetermineOutputMode() = %d, want %d", got, tt.expected)
			}
		})
	}
}

func TestDashExtraWindowSize(t *testing.T) {
	tests := []struct {
		name      string
		recording bool
		expected  string
	}{
		{"recording keeps all segments", true, "999999"},
		{"non-recording deletes old segments", false, "0"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := dashExtraWindowSize(tt.recording)
			if got != tt.expected {
				t.Errorf("dashExtraWindowSize(%v) = %q, want %q", tt.recording, got, tt.expected)
			}
		})
	}
}

func TestHlsFlags(t *testing.T) {
	tests := []struct {
		name      string
		recording bool
		expected  string
	}{
		{"recording omits delete_segments", true, "temp_file+independent_segments"},
		{"non-recording includes delete_segments", false, "delete_segments+temp_file+independent_segments"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := hlsFlags(tt.recording)
			if got != tt.expected {
				t.Errorf("hlsFlags(%v) = %q, want %q", tt.recording, got, tt.expected)
			}
		})
	}
}

func TestCreateFFmpegCommand_HLSPassthrough(t *testing.T) {
	stream := &models.Stream{
		Distribution: models.Distribution{
			Hls: &models.Hls{SegmentDuration: 2, WindowSize: 3},
		},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeHLS)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-f hls") {
		t.Errorf("expected -f hls in command, got: %s", args)
	}
	if !strings.Contains(args, "-c:v copy") {
		t.Errorf("expected -c:v copy in passthrough command, got: %s", args)
	}
	if !strings.Contains(args, constants.SubPlaylist) {
		t.Errorf("expected %s in command, got: %s", constants.SubPlaylist, args)
	}
}

func TestCreateFFmpegCommand_HLSMultiQuality(t *testing.T) {
	stream := &models.Stream{
		Qualities: map[string]models.Quality{
			"low": {Width: 640, Height: 360, Bitrate: "800k", Codec: "libx264", Audio: models.Audio{Bitrate: "96k", Codec: "aac"}},
		},
		Distribution: models.Distribution{
			Hls: &models.Hls{SegmentDuration: 2, WindowSize: 3},
		},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeHLS)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-f hls") {
		t.Errorf("expected -f hls in command, got: %s", args)
	}
	if !strings.Contains(args, "-master_pl_name") {
		t.Errorf("expected -master_pl_name in multi-quality command, got: %s", args)
	}
	if !strings.Contains(args, "-var_stream_map") {
		t.Errorf("expected -var_stream_map in multi-quality command, got: %s", args)
	}
}

func TestCreateFFmpegCommand_DASHPassthrough(t *testing.T) {
	stream := &models.Stream{
		Distribution: models.Distribution{
			Dash: &models.Dash{SegmentDuration: 4, WindowSize: 5},
		},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeDASH)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-f dash") {
		t.Errorf("expected -f dash in command, got: %s", args)
	}
	if !strings.Contains(args, "-c:v copy") {
		t.Errorf("expected -c:v copy in passthrough command, got: %s", args)
	}
	if !strings.Contains(args, constants.DashManifest) {
		t.Errorf("expected %s in command, got: %s", constants.DashManifest, args)
	}
	if strings.Contains(args, "-hls_playlist") {
		t.Errorf("did not expect -hls_playlist in DASH-only mode, got: %s", args)
	}
}

func TestCreateFFmpegCommand_DASHMultiQuality(t *testing.T) {
	stream := &models.Stream{
		Qualities: map[string]models.Quality{
			"low": {Width: 640, Height: 360, Bitrate: "800k", Codec: "libx264", Audio: models.Audio{Bitrate: "96k", Codec: "aac"}},
		},
		Distribution: models.Distribution{
			Dash: &models.Dash{SegmentDuration: 4, WindowSize: 5},
		},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeDASH)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-f dash") {
		t.Errorf("expected -f dash in command, got: %s", args)
	}
	if !strings.Contains(args, "-adaptation_sets") {
		t.Errorf("expected -adaptation_sets in multi-quality DASH command, got: %s", args)
	}
	if strings.Contains(args, "-hls_playlist") {
		t.Errorf("did not expect -hls_playlist in DASH-only mode, got: %s", args)
	}
}

func TestCreateFFmpegCommand_DualPassthrough(t *testing.T) {
	stream := &models.Stream{
		Distribution: models.Distribution{
			Hls:  &models.Hls{SegmentDuration: 2, WindowSize: 3},
			Dash: &models.Dash{SegmentDuration: 2, WindowSize: 3},
		},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeDual)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-f dash") {
		t.Errorf("expected -f dash in dual mode command, got: %s", args)
	}
	if !strings.Contains(args, "-hls_playlist 1") {
		t.Errorf("expected -hls_playlist 1 in dual mode, got: %s", args)
	}
	if !strings.Contains(args, constants.DashManifest) {
		t.Errorf("expected %s in command, got: %s", constants.DashManifest, args)
	}
}

func TestCreateFFmpegCommand_DualMultiQuality(t *testing.T) {
	stream := &models.Stream{
		Qualities: map[string]models.Quality{
			"low": {Width: 640, Height: 360, Bitrate: "800k", Codec: "libx264", Audio: models.Audio{Bitrate: "96k", Codec: "aac"}},
		},
		Distribution: models.Distribution{
			Hls:  &models.Hls{SegmentDuration: 2, WindowSize: 3},
			Dash: &models.Dash{SegmentDuration: 2, WindowSize: 3},
		},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeDual)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-f dash") {
		t.Errorf("expected -f dash in dual mode command, got: %s", args)
	}
	if !strings.Contains(args, "-hls_playlist 1") {
		t.Errorf("expected -hls_playlist 1 in dual mode, got: %s", args)
	}
	if !strings.Contains(args, "-adaptation_sets") {
		t.Errorf("expected -adaptation_sets in multi-quality dual mode, got: %s", args)
	}
}

func TestCreateFFmpegCommand_DASHWithRecording(t *testing.T) {
	stream := &models.Stream{
		Distribution: models.Distribution{
			Dash: &models.Dash{SegmentDuration: 4, WindowSize: 5},
		},
		Record: models.Record{Enabled: true},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeDASH)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-extra_window_size 999999") {
		t.Errorf("expected -extra_window_size 999999 for recording, got: %s", args)
	}
}

func TestCreateFFmpegCommand_DASHWithoutRecording(t *testing.T) {
	stream := &models.Stream{
		Distribution: models.Distribution{
			Dash: &models.Dash{SegmentDuration: 4, WindowSize: 5},
		},
		Record: models.Record{Enabled: false},
	}

	ctx := context.Background()
	cmd := createFFmpegCommand(ctx, "/tmp/output", stream, OutputModeDASH)

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-extra_window_size 0") {
		t.Errorf("expected -extra_window_size 0 for non-recording, got: %s", args)
	}
}
