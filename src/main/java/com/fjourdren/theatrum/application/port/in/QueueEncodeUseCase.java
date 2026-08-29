package com.fjourdren.theatrum.application.port.in;

import com.fjourdren.theatrum.domain.model.EncodeJob;

/** Submits videos for background encoding. */
public interface QueueEncodeUseCase {

    /** Queues {@code job}; returns {@code false} if the queue is full or shut down. */
    boolean enqueue(EncodeJob job);
}
