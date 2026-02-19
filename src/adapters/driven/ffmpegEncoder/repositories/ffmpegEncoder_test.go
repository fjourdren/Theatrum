package repositories

import (
	"Theatrum/domain/models"
	"testing"
)

func TestFfmpegEncoder_EncodeVideo(t *testing.T) {
	multiQualities := map[string]models.Quality{
		"low": {
			Width:   640,
			Height:  360,
			Bitrate: "800k",
			Audio:   models.Audio{Bitrate: "96k", Codec: "aac"},
		},
		"medium": {
			Width:   1280,
			Height:  720,
			Bitrate: "2500k",
			Audio:   models.Audio{Bitrate: "128k", Codec: "aac"},
		},
		"high": {
			Width:   1920,
			Height:  1080,
			Bitrate: "5000k",
			Audio:   models.Audio{Bitrate: "192k", Codec: "aac"},
		},
	}

	tests := []struct {
		name          string
		inputPath     string
		outputPath    string
		qualities     map[string]models.Quality
		distribution  models.Distribution
		expectedError bool
	}{
		{
			name:       "HLS only encoding",
			inputPath:  "input.mp4",
			outputPath: "output/test_output.m3u8",
			qualities:  multiQualities,
			distribution: models.Distribution{
				Hls: &models.Hls{SegmentDuration: 10},
			},
			expectedError: false,
		},
		{
			name:       "DASH only encoding",
			inputPath:  "input.mp4",
			outputPath: "output/test_output.mpd",
			qualities:  multiQualities,
			distribution: models.Distribution{
				Dash: &models.Dash{SegmentDuration: 4},
			},
			expectedError: false,
		},
		{
			name:       "dual mode encoding",
			inputPath:  "input.mp4",
			outputPath: "output/test_output.mpd",
			qualities:  multiQualities,
			distribution: models.Distribution{
				Hls:  &models.Hls{SegmentDuration: 2},
				Dash: &models.Dash{SegmentDuration: 2},
			},
			expectedError: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			encoderInterface := NewFfmpegEncoder()
			encoder, ok := encoderInterface.(*FfmpegEncoder)
			if !ok {
				t.Fatal("Failed to type assert encoder to *FfmpegEncoder")
			}
			encoder.DryRun = true

			err := encoder.EncodeVideo(tt.inputPath, tt.outputPath, tt.qualities, tt.distribution)
			if (err != nil) != tt.expectedError {
				t.Errorf("EncodeVideo() error = %v, expectedError %v", err, tt.expectedError)
			}
		})
	}
}

func TestAddMuxing_HLSOnly(t *testing.T) {
	qualities := map[string]models.Quality{
		"low": {Width: 640, Height: 360, Bitrate: "800k", Audio: models.Audio{Bitrate: "96k", Codec: "aac"}},
	}
	dist := models.Distribution{
		Hls: &models.Hls{SegmentDuration: 6},
	}

	args := addMuxing([]string{}, "output/test.m3u8", dist, qualities)

	// Should use HLS muxer
	foundHLS := false
	for i, arg := range args {
		if arg == "-f" && i+1 < len(args) && args[i+1] == "hls" {
			foundHLS = true
			break
		}
	}
	if !foundHLS {
		t.Errorf("expected -f hls in args, got %v", args)
	}
}

func TestAddMuxing_DASHOnly(t *testing.T) {
	qualities := map[string]models.Quality{
		"low": {Width: 640, Height: 360, Bitrate: "800k", Audio: models.Audio{Bitrate: "96k", Codec: "aac"}},
	}
	dist := models.Distribution{
		Dash: &models.Dash{SegmentDuration: 4},
	}

	args := addMuxing([]string{}, "output/test.mpd", dist, qualities)

	// Should use DASH muxer
	foundDASH := false
	foundHLSPlaylist := false
	for i, arg := range args {
		if arg == "-f" && i+1 < len(args) && args[i+1] == "dash" {
			foundDASH = true
		}
		if arg == "-hls_playlist" {
			foundHLSPlaylist = true
		}
	}
	if !foundDASH {
		t.Errorf("expected -f dash in args, got %v", args)
	}
	if foundHLSPlaylist {
		t.Errorf("did not expect -hls_playlist in DASH-only mode, got %v", args)
	}
}

func TestAddMuxing_DualMode(t *testing.T) {
	qualities := map[string]models.Quality{
		"low": {Width: 640, Height: 360, Bitrate: "800k", Audio: models.Audio{Bitrate: "96k", Codec: "aac"}},
	}
	dist := models.Distribution{
		Hls:  &models.Hls{SegmentDuration: 2},
		Dash: &models.Dash{SegmentDuration: 2},
	}

	args := addMuxing([]string{}, "output/test.mpd", dist, qualities)

	// Should use DASH muxer with -hls_playlist 1
	foundDASH := false
	foundHLSPlaylist := false
	for i, arg := range args {
		if arg == "-f" && i+1 < len(args) && args[i+1] == "dash" {
			foundDASH = true
		}
		if arg == "-hls_playlist" && i+1 < len(args) && args[i+1] == "1" {
			foundHLSPlaylist = true
		}
	}
	if !foundDASH {
		t.Errorf("expected -f dash in args, got %v", args)
	}
	if !foundHLSPlaylist {
		t.Errorf("expected -hls_playlist 1 in dual mode, got %v", args)
	}
}