package jobs

import (
	"log"
	"path"

	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
	"Theatrum/domain/services"
)

// VideoUnencodedDetector detects unencoded videos and sends them to the encode queue
type VideoUnencodedDetector struct {
	appService      *services.ApplicationService
	encodeQueue     *EncodeJobQueue
	storage         repositories.StoragePort
	templateService *services.PathTemplateService
}

func NewVideoUnencodedDetector(
	appService *services.ApplicationService,
	encodeQueue *EncodeJobQueue,
	storage repositories.StoragePort,
	templateService *services.PathTemplateService,
) *VideoUnencodedDetector {
	return &VideoUnencodedDetector{
		appService:      appService,
		encodeQueue:     encodeQueue,
		storage:         storage,
		templateService: templateService,
	}
}

// DetectAndQueueVideos scans for unencoded videos and queues them for encoding
func (d *VideoUnencodedDetector) DetectAndQueueVideos() error {
	log.Printf("Starting video detection")
	defer log.Printf("Video detection completed")

	// Get streams from application service
	channels := d.appService.GetChannels()

	// Process each video unencoded stream
	for _, stream := range *channels {
		
		if stream.Type != models.StreamTypeVideoUnEncoded && stream.VideoInputPath == "" {
			continue
		}

		nbVideosToEncode := 0

		log.Printf("Searching videos for stream %s", stream.Path)

		// Search for video files in the stream's path
		patternVideoToEncode := path.Join(constants.VideoDir, stream.VideoInputPath)
		filesToEncode, vars, err := d.storage.SearchFiles(patternVideoToEncode, constants.ValidVideoExtensions)
		if err != nil {
			log.Printf("Error searching for videos in %s: %v", stream.Path, err)
			continue
		}

		// Process each found video file
		for i, file := range filesToEncode {
			// Template the output path with path variables for the encoded video
			outputPath, err := d.templateService.ReplacePlaceholders(path.Join(constants.VideoDir, stream.Path, constants.PlaceholderBegin+"FILENAME"+constants.PlaceholderEnd), vars[i])
			if err != nil {
				log.Printf("Error replacing placeholders: %v", err)
				continue
			}

			nbVideosToEncode++

			// Create and queue the encoding job
			job := EncodeJob{
				InputStoragePath:  file,
				OutputStoragePath: outputPath,
				Channel:          stream,
			}

			if err := d.encodeQueue.Enqueue(job); err != nil {
				log.Printf("Error queueing video %s: %v", file, err)
				continue
			}

			log.Printf("Queued video for encoding: %s", file)
		}

		log.Printf("Found %d videos to encode for stream %s", nbVideosToEncode, stream.Path)
	}

	return nil
}
