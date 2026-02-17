package jobs

import (
	"context"
	"log"
	"sync"
	"time"

	"Theatrum/adapters/driven/metrics"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
	"Theatrum/domain/services"
)

// EncodeJob represents a video encoding job
type EncodeJob struct {
	InputStoragePath  string
	OutputStoragePath string
	Channel          models.Stream
}

// EncodeJobQueue manages the queue of encoding jobs
type EncodeJobQueue struct {
	jobs            chan EncodeJob
	encodeService   *services.EncodeService
	storage         repositories.StoragePort
	metrics         *metrics.Metrics
	wg              sync.WaitGroup
	ctx             context.Context
	cancel          context.CancelFunc
}

// NewEncodeJobQueue creates a new encode job queue
func NewEncodeJobQueue(encodeService *services.EncodeService, storage repositories.StoragePort, m *metrics.Metrics) *EncodeJobQueue {
	ctx, cancel := context.WithCancel(context.Background())
	return &EncodeJobQueue{
		jobs:            make(chan EncodeJob, 100), // Buffer size of 100 jobs
		encodeService:   encodeService,
		storage:         storage,
		metrics:         m,
		ctx:             ctx,
		cancel:          cancel,
	}
}

// Start begins processing the job queue
func (q *EncodeJobQueue) Start() {
	q.wg.Add(1)
	go q.worker()
}

// Stop gracefully stops the worker
func (q *EncodeJobQueue) Stop() {
	q.cancel()
	close(q.jobs)
	q.wg.Wait()
}

// Enqueue adds a new encoding job to the queue
func (q *EncodeJobQueue) Enqueue(job EncodeJob) error {
	select {
	case <-q.ctx.Done():
		return context.Canceled
	case q.jobs <- job:
		q.metrics.EncodeQueueDepth.Set(float64(len(q.jobs)))
		return nil
	}
}

// worker processes jobs from the queue
func (q *EncodeJobQueue) worker() {
	defer q.wg.Done()
	log.Printf("Encode worker started")

	for {
		select {
		case <-q.ctx.Done():
			log.Printf("Encode worker stopping")
			return
		case job, ok := <-q.jobs:
			if !ok {
				return
			}
			q.processJob(job)
		}
	}
}

// processJob handles a single encoding job because encoder already manage multi-threading
func (q *EncodeJobQueue) processJob(job EncodeJob) {
	q.metrics.EncodeQueueDepth.Set(float64(len(q.jobs)))
	startTime := time.Now()
	log.Printf("Starting encode job: %s -> %s", job.InputStoragePath, job.OutputStoragePath)

	err := q.encodeService.EncodeStream(
		job.InputStoragePath,
		job.OutputStoragePath,
		job.Channel,
	)

	duration := time.Since(startTime)
	q.metrics.EncodeJobDuration.Observe(duration.Seconds())

	if err != nil {
		log.Printf("Error processing encode job %s after %v: %v",
			job.InputStoragePath,
			duration.Round(time.Second),
			err)
		q.metrics.EncodeJobsTotal.WithLabelValues("failure").Inc()
		return
	}

	q.metrics.EncodeJobsTotal.WithLabelValues("success").Inc()
	log.Printf("Successfully encoded video: %s (took %v)",
		job.InputStoragePath,
		duration.Round(time.Second))

	// Delete source file if enabled for video_unencoded streams
	if job.Channel.Type == models.StreamTypeVideoUnEncoded && job.Channel.DeleteAfterEncoding {
		log.Printf("Deleting source file after successful encoding: %s", job.InputStoragePath)
		if err := q.storage.DeleteFile(job.InputStoragePath); err != nil {
			log.Printf("Error deleting source file %s: %v", job.InputStoragePath, err)
		} else {
			log.Printf("Successfully deleted source file: %s", job.InputStoragePath)
		}
	}
}
