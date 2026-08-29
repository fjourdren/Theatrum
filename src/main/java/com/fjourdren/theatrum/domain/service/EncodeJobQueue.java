package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.QueueEncodeUseCase;
import com.fjourdren.theatrum.application.port.out.EncodeMetricsPort;
import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.constant.MetricConstants;
import com.fjourdren.theatrum.domain.model.EncodeJob;
import com.fjourdren.theatrum.domain.model.StreamType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Queue of encoding jobs, drained by a single worker (the encoder already does its own threading). */
@Component
@Slf4j
@RequiredArgsConstructor
public class EncodeJobQueue implements QueueEncodeUseCase {

    private static final int CAPACITY = 100;
    /** Poll slice, so both the worker and a blocked producer notice {@link #stop()} promptly. */
    private static final long POLL_MS = 50;

    private final BlockingQueue<EncodeJob> jobs = new ArrayBlockingQueue<>(CAPACITY);
    private final EncodeService encodeService;
    private final StoragePort storage;
    private final EncodeMetricsPort metrics;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private Thread worker;

    /** Starts processing the job queue. */
    public synchronized void start() {
        if (worker == null) {
            worker = Thread.ofVirtual().name("encode-worker").start(this::work);
        }
    }

    /** Stops the worker and refuses further jobs. Idempotent. */
    public synchronized void stop() {
        shutdown.set(true);
        Thread current = worker;
        worker = null;
        if (current != null) {
            try {
                current.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Adds a job to the queue, waiting for room if it is full.
     *
     * @return false when the queue is shut down (Go returned {@code context.Canceled} here)
     */
    @Override
    public boolean enqueue(EncodeJob job) {
        try {
            while (!shutdown.get()) {
                if (jobs.offer(job, POLL_MS, TimeUnit.MILLISECONDS)) {
                    metrics.setEncodeQueueDepth(jobs.size());
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private void work() {
        log.info("Encode worker started");
        while (!shutdown.get()) {
            try {
                EncodeJob job = jobs.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (job != null) {
                    processJob(job);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Encode worker stopping");
    }

    void processJob(EncodeJob job) {
        metrics.setEncodeQueueDepth(jobs.size());
        long startTime = System.nanoTime();
        log.info("Starting encode job: {} -> {}", job.inputStoragePath(), job.outputStoragePath());

        Exception failure = null;
        try {
            encodeService.encodeStream(job.inputStoragePath(), job.outputStoragePath(), job.channel());
        } catch (Exception e) {
            failure = e;
        }

        double seconds = (System.nanoTime() - startTime) / 1e9;
        metrics.observeEncodeJobDuration(seconds);

        if (failure != null) {
            log.error("Error processing encode job {} after {}s: {}",
                    job.inputStoragePath(), Math.round(seconds), failure.getMessage());
            metrics.incEncodeJobsTotal(MetricConstants.RESULT_FAILURE);
            return;
        }

        metrics.incEncodeJobsTotal(MetricConstants.RESULT_SUCCESS);
        log.info("Successfully encoded video: {} (took {}s)", job.inputStoragePath(), Math.round(seconds));

        // Delete source file if enabled for video_unencoded streams
        if (job.channel().type() == StreamType.VIDEO_UNENCODED && job.channel().deleteAfterEncoding()) {
            log.info("Deleting source file after successful encoding: {}", job.inputStoragePath());
            try {
                storage.deleteFile(job.inputStoragePath());
                log.info("Successfully deleted source file: {}", job.inputStoragePath());
            } catch (Exception e) {
                log.error("Error deleting source file {}: {}", job.inputStoragePath(), e.getMessage());
            }
        }
    }
}
