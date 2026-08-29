package com.fjourdren.theatrum.infrastructure.config;

import com.fjourdren.theatrum.domain.constant.MetricConstants;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.domain.service.EncodeJobQueue;
import com.fjourdren.theatrum.domain.service.VideoUnencodedDetector;
import com.fjourdren.theatrum.infrastructure.adapter.in.restream.RestreamLifecycle;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.RtmpLifecycle;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Startup and shutdown sequence, mirroring the Go entry point's container.Invoke block.
 * Spring owns the HTTP server lifecycle, so only the RTMP server, the restream manager
 * and the encode jobs are started here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TheatrumRunner implements ApplicationRunner {

    private final ApplicationService applicationService;
    private final EncodeJobQueue encodeQueue;
    private final VideoUnencodedDetector videoDetector;
    private final RtmpLifecycle rtmpServer;
    private final RestreamLifecycle restreamManager;
    private final Metrics metrics;

    @Override
    public void run(ApplicationArguments args) {
        publishChannelMetrics();

        encodeQueue.start();

        // Video detection runs synchronously at startup, as in the Go entry point.
        try {
            videoDetector.detectAndQueueVideos();
        } catch (RuntimeException e) {
            log.warn("Error during video detection: {}", e.getMessage(), e);
        }

        rtmpServer.startRtmpServer();
        restreamManager.start();
    }

    private void publishChannelMetrics() {
        Map<String, Double> countsByType = new HashMap<>();
        for (Stream channel : applicationService.getChannels().values()) {
            String type = channel.type() == null ? MetricConstants.CHANNEL_TYPE_UNKNOWN : channel.type().value();
            countsByType.merge(type, 1.0, Double::sum);
        }
        countsByType.forEach(metrics::setChannelsConfigured);
    }

    @PreDestroy
    public void shutdown() {
        restreamManager.stop();
        rtmpServer.shutdownRtmpServer();
        encodeQueue.stop();
    }
}
