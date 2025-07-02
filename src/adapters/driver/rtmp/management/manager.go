package stream

import (
	"context"
	"fmt"
	"log"
	"os"
	"sync"
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
func (sm *Manager) GetOrCreateStream(inputPath string, path string) (*StreamProcess, error) {
	// Try to get existing stream
	if stream, ok := sm.streams.Load(inputPath); ok {
		if sp := stream.(*StreamProcess); sp.active.Load() {
			return sp, nil
		}
		// Clean up inactive stream
		sm.streams.Delete(inputPath)
	}

	// Create new stream
	stream, err := sm.createNewStream(inputPath, path)
	if err != nil {
		return nil, err
	}

	sm.streams.Store(inputPath, stream)
	return stream, nil
}

// createNewStream creates a new FFmpeg process for a streamer
// TODO : here is the issue with the path
func (sm *Manager) createNewStream(inputPath string, outputDir string) (*StreamProcess, error) {
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create output directory: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cmd := createFFmpegCommand(ctx, outputDir)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		cancel()
		return nil, fmt.Errorf("failed to get stdin pipe: %v", err)
	}

	if err := cmd.Start(); err != nil {
		cancel()
		return nil, fmt.Errorf("failed to start FFmpeg: %v", err)
	}

	stream := &StreamProcess{
		cmd:       cmd,
		stdin:     stdin,
		cancel:    cancel,
		inputPath: inputPath,
		outputDir: outputDir,
	}
	stream.active.Store(true)

	// Start monitoring goroutine
	go stream.monitor(sm)

	log.Printf("Started new stream for : %s", inputPath)
	return stream, nil
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