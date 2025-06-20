package rtmp

import (
	"fmt"
	"io"
	"log"
	"sync"
	"time"

	"github.com/yutopp/go-rtmp"
	"github.com/yutopp/go-rtmp/message"

	"Theatrum/adapters/driver/rtmp/auth"
	rtmpconfig "Theatrum/adapters/driver/rtmp/config"
	"Theatrum/adapters/driver/rtmp/flv"
	stream "Theatrum/adapters/driver/rtmp/management"
	"Theatrum/adapters/driver/rtmp/models"
	"Theatrum/domain/models"
)

// Each connection gets its own handler instance
// So we need to store the connection info for this handler instance
// Since each connection gets its own handler instance (from main.go)

// Handler implements the RTMP handler interface
type Handler struct {
	stream        *models.Stream // TODO : pass stream
	streamProcess *stream.StreamProcess
	streamManager *stream.Manager
	config        rtmpconfig.Config
	authorizer    *auth.Authorizer
	flvWriter     *flv.Writer
	connectionInfo *models.ConnectionInfo
	connMutex      sync.RWMutex
}

// NewHandler creates a new RTMP handler
func NewHandler(manager *stream.Manager, cfg rtmpconfig.Config) *Handler {
	return &Handler{
		stream: TODO, // TODO : pass stream
		streamManager: manager,
		config:        cfg,
		authorizer:    auth.NewAuthorizer(cfg.AuthorizedPatterns),
	}
}

// RTMP handler methods
func (h *Handler) OnServe(conn *rtmp.Conn) {
	log.Printf("New RTMP connection established")
}

func (h *Handler) OnConnect(timestamp uint32, cmd *message.NetConnectionConnect) error {
	log.Printf("RTMP connection from %s", cmd.Command.TCURL)
	
	// Extract variables from TCURL
	vars, ok := h.authorizer.ExtractVariables(cmd.Command.TCURL)
	if !ok {
		log.Printf("Failed to extract variables from TCURL '%s'", cmd.Command.TCURL)
		return fmt.Errorf("failed to extract variables from TCURL: %s", cmd.Command.TCURL)
	}
	
	// Check if TCURL is authorized
	if !h.authorizer.IsAuthorized(cmd.Command.TCURL) {
		log.Printf("Unauthorized TCURL '%s' in OnConnect", cmd.Command.TCURL)
		return fmt.Errorf("unauthorized TCURL: %s", cmd.Command.TCURL)
	}
	
	// Store connection information for this handler instance
	h.connMutex.Lock()
	h.connectionInfo = &models.ConnectionInfo{
		App:     cmd.Command.App,
		TCURL:   cmd.Command.TCURL,
		Vars:    vars,
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
	
	if connInfo != nil {
		// Use the stored variables for authentication
		if err := h.authorizer.ValidateAuthentication(connInfo.Vars, cmd.PublishingName); err != nil {
			log.Printf("Authentication failed for TCURL access %s: %v", connInfo.TCURL, err)
			return err
		}

		log.Printf("Publishing to TCURL: %s", connInfo.TCURL)
	}

	streamProcess, err := h.streamManager.GetOrCreateStream(cmd.PublishingName, h.config)
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
	if h.streamProcess != nil {
		log.Printf("Connection closed for user: %s", h.streamProcess.Username())

		go func() {
			time.Sleep(time.Duration(h.config.ReconnectDelay) * time.Second)

			if h.streamProcess.IsActive() {
				log.Printf("No reconnection detected for user %s, stopping stream", h.streamProcess.Username())
				h.streamProcess.Stop(h.config)
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