package models

import (
	"fmt"

	"Theatrum/constants"
)

type StreamType string

const (
	StreamTypeVideoUnEncoded StreamType = "video_unencoded"
	StreamTypeVideoEncoded   StreamType = "video_encoded"
	StreamTypeLive StreamType = "live"
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
	Record            Record // Recording settings (live streams only)
	Viewers           Viewers // Concurrent viewer tracking (live streams only)
	Views             Views   // Total view count tracking (all stream types)
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