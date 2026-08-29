package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management;

import com.fjourdren.theatrum.application.port.in.TrackViewerUseCase;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import com.fjourdren.theatrum.infrastructure.ffmpeg.FfmpegCommand;
import com.fjourdren.theatrum.infrastructure.ffmpeg.HlsPlaylist;
import com.fjourdren.theatrum.infrastructure.ffmpeg.OutputMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the FFmpeg process of every active stream, keyed by RTMP input path. */
@Slf4j
@Component
public class StreamManager {

    /** Seam so tests can exercise the lifecycle without spawning FFmpeg. */
    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> argv) throws IOException;
    }

    private static final ProcessLauncher SPAWN_FFMPEG = argv -> new ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();

    private final Map<String, StreamProcess> streams = new ConcurrentHashMap<>();
    private final TrackViewerUseCase viewerTracker;
    private final Metrics metrics;
    private final ProcessLauncher launcher;

    // Two constructors exist (the second is the test seam), so Spring needs to be told which one.
    @Autowired
    public StreamManager(TrackViewerUseCase viewerTracker, Metrics metrics) {
        this(viewerTracker, metrics, SPAWN_FFMPEG);
    }

    StreamManager(TrackViewerUseCase viewerTracker, Metrics metrics, ProcessLauncher launcher) {
        this.viewerTracker = viewerTracker;
        this.metrics = metrics;
        this.launcher = launcher;
    }

    /**
     * Returns the stream already running for {@code inputPath}, or starts a new one. A stream that
     * is no longer active is replaced.
     *
     * @param outputDir          directory FFmpeg writes to, already resolved from the path template
     * @param resolvedRecordPath recording directory, or {@code null} for in-place / no recording
     */
    public StreamProcess getOrCreateStream(String inputPath, Path outputDir, Stream stream,
                                           Path resolvedRecordPath, String trackingKey) throws IOException {
        StreamProcess existing = streams.get(inputPath);
        if (existing != null) {
            if (existing.isActive()) {
                return existing;
            }
            streams.remove(inputPath, existing);
        }

        StreamProcess created = createNewStream(inputPath, outputDir, stream, resolvedRecordPath, trackingKey);
        streams.put(inputPath, created);
        return created;
    }

    private StreamProcess createNewStream(String inputPath, Path outputDir, Stream stream,
                                          Path resolvedRecordPath, String trackingKey) throws IOException {
        Files.createDirectories(outputDir);

        boolean multiQuality = stream.multiQuality();
        OutputMode outputMode = OutputMode.determine(stream.distribution());

        // For HLS-only passthrough, outputDir is {path}/default and streamRootDir is its parent.
        // For every other mode (HLS multi-quality, DASH, Dual) they are the same directory.
        boolean hlsPassthrough = outputMode == OutputMode.HLS && !multiQuality;
        Path streamRootDir = hlsPassthrough ? outputDir.getParent() : outputDir;

        if (hlsPassthrough) {
            HlsPlaylist.generateMasterPlaylistWrapper(streamRootDir);
        }

        Process process = launcher.start(FfmpegCommand.create("", outputDir, stream, outputMode));

        ThumbnailGenerator thumbnailGen = null;
        if (stream.thumbnail().enabled()) {
            thumbnailGen = new ThumbnailGenerator(streamRootDir, outputDir, outputMode, multiQuality,
                    new ArrayList<>(stream.qualities().keySet()), stream.thumbnail().interval());
            thumbnailGen.start();
        }

        StreamProcess streamProcess = new StreamProcess(process, inputPath, outputDir, streamRootDir, stream,
                outputMode, resolvedRecordPath, trackingKey, viewerTracker, metrics, thumbnailGen);

        Thread.ofVirtual().name("stream-monitor-" + inputPath).start(() -> streamProcess.monitor(this));

        log.info("Started new stream for: {}", inputPath);
        return streamProcess;
    }

    /** Returns the input paths of every currently active stream. */
    public List<String> getActiveStreams() {
        return streams.entrySet().stream()
                .filter(entry -> entry.getValue().isActive())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Drops {@code process} from the registry, called by its monitor once FFmpeg exits. */
    void remove(String inputPath, StreamProcess process) {
        streams.remove(inputPath, process);
    }
}
