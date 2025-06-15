package stream

import (
	"context"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync/atomic"
	"time"

	"Theatrum/adapters/driver/rtmp/config"
)

// TODO : move in another adapter
// StreamProcess represents a single stream with its FFmpeg process
type StreamProcess struct {
	username  string
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	cancel    context.CancelFunc
	outputDir string
	active    atomic.Bool // atomic boolean for active state
}

// createFFmpegCommand creates an FFmpeg command with the specified settings
func createFFmpegCommand(ctx context.Context, outputDir string) *exec.Cmd {
	return exec.CommandContext(ctx, "ffmpeg",
		"-re",                  // clock to incoming timestamps
		"-fflags", "+nobuffer", // disable buffering
		"-flags", "low_delay",  // low delay mode
		"-f", "flv",
		"-i", "pipe:0",
		"-c:v", "copy",
		"-c:a", "copy",
		"-f", "hls",
		"-hls_time", "1",
		"-hls_list_size", "3",
		"-hls_flags", "delete_segments+temp_file+independent_segments",
		"-hls_segment_type", "mpegts",
		"-hls_allow_cache", "0", // disable client caching
		"-hls_segment_filename", filepath.Join(outputDir, "live_%03d.ts"),
		filepath.Join(outputDir, "live.m3u8"),
	)
}

// monitor waits for the FFmpeg process to exit and cleans up
func (sp *StreamProcess) monitor(sm *Manager) {
	defer func() {
		sp.active.Store(false)
		sm.streams.Delete(sp.username)
		log.Printf("Stream ended and cleaned up for user: %s", sp.username)
	}()

	if err := sp.cmd.Wait(); err != nil {
		log.Printf("FFmpeg exited for user %s: %v", sp.username, err)
	} else {
		log.Printf("FFmpeg exited normally for user: %s", sp.username)
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
		log.Printf("FFmpeg process exited cleanly for user: %s", sp.username)
	case <-time.After(time.Duration(cfg.CleanupDelay) * time.Second):
		log.Printf("FFmpeg process did not exit cleanly for user: %s, forcing termination", sp.username)
		if sp.cmd.Process != nil {
			sp.cmd.Process.Kill()
		}
	}

	// Clean up the output directory
	go func() {
		time.Sleep(time.Duration(cfg.CleanupDelay) * time.Second)
		if err := os.RemoveAll(sp.outputDir); err != nil {
			log.Printf("Error cleaning up stream directory for user %s: %v", sp.username, err)
		} else {
			log.Printf("Cleaned up stream directory for user: %s", sp.username)
		}
	}()
}

// IsActive returns whether the stream is currently active
func (sp *StreamProcess) IsActive() bool {
	return sp.active.Load()
}

// Username returns the username for this stream
func (sp *StreamProcess) Username() string {
	return sp.username
}

// Stdin returns the stdin writer for this stream
func (sp *StreamProcess) Stdin() io.WriteCloser {
	return sp.stdin
} 