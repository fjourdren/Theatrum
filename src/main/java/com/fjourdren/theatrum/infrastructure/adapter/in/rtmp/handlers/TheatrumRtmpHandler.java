package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.handlers;

import com.fjourdren.theatrum.application.port.in.exception.AuthenticationException;
import com.fjourdren.theatrum.domain.constant.MetricConstants;
import com.fjourdren.theatrum.application.port.in.AuthorizePublishUseCase;
import com.fjourdren.theatrum.application.port.in.LiveStreamVarsUseCase;
import com.fjourdren.theatrum.application.port.in.PathTemplateUseCase;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.config.RtmpConfig;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.flv.FlvWriter;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management.StreamManager;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management.StreamProcess;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.models.ConnectionInfo;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol.RtmpEventHandler;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Theatrum's RTMP callbacks: authenticate the publisher, resolve its output paths and pipe the
 * incoming frames into FFmpeg as FLV.
 *
 * <p>One instance per connection — it holds that connection's state. Go guarded that state with an
 * {@code RWMutex}; here a connection is served by a single thread, so only {@code onClose} (which
 * the shutdown path may call from another thread) races, and {@code volatile} fields are enough:
 * each field is a lone reference with no cross-field invariant to keep.
 */
@Slf4j
@RequiredArgsConstructor
public class TheatrumRtmpHandler implements RtmpEventHandler {

    private final AuthorizePublishUseCase rtmpAuthService;
    private final PathTemplateUseCase templateService;
    private final LiveStreamVarsUseCase registry;
    private final StreamManager streamManager;
    private final RtmpConfig config;
    private final Metrics metrics;
    private final AppPaths appPaths;

    private volatile ConnectionInfo connectionInfo;
    private volatile StreamProcess streamProcess;
    private volatile FlvWriter flvWriter;
    /** Stream path resolved with user variables only, the registry key for the builtins. */
    private volatile String streamKey = "";
    /** Channel pattern, used as a metric label (e.g. {@code /user/{username}}). */
    private volatile String channelPattern = "";
    /** Fully resolved stream path, used as a per-stream metric label. */
    private volatile String trackingKey = "";
    /** The delayed cleanup started by {@link #onClose()}; visible for tests to join on. */
    volatile Thread cleanupTask;

    @Override
    public void onServe() {
        log.info("New RTMP connection established");
        metrics.incRtmpConnections();
        metrics.incRtmpConnectionsActive();
    }

    @Override
    public void onConnect(String app, String tcUrl, Map<String, Object> commandObject) throws IOException {
        log.info("RTMP connection from {}", tcUrl);

        var match = rtmpAuthService.extractChannel(tcUrl);
        if (match.isEmpty()) {
            log.warn("Failed to extract channel from TCURL '{}'", tcUrl);
            metrics.incRtmpAuth(MetricConstants.RESULT_FAILURE);
            throw new IOException("failed to extract channel from TCURL: " + tcUrl);
        }

        // Redundant with extractChannel above (isAuthorized is defined as "a channel matches"),
        // kept because the Go handler performs both checks.
        if (!rtmpAuthService.isAuthorized(tcUrl)) {
            log.warn("Unauthorized TCURL '{}' in OnConnect", tcUrl);
            metrics.incRtmpAuth(MetricConstants.RESULT_FAILURE);
            throw new IOException("unauthorized TCURL: " + tcUrl);
        }

        channelPattern = match.get().pattern();
        connectionInfo = new ConnectionInfo(app, tcUrl, match.get().stream(), match.get().vars());

        log.info("RTMP connection authorized for path: {}", tcUrl);
    }

