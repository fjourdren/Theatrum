package com.fjourdren.theatrum.infrastructure.adapter.in.restream;

import com.fjourdren.theatrum.application.port.in.LiveStreamVarsUseCase;
import com.fjourdren.theatrum.application.port.in.PathTemplateUseCase;
import com.fjourdren.theatrum.application.port.in.ResolveChannelUseCase;
import com.fjourdren.theatrum.application.port.in.TrackViewerUseCase;
import com.fjourdren.theatrum.domain.constant.MetricConstants;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pulls from external URLs and outputs HLS/DASH segments. */
@Slf4j
@Component
public class RestreamManager implements RestreamLifecycle {

    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    static final Duration RESET_THRESHOLD = Duration.ofSeconds(30);

    private final ResolveChannelUseCase appService;
    private final PathTemplateUseCase templateService;
    private final LiveStreamVarsUseCase registry;
    private final TrackViewerUseCase viewerTracker;
    private final Metrics metrics;
    private final AppPaths appPaths;
    private final ProcessLauncher launcher;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ExecutorService executor;

    // Two constructors exist (the second is the test seam), so Spring needs to be told which one.
    @Autowired
    public RestreamManager(ResolveChannelUseCase appService, PathTemplateUseCase templateService,
                           LiveStreamVarsUseCase registry, TrackViewerUseCase viewerTracker, Metrics metrics,
                           AppPaths appPaths) {
        this(appService, templateService, registry, viewerTracker, metrics, appPaths,
                argv -> new ProcessBuilder(argv)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start());
    }

    RestreamManager(ResolveChannelUseCase appService, PathTemplateUseCase templateService,
                    LiveStreamVarsUseCase registry, TrackViewerUseCase viewerTracker, Metrics metrics,
                    AppPaths appPaths, ProcessLauncher launcher) {
        this.appService = appService;
        this.templateService = templateService;
        this.registry = registry;
        this.viewerTracker = viewerTracker;
        this.metrics = metrics;
        this.appPaths = appPaths;
        this.launcher = launcher;
    }

