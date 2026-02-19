package models

import (
	"fmt"

	"Theatrum/constants"
)

type StreamType string

const (
	StreamTypeVideoUnEncoded StreamType = "video_unencoded"
	StreamTypeVideoEncoded   StreamType = "video_encoded"
	StreamTypeLive           StreamType = "live"
	StreamTypeRestream       StreamType = "restream"
)

type Stream struct {
	Type         StreamType
	Path         string
	Qualities    map[string]Quality
	Distribution Distribution

	// Specific fields for video unencoded streams
	VideoInputPath      string
	DeleteAfterEncoding bool // If enabled, delete the source file after video encoding (default: false)

	// Specific fields for live streams
	LiveStreamKey     string
	AuthTokenTemplate string // Template for XOR auth input, uses {var} placeholders
	Record            Record // Recording settings (live and restream streams only)
	Viewers           Viewers // Concurrent viewer tracking (live and restream streams only)
	Views             Views   // Total view count tracking (all stream types)

	// Specific fields for restream streams
	SourceURL string // External URL to pull from (RTMP or any FFmpeg-compatible protocol)
}

type Record struct {
	Enabled bool
	Path    string
}

type Viewers struct {
	Enabled bool
	Window  int // seconds of inactivity before a viewer is considered gone
}

type Views struct {
	Enabled bool
	Window  int // seconds of inactivity before a new visit counts as a new view
}

func (s *Stream) GetMasterPlaylistTemplatePath() string {
	return fmt.Sprintf("%s/%s", s.Path, constants.MasterPlaylist)
}

func (s *Stream) GetDashManifestTemplatePath() string {
	return fmt.Sprintf("%s/%s", s.Path, constants.DashManifest)
}