    @Override
    public void onPublish(String publishingName, String publishingType) throws IOException {
        ConnectionInfo connInfo = connectionInfo;
        if (connInfo == null) {
            throw new IOException("no connection info available");
        }
        log.info("Stream publish request on {}", connInfo.tcUrl());

        try {
            rtmpAuthService.validateAuthentication(connInfo.stream(), connInfo.vars(), publishingName);
        } catch (AuthenticationException e) {
            log.warn("Authentication failed for TCURL {}: {}", connInfo.tcUrl(), e.getMessage());
            metrics.incRtmpAuth(MetricConstants.RESULT_FAILURE);
            throw new IOException(e.getMessage(), e);
        }

        metrics.incRtmpAuth(MetricConstants.RESULT_SUCCESS);
        metrics.incLiveStreamsActive();
        log.info("Publishing to TCURL: {}", connInfo.tcUrl());

        Stream stream = connInfo.stream();
        Map<String, String> builtinVars = templateService.generateBuiltinVars(stream.path());

        // The stream key resolves user variables only, leaving builtins unresolved, so a
        // reconnecting publisher lands on the same registry entry.
        streamKey = resolve("stream key template",
                () -> templateService.replacePlaceholders(stream.path(), connInfo.vars()));

        // Atomic: reuse existing builtins on reconnection, store new ones on first publish.
        builtinVars = registry.getOrRegister(streamKey, builtinVars);

        Map<String, String> mergedVars = new LinkedHashMap<>(connInfo.vars());
        mergedVars.putAll(builtinVars);

        String streamPath = resolve("stream path", () -> rtmpAuthService.buildStreamPath(stream, mergedVars));

        // Output layout: DASH (any mode) and multi-quality HLS are flat — FFmpeg owns the subdirs.
        // HLS passthrough gets its own default/ subdirectory.
        Path streamRoot = appPaths.videoDir().resolve(streamPath);
        Path localPath = stream.distribution().dashEnabled() || stream.multiQuality()
                ? streamRoot
                : streamRoot.resolve(VideoConstants.DEFAULT_QUALITY);

        Path resolvedRecordPath = null;
        if (stream.record().enabled() && !stream.record().path().isEmpty()) {
            resolvedRecordPath = appPaths.videoDir()
                    .resolve(resolve("record path",
                            () -> templateService.replacePlaceholders(stream.record().path(), mergedVars)));
            log.info("Recording path: {}", resolvedRecordPath);
        }

        trackingKey = resolve("tracking key template",
                () -> templateService.replacePlaceholders(stream.path(), mergedVars));

        log.info("Stream output path: {}", localPath);

        StreamProcess process = streamManager.getOrCreateStream(
                connInfo.tcUrl(), localPath, stream, resolvedRecordPath, trackingKey);

        streamProcess = process;
        flvWriter = new FlvWriter(process.stdin());
        log.info("Stream started for TCURL: {}", connInfo.tcUrl());
    }

    @Override
    public void onPlay(String streamName) throws IOException {
        log.info("Play connection refused: {}", streamName);
        throw new IOException("play connections are not allowed");
    }

    @Override
    public void onSetDataFrame(long timestamp, byte[] payload) throws IOException {
        FlvWriter writer = flvWriter;
        if (writer != null) {
            writer.writeScript(timestamp, payload);
        }
    }

    @Override
    public void onAudio(long timestamp, byte[] payload) throws IOException {
        FlvWriter writer = flvWriter;
        if (writer != null) {
            count(MetricConstants.FRAME_TYPE_AUDIO, payload.length);
            writer.writeAudio(timestamp, payload);
        }
    }

    @Override
    public void onVideo(long timestamp, byte[] payload) throws IOException {
        FlvWriter writer = flvWriter;
        if (writer != null) {
            count(MetricConstants.FRAME_TYPE_VIDEO, payload.length);
            writer.writeVideo(timestamp, payload);
        }
    }

    @Override
    public void onClose() {
        metrics.decRtmpConnectionsActive();

        StreamProcess process = streamProcess;
        if (process != null) {
            log.info("Connection closed for: {}", process.inputPath());

            String key = streamKey;
            // Wait out the reconnect window: a publisher that comes back keeps its stream alive.
            cleanupTask = Thread.ofVirtual().name("rtmp-cleanup-" + process.inputPath()).start(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(config.reconnectDelay()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!process.isActive()) {
                    return;
                }
                log.info("No reconnection detected for {}, stopping stream", process.inputPath());
                process.stop(config);
                metrics.decLiveStreamsActive();
                if (!key.isEmpty()) {
                    registry.unregister(key);
                }
            });
        }

        connectionInfo = null;
    }

    private void count(String type, int bytes) {
        metrics.addRtmpReceivedBytes(channelPattern, type, trackingKey, bytes);
        metrics.incRtmpReceivedFrames(channelPattern, type, trackingKey);
    }

    /** Resolves a path template, turning a bad template into a refused publish. */
    private String resolve(String what, Supplier<String> resolver) throws IOException {
        try {
            return resolver.get();
        } catch (RuntimeException e) {
            log.warn("Failed to build {}: {}", what, e.getMessage());
            throw new IOException("failed to build " + what + ": " + e.getMessage(), e);
        }
    }
}
