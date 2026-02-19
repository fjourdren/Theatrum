package repositories

import (
	"fmt"
	"os"
	"regexp"
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

		// Live streams require auth_token_template variables to exist in channel pattern
		if channel.Stream.Type == string(models.StreamTypeLive) {
			if err := y.validateAuthTokenTemplate(name, channel.Stream); err != nil {
				return err
			}
		}

		// Restream channels must not contain user variable placeholders
		if channel.Stream.Type == string(models.StreamTypeRestream) {
			if err := y.validateRestreamChannel(name, channel.Stream); err != nil {
				return err
			}
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
	   stream.Type != string(models.StreamTypeLive) &&
	   stream.Type != string(models.StreamTypeRestream) {
		return fmt.Errorf("%s has invalid type: %s", context, stream.Type)
	}

	if stream.Path == "" {
		return fmt.Errorf("%s has empty path", context)
	}

	// Validate path security
	if err := y.validatePath(stream.Path, fmt.Sprintf("%s path", context)); err != nil {
		return err
	}

	// Validate viewers config: only valid for live and restream streams
	if stream.Viewers.Enabled && stream.Type != string(models.StreamTypeLive) && stream.Type != string(models.StreamTypeRestream) {
		return fmt.Errorf("%s has viewers enabled but is not a live or restream stream (only live and restream streams support viewer tracking)", context)
	}
	if stream.Viewers.Enabled && stream.Viewers.Window <= 0 {
		return fmt.Errorf("%s has invalid viewers window: must be > 0 (viewers require an expiry window)", context)
	}

	// Validate views config
	if stream.Views.Window < 0 {
		return fmt.Errorf("%s has invalid views window: must be >= 0 (0 means instant count)", context)
	}

	// Validate thumbnail config: only valid for live streams
	if stream.Thumbnail.Enabled && stream.Type != string(models.StreamTypeLive) {
		return fmt.Errorf("%s has thumbnail enabled but is not a live stream", context)
	}
	if stream.Thumbnail.Enabled && stream.Thumbnail.Interval <= 0 {
		return fmt.Errorf("%s has invalid thumbnail interval: must be > 0", context)
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
		if stream.Record.Enabled {
			return fmt.Errorf("%s of type video_unencoded should not have record enabled (only live streams support recording)", context)
		}
	} else if stream.Type == string(models.StreamTypeLive) {
		// Validate live stream specific fields
		if stream.LiveStreamKey == "" {
			return fmt.Errorf("%s of type live must have live_stream_key", context)
		}
		if stream.AuthTokenTemplate == "" {
			return fmt.Errorf("%s of type live must have auth_token_template", context)
		}
		// For live streams, these fields should not be set
		if stream.VideoInputPath != "" {
			return fmt.Errorf("%s of type live should not have video_input_path", context)
		}
		if stream.DeleteAfterEncoding {
			return fmt.Errorf("%s of type live should not have delete_after_encoding enabled", context)
		}
		// Validate record settings
		if stream.Record.Enabled && stream.Record.Path != "" {
			if err := y.validatePath(stream.Record.Path, fmt.Sprintf("%s record path", context)); err != nil {
				return err
			}
		}
	} else if stream.Type == string(models.StreamTypeRestream) {
		// Validate restream specific fields
		if stream.SourceURL == "" {
			return fmt.Errorf("%s of type restream must have source_url", context)
		}
		// For restream streams, these fields should not be set
		if stream.LiveStreamKey != "" {
			return fmt.Errorf("%s of type restream should not have live_stream_key", context)
		}
		if stream.AuthTokenTemplate != "" {
			return fmt.Errorf("%s of type restream should not have auth_token_template", context)
		}
		if stream.VideoInputPath != "" {
			return fmt.Errorf("%s of type restream should not have video_input_path", context)
		}
		if stream.DeleteAfterEncoding {
			return fmt.Errorf("%s of type restream should not have delete_after_encoding enabled", context)
		}
		// Validate record settings
		if stream.Record.Enabled && stream.Record.Path != "" {
			if err := y.validatePath(stream.Record.Path, fmt.Sprintf("%s record path", context)); err != nil {
				return err
			}
		}
	} else {
		// For video_encoded streams, these fields should not be set
		if stream.Record.Enabled {
			return fmt.Errorf("%s of type %s should not have record enabled (only live streams support recording)", context, stream.Type)
		}
	}

	// Validate qualities
	// TODO : make qualities optional for some types of streams
	if stream.Type != string(models.StreamTypeLive) && stream.Type != string(models.StreamTypeRestream) && len(stream.Qualities) == 0 {
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
	// At least one distribution format must be configured
	if distribution.Hls == nil && distribution.Dash == nil {
		return fmt.Errorf("%s must have at least one distribution format (hls or dash)", context)
	}

	// Validate HLS settings if present
	if distribution.Hls != nil {
		if distribution.Hls.SegmentDuration <= 0 {
			return fmt.Errorf("%s has invalid HLS segment_duration: must be greater than 0", context)
		}
		if distribution.Hls.WindowSize < 0 {
			return fmt.Errorf("%s has invalid HLS window_size: must be 0 or greater (0 uses default of 3)", context)
		}
	}

	// Validate DASH settings if present
	if distribution.Dash != nil {
		if distribution.Dash.SegmentDuration <= 0 {
			return fmt.Errorf("%s has invalid DASH segment_duration: must be greater than 0", context)
		}
		if distribution.Dash.WindowSize < 0 {
			return fmt.Errorf("%s has invalid DASH window_size: must be 0 or greater (0 uses default of 3)", context)
		}
	}

	// In dual mode, segment durations and window sizes must match
	if distribution.Hls != nil && distribution.Dash != nil {
		if distribution.Hls.SegmentDuration != distribution.Dash.SegmentDuration {
			return fmt.Errorf("%s has mismatched segment_duration between HLS (%d) and DASH (%d): must be equal in dual mode",
				context, distribution.Hls.SegmentDuration, distribution.Dash.SegmentDuration)
		}
		if distribution.Hls.WindowSize != distribution.Dash.WindowSize {
			return fmt.Errorf("%s has mismatched window_size between HLS (%d) and DASH (%d): must be equal in dual mode",
				context, distribution.Hls.WindowSize, distribution.Dash.WindowSize)
		}
	}

	return nil
}

func (y *YamlConfigFile) validateAuthTokenTemplate(channelName string, stream yamlConfigFileEntities.Stream) error {
	// Extract variable names from template
	varRegex := regexp.MustCompile(`\{([a-zA-Z0-9_]+)\}`)
	templateVars := varRegex.FindAllStringSubmatch(stream.AuthTokenTemplate, -1)

	if len(templateVars) == 0 {
		return fmt.Errorf("channel '%s': auth_token_template must contain at least one {variable}", channelName)
	}

	// Verify each template variable exists in channel pattern
	for _, match := range templateVars {
		varName := match[1]
		varPlaceholder := "{" + varName + "}"
		if !strings.Contains(channelName, varPlaceholder) {
			return fmt.Errorf("channel '%s': auth_token_template references {%s} but channel pattern doesn't contain it", channelName, varName)
		}
	}

	return nil
}

// validateRestreamChannel checks that restream channels don't use user variable placeholders ({var})
// in channel name, stream path, or record path. Only builtin functions ({%FUNC%}) are allowed.
func (y *YamlConfigFile) validateRestreamChannel(channelName string, stream yamlConfigFileEntities.Stream) error {
	// Regex matches {var} but not {%FUNC%}
	userVarRegex := regexp.MustCompile(`\{([^%][^}]*)\}`)

	if userVarRegex.MatchString(channelName) {
		return fmt.Errorf("channel '%s': restream channels must not contain user variable placeholders like {var} in channel name", channelName)
	}
	if userVarRegex.MatchString(stream.Path) {
		return fmt.Errorf("channel '%s': restream stream path must not contain user variable placeholders like {var}", channelName)
	}
	if stream.Record.Enabled && stream.Record.Path != "" && userVarRegex.MatchString(stream.Record.Path) {
		return fmt.Errorf("channel '%s': restream record path must not contain user variable placeholders like {var}", channelName)
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