package rtmp

import (
	"fmt"
	"io"
	"log"
	"path/filepath"
	"sync"
	"time"

	"github.com/yutopp/go-rtmp"
	"github.com/yutopp/go-rtmp/message"

	"Theatrum/adapters/driven/metrics"
	rtmpconfig "Theatrum/adapters/driver/rtmp/config"
	"Theatrum/adapters/driver/rtmp/flv"
	stream "Theatrum/adapters/driver/rtmp/management"
	"Theatrum/adapters/driver/rtmp/models"
	"Theatrum/constants"
	"Theatrum/domain/services"
)

// Handler implements the RTMP handler interface
// Each connection gets its own handler instance
type Handler struct {
	rtmpAuthService *services.RtmpAuthService
	templateService *services.PathTemplateService
	registry        *services.LiveStreamRegistry
	metrics         *metrics.Metrics
	streamProcess   *stream.StreamProcess
	streamManager   *stream.Manager
	config          rtmpconfig.Config
	flvWriter       *flv.Writer
	connectionInfo  *models.ConnectionInfo
	streamKey       string // computed stream key for registry lookup/cleanup
	channelPattern  string // channel pattern for metric labels (e.g. "/user/{username}")
	trackingKey     string // fully resolved stream path for per-stream metric labels
	connMutex       sync.RWMutex
}

// NewHandler creates a new RTMP handler with injected dependencies
func NewHandler(rtmpAuthService *services.RtmpAuthService, templateService *services.PathTemplateService, registry *services.LiveStreamRegistry, manager *stream.Manager, cfg rtmpconfig.Config, m *metrics.Metrics) *Handler {
	return &Handler{
		rtmpAuthService: rtmpAuthService,
		templateService: templateService,
		registry:        registry,
		metrics:         m,
		streamManager:   manager,
		config:          cfg,
	}
}

// RTMP handler methods
func (h *Handler) OnServe(conn *rtmp.Conn) {
	log.Printf("New RTMP connection established")
	h.metrics.RtmpConnectionsTotal.Inc()
	h.metrics.RtmpConnectionsActive.Inc()
}

func (h *Handler) OnConnect(timestamp uint32, cmd *message.NetConnectionConnect) error {
	log.Printf("RTMP connection from %s", cmd.Command.TCURL)

	// Extract channel configuration and variables from TCURL using domain service
	stream, vars, pattern, ok := h.rtmpAuthService.ExtractChannel(cmd.Command.TCURL)
	if !ok {
		log.Printf("Failed to extract channel from TCURL '%s'", cmd.Command.TCURL)
		h.metrics.RtmpAuthTotal.WithLabelValues("failure").Inc()
		return fmt.Errorf("failed to extract channel from TCURL: %s", cmd.Command.TCURL)
	}

	// Check if TCURL is authorized
	if !h.rtmpAuthService.IsAuthorized(cmd.Command.TCURL) {
		log.Printf("Unauthorized TCURL '%s' in OnConnect", cmd.Command.TCURL)
		h.metrics.RtmpAuthTotal.WithLabelValues("failure").Inc()
		return fmt.Errorf("unauthorized TCURL: %s", cmd.Command.TCURL)
	}

	h.channelPattern = pattern

	// Store connection information for this handler instance
	h.connMutex.Lock()
	h.connectionInfo = &models.ConnectionInfo{
		App:    cmd.Command.App,
		TCURL:  cmd.Command.TCURL,
		Stream: stream,
		Vars:   vars,
	}
	h.connMutex.Unlock()

	log.Printf("RTMP connection authorized for path: %s", cmd.Command.TCURL)
	return nil
}

func (h *Handler) OnCreateStream(timestamp uint32, cmd *message.NetConnectionCreateStream) error {
	return nil
}

func (h *Handler) OnPlay(ctx *rtmp.StreamContext, timestamp uint32, cmd *message.NetStreamPlay) error {
	log.Printf("Play connection refused: %s", cmd.StreamName)
	return fmt.Errorf("play connections are not allowed")
}