    /** Starts FFmpeg (or a test double). */
    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> argv) throws IOException;
    }

    /**
     * Everything a channel's reconnect loop needs, resolved once at startup so builtin template
     * values (e.g. {@code {%UUID%}}) stay stable across reconnections.
     *
     * @param recordDir target directory for the recording, or null when not recording to a path
     */
    record ChannelPlan(String channelName, Stream stream, Path outputDir, Path streamRootDir,
                       Path recordDir, String trackingKey, OutputMode outputMode, String streamKey) {
    }

    /** Launches a virtual thread per restream channel. */
    @Override
    public void start() {
        var plans = plan();
        if (plans.isEmpty()) {
            return;
        }
        running.set(true);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        plans.forEach(p -> executor.execute(() -> runWithReconnect(p)));
    }

    /** Cancels all restream threads and waits for them to finish. */
    @Override
    public void stop() {
        running.set(false);
        var ex = executor;
        executor = null;
        if (ex == null) {
            return;
        }
        ex.shutdownNow();
        try {
            ex.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Resolves the paths of every restream channel, skipping the ones whose templates don't resolve. */
    List<ChannelPlan> plan() {
        var channels = appService.getChannels();
        if (channels == null) {
            return List.of();
        }

        var plans = new ArrayList<ChannelPlan>();
        for (var entry : channels.entrySet()) {
            var channelName = entry.getKey();
            var ch = entry.getValue();
            if (ch.type() != StreamType.RESTREAM) {
                continue;
            }

            // Builtin vars (e.g. {%STARTING_DATE%}, {%UUID%}) are generated once per session.
            var builtinVars = templateService.generateBuiltinVars(ch.path());

            // Restream channels have no user variables, so the stream key is the raw path template.
            // Registering it lets the HTTP handler resolve the tracking key.
            var streamKey = ch.path();
            registry.getOrRegister(streamKey, builtinVars);

            String resolvedPath;
            try {
                resolvedPath = templateService.replacePlaceholders(ch.path(), builtinVars);
            } catch (RuntimeException e) {
                log.error("Error resolving restream path for {}: {}", channelName, e.getMessage());
                continue;
            }

            var streamRootDir = appPaths.videoDir().resolve(resolvedPath);
            // DASH (and multi-quality HLS) let FFmpeg lay out the representations itself; a
            // passthrough HLS stream gets the single "default" quality subdir.
            var outputMode = OutputMode.determine(ch.distribution());
            var outputDir = ch.distribution().dashEnabled() || ch.multiQuality()
                    ? streamRootDir
                    : streamRootDir.resolve(VideoConstants.DEFAULT_QUALITY);

            Path recordDir = null;
            if (ch.record().enabled() && !ch.record().path().isEmpty()) {
                try {
                    recordDir = appPaths.videoDir()
                            .resolve(templateService.replacePlaceholders(ch.record().path(), builtinVars));
                } catch (RuntimeException e) {
                    log.error("Error resolving restream record path for {}: {}", channelName, e.getMessage());
                    continue;
                }
            }

            plans.add(new ChannelPlan(channelName, ch, outputDir, streamRootDir, recordDir,
                    resolvedPath, outputMode, streamKey));
        }
        return plans;
    }

    /** Runs FFmpeg in a loop with exponential backoff on failure, until {@link #stop()}. */
    private void runWithReconnect(ChannelPlan p) {
        var backoff = INITIAL_BACKOFF;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            log.info("Starting restream for {} from {}", p.channelName(), p.stream().sourceUrl());

            var startedAt = System.nanoTime();
            String error;
            try {
                error = runOnce(p);
            } catch (InterruptedException e) {
                // Cancelled — graceful shutdown.
                Thread.currentThread().interrupt();
                break;
            }

            if (!running.get()) {
                break;
            }

            if (error != null) {
                log.warn("Restream {} exited with error: {}", p.channelName(), error);
                metrics.incFfmpegExits(MetricConstants.EXIT_ERROR, p.trackingKey());
            } else {
                log.info("Restream {} exited normally", p.channelName());
                metrics.incFfmpegExits(MetricConstants.EXIT_CLEAN, p.trackingKey());
            }

            // A process that ran for a while earned a fresh backoff budget.
            if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(RESET_THRESHOLD) > 0) {
                backoff = INITIAL_BACKOFF;
            }

            log.info("Restream {} will retry in {}", p.channelName(), backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = nextBackoff(backoff);
        }

        handleShutdown(p);
    }

    static Duration nextBackoff(Duration current) {
        var doubled = current.multipliedBy(2);
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    /**
     * Starts FFmpeg and waits for it to exit.
     *
     * @return null on a clean exit, otherwise the reason it failed
     */
    private String runOnce(ChannelPlan p) throws InterruptedException {
        try {
            Files.createDirectories(p.outputDir());
        } catch (IOException e) {
            return "failed to create output directory " + p.outputDir() + ": " + e.getMessage();
        }

        // Passthrough HLS has no master playlist of its own; write a wrapper pointing at default/.
        if (p.outputMode() == OutputMode.HLS && !p.stream().multiQuality()) {
            try {
                HlsPlaylist.generateMasterPlaylistWrapper(p.streamRootDir());
            } catch (IOException e) {
                return "failed to generate master playlist wrapper: " + e.getMessage();
            }
        }

        var argv = FfmpegCommand.create(p.stream().sourceUrl(), p.outputDir(), p.stream(), p.outputMode());
        Process process;
        try {
            process = launcher.start(argv);
        } catch (IOException e) {
            return "failed to start FFmpeg: " + e.getMessage();
        }

        metrics.incLiveStreamsActive();
        var startedAt = System.nanoTime();
        try {
            int exit = process.waitFor();
            return exit == 0 ? null : "exit status " + exit;
        } finally {
            // No-op once exited; on interruption this is what kills FFmpeg.
            process.destroy();
            metrics.decLiveStreamsActive();
            metrics.observeStreamDuration(p.trackingKey(), (System.nanoTime() - startedAt) / 1e9);
        }
    }

    /** Unregisters the stream and either records or deletes its files. */
    void handleShutdown(ChannelPlan p) {
        if (viewerTracker != null && !p.trackingKey().isEmpty()) {
            viewerTracker.unregisterStream(p.trackingKey());
        }
        registry.unregister(p.streamKey());

        if (!p.stream().record().enabled()) {
            deleteRecursively(p.streamRootDir());
            log.info("Restream cleanup completed for: {}", p.trackingKey());
            return;
        }

        if (p.recordDir() == null) {
            // In-place recording: files stay where they are.
            metrics.incRecordings(MetricConstants.RECORD_MODE_IN_PLACE, MetricConstants.RESULT_SUCCESS, p.trackingKey());
            log.info("Restream in-place recording saved at: {}", p.streamRootDir());
            return;
        }

        try {
            Files.createDirectories(p.recordDir());
        } catch (IOException e) {
            log.error("Error creating recording directory {}: {}", p.recordDir(), e.getMessage());
            metrics.incRecordings(MetricConstants.RECORD_MODE_MOVE, MetricConstants.RESULT_FAILURE, p.trackingKey());
            return;
        }
        try {
            moveContents(p.streamRootDir(), p.recordDir());
        } catch (IOException e) {
            log.error("Error moving files to recording directory: {}", e.getMessage());
            metrics.incRecordings(MetricConstants.RECORD_MODE_MOVE, MetricConstants.RESULT_FAILURE, p.trackingKey());
            return;
        }
        deleteRecursively(p.streamRootDir());
        metrics.incRecordings(MetricConstants.RECORD_MODE_MOVE, MetricConstants.RESULT_SUCCESS, p.trackingKey());
        log.info("Restream recording saved to: {}", p.recordDir());
    }

    /** Moves all files and directories from src to dst. */
    private static void moveContents(Path src, Path dst) throws IOException {
        List<Path> entries;
        try (var list = Files.list(src)) {
            entries = list.toList();
        } catch (IOException e) {
            throw new IOException("failed to read source directory: " + e.getMessage(), e);
        }
        for (var entry : entries) {
            var target = dst.resolve(entry.getFileName());
            try {
                Files.move(entry, target);
            } catch (IOException e) {
                throw new IOException("failed to move " + entry + " to " + target + ": " + e.getMessage(), e);
            }
        }
    }

    /** Best-effort equivalent of Go's {@code os.RemoveAll}: errors are logged, never thrown. */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Error deleting {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Error deleting {}: {}", dir, e.getMessage());
        }
    }
}
