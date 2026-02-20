package stream

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync/atomic"
	"time"

	"Theatrum/adapters/driven/metrics"
	"Theatrum/adapters/driver/rtmp/config"
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/services"
	"Theatrum/shared/streamcmd"
)

// LATER : move in another adapter
// StreamProcess represents a single stream with its FFmpeg process
type StreamProcess struct {
	cmd                *exec.Cmd
	stdin              io.WriteCloser
	cancel             context.CancelFunc
	inputPath          string
	outputDir          string
	streamRootDir      string // Root dir for stream files (parent of "default/" for HLS passthrough, same as outputDir otherwise)
	active             atomic.Bool // atomic boolean for active state
	record             models.Record
	resolvedRecordPath string
	segmentDuration    int
	multiQuality       bool
	outputMode         streamcmd.OutputMode
	trackingKey        string
	viewerTracker      *services.ViewerTracker
	metrics            *metrics.Metrics
	startedAt          time.Time
	thumbnailGen       *ThumbnailGenerator
}

// monitor waits for the FFmpeg process to exit and cleans up
func (sp *StreamProcess) monitor(sm *Manager) {
	defer func() {
		sp.active.Store(false)
		sm.streams.Delete(sp.inputPath)
		log.Printf("Stream ended and cleaned up for: %s", sp.inputPath)
	}()

	if err := sp.cmd.Wait(); err != nil {
		log.Printf("FFmpeg exited for: %s: %v", sp.inputPath, err)
		sp.metrics.FfmpegExitsTotal.WithLabelValues("error", sp.trackingKey).Inc()
	} else {
		log.Printf("FFmpeg exited normally for: %s", sp.inputPath)
		sp.metrics.FfmpegExitsTotal.WithLabelValues("clean", sp.trackingKey).Inc()
	}
}

// Stop gracefully stops the stream
func (sp *StreamProcess) Stop(cfg config.Config) {
	if !sp.active.Swap(false) {
		return // already stopped
	}

	// Stop thumbnail generation before shutting down FFmpeg
	if sp.thumbnailGen != nil {
		sp.thumbnailGen.Stop()
	}

	// Close stdin to signal FFmpeg to stop
	if sp.stdin != nil {
		sp.stdin.Close()
	}

	// Cancel the context
	if sp.cancel != nil {
		sp.cancel()
	}

	// Wait for FFmpeg to exit with timeout
	done := make(chan struct{})
	go func() {
		sp.cmd.Wait()
		close(done)
	}()

	select {
	case <-done:
		log.Printf("FFmpeg process exited cleanly for: %s", sp.inputPath)
	case <-time.After(time.Duration(cfg.CleanupDelay) * time.Second):
		log.Printf("FFmpeg process did not exit cleanly for: %s, forcing termination", sp.inputPath)
		if sp.cmd.Process != nil {
			sp.cmd.Process.Kill()
		}
		sp.metrics.FfmpegExitsTotal.WithLabelValues("killed", sp.trackingKey).Inc()
	}

	// Unregister viewer/view tracking for this stream
	if sp.viewerTracker != nil && sp.trackingKey != "" {
		sp.viewerTracker.UnregisterStream(sp.trackingKey)
	}
	sp.metrics.StreamDuration.WithLabelValues(sp.trackingKey).Observe(time.Since(sp.startedAt).Seconds())

	if sp.record.Enabled && sp.resolvedRecordPath != "" {
		go sp.saveRecording()
	} else if sp.record.Enabled {
		go sp.saveInPlace()
	} else {
		// Clean up the stream root directory (includes master.m3u8 for passthrough)
		go func() {
			time.Sleep(time.Duration(cfg.CleanupDelay) * time.Second)
			if err := os.RemoveAll(sp.streamRootDir); err != nil {
				log.Printf("Error cleaning up stream directory for: %s: %v", sp.inputPath, err)
			} else {
				log.Printf("Cleaned up stream directory for: %s", sp.inputPath)
			}
		}()
	}
}

