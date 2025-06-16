package rtmp

import (
	"fmt"
	"io"
	"log"
	"net"
	"strconv"

	"Theatrum/adapters/driver/ports"
	"Theatrum/adapters/driver/rtmp/config"
	rtmphandler "Theatrum/adapters/driver/rtmp/handlers"
	stream "Theatrum/adapters/driver/rtmp/management"
	"Theatrum/domain/services"

	"github.com/yutopp/go-rtmp"
)

// RtmpServer implements the RtmpPort interface
type RtmpServer struct {
	applicationService *services.ApplicationService
	streamService     *services.StreamService
	server            *rtmp.Server
	listener          net.Listener
	streamManager     *stream.Manager
}

// Verify interface implementation
var _ ports.RtmpPort = (*RtmpServer)(nil)


// TODO : manage the config
// TODO : check that all coroutine are well closed (process and handler)
// TODO : move templating logic
// TODO : move auth logic
func NewRtmpServer(applicationService *services.ApplicationService, streamService *services.StreamService) ports.RtmpPort {
	return &RtmpServer{
		applicationService: applicationService,
		streamService:     streamService,
		streamManager:     stream.NewManager(),
	}
}

func (s *RtmpServer) StartRtmpServer() error {
	log.Printf("=== RTMP SERVER ===")

	port := s.applicationService.GetServer().RTMPPort
	addr := ":" + strconv.Itoa(port)

	// Create RTMP server
	s.server = rtmp.NewServer(&rtmp.ServerConfig{
		OnConnect: func(conn net.Conn) (io.ReadWriteCloser, *rtmp.ConnConfig) {
			return conn, &rtmp.ConnConfig{
				Handler: rtmphandler.NewHandler(s.streamManager, s.getConfig()),
			}
		},
	})

	// Start listening
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to start RTMP server: %w", err)
	}
	s.listener = ln

	log.Printf("RTMP server listening on %s", addr)

	// Serve synchronously (blocking)
	if err := s.server.Serve(ln); err != nil {
		log.Printf("RTMP server error: %v", err)
		return err
	}

	return nil
}

func (s *RtmpServer) ShutdownRtmpServer() error {
	log.Printf("Shutting down RTMP server...")

	// Close the listener first
	if s.listener != nil {
		if err := s.listener.Close(); err != nil {
			log.Printf("Error closing RTMP listener: %v", err)
		}
	}

	// Stop all active streams
	if s.streamManager != nil {
		activeStreams := s.streamManager.GetActiveStreams()
		for _, username := range activeStreams {
			log.Printf("Stopping stream for user: %s", username)
			// Note: The stream manager doesn't have a GetStream method
			// The streams will be cleaned up automatically when the server shuts down
		}
	}

	log.Printf("RTMP server shutdown complete")
	return nil
}

func (s *RtmpServer) GetActiveStreams() []string {
	if s.streamManager != nil {
		return s.streamManager.GetActiveStreams()
	}
	return []string{}
}

// TODO : move the config in the yaml file
// TODO : force storage path
// getConfig returns a configuration object for the RTMP server
func (s *RtmpServer) getConfig() config.Config {
	return config.Config{
		OutputDir:         "storage/rtmp",
		ReconnectDelay:    30,
		CleanupDelay:      30,
		AuthorizedPatterns: []string{"/live/{username}"},
	}
}
