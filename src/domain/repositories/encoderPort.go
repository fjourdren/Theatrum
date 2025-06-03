package repositories

import "Theatrum/domain/models"

// EncoderPort defines the interface for video encoding operations
type EncoderPort interface {
	// EncodeVideo encodes a video file to multiple qualities using the specified distribution settings
	EncodeVideo(inputPath string, outputPath string, qualities map[string]models.Quality, distribution models.Distribution) error
} 