package repositories

import (
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"

	yamlConfigFileEntities "Theatrum/adapters/driven/yamlConfigFile/entities"
	yamlConfigFileMappers "Theatrum/adapters/driven/yamlConfigFile/mappers"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
)

// YamlConfigFile implements the ConfigurationPort interface using YAML files
type YamlConfigFile struct{}

// Verify interface implementation
var _ repositories.ConfigurationPort = (*YamlConfigFile)(nil)

// NewYamlConfigFile creates a new instance of YamlConfigFile
func NewYamlConfigFile() repositories.ConfigurationPort {
	return &YamlConfigFile{}
}

// Load implements ConfigurationPort.Load
func (y *YamlConfigFile) Load(configPath string) (*models.Application, *models.Server, *map[string]models.Stream, error) {
	// Read the config file
	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("error reading config file: %w", err)
	}

	// Create a new config struct
	config := &yamlConfigFileEntities.Config{}

	// Parse the YAML
	err = yaml.Unmarshal(data, config)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("error parsing config file: %w", err)
	}

	// Validate the configuration
	if err := y.validateConfig(config); err != nil {
		return nil, nil, nil, fmt.Errorf("invalid configuration: %w", err)
	}

	// Map the configuration to domain models
	application := yamlConfigFileMappers.ToDomainApplication(config.Application)
	server := yamlConfigFileMappers.ToDomainServer(config.Server)
	channels := yamlConfigFileMappers.ToDomainChannels(config.Channels)

	return &application, &server, &channels, nil
}

func (y *YamlConfigFile) validateConfig(config *yamlConfigFileEntities.Config) error {
	// Validate application configuration
	if config.Application.AllStreamsPlaylist.Enabled && config.Application.AllStreamsPlaylist.Path == "" {
		return fmt.Errorf("all_streams_playlist is enabled but path is empty")
	}

	// Validate server configuration
	if config.Server.HTTPPort <= 0 {
		return fmt.Errorf("invalid HTTP port: must be greater than 0")
	}

	if config.Server.RTMPPort <= 0 {
		return fmt.Errorf("invalid RTMP port: must be greater than 0")
	}

	// Validate stream templates after inheritance resolution
	for name, template := range config.StreamTemplates {
		// Check that name never = "/"
		if name == "/" || name == "" {
			return fmt.Errorf("invalid template name: must not be '/'")
		}

		// Validate template stream
		if err := y.validateStream(template.Stream, fmt.Sprintf("template '%s'", name)); err != nil {
			return err
		}
	}

	// Validate channels
	for name, channel := range config.Channels {
		// Check that name never = "/"
		if name == "/" || name == "" {
			return fmt.Errorf("invalid channel name: must not be '/'")
		}

		// Validate stream
		if err := y.validateStream(channel.Stream, fmt.Sprintf("channel '%s'", name)); err != nil {
			return err
		}
	}

	return nil
}

