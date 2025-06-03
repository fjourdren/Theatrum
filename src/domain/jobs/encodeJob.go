package jobs

import (
	"context"
	"log"
	"sync"
	"time"

	"Theatrum/domain/models"
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
	encodeService *services.EncodeService
	wg              sync.WaitGroup
	ctx             context.Context
	cancel          context.CancelFunc
}

// NewEncodeJobQueue creates a new encode job queue
func NewEncodeJobQueue(encodeService *services.EncodeService) *EncodeJobQueue {
	ctx, cancel := context.WithCancel(context.Background())
	return &EncodeJobQueue{
		jobs:            make(chan EncodeJob, 100), // Buffer size of 100 jobs
		encodeService: encodeService,
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
	startTime := time.Now()
	log.Printf("Starting encode job: %s -> %s", job.InputStoragePath, job.OutputStoragePath)

	err := q.encodeService.EncodeStream(
		job.InputStoragePath,
		job.OutputStoragePath,
		job.Channel,
	)

	duration := time.Since(startTime)
	if err != nil {
		log.Printf("Error processing encode job %s after %v: %v", 
			job.InputStoragePath, 
			duration.Round(time.Second), 
			err)
		return
	}

	log.Printf("Successfully encoded video: %s (took %v)", 
		job.InputStoragePath, 
		duration.Round(time.Second))
}
