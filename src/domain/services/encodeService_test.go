package services

import (
	"fmt"
	"testing"

	"Theatrum/constants"
	"Theatrum/domain/models"
)

// mockEncoder implements repositories.EncoderPort for testing.
type mockEncoder struct {
	encodeVideoCalled bool
	lastInputPath     string
	lastOutputPath    string
	lastQualities     map[string]models.Quality
	lastDistribution  models.Distribution
	err               error
}

func (m *mockEncoder) EncodeVideo(inputPath string, outputPath string, qualities map[string]models.Quality, distribution models.Distribution) error {
	m.encodeVideoCalled = true
	m.lastInputPath = inputPath
	m.lastOutputPath = outputPath
	m.lastQualities = qualities
	m.lastDistribution = distribution
	return m.err
}

func TestEncodeService_EncodeStream(t *testing.T) {
	t.Run("success", func(t *testing.T) {
		enc := &mockEncoder{}
		svc := NewEncodeService(enc)

		channel := models.Stream{
			Path: "test/path",
			Qualities: map[string]models.Quality{
				"low":  {Width: 640, Height: 360},
				"high": {Width: 1920, Height: 1080},
			},
			Distribution: models.Distribution{
				Hls: &models.Hls{SegmentDuration: 6},
			},
		}

		err := svc.EncodeStream("input.mp4", "output/", channel)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !enc.encodeVideoCalled {
			t.Error("expected EncodeVideo to be called")
		}
		if enc.lastInputPath != "input.mp4" {
			t.Errorf("expected input path input.mp4, got %q", enc.lastInputPath)
		}
		if len(enc.lastQualities) != 2 {
			t.Errorf("expected 2 qualities, got %d", len(enc.lastQualities))
		}
	})

	t.Run("no qualities returns error", func(t *testing.T) {
		enc := &mockEncoder{}
		svc := NewEncodeService(enc)

		channel := models.Stream{
			Path:      "test/path",
			Qualities: map[string]models.Quality{},
		}

		err := svc.EncodeStream("input.mp4", "output/", channel)
		if err == nil {
			t.Error("expected error for empty qualities")
		}
		if enc.encodeVideoCalled {
			t.Error("EncodeVideo should not be called when no qualities")
		}
	})

	t.Run("encoder error propagated", func(t *testing.T) {
		enc := &mockEncoder{err: fmt.Errorf("ffmpeg crashed")}
		svc := NewEncodeService(enc)

		channel := models.Stream{
			Path: "test/path",
			Qualities: map[string]models.Quality{
				"low": {Width: 640, Height: 360},
			},
		}

		err := svc.EncodeStream("input.mp4", "output/", channel)
		if err == nil {
			t.Error("expected error from encoder")
		}
	})
}

func TestEncodeService_EncodeQuality(t *testing.T) {
	t.Run("with name", func(t *testing.T) {
		enc := &mockEncoder{}
		svc := NewEncodeService(enc)

		quality := models.Quality{Width: 1280, Height: 720}
		dist := models.Distribution{Hls: &models.Hls{SegmentDuration: 4}}

		err := svc.EncodeQuality("input.mp4", "output/", "medium", quality, dist)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !enc.encodeVideoCalled {
			t.Error("expected EncodeVideo to be called")
		}
		if _, ok := enc.lastQualities["medium"]; !ok {
			t.Error("expected quality key 'medium'")
		}
	})

	t.Run("empty name defaults to default quality", func(t *testing.T) {
		enc := &mockEncoder{}
		svc := NewEncodeService(enc)

		quality := models.Quality{Width: 640, Height: 360}
		dist := models.Distribution{}

		err := svc.EncodeQuality("input.mp4", "output/", "", quality, dist)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if _, ok := enc.lastQualities[constants.DefaultQuality]; !ok {
			t.Errorf("expected quality key %q", constants.DefaultQuality)
		}
	})

	t.Run("encoder error propagated", func(t *testing.T) {
		enc := &mockEncoder{err: fmt.Errorf("encode failed")}
		svc := NewEncodeService(enc)

		quality := models.Quality{Width: 640, Height: 360}
		dist := models.Distribution{}

		err := svc.EncodeQuality("input.mp4", "output/", "low", quality, dist)
		if err == nil {
			t.Error("expected error from encoder")
		}
	})
}
