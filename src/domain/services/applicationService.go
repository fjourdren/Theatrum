package services

import (
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
	"Theatrum/domain/utils"
	"fmt"
	"log"
	"path"
	"strings"
)

// ApplicationService handles application-wide operations and configuration
type ApplicationService struct {
	application    *models.Application
	server         *models.Server
	channels       *map[string]models.Stream
	storage        repositories.StoragePort
	templateService *PathTemplateService
}

// NewApplicationService creates a new instance of ApplicationService
func NewApplicationService(application *models.Application, server *models.Server, channels *map[string]models.Stream, storage repositories.StoragePort, templateService *PathTemplateService) *ApplicationService {
	applicationService := &ApplicationService{
		application:    application,
		server:        server,
		channels:      channels,
		storage:       storage,
		templateService: templateService,
	}
	return applicationService
}

// GetApplication returns the application configuration
func (s *ApplicationService) GetApplication() *models.Application {
	return s.application
}

// GetServer returns the server configuration
func (s *ApplicationService) GetServer() *models.Server {
	return s.server
}

// GetChannels returns all channels
func (s *ApplicationService) GetChannels() *map[string]models.Stream {
	return s.channels
}

// GetChannel retrieves a specific channel by ID
func (s *ApplicationService) GetChannel(channelId string) (models.Stream, error) {
	channels := s.GetChannels()
	channel, exists := (*channels)[channelId]
	if !exists {
		return models.Stream{}, fmt.Errorf("channel not found: %s", channelId)
	}
	return channel, nil
}

// GetAllStreamsPlaylist generates an M3U8 playlist containing all available streams
func (s *ApplicationService) BuildAllStreamsPlaylist() (string, error) {
	if !s.application.AllStreamsPlaylist.Enabled {
		return "", fmt.Errorf("all streams playlist is not enabled")
	}

	// Start building the M3U8 playlist
	var playlist strings.Builder

	// Write M3U8 header
	playlist.WriteString("#EXTM3U\n")
	playlist.WriteString("#EXT-X-VERSION:3\n")
	playlist.WriteString("#EXT-X-STREAM-INF:BANDWIDTH=0\n")

	// Search for the MasterPlaylist file for each streams
	for index, stream := range *s.channels {
		patternMasterPlaylist := path.Join(constants.VideoDir, stream.GetMasterPlaylistTemplatePath())
		masterFiles, vars, err := s.storage.SearchFiles(patternMasterPlaylist, constants.ValidMasterPlaylistExtensions)
		if err != nil {
			log.Printf("Error searching for videos in %s: %v", stream.Path, err)
			continue
		}

		// Search in the filename that there is a constant.MasterPlaylist
		for i, _ := range masterFiles {
			// Get the public path of the master playlist
			channelPath, err := s.templateService.ReplacePlaceholders(index, vars[i])
			if err != nil {
				log.Printf("Error replacing placeholders: %v", err)
				continue
			}

			masterFilePublicPath := utils.JoinURL(s.application.PublicPath, channelPath, constants.MasterPlaylist)

			// Add the master playlist to the playlist
			log.Println("Adding master playlist:", masterFilePublicPath)
			playlist.WriteString(fmt.Sprintf("%s\n", masterFilePublicPath))
		}
	}

	return playlist.String(), nil
}

// Cleanup performs any necessary cleanup operations
func (s *ApplicationService) Cleanup() {
	// Add any cleanup operations here
	// For example, closing any open connections, files, etc.
}