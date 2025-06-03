package repositories

import (
	"fmt"
	"os"

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

// TODO : move in domain
func (y *YamlConfigFile) validateStream(stream yamlConfigFileEntities.Stream, context string) error {

	if stream.Type == "" {
		return fmt.Errorf("%s has empty type", context)
	}

	// get stream type from StreamTypeVideoEncoded
	if stream.Type != string(models.StreamTypeVideoEncoded) && stream.Type != string(models.StreamTypeVideoUnEncoded) {
		return fmt.Errorf("%s has invalid type: %s", context, stream.Type)
	}

	if stream.Path == "" {
		return fmt.Errorf("%s has empty path", context)
	}

	return nil
}