package mappers

import (
	"Theatrum/adapters/driven/yamlConfigFile/entities"
	"Theatrum/domain/models"
)

// ToDomainServer converts a YAML server configuration to a domain server model
func ToDomainServer(server entities.Server) models.Server {
	// Set default values for RTMP config
	reconnectDelay := server.RTMP.ReconnectDelay
	if reconnectDelay <= 0 {
		reconnectDelay = 30
	}
	cleanupDelay := server.RTMP.CleanupDelay
	if cleanupDelay <= 0 {
		cleanupDelay = 30
	}

	return models.Server{
		HTTPPort: server.HTTPPort,
		RTMPPort: server.RTMPPort,
		RTMP: models.RTMP{
			ReconnectDelay: reconnectDelay,
			CleanupDelay:   cleanupDelay,
		},
	}
}

// ToDomainQuality converts a YAML quality configuration to a domain quality model
func ToDomainQuality(quality entities.Quality) models.Quality {
	return models.Quality{
		Width:     quality.Width,
		Height:    quality.Height,
		Framerate: quality.Framerate,
		Bitrate:   quality.Bitrate,
		Codec:     quality.Codec,
		Audio: models.Audio{
			Bitrate: quality.Audio.Bitrate,
			Codec:   quality.Audio.Codec,
		},
	}
}

// ToDomainStream converts a YAML stream to a domain stream model
func ToDomainStream(stream entities.Stream) models.Stream {
	qualities := make(map[string]models.Quality)
	for key, quality := range stream.Qualities {
		qualities[key] = ToDomainQuality(quality)
	}

	var hlsModel *models.Hls
	if stream.Distribution.Hls != nil {
		windowSize := stream.Distribution.Hls.WindowSize
		if windowSize <= 0 {
			windowSize = 3
		}
		hlsModel = &models.Hls{
			SegmentDuration: stream.Distribution.Hls.SegmentDuration,
			WindowSize:      windowSize,
		}
	}

	var dashModel *models.Dash
	if stream.Distribution.Dash != nil {
		windowSize := stream.Distribution.Dash.WindowSize
		if windowSize <= 0 {
			windowSize = 3
		}
		dashModel = &models.Dash{
			SegmentDuration: stream.Distribution.Dash.SegmentDuration,
			WindowSize:      windowSize,
		}
	}

	return models.Stream{
		Type:      models.StreamType(stream.Type),
		Path:      stream.Path,
		Qualities: qualities,
		Distribution: models.Distribution{
			Hls:  hlsModel,
			Dash: dashModel,
		},

		// Specific fields for video unencoded streams
		VideoInputPath:      stream.VideoInputPath,
		DeleteAfterEncoding: stream.DeleteAfterEncoding,

		// Specific fields for live streams
		LiveStreamKey:     stream.LiveStreamKey,
		AuthTokenTemplate: stream.AuthTokenTemplate,

		// Specific fields for restream streams
		SourceURL: stream.SourceURL,
		Record: models.Record{
			Enabled: stream.Record.Enabled,
			Path:    stream.Record.Path,
		},
		Viewers: models.Viewers{
			Enabled: stream.Viewers.Enabled,
			Window:  stream.Viewers.Window,
		},
		Views: models.Views{
			Enabled: stream.Views.Enabled,
			Window:  stream.Views.Window,
		},
		Thumbnail: models.Thumbnail{
			Enabled:  stream.Thumbnail.Enabled,
			Interval: stream.Thumbnail.Interval,
		},
	}
}

// ToDomainChannels converts a map of YAML channels to a map of domain stream models
func ToDomainChannels(channels map[string]entities.Channel) map[string]models.Stream {
	result := make(map[string]models.Stream)
	for key, channel := range channels {
		result[key] = ToDomainStream(channel.Stream)
	}
	return result
}

// ToDomainApplication converts a YAML application configuration to a domain application model
func ToDomainApplication(app entities.Application) models.Application {
	// Set default value for enabled to false if not specified
	enabled := false
	if app.AllStreamsPlaylist.Enabled {
		enabled = true
	}

	return models.Application{
		PublicPath: app.PublicPath,
		AllStreamsPlaylist: models.AllStreamsPlaylist{
			Enabled: enabled,
			Path:    app.AllStreamsPlaylist.Path,
		},
	}
}
