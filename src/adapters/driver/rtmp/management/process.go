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

	"Theatrum/shared/ffmpegargs"
	"Theatrum/adapters/driven/metrics"
	"Theatrum/adapters/driver/rtmp/config"
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/services"
)

// OutputMode determines the muxer used by FFmpeg
type OutputMode int

const (
	OutputModeHLS  OutputMode = iota // HLS-only (ts segments, quality subdirs)
	OutputModeDASH                   // DASH-only (m4s segments, flat layout)
	OutputModeDual                   // Dual: DASH muxer with -hls_playlist 1 (both MPD and M3U8, m4s segments)
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
	outputMode         OutputMode
	trackingKey        string
	viewerTracker      *services.ViewerTracker
	metrics            *metrics.Metrics
	startedAt          time.Time
}

// DetermineOutputMode returns the appropriate output mode from a Distribution config.
func DetermineOutputMode(dist models.Distribution) OutputMode {
	if dist.HlsEnabled() && dist.DashEnabled() {
		return OutputModeDual
	}
	if dist.DashEnabled() {
		return OutputModeDASH
	}
	return OutputModeHLS
}

// createFFmpegCommand creates an FFmpeg command with the specified settings.
// When the stream has qualities defined, it produces multi-quality output.
// Otherwise it uses codec copy for passthrough.
func createFFmpegCommand(ctx context.Context, outputDir string, stream *models.Stream, mode OutputMode) *exec.Cmd {
	multiQuality := len(stream.Qualities) > 0
	switch mode {
	case OutputModeDASH, OutputModeDual:
		if multiQuality {
			return createDashMultiQualityCommand(ctx, outputDir, stream, mode)
		}
		return createDashCopyCommand(ctx, outputDir, stream, mode)
	default: // OutputModeHLS
		if multiQuality {
			return createMultiQualityCommand(ctx, outputDir, stream)
		}
		return createCopyCommand(ctx, outputDir, stream)
	}
}

// hlsFlags returns the appropriate -hls_flags value based on recording mode.
func hlsFlags(recording bool) string {
	if recording {
		return "temp_file+independent_segments"
	}
	return "delete_segments+temp_file+independent_segments"
}

// dashExtraWindowSize returns the extra_window_size for DASH muxer.
// Recording keeps all segments; non-recording deletes old ones.
func dashExtraWindowSize(recording bool) string {
	if recording {
		return "999999"
	}
	return "0"
}

// createCopyCommand creates an FFmpeg command that copies codecs without transcoding (passthrough, HLS-only).
func createCopyCommand(ctx context.Context, outputDir string, stream *models.Stream) *exec.Cmd {
	segmentDuration := fmt.Sprintf("%d", stream.Distribution.Hls.SegmentDuration)
	windowSize := fmt.Sprintf("%d", stream.Distribution.Hls.WindowSize)

	return exec.CommandContext(ctx, "ffmpeg",
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
		"-f", "flv",
		"-i", "pipe:0",
		"-c:v", "copy",
		"-c:a", "copy",
		"-f", "hls",
		"-hls_time", segmentDuration,
		"-hls_list_size", windowSize,
		"-hls_flags", hlsFlags(stream.Record.Enabled),
		"-hls_segment_type", "mpegts",
		"-hls_allow_cache", "0",
		"-hls_segment_filename", filepath.Join(outputDir, constants.SegmentName),
		filepath.Join(outputDir, constants.SubPlaylist),
	)
}

// createMultiQualityCommand creates an FFmpeg command that transcodes into multiple quality levels (HLS-only).
func createMultiQualityCommand(ctx context.Context, outputDir string, stream *models.Stream) *exec.Cmd {
	segmentDuration := fmt.Sprintf("%d", stream.Distribution.Hls.SegmentDuration)
	windowSize := fmt.Sprintf("%d", stream.Distribution.Hls.WindowSize)

	args := []string{
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
		"-f", "flv",
		"-i", "pipe:0",
	}

	args = ffmpegargs.AddFilter(args, stream.Qualities)
	args = ffmpegargs.AddVideoCodecLive(args, stream.Qualities)
	args = ffmpegargs.AddAudioCodec(args, stream.Qualities)

	streamMap := ffmpegargs.BuildVarStreamMap(stream.Qualities)

	args = append(args,
		"-f", "hls",
		"-hls_time", segmentDuration,
		"-hls_list_size", windowSize,
		"-hls_flags", hlsFlags(stream.Record.Enabled),
		"-hls_segment_type", "mpegts",
		"-hls_allow_cache", "0",
		"-var_stream_map", streamMap,
		"-master_pl_name", constants.MasterPlaylist,
		"-hls_segment_filename", filepath.Join(outputDir, "%v", constants.SegmentName),
		filepath.Join(outputDir, "%v", constants.SubPlaylist),
	)

	return exec.CommandContext(ctx, "ffmpeg", args...)
}

