package stream

import (
	"context"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"Theatrum/constants"
	"Theatrum/shared/streamcmd"
)

// ThumbnailGenerator periodically extracts a PNG frame from the latest segment.
type ThumbnailGenerator struct {
	streamRootDir string
	outputDir     string
	outputMode    streamcmd.OutputMode
	multiQuality  bool
	qualities     []string
	interval      time.Duration
	cancel        context.CancelFunc
	wg            sync.WaitGroup
}

// NewThumbnailGenerator creates a new ThumbnailGenerator.
func NewThumbnailGenerator(streamRootDir, outputDir string, outputMode streamcmd.OutputMode, multiQuality bool, qualities []string, intervalSec int) *ThumbnailGenerator {
	return &ThumbnailGenerator{
		streamRootDir: streamRootDir,
		outputDir:     outputDir,
		outputMode:    outputMode,
		multiQuality:  multiQuality,
		qualities:     qualities,
		interval:      time.Duration(intervalSec) * time.Second,
	}
}

// Start begins periodic thumbnail generation in a background goroutine.
func (tg *ThumbnailGenerator) Start() {
	ctx, cancel := context.WithCancel(context.Background())
	tg.cancel = cancel
	tg.wg.Add(1)
	go tg.loop(ctx)
}

// Stop cancels thumbnail generation and waits for the goroutine to exit.
func (tg *ThumbnailGenerator) Stop() {
	if tg.cancel != nil {
		tg.cancel()
	}
	tg.wg.Wait()
}

func (tg *ThumbnailGenerator) loop(ctx context.Context) {
	defer tg.wg.Done()

	ticker := time.NewTicker(tg.interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			tg.generate(ctx)
		}
	}
}

func (tg *ThumbnailGenerator) generate(ctx context.Context) {
	segment := tg.findLatestSegment()
	if segment == "" {
		return
	}

	outputPath := filepath.Join(tg.streamRootDir, constants.ThumbnailFile)
	tmpPath := outputPath[:len(outputPath)-len(filepath.Ext(outputPath))] + ".tmp" + filepath.Ext(outputPath)

	args := tg.buildFFmpegArgs(segment, tmpPath)

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	if err := cmd.Run(); err != nil {
		// Don't log on context cancellation (normal shutdown)
		if ctx.Err() == nil {
			log.Printf("Thumbnail generation failed for %s: %v", tg.streamRootDir, err)
		}
		os.Remove(tmpPath)
		return
	}

	// Atomic rename
	if err := os.Rename(tmpPath, outputPath); err != nil {
		log.Printf("Thumbnail rename failed for %s: %v", tg.streamRootDir, err)
		os.Remove(tmpPath)
	}
}

func (tg *ThumbnailGenerator) buildFFmpegArgs(segmentPath, outputPath string) []string {
	ext := filepath.Ext(segmentPath)

	if ext == ".m4s" {
		// For m4s segments, we need to prepend the init segment
		initSeg := tg.findInitSegment()
		if initSeg == "" {
			// Fallback: try without init segment
			return []string{"-y", "-loglevel", "error", "-i", segmentPath, "-frames:v", "1", "-update", "1", outputPath}
		}
		// Use concat protocol to combine init + chunk
		concatInput := "concat:" + initSeg + "|" + segmentPath
		return []string{"-y", "-loglevel", "error", "-i", concatInput, "-frames:v", "1", "-update", "1", outputPath}
	}

	// For .ts segments
	return []string{"-y", "-loglevel", "error", "-i", segmentPath, "-frames:v", "1", "-update", "1", outputPath}
}

// findLatestSegment locates the most recently modified segment file.
func (tg *ThumbnailGenerator) findLatestSegment() string {
	switch {
	case tg.outputMode == streamcmd.OutputModeHLS && !tg.multiQuality:
		// HLS passthrough: segments in outputDir (the default/ subdir)
		return findLatestFileByGlob(filepath.Join(tg.outputDir, "*.ts"))
	case tg.outputMode == streamcmd.OutputModeHLS && tg.multiQuality:
		// HLS multi-quality: use best quality dir
		bestDir := tg.pickBestQualityDir()
		if bestDir == "" {
			return ""
		}
		return findLatestFileByGlob(filepath.Join(bestDir, "*.ts"))
	default:
		// DASH or Dual: m4s chunks in streamRootDir
		return findLatestFileByGlob(filepath.Join(tg.streamRootDir, "chunk-stream*.m4s"))
	}
}

// pickBestQualityDir returns the directory for the highest quality available.
// Preference: high > medium > low > first alphabetically.
func (tg *ThumbnailGenerator) pickBestQualityDir() string {
	preference := []string{"high", "medium", "low"}
	for _, name := range preference {
		for _, q := range tg.qualities {
			if q == name {
				dir := filepath.Join(tg.outputDir, name)
				if info, err := os.Stat(dir); err == nil && info.IsDir() {
					return dir
				}
			}
		}
	}
	// Fallback: first quality directory that exists
	for _, q := range tg.qualities {
		dir := filepath.Join(tg.outputDir, q)
		if info, err := os.Stat(dir); err == nil && info.IsDir() {
			return dir
		}
	}
	return ""
}

// findInitSegment finds a DASH/Dual video init segment (init-stream*.m4s).
func (tg *ThumbnailGenerator) findInitSegment() string {
	matches, err := filepath.Glob(filepath.Join(tg.streamRootDir, "init-stream*.m4s"))
	if err != nil || len(matches) == 0 {
		return ""
	}
	// Prefer init-stream0.m4s (video)
	for _, m := range matches {
		if strings.Contains(filepath.Base(m), "init-stream0") {
			return m
		}
	}
	return matches[0]
}

// findLatestFileByGlob returns the most recently modified file matching the glob pattern.
func findLatestFileByGlob(pattern string) string {
	matches, err := filepath.Glob(pattern)
	if err != nil || len(matches) == 0 {
		return ""
	}

	sort.Slice(matches, func(i, j int) bool {
		infoI, errI := os.Stat(matches[i])
		infoJ, errJ := os.Stat(matches[j])
		if errI != nil || errJ != nil {
			return false
		}
		return infoI.ModTime().After(infoJ.ModTime())
	})

	return matches[0]
}
