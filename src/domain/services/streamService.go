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

	// If the path does not contain the quality placeholder, add it (except for master.m3u8)
	streamStorageTemplate := stream.Path
	if !strings.Contains(stream.Path, constants.PlaceholderBegin+"quality"+constants.PlaceholderEnd) && templatingVars["resource"] != constants.MasterPlaylist {
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
