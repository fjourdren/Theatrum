package models

import (
	"testing"

	"Theatrum/constants"
)

func TestStream_GetMasterPlaylistTemplatePath(t *testing.T) {
	tests := []struct {
		name   string
		path   string
		expect string
	}{
		{
			"simple path",
			"videos/{username}",
			"videos/{username}/" + constants.MasterPlaylist,
		},
		{
			"nested path",
			"live/{room_id}/{user}",
			"live/{room_id}/{user}/" + constants.MasterPlaylist,
		},
		{
			"path with builtin function",
			"recordings/{%STARTING_DATE%}",
			"recordings/{%STARTING_DATE%}/" + constants.MasterPlaylist,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			s := &Stream{Path: tt.path}
			got := s.GetMasterPlaylistTemplatePath()
			if got != tt.expect {
				t.Errorf("GetMasterPlaylistTemplatePath() = %q, want %q", got, tt.expect)
			}
		})
	}
}