func (h *Handler) OnPublish(ctx *rtmp.StreamContext, timestamp uint32, cmd *message.NetStreamPublish) error {
	log.Printf("Stream publish request on %s", h.GetTCURL())

	// Access the connection information
	h.connMutex.RLock()
	connInfo := h.connectionInfo
	h.connMutex.RUnlock()

	if connInfo == nil {
		return fmt.Errorf("no connection info available")
	}

	// Validate authentication using domain service
	if err := h.rtmpAuthService.ValidateAuthentication(connInfo.Stream, connInfo.Vars, cmd.PublishingName); err != nil {
		log.Printf("Authentication failed for TCURL %s: %v", connInfo.TCURL, err)
		h.metrics.RtmpAuthTotal.WithLabelValues("failure").Inc()
		return err
	}

	h.metrics.RtmpAuthTotal.WithLabelValues("success").Inc()
	h.metrics.LiveStreamsActive.Inc()
	log.Printf("Publishing to TCURL: %s", connInfo.TCURL)

	// Generate builtin values for this stream session
	builtinVars := h.templateService.GenerateBuiltinVars(connInfo.Stream.Path)

	// Compute stream key = stream.Path resolved with user vars only (builtins left as-is)
	streamKey, _ := h.templateService.ReplacePlaceholders(connInfo.Stream.Path, connInfo.Vars)

	// Atomic: reuse existing builtins on reconnection, store new ones on first publish
	builtinVars = h.registry.GetOrRegister(streamKey, builtinVars)

	// Merge builtins into vars for full path resolution
	mergedVars := make(map[string]string)
	for k, v := range connInfo.Vars {
		mergedVars[k] = v
	}
	for k, v := range builtinVars {
		mergedVars[k] = v
	}

	// Store stream key for cleanup in OnClose
	h.streamKey = streamKey

	// Build stream path using domain service (builtins now come from vars, deterministic)
	streamPath, err := h.rtmpAuthService.BuildStreamPath(connInfo.Stream, mergedVars)
	if err != nil {
		log.Printf("Failed to build stream path: %v", err)
		return fmt.Errorf("failed to build stream path: %w", err)
	}

	// Determine output directory based on distribution mode:
	// - DASH-enabled (any mode): flat layout at {path}/ (no quality subdirs)
	// - HLS-only with qualities: FFmpeg creates quality subdirs via %v
	// - HLS-only passthrough: output goes into default/ subdirectory
	var localPath string
	dashEnabled := connInfo.Stream.Distribution.DashEnabled()
	if dashEnabled || len(connInfo.Stream.Qualities) > 0 {
		localPath = filepath.Join(constants.VideoDir, streamPath)
	} else {
		localPath = filepath.Join(constants.VideoDir, streamPath, constants.DefaultQuality)
	}

	// Resolve record path if recording is enabled
	var resolvedRecordPath string
	if connInfo.Stream.Record.Enabled && connInfo.Stream.Record.Path != "" {
		recordPath, err := h.templateService.ReplacePlaceholders(connInfo.Stream.Record.Path, mergedVars)
		if err != nil {
			log.Printf("Failed to build record path: %v", err)
			return fmt.Errorf("failed to build record path: %w", err)
		}
		resolvedRecordPath = filepath.Join(constants.VideoDir, recordPath)
		log.Printf("Recording path: %s", resolvedRecordPath)
	}

	// Compute tracking key = fully resolved stream path (for viewer/view tracking)
	trackingKey, _ := h.templateService.ReplacePlaceholders(connInfo.Stream.Path, mergedVars)
	h.trackingKey = trackingKey

	log.Printf("Stream output path: %s", localPath)

	streamProcess, err := h.streamManager.GetOrCreateStream(connInfo.TCURL, localPath, connInfo.Stream, resolvedRecordPath, trackingKey, h.metrics)
	if err != nil {
		log.Printf("Failed to create stream for TCURL %s: %v", connInfo.TCURL, err)
		return err
	}

	h.streamProcess = streamProcess
	h.flvWriter = flv.NewWriter(streamProcess.Stdin())
	log.Printf("Stream started for TCURL: %s", connInfo.TCURL)
	return nil
}

