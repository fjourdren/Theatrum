package com.fjourdren.theatrum.infrastructure.adapter.out.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final Metrics metrics = new Metrics(registry);

    @Test
    void incHttpRequestsCountsPerLabelTuple() {
        metrics.incHttpRequests("200", "live", "ts");
        metrics.incHttpRequests("200", "live", "ts");
        metrics.incHttpRequests("404", "live", "ts");

        assertThat(registry.get("theatrum_http_requests")
                .tags("status_code", "200", "stream_type", "live", "file_type", "ts")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("theatrum_http_requests")
                .tags("status_code", "404", "stream_type", "live", "file_type", "ts")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void observeHttpRequestDurationRecordsSeconds() {
        metrics.observeHttpRequestDuration("vod", "m3u8", 0.25);
        metrics.observeHttpRequestDuration("vod", "m3u8", 0.75);

        var timer = registry.get("theatrum_http_request_duration")
                .tags("stream_type", "vod", "file_type", "m3u8").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void addHttpResponseBytesAccumulates() {
        metrics.addHttpResponseBytes("live", "ts", 5);
        metrics.addHttpResponseBytes("live", "ts", 6);

        assertThat(registry.get("theatrum_http_response_bytes")
                .tags("stream_type", "live", "file_type", "ts")
                .counter().count()).isEqualTo(11.0);
    }

    @Test
    void httpRequestsInFlightGoesUpAndDown() {
        metrics.incHttpRequestsInFlight();
        metrics.incHttpRequestsInFlight();
        assertThat(registry.get("theatrum_http_requests_in_flight").gauge().value()).isEqualTo(2.0);

        metrics.decHttpRequestsInFlight();
        assertThat(registry.get("theatrum_http_requests_in_flight").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void rtmpConnectionCountersAndGauge() {
        metrics.incRtmpConnections();
        metrics.incRtmpConnections();
        assertThat(registry.get("theatrum_rtmp_connections").counter().count()).isEqualTo(2.0);

        metrics.incRtmpConnectionsActive();
        metrics.incRtmpConnectionsActive();
        metrics.decRtmpConnectionsActive();
        assertThat(registry.get("theatrum_rtmp_connections_active").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void rtmpAuthAndPayloadCounters() {
        metrics.incRtmpAuth("success");
        metrics.incRtmpAuth("failure");
        metrics.incRtmpAuth("failure");
        assertThat(registry.get("theatrum_rtmp_auth").tags("result", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("theatrum_rtmp_auth").tags("result", "failure").counter().count()).isEqualTo(2.0);

        metrics.addRtmpReceivedBytes("/user/{username}", "video", "live/alice", 1024);
        metrics.addRtmpReceivedBytes("/user/{username}", "video", "live/alice", 512);
        assertThat(registry.get("theatrum_rtmp_received_bytes")
                .tags("channel", "/user/{username}", "type", "video", "stream_path", "live/alice")
                .counter().count()).isEqualTo(1536.0);

        metrics.incRtmpReceivedFrames("/user/{username}", "audio", "live/alice");
        assertThat(registry.get("theatrum_rtmp_received_frames")
                .tags("channel", "/user/{username}", "type", "audio", "stream_path", "live/alice")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void liveStreamLifecycleMetrics() {
        metrics.incLiveStreamsActive();
        assertThat(registry.get("theatrum_live_streams_active").gauge().value()).isEqualTo(1.0);
        metrics.decLiveStreamsActive();
        assertThat(registry.get("theatrum_live_streams_active").gauge().value()).isEqualTo(0.0);

        metrics.observeStreamDuration("live/alice", 42.0);
        assertThat(registry.get("theatrum_stream_duration").tags("stream_path", "live/alice").timer().count())
                .isEqualTo(1);

        metrics.incFfmpegExits("success", "live/alice");
        assertThat(registry.get("theatrum_ffmpeg_exits")
                .tags("status", "success", "stream_path", "live/alice").counter().count()).isEqualTo(1.0);

        metrics.incRecordings("move", "success", "live/alice");
        assertThat(registry.get("theatrum_recordings")
                .tags("mode", "move", "status", "success", "stream_path", "live/alice").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void encodeMetrics() {
        metrics.setEncodeQueueDepth(5.0);
        assertThat(registry.get("theatrum_encode_queue_depth").gauge().value()).isEqualTo(5.0);
        metrics.setEncodeQueueDepth(2.0);
        assertThat(registry.get("theatrum_encode_queue_depth").gauge().value()).isEqualTo(2.0);

        metrics.incEncodeJobs("success");
        metrics.incEncodeJobs("failure");
        metrics.incEncodeJobs("success");
        assertThat(registry.get("theatrum_encode_jobs").tags("status", "success").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("theatrum_encode_jobs").tags("status", "failure").counter().count()).isEqualTo(1.0);

        metrics.observeEncodeJobDuration(1.5);
        metrics.observeEncodeJobDuration(3.0);
        var timer = registry.get("theatrum_encode_job_duration").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(4.5, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void channelsConfiguredIsSettablePerType() {
        metrics.setChannelsConfigured("live", 3);
        metrics.setChannelsConfigured("restream", 1);
        assertThat(registry.get("theatrum_channels_configured").tags("type", "live").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("theatrum_channels_configured").tags("type", "restream").gauge().value()).isEqualTo(1.0);

        metrics.setChannelsConfigured("live", 7);
        assertThat(registry.get("theatrum_channels_configured").tags("type", "live").gauge().value()).isEqualTo(7.0);
    }

    /**
     * The point of this slice: the Prometheus exposition must carry the exact metric names and
     * label keys the Go implementation exposed, so existing dashboards/alerts keep working.
     */
    @Test
    void prometheusExpositionUsesTheExactGoMetricNames() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var m = new Metrics(prometheus);

        m.incHttpRequests("200", "live", "ts");
        m.observeHttpRequestDuration("live", "ts", 0.25);
        m.addHttpResponseBytes("live", "ts", 100);
        m.incHttpRequestsInFlight();
        m.incRtmpConnections();
        m.incRtmpConnectionsActive();
        m.incRtmpAuth("success");
        m.addRtmpReceivedBytes("/user/{username}", "video", "live/alice", 10);
        m.incRtmpReceivedFrames("/user/{username}", "video", "live/alice");
        m.incLiveStreamsActive();
        m.observeStreamDuration("live/alice", 12.0);
        m.incFfmpegExits("success", "live/alice");
        m.incRecordings("move", "success", "live/alice");
        m.setEncodeQueueDepth(3);
        m.incEncodeJobs("success");
        m.observeEncodeJobDuration(2.0);
        m.setChannelsConfigured("live", 2);

        String scrape = prometheus.scrape();

        assertThat(scrape).contains(
                "theatrum_http_requests_total",
                "theatrum_http_request_duration_seconds_bucket",
                "theatrum_http_request_duration_seconds_count",
                "theatrum_http_response_bytes_total",
                "theatrum_http_requests_in_flight",
                "theatrum_rtmp_connections_total",
                "theatrum_rtmp_connections_active",
                "theatrum_rtmp_auth_total",
                "theatrum_rtmp_received_bytes_total",
                "theatrum_rtmp_received_frames_total",
                "theatrum_live_streams_active",
                "theatrum_stream_duration_seconds_bucket",
                "theatrum_ffmpeg_exits_total",
                "theatrum_recordings_total",
                "theatrum_encode_queue_depth",
                "theatrum_encode_jobs_total",
                "theatrum_encode_job_duration_seconds_bucket",
                "theatrum_channels_configured");

        // Go's explicit histogram buckets survive the Micrometer SLO mapping.
        assertThat(scrape).contains(
                "theatrum_stream_duration_seconds_bucket{stream_path=\"live/alice\",le=\"10.0\"}",
                "theatrum_stream_duration_seconds_bucket{stream_path=\"live/alice\",le=\"14400.0\"}",
                "theatrum_http_request_duration_seconds_bucket{file_type=\"ts\",stream_type=\"live\",le=\"0.005\"}",
                "theatrum_encode_job_duration_seconds_bucket{le=\"3600.0\"}");

        // No accidental double suffixing.
        assertThat(scrape).doesNotContain("theatrum_http_requests_total_total", "theatrum_stream_duration_seconds_seconds");

        // Exact label keys.
        assertThat(scrape)
                .contains("status_code=\"200\"")
                .contains("stream_type=\"live\"")
                .contains("file_type=\"ts\"")
                .contains("result=\"success\"")
                .contains("channel=\"/user/{username}\"")
                .contains("type=\"video\"")
                .contains("stream_path=\"live/alice\"")
                .contains("status=\"success\"")
                .contains("mode=\"move\"")
                .contains("theatrum_channels_configured{type=\"live\"}");
    }
}
