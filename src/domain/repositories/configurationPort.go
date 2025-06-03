package repositories

import "Theatrum/domain/models"

// ConfigurationPort defines the interface for configuration operations
type ConfigurationPort interface {
	// Returns the application configuration, server configuration and channels map from a YAML file
	Load(configPath string) (*models.Application, *models.Server, *map[string]models.Stream, error)
} 