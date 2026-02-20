package restream

import (
	"context"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"sync"
	"time"

	"Theatrum/adapters/driven/metrics"
	"Theatrum/adapters/driver/ports"
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/services"
	"Theatrum/shared/streamcmd"
)

// Verify interface implementation
var _ ports.RestreamPort = (*RestreamManager)(nil)

// RestreamManager pulls from external URLs and outputs HLS/DASH segments.
type RestreamManager struct {
	channels        *map[string]models.Stream
	templateService *services.PathTemplateService
	registry        *services.LiveStreamRegistry
	viewerTracker   *services.ViewerTracker
	metrics         *metrics.Metrics
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
}

// NewRestreamManager creates a new RestreamManager.
func NewRestreamManager(
	appService *services.ApplicationService,
	templateService *services.PathTemplateService,
	registry *services.LiveStreamRegistry,
	viewerTracker *services.ViewerTracker,
	m *metrics.Metrics,
) ports.RestreamPort {
	return &RestreamManager{
		channels:        appService.GetChannels(),
		templateService: templateService,
		registry:        registry,
		viewerTracker:   viewerTracker,
		metrics:         m,
	}
}

// Start launches goroutines for all restream channels.
func (rm *RestreamManager) Start() {
	rm.ctx, rm.cancel = context.WithCancel(context.Background())

	if rm.channels == nil {
		return
	}

	for channelName, ch := range *rm.channels {
		if ch.Type != models.StreamTypeRestream {
			continue
		}

		// Generate builtin vars (e.g., {%STARTING_DATE%}, {%UUID%})
		builtinVars := rm.templateService.GenerateBuiltinVars(ch.Path)

		// For restream channels, there are no user variables, so the stream key is the path with builtins left unresolved.
		// Since restream paths have no {var} placeholders, the stream key = the raw path template.
		streamKey := ch.Path

		// Register builtins in the LiveStreamRegistry so HTTP handler can resolve tracking key
		rm.registry.GetOrRegister(streamKey, builtinVars)

		// Resolve the output path fully
		resolvedPath, err := rm.templateService.ReplacePlaceholders(ch.Path, builtinVars)
		if err != nil {
			log.Printf("Error resolving restream path for %s: %v", channelName, err)
			continue
		}

		// Determine output directory based on distribution mode
		multiQuality := len(ch.Qualities) > 0
		outputMode := streamcmd.DetermineOutputMode(ch.Distribution)
		dashEnabled := ch.Distribution.DashEnabled()

		var outputDir, streamRootDir string
		if dashEnabled || multiQuality {
			outputDir = filepath.Join(constants.VideoDir, resolvedPath)
			streamRootDir = outputDir
		} else {
			outputDir = filepath.Join(constants.VideoDir, resolvedPath, constants.DefaultQuality)
			streamRootDir = filepath.Join(constants.VideoDir, resolvedPath)
		}

		// Resolve record path if recording is enabled
		var resolvedRecordPath string
		if ch.Record.Enabled && ch.Record.Path != "" {
			recordPath, err := rm.templateService.ReplacePlaceholders(ch.Record.Path, builtinVars)
			if err != nil {
				log.Printf("Error resolving restream record path for %s: %v", channelName, err)
				continue
			}
			resolvedRecordPath = filepath.Join(constants.VideoDir, recordPath)
		}

		trackingKey := resolvedPath

		// Capture loop variables for goroutine
		chCopy := ch
		rm.wg.Add(1)
		go rm.runWithReconnect(channelName, chCopy, outputDir, streamRootDir, resolvedRecordPath, trackingKey, outputMode, streamKey)
	}
}

// Stop cancels all restream goroutines and waits for them to finish.
func (rm *RestreamManager) Stop() {
	if rm.cancel != nil {
		rm.cancel()
	}
	rm.wg.Wait()
}

