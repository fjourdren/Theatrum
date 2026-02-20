package streamcmd

import (
	"fmt"
	"os"
	"path/filepath"

	"Theatrum/constants"
)

// GenerateMasterPlaylistWrapper writes a master.m3u8 at rootDir that references default/playlist.m3u8.
// This is used for passthrough (single-quality) streams so they are discoverable via master.m3u8.
func GenerateMasterPlaylistWrapper(rootDir string) error {
	content := "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=0,CODECS=\"avc1.64001f,mp4a.40.2\"\n" +
		constants.DefaultQuality + "/" + constants.SubPlaylist + "\n"
	playlistPath := filepath.Join(rootDir, constants.MasterPlaylist)
	if err := os.WriteFile(playlistPath, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write master playlist wrapper %s: %w", playlistPath, err)
	}
	return nil
}
