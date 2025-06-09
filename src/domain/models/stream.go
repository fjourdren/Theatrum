package models

import (
	"fmt"

	"Theatrum/constants"
)

type StreamType string

const (
	StreamTypeVideoUnEncoded StreamType = "video_unencoded"
	StreamTypeVideoEncoded   StreamType = "video_encoded"
	// StreamTypeLive StreamType = "live"
)

type Stream struct {
	Type         StreamType
	Path         string
	Qualities    map[string]Quality
	Distribution Distribution

	// Specific fields for video unencoded streams
	VideoInputPath      string
	DeleteAfterEncoding bool // If enabled, delete the source file after video encoding (default: false)
}

func (s *Stream) GetMasterPlaylistTemplatePath() string {
	return fmt.Sprintf("%s/%s", s.Path, constants.MasterPlaylist)
}