// runWithReconnect runs FFmpeg in a loop with exponential backoff on failure.
func (rm *RestreamManager) runWithReconnect(
	channelName string,
	ch models.Stream,
	outputDir string,
	streamRootDir string,
	resolvedRecordPath string,
	trackingKey string,
	outputMode streamcmd.OutputMode,
	streamKey string,
) {
	defer rm.wg.Done()

	const (
		initialBackoff = 1 * time.Second
		maxBackoff     = 30 * time.Second
		resetThreshold = 30 * time.Second
	)

	backoff := initialBackoff

	for {
		if rm.ctx.Err() != nil {
			break
		}

		log.Printf("Starting restream for %s from %s", channelName, ch.SourceURL)

		startTime := time.Now()
		err := rm.runOnce(channelName, ch, outputDir, streamRootDir, trackingKey, outputMode)

		if rm.ctx.Err() != nil {
			// Context canceled — graceful shutdown
			break
		}

		runDuration := time.Since(startTime)
		if err != nil {
			log.Printf("Restream %s exited with error: %v", channelName, err)
			rm.metrics.FfmpegExitsTotal.WithLabelValues("error", trackingKey).Inc()
		} else {
			log.Printf("Restream %s exited normally", channelName)
			rm.metrics.FfmpegExitsTotal.WithLabelValues("clean", trackingKey).Inc()
		}

		// Reset backoff if the process ran for a while
		if runDuration > resetThreshold {
			backoff = initialBackoff
		}

		log.Printf("Restream %s will retry in %v", channelName, backoff)

		select {
		case <-time.After(backoff):
		case <-rm.ctx.Done():
			rm.handleShutdown(ch, outputDir, streamRootDir, resolvedRecordPath, trackingKey, streamKey, outputMode)
			return
		}

		// Exponential backoff
		backoff = time.Duration(math.Min(float64(backoff*2), float64(maxBackoff)))
	}

	rm.handleShutdown(ch, outputDir, streamRootDir, resolvedRecordPath, trackingKey, streamKey, outputMode)
}

// runOnce starts FFmpeg and waits for it to exit.
func (rm *RestreamManager) runOnce(
	channelName string,
	ch models.Stream,
	outputDir string,
	streamRootDir string,
	trackingKey string,
	outputMode streamcmd.OutputMode,
) error {
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return fmt.Errorf("failed to create output directory %s: %v", outputDir, err)
	}

	multiQuality := len(ch.Qualities) > 0

	// Generate master.m3u8 wrapper for HLS-only passthrough streams
	if outputMode == streamcmd.OutputModeHLS && !multiQuality {
		if err := streamcmd.GenerateMasterPlaylistWrapper(streamRootDir); err != nil {
			return fmt.Errorf("failed to generate master playlist wrapper: %v", err)
		}
	}

	ctx, cancel := context.WithCancel(rm.ctx)
	defer cancel()

	cmd := streamcmd.CreateFFmpegCommand(ctx, ch.SourceURL, outputDir, &ch, outputMode)

	startedAt := time.Now()

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("failed to start FFmpeg: %v", err)
	}

	rm.metrics.LiveStreamsActive.Inc()
	defer rm.metrics.LiveStreamsActive.Dec()

	err := cmd.Wait()
	rm.metrics.StreamDuration.WithLabelValues(trackingKey).Observe(time.Since(startedAt).Seconds())
	return err
}

// handleShutdown handles cleanup on graceful shutdown.
func (rm *RestreamManager) handleShutdown(
	ch models.Stream,
	outputDir string,
	streamRootDir string,
	resolvedRecordPath string,
	trackingKey string,
	streamKey string,
	outputMode streamcmd.OutputMode,
) {
	// Unregister viewer/view tracking
	if rm.viewerTracker != nil && trackingKey != "" {
		rm.viewerTracker.UnregisterStream(trackingKey)
	}

	// Unregister from LiveStreamRegistry
	rm.registry.Unregister(streamKey)

	if ch.Record.Enabled {
		if resolvedRecordPath != "" {
			// Move files to record path
			if err := os.MkdirAll(resolvedRecordPath, 0755); err != nil {
				log.Printf("Error creating recording directory %s: %v", resolvedRecordPath, err)
				rm.metrics.RecordingsTotal.WithLabelValues("move", "failure", trackingKey).Inc()
				return
			}
			if err := moveContents(streamRootDir, resolvedRecordPath); err != nil {
				log.Printf("Error moving files to recording directory: %v", err)
				rm.metrics.RecordingsTotal.WithLabelValues("move", "failure", trackingKey).Inc()
				return
			}
			os.RemoveAll(streamRootDir)
			rm.metrics.RecordingsTotal.WithLabelValues("move", "success", trackingKey).Inc()
			log.Printf("Restream recording saved to: %s", resolvedRecordPath)
		} else {
			// In-place recording: files stay where they are
			rm.metrics.RecordingsTotal.WithLabelValues("in_place", "success", trackingKey).Inc()
			log.Printf("Restream in-place recording saved at: %s", streamRootDir)
		}
	} else {
		// Clean up stream files
		os.RemoveAll(streamRootDir)
		log.Printf("Restream cleanup completed for: %s", trackingKey)
	}
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
