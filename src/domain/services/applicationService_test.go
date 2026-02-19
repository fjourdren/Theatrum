package services

import (
	"fmt"
	"testing"

	"Theatrum/domain/models"
)

// appTestStorage implements repositories.StoragePort with configurable behavior.
type appTestStorage struct {
	searchFilesFn   func(pattern string, extensions []string) ([]string, []map[string]string, error)
	readFileData    []byte
	readFileErr     error
	writeFileErr    error
	deleteFileErr   error
}

func (m *appTestStorage) ReadFile(path string) ([]byte, error) {
	return m.readFileData, m.readFileErr
}
func (m *appTestStorage) WriteFile(path string, data []byte) error {
	return m.writeFileErr
}
func (m *appTestStorage) DeleteFile(path string) error {
	return m.deleteFileErr
}
func (m *appTestStorage) ListFiles(pattern string) ([]string, error) {
	return nil, nil
}
func (m *appTestStorage) GetFileSize(path string) (int64, error) {
	return 0, nil
}
func (m *appTestStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	if m.searchFilesFn != nil {
		return m.searchFilesFn(pattern, extensions)
	}
	return nil, nil, nil
}

func TestApplicationService_GetApplication(t *testing.T) {
	app := &models.Application{PublicPath: "http://localhost:8080"}
	server := &models.Server{HTTPPort: 8080}
	channels := map[string]models.Stream{}
	svc := NewApplicationService(app, server, &channels, &appTestStorage{}, NewPathTemplateService())

	got := svc.GetApplication()
	if got != app {
		t.Error("expected same application pointer")
	}
	if got.PublicPath != "http://localhost:8080" {
		t.Errorf("expected PublicPath http://localhost:8080, got %q", got.PublicPath)
	}
}

func TestApplicationService_GetServer(t *testing.T) {
	app := &models.Application{}
	server := &models.Server{HTTPPort: 8080, RTMPPort: 1935}
	channels := map[string]models.Stream{}
	svc := NewApplicationService(app, server, &channels, &appTestStorage{}, NewPathTemplateService())

	got := svc.GetServer()
	if got != server {
		t.Error("expected same server pointer")
	}
	if got.HTTPPort != 8080 {
		t.Errorf("expected HTTPPort 8080, got %d", got.HTTPPort)
	}
}

func TestApplicationService_GetChannels(t *testing.T) {
	app := &models.Application{}
	server := &models.Server{}
	channels := map[string]models.Stream{
		"/user/{username}": {Type: models.StreamTypeLive},
	}
	svc := NewApplicationService(app, server, &channels, &appTestStorage{}, NewPathTemplateService())

	got := svc.GetChannels()
	if len(*got) != 1 {
		t.Errorf("expected 1 channel, got %d", len(*got))
	}
}

func TestApplicationService_GetChannel(t *testing.T) {
	app := &models.Application{}
	server := &models.Server{}
	channels := map[string]models.Stream{
		"/user/{username}": {Type: models.StreamTypeLive, Path: "live/{username}"},
	}
	svc := NewApplicationService(app, server, &channels, &appTestStorage{}, NewPathTemplateService())

	t.Run("found", func(t *testing.T) {
		ch, err := svc.GetChannel("/user/{username}")
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if ch.Type != models.StreamTypeLive {
			t.Errorf("expected StreamTypeLive, got %s", ch.Type)
		}
	})

	t.Run("not found", func(t *testing.T) {
		_, err := svc.GetChannel("/nonexistent")
		if err == nil {
			t.Error("expected error for nonexistent channel")
		}
	})
}

func TestApplicationService_BuildAllStreamsPlaylist(t *testing.T) {
	t.Run("disabled returns error", func(t *testing.T) {
		app := &models.Application{AllStreamsPlaylist: models.AllStreamsPlaylist{Enabled: false}}
		channels := map[string]models.Stream{}
		svc := NewApplicationService(app, &models.Server{}, &channels, &appTestStorage{}, NewPathTemplateService())

		_, err := svc.BuildAllStreamsPlaylist()
		if err == nil {
			t.Error("expected error when disabled")
		}
	})

	t.Run("enabled builds M3U8", func(t *testing.T) {
		app := &models.Application{
			PublicPath: "http://localhost:8080",
			AllStreamsPlaylist: models.AllStreamsPlaylist{
				Enabled: true,
				Path:    "/all.m3u8",
			},
		}
		channels := map[string]models.Stream{
			"/user/{username}": {
				Type: models.StreamTypeVideoEncoded,
				Path: "videos/{username}",
			},
		}
		storage := &appTestStorage{
			searchFilesFn: func(pattern string, extensions []string) ([]string, []map[string]string, error) {
				return []string{"videos/alice/master.m3u8"}, []map[string]string{{"username": "alice"}}, nil
			},
		}
		svc := NewApplicationService(app, &models.Server{}, &channels, storage, NewPathTemplateService())

		result, err := svc.BuildAllStreamsPlaylist()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if result == "" {
			t.Error("expected non-empty playlist")
		}
		if !contains(result, "#EXTM3U") {
			t.Error("expected M3U8 header")
		}
		if !contains(result, "master.m3u8") {
			t.Error("expected master.m3u8 reference in playlist")
		}
	})

	t.Run("storage search error is handled gracefully", func(t *testing.T) {
		app := &models.Application{
			PublicPath: "http://localhost:8080",
			AllStreamsPlaylist: models.AllStreamsPlaylist{Enabled: true, Path: "/all.m3u8"},
		}
		channels := map[string]models.Stream{
			"/user/{username}": {Type: models.StreamTypeVideoEncoded, Path: "videos/{username}"},
		}
		storage := &appTestStorage{
			searchFilesFn: func(pattern string, extensions []string) ([]string, []map[string]string, error) {
				return nil, nil, fmt.Errorf("disk error")
			},
		}
		svc := NewApplicationService(app, &models.Server{}, &channels, storage, NewPathTemplateService())

		result, err := svc.BuildAllStreamsPlaylist()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		// Should still return valid M3U8 header even with no streams
		if !contains(result, "#EXTM3U") {
			t.Error("expected M3U8 header")
		}
	})
}

func contains(s, substr string) bool {
	return len(s) >= len(substr) && containsSubstring(s, substr)
}

func containsSubstring(s, sub string) bool {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