// saveRecording generates VOD playlists and moves files to the recording path.
func (sp *StreamProcess) saveRecording() {
	recordDir := sp.resolvedRecordPath

	if sp.outputMode == streamcmd.OutputModeHLS {
		// HLS-only mode: generate VOD playlists from .ts segments
		if sp.multiQuality {
			entries, err := os.ReadDir(sp.outputDir)
			if err != nil {
				log.Printf("Error reading output directory for recording: %s: %v", sp.outputDir, err)
				sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
				return
			}
			for _, entry := range entries {
				if entry.IsDir() {
					qualityDir := filepath.Join(sp.outputDir, entry.Name())
					if err := generateVODPlaylist(qualityDir, sp.segmentDuration); err != nil {
						log.Printf("Error generating VOD playlist for quality %s: %v", entry.Name(), err)
					}
				}
			}
		} else {
			if err := generateVODPlaylist(sp.outputDir, sp.segmentDuration); err != nil {
				log.Printf("Error generating VOD playlist: %v", err)
			}
		}

		// Create recording directory (with default/ subdir for passthrough)
		if !sp.multiQuality {
			if err := os.MkdirAll(filepath.Join(recordDir, constants.DefaultQuality), 0755); err != nil {
				log.Printf("Error creating recording directory %s: %v", recordDir, err)
				sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
				return
			}
		} else {
			if err := os.MkdirAll(recordDir, 0755); err != nil {
				log.Printf("Error creating recording directory %s: %v", recordDir, err)
				sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
				return
			}
		}

		if sp.multiQuality {
			if err := moveContents(sp.streamRootDir, recordDir); err != nil {
				log.Printf("Error moving files to recording directory: %v", err)
				sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
				return
			}
		} else {
			if err := moveContents(sp.outputDir, filepath.Join(recordDir, constants.DefaultQuality)); err != nil {
				log.Printf("Error moving files to recording directory: %v", err)
				sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
				return
			}
			if err := streamcmd.GenerateMasterPlaylistWrapper(recordDir); err != nil {
				log.Printf("Error generating master playlist wrapper for recording: %v", err)
			}
			viewsSrc := filepath.Join(sp.streamRootDir, constants.ViewsFile)
			if _, err := os.Stat(viewsSrc); err == nil {
				os.Rename(viewsSrc, filepath.Join(recordDir, constants.ViewsFile))
			}
			thumbnailSrc := filepath.Join(sp.streamRootDir, constants.ThumbnailFile)
			if _, err := os.Stat(thumbnailSrc); err == nil {
				os.Rename(thumbnailSrc, filepath.Join(recordDir, constants.ThumbnailFile))
			}
		}
	} else {
		// DASH or Dual mode: FFmpeg finalizes manifests on clean exit
		// No additional manifest generation needed
		if err := os.MkdirAll(recordDir, 0755); err != nil {
			log.Printf("Error creating recording directory %s: %v", recordDir, err)
			sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
			return
		}

		if err := moveContents(sp.streamRootDir, recordDir); err != nil {
			log.Printf("Error moving files to recording directory: %v", err)
			sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
			return
		}
	}

	// Remove original stream root directory
	if err := os.RemoveAll(sp.streamRootDir); err != nil {
		log.Printf("Error removing original stream directory: %s: %v", sp.streamRootDir, err)
		sp.metrics.RecordingsTotal.WithLabelValues("move", "failure", sp.trackingKey).Inc()
		return
	}

	sp.metrics.RecordingsTotal.WithLabelValues("move", "success", sp.trackingKey).Inc()
	log.Printf("Recording saved to: %s", recordDir)
}

// saveInPlace generates VOD playlists in the output directory without moving files.
func (sp *StreamProcess) saveInPlace() {
	if sp.outputMode == streamcmd.OutputModeHLS {
		// HLS-only: generate VOD playlists from .ts segments
		if sp.multiQuality {
			entries, err := os.ReadDir(sp.outputDir)
			if err != nil {
				log.Printf("Error reading output directory for in-place recording: %s: %v", sp.outputDir, err)
				sp.metrics.RecordingsTotal.WithLabelValues("in_place", "failure", sp.trackingKey).Inc()
				return
			}
			for _, entry := range entries {
				if entry.IsDir() {
					qualityDir := filepath.Join(sp.outputDir, entry.Name())
					if err := generateVODPlaylist(qualityDir, sp.segmentDuration); err != nil {
						log.Printf("Error generating VOD playlist for quality %s: %v", entry.Name(), err)
					}
				}
			}
		} else {
			if err := generateVODPlaylist(sp.outputDir, sp.segmentDuration); err != nil {
				log.Printf("Error generating VOD playlist: %v", err)
			}
			if err := streamcmd.GenerateMasterPlaylistWrapper(sp.streamRootDir); err != nil {
				log.Printf("Error generating master playlist wrapper for in-place recording: %v", err)
			}
		}
	}
	// DASH/Dual: FFmpeg finalizes manifests on clean exit, nothing to do

	sp.metrics.RecordingsTotal.WithLabelValues("in_place", "success", sp.trackingKey).Inc()
	log.Printf("In-place recording saved at: %s", sp.streamRootDir)
}

// moveContents moves all files and directories from src to dst.
func moveContents(src, dst string) error {
	entries, err := os.ReadDir(src)
	if err != nil {
		return fmt.Errorf("failed to read source directory: %w", err)
	}

	for _, entry := range entries {
		srcPath := filepath.Join(src, entry.Name())
		dstPath := filepath.Join(dst, entry.Name())
		if err := os.Rename(srcPath, dstPath); err != nil {
			return fmt.Errorf("failed to move %s to %s: %w", srcPath, dstPath, err)
		}
	}
	return nil
}

// IsActive returns whether the stream is currently active
func (sp *StreamProcess) IsActive() bool {
	return sp.active.Load()
}

// InputPath returns the input path for this stream
func (sp *StreamProcess) InputPath() string {
	return sp.inputPath
}

// Stdin returns the stdin writer for this stream
func (sp *StreamProcess) Stdin() io.WriteCloser {
	return sp.stdin
}
