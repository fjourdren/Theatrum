package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management;

import com.fjourdren.theatrum.application.port.in.TrackViewerUseCase;
import com.fjourdren.theatrum.domain.constant.MetricConstants;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.Record;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.config.RtmpConfig;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import com.fjourdren.theatrum.infrastructure.ffmpeg.HlsPlaylist;
import com.fjourdren.theatrum.infrastructure.ffmpeg.OutputMode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single live stream and the FFmpeg process transcoding it. */
@Slf4j
public class StreamProcess {

    private final Process process;
    private final String inputPath;
    private final Path outputDir;

    /** Parent of {@code default/} for HLS passthrough, same as {@code outputDir} otherwise. */
    private final Path streamRootDir;

    private final Record record;

    /** Resolved recording directory, or {@code null} for in-place recording. */
    private final Path resolvedRecordPath;

    private final int segmentDuration;
    private final boolean multiQuality;
    private final OutputMode outputMode;
    private final String trackingKey;
    private final TrackViewerUseCase viewerTracker;
    private final Metrics metrics;
    private final ThumbnailGenerator thumbnailGen;
    private final Instant startedAt = Instant.now();
    private final AtomicBoolean active = new AtomicBoolean(true);

    StreamProcess(Process process, String inputPath, Path outputDir, Path streamRootDir, Stream streamConfig,
                  OutputMode outputMode, Path resolvedRecordPath, String trackingKey,
                  TrackViewerUseCase viewerTracker, Metrics metrics, ThumbnailGenerator thumbnailGen) {
        this.process = process;
        this.inputPath = inputPath;
        this.outputDir = outputDir;
        this.streamRootDir = streamRootDir;
        this.record = streamConfig.record();
        this.resolvedRecordPath = resolvedRecordPath;
        this.segmentDuration = streamConfig.distribution().segmentDuration();
        this.multiQuality = streamConfig.multiQuality();
        this.outputMode = outputMode;
        this.trackingKey = trackingKey;
        this.viewerTracker = viewerTracker;
        this.metrics = metrics;
        this.thumbnailGen = thumbnailGen;
    }

