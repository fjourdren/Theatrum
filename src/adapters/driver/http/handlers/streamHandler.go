package handlers

import (
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"

	"github.com/gorilla/mux"

	"Theatrum/domain/models"
	"Theatrum/domain/services"
)

type StreamHandler struct {
	stream             *models.Stream
	streamService      *services.StreamService
	applicationService *services.ApplicationService
	templateService    *services.PathTemplateService
	registry           *services.LiveStreamRegistry
}

func NewStreamHandler(stream *models.Stream, streamService *services.StreamService, applicationService *services.ApplicationService, templateService *services.PathTemplateService, registry *services.LiveStreamRegistry) *StreamHandler {
	return &StreamHandler{
		stream:             stream,
		streamService:      streamService,
		applicationService: applicationService,
		templateService:    templateService,
		registry:           registry,
	}
}

func (h *StreamHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	
	resource := vars["resource"]

	if(resource == "" || resource == "/") {
		http.Error(w, "File not found", http.StatusNotFound)
		return
	}

	// Set CORS headers for HLS streaming using public_path from config
	publicPath := h.applicationService.GetApplication().PublicPath
	w.Header().Set("Access-Control-Allow-Origin", publicPath)
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Origin, Content-Type")

	// Handle OPTIONS request for CORS
	if r.Method == "OPTIONS" {
		w.WriteHeader(http.StatusOK)
		return
	}

	// Set appropriate headers based on file type
	ext := filepath.Ext(resource)
	if mimeType := http.DetectContentType([]byte(ext)); mimeType != "" {
		w.Header().Set("Content-Type", mimeType)
	}

	// Set cache control headers based on stream type and file type
	isLive := h.stream.Type == models.StreamTypeLive
	switch ext {
	case ".m3u8":
		if isLive {
			// Live playlists update every segment, must not be cached
			w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		} else {
			// VOD playlists are stable
			w.Header().Set("Cache-Control", "public, max-age=600")
		}
	case ".ts":
		if isLive {
			// Live segments are short-lived
			w.Header().Set("Cache-Control", "public, max-age=10")
		} else {
			// VOD segments don't change
			w.Header().Set("Cache-Control", "public, max-age=86400")
		}
	default:
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")
	}

	// For live streams, look up pre-resolved builtin vars from the registry
	if h.stream.Type == models.StreamTypeLive {
		// Compute stream key (same formula as RTMP side: resolve user vars only)
		streamKey, _ := h.templateService.ReplacePlaceholders(h.stream.Path, vars)

		if builtinVars, ok := h.registry.GetBuiltinVars(streamKey); ok {
			for k, v := range builtinVars {
				vars[k] = v
			}
		}
		// If not found → stream offline → builtins unresolved → file won't exist → 404
	}

	// Get the storage path
	storagePath, err := h.streamService.GetStreamStoragePath(h.stream, vars)
	if err != nil {
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}
	resourceStoragePath := path.Join(storagePath, resource)

	// Check if file exists
	if _, err := os.Stat(resourceStoragePath); os.IsNotExist(err) {
		log.Printf("File not found: %s", resourceStoragePath)
		http.Error(w, "File not found", http.StatusNotFound)
		return
	}

	// Serve the file
	http.ServeFile(w, r, resourceStoragePath)
}