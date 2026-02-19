package handlers

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"Theatrum/domain/models"
	"Theatrum/domain/services"
)

// handlerMockStorage implements repositories.StoragePort for testing.
type handlerMockStorage struct {
	searchFilesFn func(pattern string, extensions []string) ([]string, []map[string]string, error)
}

func (m *handlerMockStorage) ReadFile(path string) ([]byte, error)     { return nil, nil }
func (m *handlerMockStorage) WriteFile(path string, data []byte) error { return nil }
func (m *handlerMockStorage) DeleteFile(path string) error             { return nil }
func (m *handlerMockStorage) ListFiles(pattern string) ([]string, error) {
	return nil, nil
}
func (m *handlerMockStorage) GetFileSize(path string) (int64, error) { return 0, nil }
func (m *handlerMockStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	if m.searchFilesFn != nil {
		return m.searchFilesFn(pattern, extensions)
	}
	return nil, nil, nil
}

func TestAllStreamsPlaylistHandler_ServeHTTP(t *testing.T) {
	t.Run("builds playlist with correct content-type", func(t *testing.T) {
		app := &models.Application{
			PublicPath: "http://localhost:8080",
			AllStreamsPlaylist: models.AllStreamsPlaylist{
				Enabled: true,
				Path:    "/all.m3u8",
			},
		}
		channels := map[string]models.Stream{
			"/vod/{name}": {
				Type: models.StreamTypeVideoEncoded,
				Path: "videos/{name}",
			},
		}
		storage := &handlerMockStorage{
			searchFilesFn: func(pattern string, extensions []string) ([]string, []map[string]string, error) {
				return []string{"videos/test/master.m3u8"}, []map[string]string{{"name": "test"}}, nil
			},
		}
		templateSvc := services.NewPathTemplateService()
		appSvc := services.NewApplicationService(app, &models.Server{}, &channels, storage, templateSvc)
		streamSvc := services.NewStreamService(templateSvc)

		handler := NewAllStreamsPlaylistHandler(appSvc, streamSvc)

		req := httptest.NewRequest("GET", "/all.m3u8", nil)
		w := httptest.NewRecorder()

		handler.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Errorf("expected status 200, got %d", w.Code)
		}
		if ct := w.Header().Get("Content-Type"); ct != "application/x-mpegURL" {
			t.Errorf("expected Content-Type application/x-mpegURL, got %q", ct)
		}
		body := w.Body.String()
		if !strings.Contains(body, "#EXTM3U") {
			t.Error("expected M3U8 header in response body")
		}
	})

	t.Run("error handling when disabled", func(t *testing.T) {
		app := &models.Application{
			AllStreamsPlaylist: models.AllStreamsPlaylist{Enabled: false},
		}
		channels := map[string]models.Stream{}
		storage := &handlerMockStorage{}
		templateSvc := services.NewPathTemplateService()
		appSvc := services.NewApplicationService(app, &models.Server{}, &channels, storage, templateSvc)
		streamSvc := services.NewStreamService(templateSvc)

		handler := NewAllStreamsPlaylistHandler(appSvc, streamSvc)

		req := httptest.NewRequest("GET", "/all.m3u8", nil)
		w := httptest.NewRecorder()

		handler.ServeHTTP(w, req)

		if w.Code != http.StatusInternalServerError {
			t.Errorf("expected status 500, got %d", w.Code)
		}
	})

	t.Run("error handling when storage fails", func(t *testing.T) {
		app := &models.Application{
			PublicPath: "http://localhost:8080",
			AllStreamsPlaylist: models.AllStreamsPlaylist{
				Enabled: true,
				Path:    "/all.m3u8",
			},
		}
		channels := map[string]models.Stream{
			"/vod/{name}": {
				Type: models.StreamTypeVideoEncoded,
				Path: "videos/{name}",
			},
		}
		storage := &handlerMockStorage{
			searchFilesFn: func(pattern string, extensions []string) ([]string, []map[string]string, error) {
				return nil, nil, fmt.Errorf("storage error")
			},
		}
		templateSvc := services.NewPathTemplateService()
		appSvc := services.NewApplicationService(app, &models.Server{}, &channels, storage, templateSvc)
		streamSvc := services.NewStreamService(templateSvc)

		handler := NewAllStreamsPlaylistHandler(appSvc, streamSvc)

		req := httptest.NewRequest("GET", "/all.m3u8", nil)
		w := httptest.NewRecorder()

		handler.ServeHTTP(w, req)

		// Should still return 200 with partial playlist (errors are logged, not fatal)
		if w.Code != http.StatusOK {
			t.Errorf("expected status 200, got %d", w.Code)
		}
	})
}