// LATER : move in domain
func (y *YamlConfigFile) validateStream(stream yamlConfigFileEntities.Stream, context string) error {

	if stream.Type == "" {
		return fmt.Errorf("%s has empty type", context)
	}

	// get stream type from StreamTypeVideoEncoded
	if stream.Type != string(models.StreamTypeVideoEncoded) && 
	   stream.Type != string(models.StreamTypeVideoUnEncoded) && 
	   stream.Type != string(models.StreamTypeLive) {
		return fmt.Errorf("%s has invalid type: %s", context, stream.Type)
	}

	if stream.Path == "" {
		return fmt.Errorf("%s has empty path", context)
	}

	// Validate path security
	if err := y.validatePath(stream.Path, fmt.Sprintf("%s path", context)); err != nil {
		return err
	}

	// Validate video_unencoded specific fields
	if stream.Type == string(models.StreamTypeVideoUnEncoded) {
		if stream.VideoInputPath == "" {
			return fmt.Errorf("%s of type video_unencoded must have video_input_path", context)
		}
		
		// Validate video input path security  
		if err := y.validatePath(stream.VideoInputPath, fmt.Sprintf("%s video_input_path", context)); err != nil {
			return err
		}
		
		// delete_after_encoding is valid for video_unencoded streams (no validation needed, bool defaults to false)
	} else if stream.Type == string(models.StreamTypeLive) {
		// Validate live stream specific fields
		if stream.LiveStreamKey == "" {
			return fmt.Errorf("%s of type live must have live_stream_key", context)
		}
		// For live streams, these fields should not be set
		if stream.VideoInputPath != "" {
			return fmt.Errorf("%s of type live should not have video_input_path", context)
		}
		if stream.DeleteAfterEncoding {
			return fmt.Errorf("%s of type live should not have delete_after_encoding enabled", context)
		}
	} else {
		// For video_encoded streams, these fields should not be set
		
	}

	// Validate qualities
	if len(stream.Qualities) == 0 {
		return fmt.Errorf("%s has no quality profiles defined", context)
	}

	for qualityName, quality := range stream.Qualities {
		if err := y.validateQuality(quality, fmt.Sprintf("%s quality '%s'", context, qualityName)); err != nil {
			return err
		}
	}

	// Validate distribution settings
	if err := y.validateDistribution(stream.Distribution, stream.Type, context); err != nil {
		return err
	}

	return nil
}

func (y *YamlConfigFile) validateQuality(quality yamlConfigFileEntities.Quality, context string) error {
	if quality.Width <= 0 {
		return fmt.Errorf("%s has invalid width: must be greater than 0", context)
	}
	
	if quality.Height <= 0 {
		return fmt.Errorf("%s has invalid height: must be greater than 0", context)
	}
	
	if quality.Framerate <= 0 {
		return fmt.Errorf("%s has invalid framerate: must be greater than 0", context)
	}
	
	if quality.Bitrate == "" {
		return fmt.Errorf("%s has empty bitrate", context)
	}
	
	if quality.Codec == "" {
		return fmt.Errorf("%s has empty codec", context)
	}
	
	// Validate audio settings
	if quality.Audio.Bitrate == "" {
		return fmt.Errorf("%s has empty audio bitrate", context)
	}
	
	if quality.Audio.Codec == "" {
		return fmt.Errorf("%s has empty audio codec", context)
	}
	
	return nil
}

func (y *YamlConfigFile) validateDistribution(distribution yamlConfigFileEntities.Distribution, streamType string, context string) error {
	// Validate HLS settings
	if distribution.Hls.SegmentDuration <= 0 {
		return fmt.Errorf("%s has invalid HLS segment_duration: must be greater than 0", context)
	}

	return nil
}

func (y *YamlConfigFile) validatePath(path string, context string) error {
	// Check for path traversal attempts
	if strings.Contains(path, "..") {
		return fmt.Errorf("%s cannot contain '..' (path traversal attempt)", context)
	}

	// Check for absolute paths (should be relative)
	if strings.HasPrefix(path, "/") || strings.HasPrefix(path, "\\") {
		return fmt.Errorf("%s should be a relative path, not absolute", context)
	}

	// Check for Windows drive paths
	if len(path) >= 2 && path[1] == ':' {
		return fmt.Errorf("%s should not contain Windows drive letters", context)
	}

	// Check for empty segments
	segments := strings.Split(path, "/")
	for _, seg := range segments {
		if seg == "" {
			return fmt.Errorf("%s cannot contain empty segments", context)
		}
	}

	// Check for potentially dangerous characters
	dangerousChars := []string{"%00", "%2e", "%2f", "%5c", "|", ">", "<", "*", "?"}
	for _, char := range dangerousChars {
		if strings.Contains(path, char) {
			return fmt.Errorf("%s contains potentially dangerous character: %s", context, char)
		}
	}

	return nil
}