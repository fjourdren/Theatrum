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
	stream *models.Stream
	streamService *services.StreamService
	applicationService *services.ApplicationService
}

func NewStreamHandler(stream *models.Stream, streamService *services.StreamService, applicationService *services.ApplicationService) *StreamHandler {
	return &StreamHandler{
		stream: stream,
		streamService: streamService,
		applicationService: applicationService,
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

	// LATER : put in stream config the cache control headers
	// Set cache control headers based on file type
	switch ext {
	case ".m3u8": // Master playlist and sub-playlists
		// Cache playlists for a shorter time since they are updated frequently
		w.Header().Set("Cache-Control", "public, max-age=600") // 10 minutes cache
	case ".ts": // Video segments
		// Cache video segments for a longer time since they don't change
		w.Header().Set("Cache-Control", "public, max-age=86400") // 24 hours cache
	default:
		// For other files, use no cache
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")
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