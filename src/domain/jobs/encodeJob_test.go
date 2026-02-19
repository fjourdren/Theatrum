package jobs

import (
	"fmt"
	"testing"

	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
	"Theatrum/domain/services"
)

// jobMockEncoder implements repositories.EncoderPort for testing.
type jobMockEncoder struct {
	err error
}

func (m *jobMockEncoder) EncodeVideo(inputPath string, outputPath string, qualities map[string]models.Quality, distribution models.Distribution) error {
	return m.err
}

// jobMockStorage implements repositories.StoragePort for testing.
type jobMockStorage struct {
	deletedFiles []string
	deleteErr    error
}

func (m *jobMockStorage) ReadFile(path string) ([]byte, error)       { return nil, nil }
func (m *jobMockStorage) WriteFile(path string, data []byte) error   { return nil }
func (m *jobMockStorage) DeleteFile(path string) error {
	m.deletedFiles = append(m.deletedFiles, path)
	return m.deleteErr
}
func (m *jobMockStorage) ListFiles(pattern string) ([]string, error) { return nil, nil }
func (m *jobMockStorage) GetFileSize(path string) (int64, error)     { return 0, nil }
func (m *jobMockStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	return nil, nil, nil
}

// jobMockMetrics implements repositories.EncodeMetricsPort for testing.
type jobMockMetrics struct {
	lastDepth    float64
	lastDuration float64
	jobStatuses  []string
}

func (m *jobMockMetrics) SetEncodeQueueDepth(depth float64)       { m.lastDepth = depth }
func (m *jobMockMetrics) ObserveEncodeJobDuration(seconds float64) { m.lastDuration = seconds }
func (m *jobMockMetrics) IncEncodeJobsTotal(status string)         { m.jobStatuses = append(m.jobStatuses, status) }

// Verify interface compliance
var _ repositories.EncodeMetricsPort = (*jobMockMetrics)(nil)

func TestEncodeJobQueue_Enqueue(t *testing.T) {
	enc := &jobMockEncoder{}
	storage := &jobMockStorage{}
	metrics := &jobMockMetrics{}
	svc := services.NewEncodeService(enc)
	q := NewEncodeJobQueue(svc, storage, metrics)

	t.Run("success", func(t *testing.T) {
		job := EncodeJob{
			InputStoragePath:  "input.mp4",
			OutputStoragePath: "output/",
			Channel: models.Stream{
				Qualities: map[string]models.Quality{"low": {Width: 640}},
			},
		}
		err := q.Enqueue(job)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		// Drain the job
		<-q.jobs
	})

	t.Run("after cancel returns error", func(t *testing.T) {
		enc := &jobMockEncoder{}
		storage := &jobMockStorage{}
		metrics := &jobMockMetrics{}
		svc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(svc, storage, metrics)

		// Fill the buffer to force select to choose between ctx.Done and channel write
		for i := 0; i < 100; i++ {
			q.jobs <- EncodeJob{}
		}

		// Now cancel - the next Enqueue can't write to the full channel,
		// so it will select ctx.Done
		q.cancel()

		job := EncodeJob{InputStoragePath: "input.mp4"}
		err := q.Enqueue(job)
		if err == nil {
			t.Error("expected error after cancel with full buffer")
		}
	})
}

func TestEncodeJobQueue_StartStop(t *testing.T) {
	enc := &jobMockEncoder{}
	storage := &jobMockStorage{}
	metrics := &jobMockMetrics{}
	svc := services.NewEncodeService(enc)
	q := NewEncodeJobQueue(svc, storage, metrics)

	q.Start()
	q.Stop()
	// If we reach here without hanging, the test passes
}

func TestEncodeJobQueue_ProcessJob(t *testing.T) {
	t.Run("success with metrics", func(t *testing.T) {
		enc := &jobMockEncoder{}
		storage := &jobMockStorage{}
		metrics := &jobMockMetrics{}
		svc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(svc, storage, metrics)

		job := EncodeJob{
			InputStoragePath:  "input.mp4",
			OutputStoragePath: "output/",
			Channel: models.Stream{
				Qualities: map[string]models.Quality{"low": {Width: 640}},
			},
		}
		q.processJob(job)

		if len(metrics.jobStatuses) != 1 || metrics.jobStatuses[0] != "success" {
			t.Errorf("expected success status, got %v", metrics.jobStatuses)
		}
		if metrics.lastDuration <= 0 {
			t.Error("expected positive duration")
		}
	})

	t.Run("failure with metrics", func(t *testing.T) {
		enc := &jobMockEncoder{err: fmt.Errorf("encode failed")}
		storage := &jobMockStorage{}
		metrics := &jobMockMetrics{}
		svc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(svc, storage, metrics)

		job := EncodeJob{
			InputStoragePath:  "input.mp4",
			OutputStoragePath: "output/",
			Channel: models.Stream{
				Qualities: map[string]models.Quality{"low": {Width: 640}},
			},
		}
		q.processJob(job)

		if len(metrics.jobStatuses) != 1 || metrics.jobStatuses[0] != "failure" {
			t.Errorf("expected failure status, got %v", metrics.jobStatuses)
		}
	})

	t.Run("delete after encoding for video_unencoded", func(t *testing.T) {
		enc := &jobMockEncoder{}
		storage := &jobMockStorage{}
		metrics := &jobMockMetrics{}
		svc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(svc, storage, metrics)

		job := EncodeJob{
			InputStoragePath:  "input.mp4",
			OutputStoragePath: "output/",
			Channel: models.Stream{
				Type:                models.StreamTypeVideoUnEncoded,
				DeleteAfterEncoding: true,
				Qualities:           map[string]models.Quality{"low": {Width: 640}},
			},
		}
		q.processJob(job)

		if len(storage.deletedFiles) != 1 || storage.deletedFiles[0] != "input.mp4" {
			t.Errorf("expected input.mp4 to be deleted, got %v", storage.deletedFiles)
		}
	})

	t.Run("no delete when not video_unencoded", func(t *testing.T) {
		enc := &jobMockEncoder{}
		storage := &jobMockStorage{}
		metrics := &jobMockMetrics{}
		svc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(svc, storage, metrics)

		job := EncodeJob{
			InputStoragePath:  "input.mp4",
			OutputStoragePath: "output/",
			Channel: models.Stream{
				Type:                models.StreamTypeVideoEncoded,
				DeleteAfterEncoding: true,
				Qualities:           map[string]models.Quality{"low": {Width: 640}},
			},
		}
		q.processJob(job)

		if len(storage.deletedFiles) != 0 {
			t.Errorf("expected no files deleted, got %v", storage.deletedFiles)
		}
	})

	t.Run("no delete when flag disabled", func(t *testing.T) {
		enc := &jobMockEncoder{}
		storage := &jobMockStorage{}
		metrics := &jobMockMetrics{}
		svc := services.NewEncodeService(enc)
		q := NewEncodeJobQueue(svc, storage, metrics)

		job := EncodeJob{
			InputStoragePath:  "input.mp4",
			OutputStoragePath: "output/",
			Channel: models.Stream{
				Type:                models.StreamTypeVideoUnEncoded,
				DeleteAfterEncoding: false,
				Qualities:           map[string]models.Quality{"low": {Width: 640}},
			},
		}
		q.processJob(job)

		if len(storage.deletedFiles) != 0 {
			t.Errorf("expected no files deleted, got %v", storage.deletedFiles)
		}
	})
}
