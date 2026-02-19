package handlers

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"time"

	"github.com/gorilla/mux"

	"Theatrum/constants"
	"Theatrum/adapters/driven/metrics"
	"Theatrum/domain/models"
	"Theatrum/domain/services"
)

type StreamHandler struct {
	stream             *models.Stream
	streamService      *services.StreamService
	applicationService *services.ApplicationService
	templateService    *services.PathTemplateService
	registry           *services.LiveStreamRegistry
	viewerTracker      *services.ViewerTracker
	metrics            *metrics.Metrics
}

func NewStreamHandler(stream *models.Stream, streamService *services.StreamService, applicationService *services.ApplicationService, templateService *services.PathTemplateService, registry *services.LiveStreamRegistry, viewerTracker *services.ViewerTracker, m *metrics.Metrics) *StreamHandler {
	return &StreamHandler{
		stream:             stream,
		streamService:      streamService,
		applicationService: applicationService,
		templateService:    templateService,
		registry:           registry,
		viewerTracker:      viewerTracker,
		metrics:            m,
	}
}

func (h *StreamHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
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

	// Set appropriate content type based on file extension
	ext := filepath.Ext(resource)
	switch ext {
	case ".m3u8":
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	case ".ts":
		w.Header().Set("Content-Type", "video/mp2t")
	case ".mpd":
		w.Header().Set("Content-Type", "application/dash+xml")
	case ".m4s":
		w.Header().Set("Content-Type", "video/iso.segment")
	default:
		if mimeType := http.DetectContentType([]byte(ext)); mimeType != "" {
			w.Header().Set("Content-Type", mimeType)
		}
	}

	// Set cache control headers based on stream type and file type
	isLive := h.stream.Type == models.StreamTypeLive
	switch ext {
	case ".m3u8", ".mpd":
		if isLive {
			// Live playlists/manifests update every segment, must not be cached
			w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		} else {
			// VOD playlists/manifests are stable
			w.Header().Set("Cache-Control", "public, max-age=600")
		}
	case ".ts", ".m4s":
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

	// Compute tracking key for viewer/view tracking
	var trackingKey string

	// For live streams, look up pre-resolved builtin vars from the registry
	if h.stream.Type == models.StreamTypeLive {
		// Compute stream key (same formula as RTMP side: resolve user vars only)
		streamKey, err := h.templateService.ReplacePlaceholders(h.stream.Path, vars)
		if err != nil {
			log.Printf("Error resolving stream key template: %v", err)
		}

		if builtinVars, ok := h.registry.GetBuiltinVars(streamKey); ok {
			for k, v := range builtinVars {
				vars[k] = v
			}
		}
		// If not found → stream offline → builtins unresolved → file won't exist → 404

		// Tracking key = fully resolved stream path
		trackingKey, err = h.templateService.ReplacePlaceholders(h.stream.Path, vars)
		if err != nil {
			log.Printf("Error resolving tracking key template: %v", err)
		}
	} else {
		// For non-live streams, tracking key = resolved stream path
		var err error
		trackingKey, err = h.templateService.ReplacePlaceholders(h.stream.Path, vars)
		if err != nil {
			log.Printf("Error resolving tracking key template: %v", err)
		}
	}

	// Handle viewers.txt request
	if resource == constants.ViewersFile {
		if !h.stream.Viewers.Enabled {
			http.Error(w, "File not found", http.StatusNotFound)
			return
		}
		count := h.viewerTracker.GetViewerCount(trackingKey)
		w.Header().Set("Content-Type", "text/plain")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		fmt.Fprintf(w, "%d", count)
		return
	}

	// Handle views.txt request
	if resource == constants.ViewsFile {
		if !h.stream.Views.Enabled {
			http.Error(w, "File not found", http.StatusNotFound)
			return
		}
		count := h.viewerTracker.GetViewCount(trackingKey)
		w.Header().Set("Content-Type", "text/plain")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		fmt.Fprintf(w, "%d", count)
		return
	}

	// Track .ts and .m4s segment requests for viewer/view counting
	if (ext == ".ts" || ext == ".m4s") && (h.stream.Viewers.Enabled || h.stream.Views.Enabled) {
		clientIP := services.GetClientIP(r)
		h.viewerTracker.TrackSegmentRequest(trackingKey, clientIP, h.stream.Viewers, h.stream.Views)
	}

	// Get the storage path
	storagePath, err := h.streamService.GetStreamStoragePath(h.stream, vars)
	if err != nil {
		log.Printf("Error getting stream storage path: %v", err)
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

	// Determine metric labels
	streamType := "vod"
	if h.stream.Type == models.StreamTypeLive {
		streamType = "live"
	}
	fileType := "other"
	switch ext {
	case ".m3u8", ".mpd":
		fileType = "playlist"
	case ".ts", ".m4s":
		fileType = "segment"
	}

	// Wrap writer to capture status code and bytes written
	rw := metrics.NewResponseWriter(w)

	// Serve the file
	http.ServeFile(rw, r, resourceStoragePath)

	// Record metrics
	duration := time.Since(start).Seconds()
	h.metrics.HttpRequestDuration.WithLabelValues(streamType, fileType).Observe(duration)
	h.metrics.HttpRequestsTotal.WithLabelValues(strconv.Itoa(rw.StatusCode), streamType, fileType).Inc()
	h.metrics.HttpResponseBytes.WithLabelValues(streamType, fileType).Add(float64(rw.BytesWritten))
}
