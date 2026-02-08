package stream

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"

	"Theatrum/domain/models"
)

// Manager manages multiple active streams
type Manager struct {
	streams sync.Map // thread-safe map of username -> *StreamProcess
	ctx     context.Context
}

// NewManager creates a new stream manager
func NewManager() *Manager {
	return &Manager{
		ctx: context.Background(),
	}
}

// SetContext sets the context for the manager
func (sm *Manager) SetContext(ctx context.Context) {
	sm.ctx = ctx
}

// GetOrCreateStream gets an existing stream or creates a new one
func (sm *Manager) GetOrCreateStream(inputPath string, outputDir string, stream *models.Stream, resolvedRecordPath string) (*StreamProcess, error) {
	// Try to get existing stream
	if existing, ok := sm.streams.Load(inputPath); ok {
		if sp := existing.(*StreamProcess); sp.active.Load() {
			return sp, nil
		}
		// Clean up inactive stream
		sm.streams.Delete(inputPath)
	}

	// Create new stream
	sp, err := sm.createNewStream(inputPath, outputDir, stream, resolvedRecordPath)
	if err != nil {
		return nil, err
	}

	sm.streams.Store(inputPath, sp)
	return sp, nil
}

// createNewStream creates a new FFmpeg process for a streamer
// The outputDir is built by the RtmpAuthService using PathTemplateService
func (sm *Manager) createNewStream(inputPath string, outputDir string, streamConfig *models.Stream, resolvedRecordPath string) (*StreamProcess, error) {
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create output directory: %v", err)
	}

	multiQuality := len(streamConfig.Qualities) > 0

	// For passthrough, outputDir is {path}/default; streamRootDir is {path} (parent).
	// For multi-quality, outputDir == streamRootDir (FFmpeg creates quality subdirs).
	var streamRootDir string
	if multiQuality {
		streamRootDir = outputDir
	} else {
		streamRootDir = filepath.Dir(outputDir)
	}

	// Generate master.m3u8 wrapper for passthrough streams
	if !multiQuality {
		if err := generateMasterPlaylistWrapper(streamRootDir); err != nil {
			return nil, fmt.Errorf("failed to generate master playlist wrapper: %v", err)
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	cmd := createFFmpegCommand(ctx, outputDir, streamConfig)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		cancel()
		return nil, fmt.Errorf("failed to get stdin pipe: %v", err)
	}

	if err := cmd.Start(); err != nil {
		cancel()
		return nil, fmt.Errorf("failed to start FFmpeg: %v", err)
	}

	sp := &StreamProcess{
		cmd:                cmd,
		stdin:              stdin,
		cancel:             cancel,
		inputPath:          inputPath,
		outputDir:          outputDir,
		streamRootDir:      streamRootDir,
		record:             streamConfig.Record,
		resolvedRecordPath: resolvedRecordPath,
		segmentDuration:    streamConfig.Distribution.Hls.SegmentDuration,
		multiQuality:       multiQuality,
	}
	sp.active.Store(true)

	// Start monitoring goroutine
	go sp.monitor(sm)

	log.Printf("Started new stream for : %s", inputPath)
	return sp, nil
}

// GetActiveStreams returns a list of usernames for all active streams
func (sm *Manager) GetActiveStreams() []string {
	var activeStreams []string
	sm.streams.Range(func(key, value interface{}) bool {
		if sp := value.(*StreamProcess); sp.active.Load() {
			activeStreams = append(activeStreams, key.(string))
		}
		return true
	})
	return activeStreams
} 