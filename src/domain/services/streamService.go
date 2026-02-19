package services

import (
	"Theatrum/constants"
	"Theatrum/domain/models"
	"path"
	"strings"
)

type StreamService struct {
	pathTemplateService *PathTemplateService
}

func NewStreamService(pathTemplateService *PathTemplateService) *StreamService {
	return &StreamService{
		pathTemplateService: pathTemplateService,
	}
}

func (s *StreamService) GetStreamStoragePath(stream *models.Stream, templatingVars map[string]string) (string, error) {
	// If quality is not provided, use the default quality
	if _, ok := templatingVars["quality"]; !ok {
		templatingVars["quality"] = constants.DefaultQuality
	}

	streamStorageTemplate := stream.Path

	// Only HLS-only mode uses quality subdirs.
	// DASH-enabled modes (DASH-only or dual) use a flat layout — no quality subdir appended.
	hlsOnly := stream.Distribution.HlsEnabled() && !stream.Distribution.DashEnabled()

	isRootResource := templatingVars["resource"] == constants.MasterPlaylist ||
		templatingVars["resource"] == constants.DashManifest

	if hlsOnly && !isRootResource && !strings.Contains(stream.Path, constants.PlaceholderBegin+"quality"+constants.PlaceholderEnd) {
		streamStorageTemplate += "/" + templatingVars["quality"]
	}

	// Replace the placeholders in the path
	storagePath, err := s.pathTemplateService.ReplacePlaceholders(streamStorageTemplate, templatingVars)
	if err != nil {
		return "", err
	}

	// Add the video directory to the path
	finalStoragePath := path.Join(constants.VideoDir, storagePath)

	return finalStoragePath, nil
}
