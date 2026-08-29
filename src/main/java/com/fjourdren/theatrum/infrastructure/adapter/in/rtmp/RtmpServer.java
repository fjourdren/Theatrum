package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp;

import com.fjourdren.theatrum.application.port.in.AuthorizePublishUseCase;
import com.fjourdren.theatrum.application.port.in.LiveStreamVarsUseCase;
import com.fjourdren.theatrum.application.port.in.PathTemplateUseCase;
import com.fjourdren.theatrum.application.port.in.ResolveChannelUseCase;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Rtmp;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.config.RtmpConfig;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.handlers.TheatrumRtmpHandler;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management.StreamManager;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol.RtmpConnection;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Accepts RTMP publishers and serves each one on its own virtual thread. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RtmpServer implements RtmpLifecycle {

    private final ResolveChannelUseCase applicationService;
    private final AuthorizePublishUseCase rtmpAuthService;
    private final PathTemplateUseCase templateService;
    private final LiveStreamVarsUseCase registry;
    private final StreamManager streamManager;
    private final Metrics metrics;
    private final AppPaths appPaths;

    private volatile ServerSocket listener;
    private volatile ExecutorService executor;
    private volatile boolean running;

    /**
     * Binds the RTMP port and returns; the accept loop runs on its own virtual thread so Spring
     * startup is not blocked (the Go server served synchronously).
     */
    @Override
    public void startRtmpServer() {
        int port = applicationService.getServer().rtmpPort();
        log.info("=== RTMP SERVER (port {}) ===", port);

        try {
            listener = new ServerSocket(port);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start RTMP server", e);
        }

        running = true;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("RTMP server listening on :{}", port);
        executor.submit(this::acceptLoop);
    }

    @Override
    public void shutdownRtmpServer() {
        log.info("Shutting down RTMP server");
        running = false;

        if (listener != null) {
            try {
                listener.close();
            } catch (IOException e) {
                log.warn("Error closing RTMP listener: {}", e.getMessage());
            }
        }
        // Active streams stop on their own once their FFmpeg process exits, as in the Go server.
        for (String inputPath : getActiveStreams()) {
            log.info("Stopping stream for: {}", inputPath);
        }
        if (executor != null) {
            executor.shutdownNow();
        }

        log.info("RTMP server shutdown complete");
    }

    @Override
    public List<String> getActiveStreams() {
        return streamManager.getActiveStreams();
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (running) {
                    log.warn("RTMP server error: {}", e.getMessage());
                }
                return; // the listener is closed: shutting down, or unusable either way
            }
            executor.submit(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        try {
            new RtmpConnection(socket, newHandler()).serve();
        } catch (IOException e) {
            log.warn("Failed to serve RTMP connection: {}", e.getMessage());
        }
    }

    /** One handler per connection: it carries that connection's authentication and stream state. */
    private TheatrumRtmpHandler newHandler() {
        Rtmp rtmp = applicationService.getServer().rtmp();
        return new TheatrumRtmpHandler(rtmpAuthService, templateService, registry, streamManager,
                new RtmpConfig(rtmp.reconnectDelay(), rtmp.cleanupDelay()), metrics, appPaths);
    }
}
