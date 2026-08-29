package com.fjourdren.theatrum.infrastructure.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds all Prometheus metrics for Theatrum.
 *
 * <p>Names are declared without their {@code _total} / {@code _seconds} suffixes: Micrometer's
 * Prometheus naming convention appends those when scraping, so the exposition matches the names
 * the Go implementation published (e.g. {@code theatrum_http_requests_total}).
 */
@Component
public class Metrics {

    // prometheus.DefBuckets
    private static final double[] HTTP_DURATION_BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    private static final double[] STREAM_DURATION_BUCKETS = {10, 30, 60, 300, 600, 1800, 3600, 7200, 14400};
    private static final double[] ENCODE_JOB_DURATION_BUCKETS = {1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600};

    private final MeterRegistry registry;

    private final ConcurrentHashMap<List<String>, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<List<String>, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicReference<Double>> channelsConfigured = new ConcurrentHashMap<>();

    // Gauges need a strongly held reference; Micrometer only keeps a weak one.
    private final AtomicInteger httpRequestsInFlight = new AtomicInteger();
    private final AtomicInteger rtmpConnectionsActive = new AtomicInteger();
    private final AtomicInteger liveStreamsActive = new AtomicInteger();
    private final AtomicReference<Double> encodeQueueDepth = new AtomicReference<>(0.0);

    public Metrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("theatrum_http_requests_in_flight", httpRequestsInFlight, AtomicInteger::get)
                .description("Number of HTTP requests currently being served.")
                .register(registry);
        Gauge.builder("theatrum_rtmp_connections_active", rtmpConnectionsActive, AtomicInteger::get)
                .description("Number of currently active RTMP connections.")
                .register(registry);
        Gauge.builder("theatrum_live_streams_active", liveStreamsActive, AtomicInteger::get)
                .description("Number of currently active live streams.")
                .register(registry);
        Gauge.builder("theatrum_encode_queue_depth", encodeQueueDepth, AtomicReference::get)
                .description("Number of jobs currently in the encode queue.")
                .register(registry);
    }

    // ---------------------------------------------------------------- HTTP

    public void incHttpRequests(String statusCode, String streamType, String fileType) {
        counter("theatrum_http_requests", "Total number of HTTP requests served.",
                        "status_code", statusCode, "stream_type", streamType, "file_type", fileType)
                .increment();
    }

    public void observeHttpRequestDuration(String streamType, String fileType, double seconds) {
        timer("theatrum_http_request_duration", "Duration of HTTP requests in seconds.", HTTP_DURATION_BUCKETS,
                        "stream_type", streamType, "file_type", fileType)
                .record(toDuration(seconds));
    }

    public void addHttpResponseBytes(String streamType, String fileType, long bytes) {
        counter("theatrum_http_response_bytes", "Total bytes sent in HTTP responses.",
                        "stream_type", streamType, "file_type", fileType)
                .increment(bytes);
    }

    public void incHttpRequestsInFlight() {
        httpRequestsInFlight.incrementAndGet();
    }

    public void decHttpRequestsInFlight() {
        httpRequestsInFlight.decrementAndGet();
    }

    // ---------------------------------------------------------------- RTMP

    public void incRtmpConnections() {
        counter("theatrum_rtmp_connections", "Total number of RTMP connections.").increment();
    }

    public void incRtmpConnectionsActive() {
        rtmpConnectionsActive.incrementAndGet();
    }

    public void decRtmpConnectionsActive() {
        rtmpConnectionsActive.decrementAndGet();
    }

    public void incRtmpAuth(String result) {
        counter("theatrum_rtmp_auth", "Total number of RTMP authentication attempts.", "result", result).increment();
    }

    public void addRtmpReceivedBytes(String channel, String type, String streamPath, long bytes) {
        counter("theatrum_rtmp_received_bytes", "Total bytes received from RTMP streams.",
                        "channel", channel, "type", type, "stream_path", streamPath)
                .increment(bytes);
    }

    public void incRtmpReceivedFrames(String channel, String type, String streamPath) {
        counter("theatrum_rtmp_received_frames", "Total frames received from RTMP streams.",
                        "channel", channel, "type", type, "stream_path", streamPath)
                .increment();
    }

    // -------------------------------------------------------- Live streams

    public void incLiveStreamsActive() {
        liveStreamsActive.incrementAndGet();
    }

    public void decLiveStreamsActive() {
        liveStreamsActive.decrementAndGet();
    }

    public void observeStreamDuration(String streamPath, double seconds) {
        timer("theatrum_stream_duration", "Duration of live streams in seconds.", STREAM_DURATION_BUCKETS,
                        "stream_path", streamPath)
                .record(toDuration(seconds));
    }

    public void incFfmpegExits(String status, String streamPath) {
        counter("theatrum_ffmpeg_exits", "Total number of FFmpeg process exits.",
                        "status", status, "stream_path", streamPath)
                .increment();
    }

    public void incRecordings(String mode, String status, String streamPath) {
        counter("theatrum_recordings", "Total number of recording operations.",
                        "mode", mode, "status", status, "stream_path", streamPath)
                .increment();
    }

    // ------------------------------------------------------------ Encoding

    public void setEncodeQueueDepth(double depth) {
        encodeQueueDepth.set(depth);
    }

    public void incEncodeJobs(String status) {
        counter("theatrum_encode_jobs", "Total number of encode jobs processed.", "status", status).increment();
    }

    public void observeEncodeJobDuration(double seconds) {
        timer("theatrum_encode_job_duration", "Duration of encode jobs in seconds.", ENCODE_JOB_DURATION_BUCKETS)
                .record(toDuration(seconds));
    }

    // --------------------------------------------------------- Config info

    public void setChannelsConfigured(String type, double count) {
        channelsConfigured
                .computeIfAbsent(type, t -> {
                    var holder = new AtomicReference<>(0.0);
                    Gauge.builder("theatrum_channels_configured", holder, AtomicReference::get)
                            .description("Number of configured channels by type.")
                            .tag("type", t)
                            .register(registry);
                    return holder;
                })
                .set(count);
    }

    // -------------------------------------------------------------- Internals

    private Counter counter(String name, String description, String... tags) {
        return counters.computeIfAbsent(
                key(name, tags),
                k -> Counter.builder(name).description(description).tags(tags).register(registry));
    }

    private Timer timer(String name, String description, double[] buckets, String... tags) {
        return timers.computeIfAbsent(key(name, tags), k -> Timer.builder(name)
                .description(description)
                .serviceLevelObjectives(toDurations(buckets))
                .tags(tags)
                .register(registry));
    }

    /** Cache key: the metric name plus the tag values, in declaration order. */
    private static List<String> key(String name, String[] tags) {
        var parts = new String[1 + tags.length / 2];
        parts[0] = name;
        for (int i = 1, j = 1; i < tags.length; i += 2, j++) {
            parts[j] = tags[i];
        }
        return List.of(parts);
    }

    private static Duration toDuration(double seconds) {
        return Duration.ofNanos((long) (seconds * 1_000_000_000L));
    }

    private static Duration[] toDurations(double[] seconds) {
        var durations = new Duration[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            durations[i] = toDuration(seconds[i]);
        }
        return durations;
    }
}
