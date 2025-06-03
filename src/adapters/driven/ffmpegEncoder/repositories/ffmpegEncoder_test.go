package repositories

import (
	"Theatrum/domain/models"
	"testing"
)

func TestFfmpegEncoder_EncodeVideo(t *testing.T) {
	// Setup test cases
	tests := []struct {
		name           string
		inputPath      string
		outputPath     string
		qualities      map[string]models.Quality
		distribution   models.Distribution
		expectedError  bool
		setupMock      func()
		cleanupMock    func()
	}{
		{
			name:       "successful encoding",
			inputPath:  "input.mp4",
			outputPath: "output/test_output.m3u8",
			qualities: map[string]models.Quality{
				"low": {
					Width:    640,
					Height:   360,
					Bitrate:  "800k",
					Audio: models.Audio{
						Bitrate: "96k",
						Codec:   "aac",
					},
				},
				"medium": {
					Width:    1280,
					Height:   720,
					Bitrate:  "2500k",
					Audio: models.Audio{
						Bitrate: "128k",
						Codec:   "aac",
					},
				},
				"high": {
					Width:    1920,
					Height:   1080,
					Bitrate:  "5000k",
					Audio: models.Audio{
						Bitrate: "192k",
						Codec:   "aac",
					},
				},
			},
			distribution: models.Distribution{
				Hls: models.Hls{
					SegmentDuration: 10,
					PlaylistLength:  5,
				},
			},
			expectedError: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Get encoder as interface and type assert to concrete type for testing
			encoderInterface := NewFfmpegEncoder()
			encoder, ok := encoderInterface.(*FfmpegEncoder)
			if !ok {
				t.Fatal("Failed to type assert encoder to *FfmpegEncoder")
			}
			encoder.DryRun = true

			// Execute the encoding
			err := encoder.EncodeVideo(tt.inputPath, tt.outputPath, tt.qualities, tt.distribution)

			// Check error expectations
			if (err != nil) != tt.expectedError {
				t.Errorf("EncodeVideo() error = %v, expectedError %v", err, tt.expectedError)
				return
			}

			// Additional assertions could be added here to verify the command construction
			// For example, checking that the command contains expected parameters
		})
	}
}