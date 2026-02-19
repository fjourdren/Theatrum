//go:build !e2e

package stream

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPickBestQualityDir(t *testing.T) {
	tmpDir := t.TempDir()

	// Create quality directories
	os.MkdirAll(filepath.Join(tmpDir, "low"), 0755)
	os.MkdirAll(filepath.Join(tmpDir, "medium"), 0755)
	os.MkdirAll(filepath.Join(tmpDir, "high"), 0755)

	tg := &ThumbnailGenerator{
		outputDir:  tmpDir,
		qualities:  []string{"low", "medium", "high"},
	}

	result := tg.pickBestQualityDir()
	if result != filepath.Join(tmpDir, "high") {
		t.Errorf("expected high dir, got %q", result)
	}
}

func TestPickBestQualityDir_MissingHigh(t *testing.T) {
	tmpDir := t.TempDir()

	os.MkdirAll(filepath.Join(tmpDir, "low"), 0755)
	os.MkdirAll(filepath.Join(tmpDir, "medium"), 0755)

	tg := &ThumbnailGenerator{
		outputDir:  tmpDir,
		qualities:  []string{"low", "medium", "high"},
	}

	result := tg.pickBestQualityDir()
	if result != filepath.Join(tmpDir, "medium") {
		t.Errorf("expected medium dir, got %q", result)
	}
}

func TestPickBestQualityDir_Empty(t *testing.T) {
	tmpDir := t.TempDir()

	tg := &ThumbnailGenerator{
		outputDir:  tmpDir,
		qualities:  []string{},
	}

	result := tg.pickBestQualityDir()
	if result != "" {
		t.Errorf("expected empty string, got %q", result)
	}
}

func TestFindLatestSegment_HLSPassthrough(t *testing.T) {
	tmpDir := t.TempDir()
	outputDir := filepath.Join(tmpDir, "default")
	os.MkdirAll(outputDir, 0755)

	// Create some .ts files with different mod times
	old := filepath.Join(outputDir, "segment_001.ts")
	os.WriteFile(old, []byte("old"), 0644)
	time.Sleep(10 * time.Millisecond)

	newest := filepath.Join(outputDir, "segment_002.ts")
	os.WriteFile(newest, []byte("new"), 0644)

	tg := &ThumbnailGenerator{
		streamRootDir: tmpDir,
		outputDir:     outputDir,
		outputMode:    OutputModeHLS,
		multiQuality:  false,
	}

	result := tg.findLatestSegment()
	if result != newest {
		t.Errorf("expected %q, got %q", newest, result)
	}
}

func TestFindLatestSegment_DASH(t *testing.T) {
	tmpDir := t.TempDir()

	// Create some .m4s chunk files
	old := filepath.Join(tmpDir, "chunk-stream0-00001.m4s")
	os.WriteFile(old, []byte("old"), 0644)
	time.Sleep(10 * time.Millisecond)

	newest := filepath.Join(tmpDir, "chunk-stream0-00002.m4s")
	os.WriteFile(newest, []byte("new"), 0644)

	tg := &ThumbnailGenerator{
		streamRootDir: tmpDir,
		outputDir:     tmpDir,
		outputMode:    OutputModeDASH,
		multiQuality:  false,
	}

	result := tg.findLatestSegment()
	if result != newest {
		t.Errorf("expected %q, got %q", newest, result)
	}
}

func TestFindLatestSegment_HLSMultiQuality(t *testing.T) {
	tmpDir := t.TempDir()

	highDir := filepath.Join(tmpDir, "high")
	os.MkdirAll(highDir, 0755)

	seg := filepath.Join(highDir, "segment_001.ts")
	os.WriteFile(seg, []byte("data"), 0644)

	tg := &ThumbnailGenerator{
		streamRootDir: tmpDir,
		outputDir:     tmpDir,
		outputMode:    OutputModeHLS,
		multiQuality:  true,
		qualities:     []string{"low", "medium", "high"},
	}

	result := tg.findLatestSegment()
	if result != seg {
		t.Errorf("expected %q, got %q", seg, result)
	}
}

func TestFindLatestSegment_NoSegments(t *testing.T) {
	tmpDir := t.TempDir()

	tg := &ThumbnailGenerator{
		streamRootDir: tmpDir,
		outputDir:     tmpDir,
		outputMode:    OutputModeHLS,
		multiQuality:  false,
	}

	result := tg.findLatestSegment()
	if result != "" {
		t.Errorf("expected empty string, got %q", result)
	}
}

func TestThumbnailGenerator_StartStop(t *testing.T) {
	tmpDir := t.TempDir()

	tg := NewThumbnailGenerator(tmpDir, tmpDir, OutputModeHLS, false, nil, 1)
	tg.Start()

	// Give it a moment to tick
	time.Sleep(50 * time.Millisecond)

	// Should not deadlock or panic
	tg.Stop()
}
