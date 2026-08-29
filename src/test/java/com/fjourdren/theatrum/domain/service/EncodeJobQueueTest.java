package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.out.EncodeMetricsPort;
import com.fjourdren.theatrum.application.port.out.EncoderPort;
import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.EncodeJob;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EncodeJobQueueTest {

    private static final Path INPUT = Path.of("input.mp4");
    private static final Path OUTPUT = Path.of("output/");

    @Mock
    private EncoderPort encoder;
    @Mock
    private StoragePort storage;
    @Mock
    private EncodeMetricsPort metrics;

    private EncodeJobQueue queue;

    private EncodeJobQueue queue() {
        queue = new EncodeJobQueue(new EncodeService(encoder), storage, metrics);
        return queue;
    }

    @AfterEach
    void stopQueue() {
        if (queue != null) {
            queue.stop();
        }
    }

    private static Stream channel(StreamType type, boolean deleteAfterEncoding) {
        return Stream.builder()
                .type(type)
                .deleteAfterEncoding(deleteAfterEncoding)
                .quality("low", new Quality(640, 360, 30, "1000k", "libx264", new Audio("128k", "aac")))
                .build();
    }

    private static EncodeJob job(StreamType type, boolean deleteAfterEncoding) {
        return new EncodeJob(INPUT, OUTPUT, channel(type, deleteAfterEncoding));
    }

    @Nested
    class Enqueue {

        @Test
        void succeedsAndReportsQueueDepth() {
            assertThat(queue().enqueue(job(null, false))).isTrue();

            verify(metrics).setEncodeQueueDepth(1d);
        }

        @Test
        void afterStopIsRefused() {
            EncodeJobQueue q = queue();
            q.stop();

            assertThat(q.enqueue(job(null, false))).isFalse();
        }
    }

    @Test
    void startThenStopDoesNotHang() {
        EncodeJobQueue q = queue();

        q.start();
        q.stop();
        q.stop(); // idempotent
    }

    @Test
    void workerProcessesEnqueuedJobs() throws Exception {
        CountDownLatch encoded = new CountDownLatch(1);
        doAnswer(invocation -> {
            encoded.countDown();
            return null;
        }).when(encoder).encodeVideo(any(), any(), any(), any());

        EncodeJobQueue q = queue();
        q.start();

        assertThat(q.enqueue(job(null, false))).isTrue();
        assertThat(encoded.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Nested
    class ProcessJob {

        @Test
        void successRecordsMetrics() {
            queue().processJob(job(null, false));

            ArgumentCaptor<Double> duration = ArgumentCaptor.forClass(Double.class);
            verify(metrics).observeEncodeJobDuration(duration.capture());
            assertThat(duration.getValue()).isGreaterThan(0d);
            verify(metrics).incEncodeJobsTotal("success");
        }

        @Test
        void failureRecordsMetrics() throws IOException {
            doThrow(new IOException("encode failed"))
                    .when(encoder).encodeVideo(any(), any(), any(), any());

            queue().processJob(job(null, false));

            verify(metrics).incEncodeJobsTotal("failure");
            verify(metrics, never()).incEncodeJobsTotal("success");
        }

        @Test
        void deletesSourceAfterEncodingForVideoUnencoded() throws IOException {
            queue().processJob(job(StreamType.VIDEO_UNENCODED, true));

            verify(storage).deleteFile(INPUT);
        }

        @Test
        void keepsSourceWhenNotVideoUnencoded() throws IOException {
            queue().processJob(job(StreamType.VIDEO_ENCODED, true));

            verify(storage, never()).deleteFile(any());
        }

        @Test
        void keepsSourceWhenFlagDisabled() throws IOException {
            queue().processJob(job(StreamType.VIDEO_UNENCODED, false));

            verify(storage, never()).deleteFile(any());
        }

        @Test
        void deletionFailureIsNotFatal() throws IOException {
            doThrow(new IOException("permission denied")).when(storage).deleteFile(any());

            queue().processJob(job(StreamType.VIDEO_UNENCODED, true));

            verify(metrics).incEncodeJobsTotal("success");
        }
    }
}
