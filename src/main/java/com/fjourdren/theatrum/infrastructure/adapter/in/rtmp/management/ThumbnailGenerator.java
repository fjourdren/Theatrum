package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.infrastructure.ffmpeg.FfmpegConstants;
import com.fjourdren.theatrum.infrastructure.ffmpeg.OutputMode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;

/**
 * Periodically extracts a PNG frame from the latest segment on disk so clients can show a live
 * preview of the stream.
 */
@Slf4j
public class ThumbnailGenerator {

    /** {@code thumbnail.png} -> {@code thumbnail.tmp.png}. */
    private static final String TMP_THUMBNAIL_FILE =
            VideoConstants.THUMBNAIL_FILE.replaceFirst("(\\.[^.]+)$", ".tmp$1");

    private static final List<String> QUALITY_PREFERENCE = List.of("high", "medium", "low");

    private final Path streamRootDir;
    private final Path outputDir;
    private final OutputMode outputMode;
    private final boolean multiQuality;
    private final List<String> qualities;
    private final Duration interval;

    private volatile Thread worker;
    private volatile boolean stopping;
    private volatile Process running;

    public ThumbnailGenerator(Path streamRootDir, Path outputDir, OutputMode outputMode, boolean multiQuality,
                              List<String> qualities, int intervalSeconds) {
        this.streamRootDir = streamRootDir;
        this.outputDir = outputDir;
        this.outputMode = outputMode;
        this.multiQuality = multiQuality;
        this.qualities = qualities == null ? List.of() : List.copyOf(qualities);
        this.interval = Duration.ofSeconds(intervalSeconds);
    }

    /** Starts periodic thumbnail generation on a virtual thread. */
    public void start() {
        worker = Thread.ofVirtual().name("thumbnail-" + streamRootDir).start(this::loop);
    }

    /** Cancels thumbnail generation and waits for the worker to exit. */
    public void stop() {
        stopping = true;
        Thread thread = worker;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        Process process = running;
        if (process != null) {
            process.destroyForcibly();
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        while (!stopping) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return;
            }
            if (stopping) {
                return;
            }
            generate();
        }
    }

    void generate() {
        Path segment = findLatestSegment();
        if (segment == null) {
            return;
        }

        Path outputPath = streamRootDir.resolve(VideoConstants.THUMBNAIL_FILE);
        Path tmpPath = streamRootDir.resolve(TMP_THUMBNAIL_FILE);

        try {
            Process process = new ProcessBuilder(buildFfmpegArgs(segment, tmpPath))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            running = process;
            int status = process.waitFor();
            if (status != 0) {
                throw new IOException("ffmpeg exited with status " + status);
            }
        } catch (IOException e) {
            // Don't log on shutdown: killing FFmpeg mid-frame is expected there.
            if (!stopping) {
                log.warn("Thumbnail generation failed for {}: {}", streamRootDir, e.toString());
            }
            deleteQuietly(tmpPath);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(tmpPath);
            return;
        } finally {
            running = null;
        }

        try {
            Files.move(tmpPath, outputPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Thumbnail rename failed for {}: {}", streamRootDir, e.toString());
            deleteQuietly(tmpPath);
        }
    }

    /** Builds the argv extracting a single frame from {@code segmentPath}. */
    List<String> buildFfmpegArgs(Path segmentPath, Path outputPath) {
        String input = segmentPath.toString();

        if (input.endsWith(VideoConstants.EXT_DASH_SEGMENT)) {
            // An m4s chunk is undecodable on its own; the init segment carries the codec setup.
            Path initSegment = findInitSegment();
            if (initSegment != null) {
                input = "concat:" + initSegment + "|" + segmentPath;
            }
        }

        return List.of(FfmpegConstants.BINARY, "-y", "-loglevel", "error", "-i", input,
                "-frames:v", "1", "-update", "1", outputPath.toString());
    }

    /** Returns the most recently modified segment file, or {@code null} when there is none. */
    Path findLatestSegment() {
        if (outputMode == OutputMode.HLS) {
            if (!multiQuality) {
                // HLS passthrough: segments live in outputDir (the default/ subdir).
                return findLatestFileByGlob(outputDir, VideoConstants.MPEGTS_SEGMENT_GLOB);
            }
            Path bestDir = pickBestQualityDir();
            return bestDir == null ? null : findLatestFileByGlob(bestDir, VideoConstants.MPEGTS_SEGMENT_GLOB);
        }
        // DASH or Dual: m4s chunks in streamRootDir.
        return findLatestFileByGlob(streamRootDir, VideoConstants.DASH_SEGMENT_GLOB);
    }

    /** Returns the highest quality directory available: high &gt; medium &gt; low &gt; first existing. */
    Path pickBestQualityDir() {
        for (String name : QUALITY_PREFERENCE) {
            if (qualities.contains(name) && Files.isDirectory(outputDir.resolve(name))) {
                return outputDir.resolve(name);
            }
        }
        for (String quality : qualities) {
            if (Files.isDirectory(outputDir.resolve(quality))) {
                return outputDir.resolve(quality);
            }
        }
        return null;
    }

    /** Finds a DASH/Dual init segment, preferring {@code init-stream0.m4s} (video). */
    Path findInitSegment() {
        Path fallback = null;
        try (DirectoryStream<Path> matches =
                     Files.newDirectoryStream(streamRootDir, VideoConstants.DASH_INIT_SEGMENT_GLOB)) {
            for (Path match : matches) {
                if (match.getFileName().toString().contains(VideoConstants.DASH_FIRST_INIT_SEGMENT)) {
                    return match;
                }
                if (fallback == null) {
                    fallback = match;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return fallback;
    }

    /** Returns the most recently modified file in {@code dir} matching {@code glob}. */
    static Path findLatestFileByGlob(Path dir, String glob) {
        Path latest = null;
        FileTime latestTime = null;
        try (DirectoryStream<Path> matches = Files.newDirectoryStream(dir, glob)) {
            for (Path match : matches) {
                FileTime modified = Files.getLastModifiedTime(match);
                if (latestTime == null || modified.compareTo(latestTime) > 0) {
                    latest = match;
                    latestTime = modified;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return latest;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
