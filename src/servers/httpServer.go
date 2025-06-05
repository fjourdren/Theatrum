package servers

import (
	"context"
	"log"
	"net/http"
	"strconv"

	"github.com/gorilla/mux"

	"Theatrum/adapters/driver/http/handlers"
	"Theatrum/adapters/driver/ports"
	"Theatrum/constants"
	"Theatrum/domain/services"
)

// HttpServer implements the HttpPort interface
type HttpServer struct {
	applicationService *services.ApplicationService
	streamService     *services.StreamService
	server            *http.Server
}

// Verify interface implementation
var _ ports.HttpPort = (*HttpServer)(nil)

func NewHttpServer(applicationService *services.ApplicationService, streamService *services.StreamService) ports.HttpPort {
	return &HttpServer{
		applicationService: applicationService,
		streamService:     streamService,
	}
}

func (s *HttpServer) BuildRouter() *mux.Router {
	// Create Gorilla Mux router
	r := mux.NewRouter()

	// Handle all streams playlist if enabled
	if s.applicationService.GetApplication().AllStreamsPlaylist.Enabled {
		playlistPath := s.applicationService.GetApplication().AllStreamsPlaylist.Path
		log.Printf("Registering all streams playlist at: %s", playlistPath)
		r.Handle("/"+playlistPath, handlers.NewAllStreamsPlaylistHandler(s.applicationService, s.streamService)).Methods("GET")
	}

	channels := *s.applicationService.GetChannels()

	// Handle all channels
	for path, channel := range channels {
		log.Printf("Registering channel: %s -> %s", path, channel.Path)
		
		// Create a subrouter for this channel
		channelRouter := r.PathPrefix(path).Subrouter()
		// Create the stream handler
		handler := handlers.NewStreamHandler(&channel, s.streamService, s.applicationService)
		
		if len(channel.Qualities) != 0 { // If there is a quality, then we need to handle quality-specific paths
			// Handle quality-specific paths
			channelRouter.Handle("/{quality}/{resource:.*}", handler).Methods("GET")
			// Handle master playlist
			channelRouter.Handle("/{resource:" + constants.MasterPlaylist + "}", handler).Methods("GET")
		} else { // If there is no quality, then we need to handle simple paths ("default" quality in the storage path)
			// Handle simple paths without quality
			channelRouter.Handle("/{resource:.*}", handler).Methods("GET")
		}
	}

	// Serve frontend for any other routes
	r.PathPrefix("/").Handler(handlers.FrontendHandler(constants.FrontendDir))

	// Log the registered routes for debugging
	r.Walk(func(route *mux.Route, router *mux.Router, ancestors []*mux.Route) error {
		template, err := route.GetPathTemplate()
		if err != nil {
			return err
		}
		log.Printf("Registered route: %s", template)
		return nil
	})

	return r
}

func (s *HttpServer) StartHttpServer() error {
	log.Printf("=== HTTP SERVER ===")

	port := s.applicationService.GetServer().HTTPPort
	addr := "localhost:" + strconv.Itoa(port)
	
	s.server = &http.Server{
		Addr:    ":" + strconv.Itoa(port),
		Handler: s.BuildRouter(),
	}

	log.Printf("Starting streaming server on http://%s", addr)

	return s.server.ListenAndServe()
}

func (s *HttpServer) Shutdown(ctx context.Context) error {
	if s.server != nil {
		return s.server.Shutdown(ctx)
	}
	return nil
}