// createDashCopyCommand creates an FFmpeg command for DASH/dual passthrough (codec copy).
func createDashCopyCommand(ctx context.Context, outputDir string, stream *models.Stream, mode OutputMode) *exec.Cmd {
	dist := stream.Distribution
	var segDur, winSize int
	if dist.DashEnabled() {
		segDur = dist.Dash.SegmentDuration
		winSize = dist.Dash.WindowSize
	} else {
		segDur = dist.Hls.SegmentDuration
		winSize = dist.Hls.WindowSize
	}

	args := []string{
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
		"-f", "flv",
		"-i", "pipe:0",
		"-c:v", "copy",
		"-c:a", "copy",
		"-f", "dash",
		"-seg_duration", fmt.Sprintf("%d", segDur),
		"-window_size", fmt.Sprintf("%d", winSize),
		"-extra_window_size", dashExtraWindowSize(stream.Record.Enabled),
		"-streaming", "1",
		"-ldash", "1",
		"-use_template", "1",
		"-use_timeline", "0",
		"-remove_at_exit", "0",
		"-init_seg_name", constants.DashInitSegName,
		"-media_seg_name", constants.DashSegName,
	}

	if mode == OutputModeDual {
		args = append(args, "-hls_playlist", "1")
	}

	args = append(args, filepath.Join(outputDir, constants.DashManifest))
	return exec.CommandContext(ctx, "ffmpeg", args...)
}

// createDashMultiQualityCommand creates an FFmpeg command for DASH/dual multi-quality transcoding.
func createDashMultiQualityCommand(ctx context.Context, outputDir string, stream *models.Stream, mode OutputMode) *exec.Cmd {
	dist := stream.Distribution
	var segDur, winSize int
	if dist.DashEnabled() {
		segDur = dist.Dash.SegmentDuration
		winSize = dist.Dash.WindowSize
	} else {
		segDur = dist.Hls.SegmentDuration
		winSize = dist.Hls.WindowSize
	}

	args := []string{
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
		"-f", "flv",
		"-i", "pipe:0",
	}

	args = ffmpegargs.AddFilter(args, stream.Qualities)
	args = ffmpegargs.AddVideoCodecLive(args, stream.Qualities)
	args = ffmpegargs.AddAudioCodec(args, stream.Qualities)

	args = append(args,
		"-f", "dash",
		"-seg_duration", fmt.Sprintf("%d", segDur),
		"-window_size", fmt.Sprintf("%d", winSize),
		"-extra_window_size", dashExtraWindowSize(stream.Record.Enabled),
		"-streaming", "1",
		"-ldash", "1",
		"-use_template", "1",
		"-use_timeline", "0",
		"-remove_at_exit", "0",
		"-init_seg_name", constants.DashInitSegName,
		"-media_seg_name", constants.DashSegName,
		"-adaptation_sets", "id=0,streams=v id=1,streams=a",
	)

	if mode == OutputModeDual {
		args = append(args, "-hls_playlist", "1")
	}

	args = append(args, filepath.Join(outputDir, constants.DashManifest))
	return exec.CommandContext(ctx, "ffmpeg", args...)
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

	if sp.outputMode == OutputModeHLS {
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
			if err := generateMasterPlaylistWrapper(recordDir); err != nil {
				log.Printf("Error generating master playlist wrapper for recording: %v", err)
			}
			viewsSrc := filepath.Join(sp.streamRootDir, constants.ViewsFile)
			if _, err := os.Stat(viewsSrc); err == nil {
				os.Rename(viewsSrc, filepath.Join(recordDir, constants.ViewsFile))
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
	if sp.outputMode == OutputModeHLS {
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
			if err := generateMasterPlaylistWrapper(sp.streamRootDir); err != nil {
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
