package models

// Application represents the application-wide configuration
type Application struct {
	PublicPath        string
	AllStreamsPlaylist AllStreamsPlaylist
}

// AllStreamsPlaylist represents the configuration for the all streams playlist feature
type AllStreamsPlaylist struct {
	Enabled bool
	Path    string
}