func (h *Handler) OnClose() {
	h.metrics.RtmpConnectionsActive.Dec()

	if h.streamProcess != nil {
		log.Printf("Connection closed for: %s", h.streamProcess.InputPath())

		streamKey := h.streamKey
		go func() {
			time.Sleep(time.Duration(h.config.ReconnectDelay) * time.Second)

			if h.streamProcess.IsActive() {
				log.Printf("No reconnection detected for %s, stopping stream", h.streamProcess.InputPath())
				h.streamProcess.Stop(h.config)
				h.metrics.LiveStreamsActive.Dec()

				// Unregister builtin vars after stream definitively stops
				if streamKey != "" {
					h.registry.Unregister(streamKey)
				}
			}
		}()
	}

	// Clean up connection information
	h.connMutex.Lock()
	h.connectionInfo = nil
	h.connMutex.Unlock()
}

// Other RTMP handler methods (empty implementations)
func (h *Handler) OnReleaseStream(timestamp uint32, cmd *message.NetConnectionReleaseStream) error {
	return nil
}
func (h *Handler) OnDeleteStream(timestamp uint32, cmd *message.NetStreamDeleteStream) error {
	return nil
}
func (h *Handler) OnFCPublish(timestamp uint32, cmd *message.NetStreamFCPublish) error { return nil }
func (h *Handler) OnFCUnpublish(timestamp uint32, cmd *message.NetStreamFCUnpublish) error {
	return nil
}
func (h *Handler) OnUnknownMessage(timestamp uint32, msg message.Message) error { return nil }
func (h *Handler) OnUnknownCommandMessage(timestamp uint32, cmd *message.CommandMessage) error {
	return nil
}
func (h *Handler) OnUnknownDataMessage(timestamp uint32, data *message.DataMessage) error {
	return nil
}

// Required RTMP handler methods
func (h *Handler) OnSetDataFrame(timestamp uint32, data *message.NetStreamSetDataFrame) error {
	if h.flvWriter != nil {
		// Write metadata as FLV script tag
		return h.flvWriter.WriteScript(timestamp, data.Payload)
	}
	return nil
}

func (h *Handler) OnAudio(timestamp uint32, payload io.Reader) error {
	if h.flvWriter != nil {
		// Read audio data and write as FLV audio tag
		data, err := io.ReadAll(payload)
		if err != nil {
			return err
		}
		h.metrics.RtmpReceivedBytes.WithLabelValues(h.channelPattern, "audio", h.trackingKey).Add(float64(len(data)))
		h.metrics.RtmpReceivedFrames.WithLabelValues(h.channelPattern, "audio", h.trackingKey).Inc()
		return h.flvWriter.WriteAudio(timestamp, data)
	}
	return nil
}

func (h *Handler) OnVideo(timestamp uint32, payload io.Reader) error {
	if h.flvWriter != nil {
		// Read video data and write as FLV video tag
		data, err := io.ReadAll(payload)
		if err != nil {
			return err
		}
		h.metrics.RtmpReceivedBytes.WithLabelValues(h.channelPattern, "video", h.trackingKey).Add(float64(len(data)))
		h.metrics.RtmpReceivedFrames.WithLabelValues(h.channelPattern, "video", h.trackingKey).Inc()
		return h.flvWriter.WriteVideo(timestamp, data)
	}
	return nil
}

// GetConnectionInfo returns the stored connection information
func (h *Handler) GetConnectionInfo() *models.ConnectionInfo {
	h.connMutex.RLock()
	defer h.connMutex.RUnlock()
	return h.connectionInfo
}

// GetApp returns the App field from the connection
func (h *Handler) GetApp() string {
	h.connMutex.RLock()
	defer h.connMutex.RUnlock()
	if connInfo := h.connectionInfo; connInfo != nil {
		return connInfo.App
	}
	return ""
}

// GetTCURL returns the TCURL field from the connection
func (h *Handler) GetTCURL() string {
	h.connMutex.RLock()
	defer h.connMutex.RUnlock()
	if connInfo := h.connectionInfo; connInfo != nil {
		return connInfo.TCURL
	}
	return ""
}

// GetVar returns a specific variable from the stored URL variables
func (h *Handler) GetVar(key string) (string, bool) {
	h.connMutex.RLock()
	defer h.connMutex.RUnlock()
	if connInfo := h.connectionInfo; connInfo != nil {
		return connInfo.GetVar(key)
	}
	return "", false
}

// GetVars returns all stored URL variables
func (h *Handler) GetVars() map[string]string {
	h.connMutex.RLock()
	defer h.connMutex.RUnlock()
	if connInfo := h.connectionInfo; connInfo != nil {
		return connInfo.GetVars()
	}
	return make(map[string]string)
}