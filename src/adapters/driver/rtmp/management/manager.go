package stream

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
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
func (sm *Manager) GetOrCreateStream(username string, path string) (*StreamProcess, error) {
	// Try to get existing stream
	if stream, ok := sm.streams.Load(username); ok {
		if sp := stream.(*StreamProcess); sp.active.Load() {
			return sp, nil
		}
		// Clean up inactive stream
		sm.streams.Delete(username)
	}

	// Create new stream
	stream, err := sm.createNewStream(username, path)
	if err != nil {
		return nil, err
	}

	sm.streams.Store(username, stream)
	return stream, nil
}

// createNewStream creates a new FFmpeg process for a streamer
func (sm *Manager) createNewStream(username string, path string) (*StreamProcess, error) {
	outputDir := filepath.Join(path, username)
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
		username:  username,
		cmd:       cmd,
		stdin:     stdin,
		cancel:    cancel,
		outputDir: outputDir,
	}
	stream.active.Store(true)

	// Start monitoring goroutine
	go stream.monitor(sm)

	log.Printf("Started new stream for user: %s", username)
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