    /** Waits for FFmpeg to exit and drops the stream from {@code manager}. */
    void monitor(StreamManager manager) {
        try {
            int status = process.waitFor();
            if (status != 0) {
                log.info("FFmpeg exited for: {}: exit status {}", inputPath, status);
                metrics.incFfmpegExits(MetricConstants.EXIT_ERROR, trackingKey);
            } else {
                log.info("FFmpeg exited normally for: {}", inputPath);
                metrics.incFfmpegExits(MetricConstants.EXIT_CLEAN, trackingKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            active.set(false);
            manager.remove(inputPath, this);
            log.info("Stream ended and cleaned up for: {}", inputPath);
        }
    }

    /** Gracefully stops the stream, then records or deletes its files. Safe to call twice. */
    public void stop(RtmpConfig config) {
        if (!active.getAndSet(false)) {
            return; // already stopped
        }

        // Stop thumbnail generation before shutting down FFmpeg
        if (thumbnailGen != null) {
            thumbnailGen.stop();
        }

        // Closing stdin signals FFmpeg to finalise its playlists and exit
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("Failed to close FFmpeg stdin for: {}: {}", inputPath, e.toString());
        }

        try {
            if (process.waitFor(config.cleanupDelay(), TimeUnit.SECONDS)) {
                log.info("FFmpeg process exited cleanly for: {}", inputPath);
            } else {
                log.info("FFmpeg process did not exit cleanly for: {}, forcing termination", inputPath);
                process.destroyForcibly();
                metrics.incFfmpegExits(MetricConstants.EXIT_KILLED, trackingKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (viewerTracker != null && trackingKey != null && !trackingKey.isEmpty()) {
            viewerTracker.unregisterStream(trackingKey);
        }
        metrics.observeStreamDuration(trackingKey, Duration.between(startedAt, Instant.now()).toMillis() / 1000.0);

        if (record.enabled() && resolvedRecordPath != null) {
            runAsync("save-recording", this::saveRecording);
        } else if (record.enabled()) {
            runAsync("save-in-place", this::saveInPlace);
        } else {
            runAsync("cleanup", () -> cleanup(config.cleanupDelay()));
        }
    }

    private void cleanup(int cleanupDelaySeconds) {
        try {
            Thread.sleep(Duration.ofSeconds(cleanupDelaySeconds));
            deleteRecursively(streamRootDir);
            log.info("Cleaned up stream directory for: {}", inputPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("Error cleaning up stream directory for: {}: {}", inputPath, e.toString());
        }
    }

    /** Generates VOD playlists and moves the stream files to the recording path. */
    void saveRecording() {
        Path recordDir = resolvedRecordPath;

        try {
            if (outputMode == OutputMode.HLS) {
                generateVodPlaylists();

                // Passthrough segments end up under recordDir/default/, matching the live layout
                Files.createDirectories(multiQuality ? recordDir : recordDir.resolve(VideoConstants.DEFAULT_QUALITY));

                if (multiQuality) {
                    moveContents(streamRootDir, recordDir);
                } else {
                    moveContents(outputDir, recordDir.resolve(VideoConstants.DEFAULT_QUALITY));
                    try {
                        HlsPlaylist.generateMasterPlaylistWrapper(recordDir);
                    } catch (IOException e) {
                        log.error("Error generating master playlist wrapper for recording: {}", e.toString());
                    }
                    moveIfExists(streamRootDir.resolve(VideoConstants.VIEWS_FILE),
                            recordDir.resolve(VideoConstants.VIEWS_FILE));
                    moveIfExists(streamRootDir.resolve(VideoConstants.THUMBNAIL_FILE),
                            recordDir.resolve(VideoConstants.THUMBNAIL_FILE));
                }
            } else {
                // DASH or Dual: FFmpeg already finalised the manifests on clean exit
                Files.createDirectories(recordDir);
                moveContents(streamRootDir, recordDir);
            }

            deleteRecursively(streamRootDir);
        } catch (IOException e) {
            log.error("Error saving recording for: {}: {}", inputPath, e.toString());
            metrics.incRecordings(MetricConstants.RECORD_MODE_MOVE, MetricConstants.RESULT_FAILURE, trackingKey);
            return;
        }

        metrics.incRecordings(MetricConstants.RECORD_MODE_MOVE, MetricConstants.RESULT_SUCCESS, trackingKey);
        log.info("Recording saved to: {}", recordDir);
    }

    /** Generates VOD playlists where the files already are, without moving anything. */
    void saveInPlace() {
        if (outputMode == OutputMode.HLS) {
            try {
                generateVodPlaylists();
            } catch (IOException e) {
                log.error("Error reading output directory for in-place recording: {}: {}",
                        outputDir, e.toString());
                metrics.incRecordings(MetricConstants.RECORD_MODE_IN_PLACE, MetricConstants.RESULT_FAILURE, trackingKey);
                return;
            }
            if (!multiQuality) {
                try {
                    HlsPlaylist.generateMasterPlaylistWrapper(streamRootDir);
                } catch (IOException e) {
                    log.error("Error generating master playlist wrapper for in-place recording: {}", e.toString());
                }
            }
        }
        // DASH/Dual: FFmpeg finalises the manifests on clean exit, nothing to do

        metrics.incRecordings(MetricConstants.RECORD_MODE_IN_PLACE, MetricConstants.RESULT_SUCCESS, trackingKey);
        log.info("In-place recording saved at: {}", streamRootDir);
    }

    /**
     * Writes a VOD playlist per quality directory (multi-quality) or one for {@code outputDir}
     * (passthrough). A quality with no segments is logged and skipped.
     *
     * @throws IOException when {@code outputDir} cannot be listed at all
     */
    private void generateVodPlaylists() throws IOException {
        if (!multiQuality) {
            try {
                VodPlaylist.generateVodPlaylist(outputDir, segmentDuration);
            } catch (IOException e) {
                log.error("Error generating VOD playlist: {}", e.toString());
            }
            return;
        }

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(outputDir, Files::isDirectory)) {
            for (Path qualityDir : entries) {
                try {
                    VodPlaylist.generateVodPlaylist(qualityDir, segmentDuration);
                } catch (IOException e) {
                    log.error("Error generating VOD playlist for quality {}: {}",
                            qualityDir.getFileName(), e.toString());
                }
            }
        }
    }

    private static void moveIfExists(Path source, Path target) {
        try {
            if (Files.exists(source)) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            log.error("Error moving {} to {}: {}", source, target, e.toString());
        }
    }

    /** Moves every entry of {@code source} into {@code target}. */
    static void moveContents(Path source, Path target) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
            for (Path entry : entries) {
                Files.move(entry, target.resolve(entry.getFileName()));
            }
        }
    }

    /** Deletes {@code dir} and everything below it. A missing directory is not an error. */
    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> paths;
        try (var walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private static void runAsync(String name, Runnable task) {
        Thread.ofVirtual().name(name).start(task);
    }

    public boolean isActive() {
        return active.get();
    }

    public String inputPath() {
        return inputPath;
    }

    /** The FFmpeg stdin pipe: RTMP frames are written here as FLV. */
    public OutputStream stdin() {
        return process.getOutputStream();
    }
}
