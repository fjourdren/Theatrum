package services

import (
	"strings"
	"testing"

	"Theatrum/constants"
	"Theatrum/domain/models"
)

func TestStreamService_GetStreamStoragePath(t *testing.T) {
	svc := NewStreamService(NewPathTemplateService())

	hlsDist := models.Distribution{Hls: &models.Hls{SegmentDuration: 6}}

	tests := []struct {
		name       string
		stream     *models.Stream
		vars       map[string]string
		wantSuffix string
		wantErr    bool
	}{
		{
			"default quality injected",
			&models.Stream{Path: "videos/{username}", Distribution: hlsDist},
			map[string]string{"username": "alice"},
			"videos/alice/default",
			false,
		},
		{
			"explicit quality in vars",
			&models.Stream{Path: "videos/{username}", Distribution: hlsDist},
			map[string]string{"username": "alice", "quality": "high"},
			"videos/alice/high",
			false,
		},
		{
			"quality placeholder in path",
			&models.Stream{Path: "videos/{username}/{quality}", Distribution: hlsDist},
			map[string]string{"username": "alice", "quality": "medium"},
			"videos/alice/medium",
			false,
		},
		{
			"master.m3u8 resource excludes quality dir",
			&models.Stream{Path: "videos/{username}", Distribution: hlsDist},
			map[string]string{"username": "alice", "resource": constants.MasterPlaylist},
			"videos/alice",
			false,
		},
		{
			"dash manifest excludes quality dir",
			&models.Stream{Path: "videos/{username}", Distribution: models.Distribution{Dash: &models.Dash{SegmentDuration: 6}}},
			map[string]string{"username": "alice", "resource": constants.DashManifest},
			"videos/alice",
			false,
		},
		{
			"dash-enabled stream skips quality subdir",
			&models.Stream{Path: "videos/{username}", Distribution: models.Distribution{Dash: &models.Dash{SegmentDuration: 6}}},
			map[string]string{"username": "alice", "quality": "high"},
			"videos/alice",
			false,
		},
		{
			"dual mode skips quality subdir",
			&models.Stream{Path: "videos/{username}", Distribution: models.Distribution{
				Hls:  &models.Hls{SegmentDuration: 2},
				Dash: &models.Dash{SegmentDuration: 2},
			}},
			map[string]string{"username": "alice", "quality": "high"},
			"videos/alice",
			false,
		},
		{
			"dual mode dash manifest excludes quality dir",
			&models.Stream{Path: "videos/{username}", Distribution: models.Distribution{
				Hls:  &models.Hls{SegmentDuration: 2},
				Dash: &models.Dash{SegmentDuration: 2},
			}},
			map[string]string{"username": "alice", "resource": constants.DashManifest},
			"videos/alice",
			false,
		},
		{
			"dual mode master playlist excludes quality dir",
			&models.Stream{Path: "videos/{username}", Distribution: models.Distribution{
				Hls:  &models.Hls{SegmentDuration: 2},
				Dash: &models.Dash{SegmentDuration: 2},
			}},
			map[string]string{"username": "alice", "resource": constants.MasterPlaylist},
			"videos/alice",
			false,
		},
		{
			"dash m4s segment excludes quality dir",
			&models.Stream{Path: "videos/{username}", Distribution: models.Distribution{Dash: &models.Dash{SegmentDuration: 4}}},
			map[string]string{"username": "alice", "resource": "chunk-stream0-00001.m4s"},
			"videos/alice",
			false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Copy vars to avoid mutation across tests
			vars := make(map[string]string)
			for k, v := range tt.vars {
				vars[k] = v
			}

			got, err := svc.GetStreamStoragePath(tt.stream, vars)
			if (err != nil) != tt.wantErr {
				t.Errorf("GetStreamStoragePath() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if tt.wantErr {
				return
			}
			if !strings.HasSuffix(got, tt.wantSuffix) {
				t.Errorf("GetStreamStoragePath() = %q, want suffix %q", got, tt.wantSuffix)
			}
			// Should start with the video directory
			if !strings.Contains(got, "data") {
				t.Errorf("expected path to contain 'data' directory, got %q", got)
			}
		})
	}
}

func TestStreamService_GetStreamStoragePath_ErrorPropagation(t *testing.T) {
	svc := NewStreamService(NewPathTemplateService())

	stream := &models.Stream{Path: "videos/{username}"}
	// Value with invalid characters should cause an error from sanitizeValue
	vars := map[string]string{"username": "alice/../etc"}

	_, err := svc.GetStreamStoragePath(stream, vars)
	if err == nil {
		t.Error("expected error for invalid path characters")
	}
}
