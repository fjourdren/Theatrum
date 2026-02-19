package handlers

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gorilla/mux"

	"Theatrum/adapters/driven/metrics"
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/services"
)

// streamTestStorage implements repositories.StoragePort for testing.
type streamTestStorage struct{}

func (m *streamTestStorage) ReadFile(path string) ([]byte, error)     { return nil, nil }
func (m *streamTestStorage) WriteFile(path string, data []byte) error { return nil }
func (m *streamTestStorage) DeleteFile(path string) error             { return nil }
func (m *streamTestStorage) ListFiles(pattern string) ([]string, error) {
	return nil, nil
}
func (m *streamTestStorage) GetFileSize(path string) (int64, error) { return 0, nil }
func (m *streamTestStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	return nil, nil, nil
}

// newStreamTestMetrics creates metrics without registering (avoids conflicts in tests).
func newStreamTestMetrics() *metrics.Metrics {
	return metrics.NewTestableMetrics()
}

func newStreamHandler(t *testing.T, stream *models.Stream) (*StreamHandler, string) {
	t.Helper()

	templateSvc := services.NewPathTemplateService()
	storage := &streamTestStorage{}
	app := &models.Application{PublicPath: "*"}
	server := &models.Server{}
	channels := map[string]models.Stream{}
	appSvc := services.NewApplicationService(app, server, &channels, storage, templateSvc)
	streamSvc := services.NewStreamService(templateSvc)
	registry := services.NewLiveStreamRegistry()
	vt := services.NewViewerTracker(storage)
	m := newStreamTestMetrics()

	// Create a temp dir for serving test files
	dir := t.TempDir()

	handler := NewStreamHandler(stream, streamSvc, appSvc, templateSvc, registry, vt, m)
	return handler, dir
}

func serveWithVars(handler http.Handler, req *http.Request, vars map[string]string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	req = mux.SetURLVars(req, vars)
	handler.ServeHTTP(w, req)
	return w
}

func TestStreamHandler_ServeHTTP_EmptyResource(t *testing.T) {
	stream := &models.Stream{
		Type: models.StreamTypeVideoEncoded,
		Path: "videos/test",
	}
	handler, _ := newStreamHandler(t, stream)

	req := httptest.NewRequest("GET", "/stream/", nil)
	w := serveWithVars(handler, req, map[string]string{"resource": ""})

	if w.Code != http.StatusNotFound {
		t.Errorf("expected 404, got %d", w.Code)
	}
}

func TestStreamHandler_ServeHTTP_CORSHeaders(t *testing.T) {
	stream := &models.Stream{
		Type: models.StreamTypeVideoEncoded,
		Path: "videos/test",
	}
	handler, _ := newStreamHandler(t, stream)

	req := httptest.NewRequest("GET", "/stream/master.m3u8", nil)
	w := serveWithVars(handler, req, map[string]string{"resource": "master.m3u8"})

	if w.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Errorf("expected CORS origin *, got %q", w.Header().Get("Access-Control-Allow-Origin"))
	}
	if w.Header().Get("Access-Control-Allow-Methods") != "GET, OPTIONS" {
		t.Errorf("expected CORS methods 'GET, OPTIONS', got %q", w.Header().Get("Access-Control-Allow-Methods"))
	}
}

func TestStreamHandler_ServeHTTP_OPTIONS(t *testing.T) {
	stream := &models.Stream{
		Type: models.StreamTypeVideoEncoded,
		Path: "videos/test",
	}
	handler, _ := newStreamHandler(t, stream)

	req := httptest.NewRequest("OPTIONS", "/stream/master.m3u8", nil)
	w := serveWithVars(handler, req, map[string]string{"resource": "master.m3u8"})

	if w.Code != http.StatusOK {
		t.Errorf("expected 200 for OPTIONS, got %d", w.Code)
	}
}

func TestStreamHandler_ServeHTTP_CacheHeaders(t *testing.T) {
	// Cache headers are set before file serving, so we can check them
	// even when the file doesn't exist (handler returns 404 after setting headers)
	tests := []struct {
		name          string
		streamType    models.StreamType
		resource      string
		expectedCache string
	}{
		{"live m3u8", models.StreamTypeLive, "playlist.m3u8", "no-cache, no-store, must-revalidate"},
		{"vod m3u8", models.StreamTypeVideoEncoded, "playlist.m3u8", "public, max-age=600"},
		{"live ts", models.StreamTypeLive, "segment_000.ts", "public, max-age=10"},
		{"vod ts", models.StreamTypeVideoEncoded, "segment_000.ts", "public, max-age=86400"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			stream := &models.Stream{
				Type: tt.streamType,
				Path: "test",
			}
			handler, _ := newStreamHandler(t, stream)

			req := httptest.NewRequest("GET", "/stream/"+tt.resource, nil)
			w := serveWithVars(handler, req, map[string]string{"resource": tt.resource})

			cacheControl := w.Header().Get("Cache-Control")
			if cacheControl != tt.expectedCache {
				t.Errorf("Cache-Control = %q, want %q", cacheControl, tt.expectedCache)
			}
		})
	}
}

func TestStreamHandler_ServeHTTP_ViewersEndpoint(t *testing.T) {
	t.Run("disabled returns 404", func(t *testing.T) {
		stream := &models.Stream{
			Type:    models.StreamTypeLive,
			Path:    "test",
			Viewers: models.Viewers{Enabled: false},
		}
		handler, _ := newStreamHandler(t, stream)

		req := httptest.NewRequest("GET", "/stream/"+constants.ViewersFile, nil)
		w := serveWithVars(handler, req, map[string]string{"resource": constants.ViewersFile})

		if w.Code != http.StatusNotFound {
			t.Errorf("expected 404 when disabled, got %d", w.Code)
		}
	})
}

func TestStreamHandler_ServeHTTP_ViewsEndpoint(t *testing.T) {
	t.Run("disabled returns 404", func(t *testing.T) {
		stream := &models.Stream{
			Type:  models.StreamTypeLive,
			Path:  "test",
			Views: models.Views{Enabled: false},
		}
		handler, _ := newStreamHandler(t, stream)

		req := httptest.NewRequest("GET", "/stream/"+constants.ViewsFile, nil)
		w := serveWithVars(handler, req, map[string]string{"resource": constants.ViewsFile})

		if w.Code != http.StatusNotFound {
			t.Errorf("expected 404 when disabled, got %d", w.Code)
		}
	})
}
