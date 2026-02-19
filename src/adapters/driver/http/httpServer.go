package http

import (
	"context"
	"log"
	"net/http"
	"strconv"

	"github.com/gorilla/mux"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"Theatrum/adapters/driven/metrics"
	"Theatrum/adapters/driver/http/handlers"
	"Theatrum/adapters/driver/ports"
	"Theatrum/constants"
	"Theatrum/domain/services"
)

// HttpServer implements the HttpPort interface
type HttpServer struct {
	applicationService *services.ApplicationService
	streamService      *services.StreamService
	templateService    *services.PathTemplateService
	registry           *services.LiveStreamRegistry
	viewerTracker      *services.ViewerTracker
	metrics            *metrics.Metrics
	server             *http.Server
}

// Verify interface implementation
var _ ports.HttpPort = (*HttpServer)(nil)

func NewHttpServer(applicationService *services.ApplicationService, streamService *services.StreamService, templateService *services.PathTemplateService, registry *services.LiveStreamRegistry, viewerTracker *services.ViewerTracker, m *metrics.Metrics) ports.HttpPort {
	return &HttpServer{
		applicationService: applicationService,
		streamService:      streamService,
		templateService:    templateService,
		registry:           registry,
		viewerTracker:      viewerTracker,
		metrics:            m,
	}
}

func (s *HttpServer) BuildRouter() *mux.Router {
	// Create Gorilla Mux router
	r := mux.NewRouter()

	// Expose Prometheus metrics endpoint
	r.Handle("/metrics", promhttp.Handler()).Methods("GET")

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
    handler := handlers.NewStreamHandler(&channel, s.streamService, s.applicationService, s.templateService, s.registry, s.viewerTracker, s.metrics)
		
		// Handle master playlist (HLS)
		channelRouter.Handle("/{resource:" + constants.MasterPlaylist + "}", handler).Methods("GET")
		// Handle DASH manifest
		channelRouter.Handle("/{resource:" + constants.DashManifest + "}", handler).Methods("GET")
		// Handle viewers.txt, views.txt, and thumbnail.png
		channelRouter.Handle("/{resource:" + constants.ViewersFile + "}", handler).Methods("GET")
		channelRouter.Handle("/{resource:" + constants.ViewsFile + "}", handler).Methods("GET")
		channelRouter.Handle("/{resource:" + constants.ThumbnailFile + "}", handler).Methods("GET")
		// Handle quality-specific paths (e.g., /low/playlist.m3u8, /default/playlist.m3u8)
		channelRouter.Handle("/{quality}/{resource:.*}", handler).Methods("GET")
		// Handle simple paths without quality prefix (backward compat)
		channelRouter.Handle("/{resource:.*}", handler).Methods("GET")
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
	port := s.applicationService.GetServer().HTTPPort
	addr := "localhost:" + strconv.Itoa(port)

	log.Printf("=== HTTP SERVER (port %d) ===", port)
	
	router := s.BuildRouter()

	// Wrap with in-flight gauge middleware
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.metrics.HttpRequestsInFlight.Inc()
		defer s.metrics.HttpRequestsInFlight.Dec()
		router.ServeHTTP(w, r)
	})

	s.server = &http.Server{
		Addr:    ":" + strconv.Itoa(port),
		Handler: handler,
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

