package handlers

import (
	"net/http"
	"path/filepath"
)

/**
Serve the static frontend files
*/
func FrontendHandler(frontendDir string) http.Handler {
	fs := http.FileServer(http.Dir(frontendDir))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Get file extension
		ext := filepath.Ext(r.URL.Path)

		// Set cache control headers based on file type
		switch ext {
		case ".html":
			// Cache HTML files for a short time since they might be updated
			w.Header().Set("Cache-Control", "public, max-age=600") // 10 minutes cache
		case ".js", ".css":
			// Cache JS and CSS files for longer since they change less frequently
			w.Header().Set("Cache-Control", "public, max-age=86400") // 24 hours cache
		case ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico":
			// Cache images for a very long time since they rarely change
			w.Header().Set("Cache-Control", "public, max-age=31536000") // 1 year cache
		default:
			// For other files, use no cache
			w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
			w.Header().Set("Pragma", "no-cache")
			w.Header().Set("Expires", "0")
		}

		fs.ServeHTTP(w, r)
	})
}
