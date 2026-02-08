package stream

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"Theatrum/constants"
)

var segmentRegex = regexp.MustCompile(`^segment_(\d+)\.ts$`)

// generateVODPlaylist scans a directory for segment files and writes a VOD-ready M3U8 playlist.
func generateVODPlaylist(dir string, segmentDuration int) error {
	segments, err := findSegments(dir)
	if err != nil {
		return fmt.Errorf("failed to find segments in %s: %w", dir, err)
	}
	if len(segments) == 0 {
		return fmt.Errorf("no segments found in %s", dir)
	}

	playlist := buildVODPlaylist(segments, segmentDuration)
	playlistPath := filepath.Join(dir, constants.SubPlaylist)
	if err := os.WriteFile(playlistPath, []byte(playlist), 0644); err != nil {
		return fmt.Errorf("failed to write VOD playlist %s: %w", playlistPath, err)
	}
	return nil
}

// findSegments returns sorted segment filenames from the given directory.
func findSegments(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}

	var segments []string
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		if segmentRegex.MatchString(entry.Name()) {
			segments = append(segments, entry.Name())
		}
	}

	sort.Slice(segments, func(i, j int) bool {
		numI := extractSegmentNumber(segments[i])
		numJ := extractSegmentNumber(segments[j])
		return numI < numJ
	})

	return segments, nil
}

// extractSegmentNumber extracts the numeric index from a segment filename.
func extractSegmentNumber(filename string) int {
	matches := segmentRegex.FindStringSubmatch(filename)
	if len(matches) < 2 {
		return 0
	}
	n, _ := strconv.Atoi(matches[1])
	return n
}

// generateMasterPlaylistWrapper writes a master.m3u8 at rootDir that references default/playlist.m3u8.
// This is used for passthrough (single-quality) streams so they are discoverable via master.m3u8.
func generateMasterPlaylistWrapper(rootDir string) error {
	content := "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=0\n" +
		constants.DefaultQuality + "/" + constants.SubPlaylist + "\n"
	playlistPath := filepath.Join(rootDir, constants.MasterPlaylist)
	if err := os.WriteFile(playlistPath, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write master playlist wrapper %s: %w", playlistPath, err)
	}
	return nil
}

// buildVODPlaylist constructs the M3U8 content for a VOD playlist.
func buildVODPlaylist(segments []string, segmentDuration int) string {
	var b strings.Builder
	b.WriteString("#EXTM3U\n")
	b.WriteString("#EXT-X-VERSION:3\n")
	b.WriteString(fmt.Sprintf("#EXT-X-TARGETDURATION:%d\n", segmentDuration))
	b.WriteString("#EXT-X-PLAYLIST-TYPE:VOD\n")
	b.WriteString("#EXT-X-MEDIA-SEQUENCE:0\n")

	for _, seg := range segments {
		b.WriteString(fmt.Sprintf("#EXTINF:%d.000000,\n", segmentDuration))
		b.WriteString(seg + "\n")
	}

	b.WriteString("#EXT-X-ENDLIST\n")
	return b.String()
}
