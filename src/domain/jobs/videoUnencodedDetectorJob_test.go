package jobs

import (
	"fmt"
	"testing"

	"Theatrum/domain/models"
	"Theatrum/domain/services"
)

// detectorMockStorage implements repositories.StoragePort for testing.
type detectorMockStorage struct {
	searchFilesFn func(pattern string, extensions []string) ([]string, []map[string]string, error)
}

func (m *detectorMockStorage) ReadFile(path string) ([]byte, error)     { return nil, nil }
func (m *detectorMockStorage) WriteFile(path string, data []byte) error { return nil }
func (m *detectorMockStorage) DeleteFile(path string) error             { return nil }
func (m *detectorMockStorage) ListFiles(pattern string) ([]string, error) {
	return nil, nil
}
func (m *detectorMockStorage) GetFileSize(path string) (int64, error) { return 0, nil }
func (m *detectorMockStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	if m.searchFilesFn != nil {
		return m.searchFilesFn(pattern, extensions)
	}
	return nil, nil, nil
}

func TestVideoUnencodedDetector_DetectAndQueueVideos(t *testing.T) {
	t.Run("finds unencoded videos", func(t *testing.T) {
		storage := &detectorMockStorage{
			searchFilesFn: func(pattern string, extensions []string) ([]string, []map[string]string, error) {
				return []string{"data/uploads/video.mp4"}, []map[string]string{{"FILENAME": "video.mp4"}}, nil
			},
		}

		channels := map[string]models.Stream{
			"/videos/{filename}": {
				Type:           models.StreamTypeVideoUnEncoded,
				Path:           "encoded/{FILENAME}",
				VideoInputPath: "uploads/{FILENAME}",
				Qualities:      map[string]models.Quality{"low": {Width: 640}},
			},
		}

		app := &models.Application{}
		server := &models.Server{}
		templateSvc := services.NewPathTemplateService()
		appSvc := services.NewApplicationService(app, server, &channels, storage, templateSvc)
		enc := &jobMockEncoder{}
		metrics := &jobMockMetrics{}
		encSvc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(encSvc, storage, metrics)

		detector := NewVideoUnencodedDetector(appSvc, q, storage, templateSvc)
		err := detector.DetectAndQueueVideos()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		// Check that a job was enqueued
		select {
		case job := <-q.jobs:
			if job.InputStoragePath != "data/uploads/video.mp4" {
				t.Errorf("expected input path data/uploads/video.mp4, got %q", job.InputStoragePath)
			}
		default:
			t.Error("expected a job to be enqueued")
		}
	})

	t.Run("skips non-unencoded streams", func(t *testing.T) {
		storage := &detectorMockStorage{}

		channels := map[string]models.Stream{
			"/live/{username}": {
				Type: models.StreamTypeLive,
				Path: "live/{username}",
			},
		}

		app := &models.Application{}
		server := &models.Server{}
		templateSvc := services.NewPathTemplateService()
		appSvc := services.NewApplicationService(app, server, &channels, storage, templateSvc)
		enc := &jobMockEncoder{}
		metrics := &jobMockMetrics{}
		encSvc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(encSvc, storage, metrics)

		detector := NewVideoUnencodedDetector(appSvc, q, storage, templateSvc)
		err := detector.DetectAndQueueVideos()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		// No jobs should be enqueued
		select {
		case <-q.jobs:
			t.Error("expected no jobs to be enqueued")
		default:
			// expected
		}
	})

	t.Run("handles search errors gracefully", func(t *testing.T) {
		storage := &detectorMockStorage{
			searchFilesFn: func(pattern string, extensions []string) ([]string, []map[string]string, error) {
				return nil, nil, fmt.Errorf("disk error")
			},
		}

		channels := map[string]models.Stream{
			"/videos/{filename}": {
				Type:           models.StreamTypeVideoUnEncoded,
				Path:           "encoded/{FILENAME}",
				VideoInputPath: "uploads/{FILENAME}",
			},
		}

		app := &models.Application{}
		server := &models.Server{}
		templateSvc := services.NewPathTemplateService()
		appSvc := services.NewApplicationService(app, server, &channels, storage, templateSvc)
		enc := &jobMockEncoder{}
		metrics := &jobMockMetrics{}
		encSvc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(encSvc, storage, metrics)

		detector := NewVideoUnencodedDetector(appSvc, q, storage, templateSvc)
		err := detector.DetectAndQueueVideos()
		if err != nil {
			t.Fatalf("expected no error (errors logged), got: %v", err)
		}
	})
}
