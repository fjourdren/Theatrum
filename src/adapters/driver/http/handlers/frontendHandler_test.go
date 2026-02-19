package handlers

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestFrontendHandler_CacheHeaders(t *testing.T) {
	// Create a temp directory with test files
	dir := t.TempDir()
	files := map[string]string{
		"index.html": "<html></html>",
		"app.js":     "console.log('hello')",
		"style.css":  "body {}",
		"logo.png":   "PNG",
		"favicon.ico": "ICO",
		"data.json":  "{}",
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0644); err != nil {
			t.Fatalf("failed to create test file %s: %v", name, err)
		}
	}

	handler := FrontendHandler(dir)

	tests := []struct {
		name           string
		path           string
		expectedCache  string
	}{
		{"html", "/index.html", "public, max-age=600"},
		{"js", "/app.js", "public, max-age=86400"},
		{"css", "/style.css", "public, max-age=86400"},
		{"png", "/logo.png", "public, max-age=31536000"},
		{"ico", "/favicon.ico", "public, max-age=31536000"},
		{"unknown ext", "/data.json", "no-cache, no-store, must-revalidate"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", tt.path, nil)
			w := httptest.NewRecorder()

			handler.ServeHTTP(w, req)

			cacheControl := w.Header().Get("Cache-Control")
			if cacheControl != tt.expectedCache {
				t.Errorf("Cache-Control for %s = %q, want %q", tt.path, cacheControl, tt.expectedCache)
			}
		})
	}
}

func TestFrontendHandler_NoCacheExtraHeaders(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "data.json"), []byte("{}"), 0644); err != nil {
		t.Fatal(err)
	}

	handler := FrontendHandler(dir)
	req := httptest.NewRequest("GET", "/data.json", nil)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Header().Get("Pragma") != "no-cache" {
		t.Errorf("expected Pragma: no-cache, got %q", w.Header().Get("Pragma"))
	}
	if w.Header().Get("Expires") != "0" {
		t.Errorf("expected Expires: 0, got %q", w.Header().Get("Expires"))
	}
}

func TestFrontendHandler_ServesFiles(t *testing.T) {
	dir := t.TempDir()
	content := "console.log('hello')"
	if err := os.WriteFile(filepath.Join(dir, "app.js"), []byte(content), 0644); err != nil {
		t.Fatal(err)
	}

	handler := FrontendHandler(dir)
	req := httptest.NewRequest("GET", "/app.js", nil)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected status 200, got %d", w.Code)
	}
}
