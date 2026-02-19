package stream

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"Theatrum/constants"
)

func TestBuildVODPlaylist(t *testing.T) {
	segments := []string{"segment_000.ts", "segment_001.ts", "segment_002.ts"}

	playlist := buildVODPlaylist(segments, 4)

	// Verify M3U8 format
	if !strings.HasPrefix(playlist, "#EXTM3U\n") {
		t.Error("expected M3U8 header")
	}
	if !strings.Contains(playlist, "#EXT-X-VERSION:3") {
		t.Error("expected version 3")
	}
	if !strings.Contains(playlist, "#EXT-X-TARGETDURATION:4") {
		t.Error("expected target duration 4")
	}
	if !strings.Contains(playlist, "#EXT-X-PLAYLIST-TYPE:VOD") {
		t.Error("expected VOD playlist type")
	}
	if !strings.Contains(playlist, "#EXT-X-MEDIA-SEQUENCE:0") {
		t.Error("expected media sequence 0")
	}
	if !strings.HasSuffix(playlist, "#EXT-X-ENDLIST\n") {
		t.Error("expected ENDLIST at end")
	}

	// Verify all segments are present and in order
	for _, seg := range segments {
		if !strings.Contains(playlist, seg) {
			t.Errorf("expected segment %q in playlist", seg)
		}
	}

	// Verify segment ordering
	idx0 := strings.Index(playlist, "segment_000.ts")
	idx1 := strings.Index(playlist, "segment_001.ts")
	idx2 := strings.Index(playlist, "segment_002.ts")
	if idx0 >= idx1 || idx1 >= idx2 {
		t.Error("expected segments in ascending order")
	}
}

func TestExtractSegmentNumber(t *testing.T) {
	tests := []struct {
		name     string
		filename string
		expect   int
	}{
		{"segment_000", "segment_000.ts", 0},
		{"segment_001", "segment_001.ts", 1},
		{"segment_123", "segment_123.ts", 123},
		{"non-matching", "other_file.ts", 0},
		{"no extension", "segment_005", 0},
		{"different prefix", "seg_001.ts", 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := extractSegmentNumber(tt.filename)
			if got != tt.expect {
				t.Errorf("extractSegmentNumber(%q) = %d, want %d", tt.filename, got, tt.expect)
			}
		})
	}
}

func TestFindSegments(t *testing.T) {
	t.Run("finds and sorts segments", func(t *testing.T) {
		dir := t.TempDir()

		// Create segment files in non-sequential order
		files := []string{"segment_002.ts", "segment_000.ts", "segment_001.ts"}
		for _, f := range files {
			if err := os.WriteFile(filepath.Join(dir, f), []byte("data"), 0644); err != nil {
				t.Fatalf("failed to create file %s: %v", f, err)
			}
		}
		// Create a non-segment file
		if err := os.WriteFile(filepath.Join(dir, "playlist.m3u8"), []byte("#EXTM3U"), 0644); err != nil {
			t.Fatalf("failed to create non-segment file: %v", err)
		}
		// Create a directory
		if err := os.Mkdir(filepath.Join(dir, "subdir"), 0755); err != nil {
			t.Fatalf("failed to create directory: %v", err)
		}

		segments, err := findSegments(dir)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if len(segments) != 3 {
			t.Fatalf("expected 3 segments, got %d", len(segments))
		}

		// Verify sorting
		if segments[0] != "segment_000.ts" {
			t.Errorf("expected first segment segment_000.ts, got %q", segments[0])
		}
		if segments[1] != "segment_001.ts" {
			t.Errorf("expected second segment segment_001.ts, got %q", segments[1])
		}
		if segments[2] != "segment_002.ts" {
			t.Errorf("expected third segment segment_002.ts, got %q", segments[2])
		}
	})

	t.Run("empty directory", func(t *testing.T) {
		dir := t.TempDir()
		segments, err := findSegments(dir)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(segments) != 0 {
			t.Errorf("expected 0 segments, got %d", len(segments))
		}
	})

	t.Run("nonexistent directory", func(t *testing.T) {
		_, err := findSegments("/nonexistent/path")
		if err == nil {
			t.Error("expected error for nonexistent directory")
		}
	})
}

func TestGenerateVODPlaylist(t *testing.T) {
	t.Run("writes playlist file", func(t *testing.T) {
		dir := t.TempDir()

		// Create segments
		for _, f := range []string{"segment_000.ts", "segment_001.ts"} {
			if err := os.WriteFile(filepath.Join(dir, f), []byte("data"), 0644); err != nil {
				t.Fatalf("failed to create file: %v", err)
			}
		}

		err := generateVODPlaylist(dir, 4)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		// Verify playlist file exists
		playlistPath := filepath.Join(dir, constants.SubPlaylist)
		data, err := os.ReadFile(playlistPath)
		if err != nil {
			t.Fatalf("failed to read playlist: %v", err)
		}

		content := string(data)
		if !strings.Contains(content, "#EXTM3U") {
			t.Error("expected M3U8 header in file")
		}
		if !strings.Contains(content, "segment_000.ts") {
			t.Error("expected segment_000.ts in playlist")
		}
	})

	t.Run("no segments error", func(t *testing.T) {
		dir := t.TempDir()
		err := generateVODPlaylist(dir, 4)
		if err == nil {
			t.Error("expected error when no segments found")
		}
	})
}

