package handlers

import (
	"log"
	"net/http"

	"Theatrum/domain/services"
)

type AllStreamsPlaylistHandler struct {
	applicationService *services.ApplicationService
	streamService     *services.StreamService
}

func NewAllStreamsPlaylistHandler(applicationService *services.ApplicationService, streamService *services.StreamService) *AllStreamsPlaylistHandler {
	return &AllStreamsPlaylistHandler{
		applicationService: applicationService,
		streamService:     streamService,
	}
}

func (h *AllStreamsPlaylistHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	content, err := h.applicationService.BuildAllStreamsPlaylist()
	if err != nil {
		log.Printf("Error building all streams playlist: %v", err)
		http.Error(w, "Error building all streams playlist", http.StatusInternalServerError)
		return
	}

	// Set response headers
	w.Header().Set("Content-Type", "application/x-mpegURL")
	w.WriteHeader(http.StatusOK)

	// Write the response
	if _, err := w.Write([]byte(content)); err != nil {
		log.Printf("Error writing all streams playlist response: %v", err)
		http.Error(w, "Error encoding response", http.StatusInternalServerError)
		return
	}
} 