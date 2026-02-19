package stream

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"Theatrum/constants"
)

func TestGenerateMasterPlaylistWrapper(t *testing.T) {
	dir := t.TempDir()

	err := GenerateMasterPlaylistWrapper(dir)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	playlistPath := filepath.Join(dir, constants.MasterPlaylist)
	data, err := os.ReadFile(playlistPath)
	if err != nil {
		t.Fatalf("failed to read master playlist: %v", err)
	}

	content := string(data)
	if !strings.Contains(content, "#EXTM3U") {
		t.Error("expected M3U8 header")
	}
	if !strings.Contains(content, "#EXT-X-STREAM-INF:BANDWIDTH=0") {
		t.Error("expected STREAM-INF tag")
	}
	expectedRef := constants.DefaultQuality + "/" + constants.SubPlaylist
	if !strings.Contains(content, expectedRef) {
		t.Errorf("expected reference to %q", expectedRef)
	}
}
