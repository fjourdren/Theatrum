//go:build e2e

package e2e

import (
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"Theatrum/constants"
)

func TestMain(m *testing.M) {
	// Check FFmpeg is available
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		log.Println("FFmpeg not found in PATH, skipping E2E tests")
		os.Exit(0)
	}

	// Create a temp directory for all E2E test data
	tmpDir, err := os.MkdirTemp("", "theatrum-e2e-*")
	if err != nil {
		log.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	// Override constants to use temp dirs
	constants.VideoDir = filepath.Join(tmpDir, "data")
	constants.FrontendDir = filepath.Join(tmpDir, "frontend")

	// Create required directories
	if err := os.MkdirAll(constants.VideoDir, 0755); err != nil {
		log.Fatalf("Failed to create video dir: %v", err)
	}
	if err := os.MkdirAll(constants.FrontendDir, 0755); err != nil {
		log.Fatalf("Failed to create frontend dir: %v", err)
	}

	// Create placeholder frontend index.html
	indexPath := filepath.Join(constants.FrontendDir, "index.html")
	if err := os.WriteFile(indexPath, []byte("<html><body>test</body></html>"), 0644); err != nil {
		log.Fatalf("Failed to create index.html: %v", err)
	}

	// Run tests
	code := m.Run()
	os.Exit(code)
}
