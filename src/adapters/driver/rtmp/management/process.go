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

	"Theatrum/adapters/driven/ffmpegEncoder/ffmpegargs"
	"Theatrum/adapters/driver/rtmp/config"
	"Theatrum/constants"
	"Theatrum/domain/models"
)

// LATER : move in another adapter
// StreamProcess represents a single stream with its FFmpeg process
type StreamProcess struct {
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	cancel    context.CancelFunc
	inputPath string
	outputDir string
	active    atomic.Bool // atomic boolean for active state
}

// createFFmpegCommand creates an FFmpeg command with the specified settings.
// When the stream has qualities defined, it produces multi-quality HLS output.
// Otherwise it uses codec copy for passthrough.
func createFFmpegCommand(ctx context.Context, outputDir string, stream *models.Stream) *exec.Cmd {
	if len(stream.Qualities) > 0 {
		return createMultiQualityCommand(ctx, outputDir, stream)
	}
	return createCopyCommand(ctx, outputDir, stream)
}

// createCopyCommand creates an FFmpeg command that copies codecs without transcoding (passthrough).
func createCopyCommand(ctx context.Context, outputDir string, stream *models.Stream) *exec.Cmd {
	segmentDuration := fmt.Sprintf("%d", stream.Distribution.Hls.SegmentDuration)

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
		"-hls_list_size", "3",
		"-hls_flags", "delete_segments+temp_file+independent_segments",
		"-hls_segment_type", "mpegts",
		"-hls_allow_cache", "0",
		"-hls_segment_filename", filepath.Join(outputDir, constants.SegmentName),
		filepath.Join(outputDir, constants.SubPlaylist),
	)
}

// createMultiQualityCommand creates an FFmpeg command that transcodes into multiple quality levels.
func createMultiQualityCommand(ctx context.Context, outputDir string, stream *models.Stream) *exec.Cmd {
	segmentDuration := fmt.Sprintf("%d", stream.Distribution.Hls.SegmentDuration)

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
		"-hls_list_size", "3",
		"-hls_flags", "delete_segments+temp_file+independent_segments",
		"-hls_segment_type", "mpegts",
		"-hls_allow_cache", "0",
		"-var_stream_map", streamMap,
		"-master_pl_name", constants.MasterPlaylist,
		"-hls_segment_filename", filepath.Join(outputDir, "%v", constants.SegmentName),
		filepath.Join(outputDir, "%v", constants.SubPlaylist),
	)

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
	} else {
		log.Printf("FFmpeg exited normally for: %s", sp.inputPath)
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
	}

	// Clean up the output directory
	go func() {
		time.Sleep(time.Duration(cfg.CleanupDelay) * time.Second)
		if err := os.RemoveAll(sp.outputDir); err != nil {
			log.Printf("Error cleaning up stream directory for: %s: %v", sp.inputPath, err)
		} else {
			log.Printf("Cleaned up stream directory for: %s", sp.inputPath)
		}
	}()
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
