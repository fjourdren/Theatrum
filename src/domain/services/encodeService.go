package services

import (
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
	"fmt"
)

type EncodeService struct {
	encoderRepository repositories.EncoderPort
}

func NewEncodeService(encoder repositories.EncoderPort) *EncodeService {
	return &EncodeService{encoderRepository: encoder}
}

func (s *EncodeService) EncodeStream(inputStoragePath string, outputStoragePath string, channel models.Stream) error {
	if len(channel.Qualities) == 0 {
		return fmt.Errorf("stream has no qualities defined for encoding (path: %s)", channel.Path)
	}

	return s.encoderRepository.EncodeVideo(
		inputStoragePath,
		outputStoragePath,
		channel.Qualities,
		channel.Distribution,
	)
}

func (s *EncodeService) EncodeQuality(inputStoragePath string, outputStoragePath string, qualityName string, quality models.Quality, distribution models.Distribution) error {
	if qualityName == "" {
		qualityName = constants.DefaultQuality
	}
	
	// Create a map with just the single quality
	singleQuality := map[string]models.Quality{
		qualityName: quality,
	}

	return s.encoderRepository.EncodeVideo(
		inputStoragePath,
		outputStoragePath,
		singleQuality,
		distribution,
	)
}
