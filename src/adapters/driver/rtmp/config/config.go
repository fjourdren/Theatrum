package config

// Config holds RTMP server configuration
type Config struct {
	OutputDir         string
	ReconnectDelay    int
	CleanupDelay      int
} 