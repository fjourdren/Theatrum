package com.fjourdren.theatrum.domain.constant;

import lombok.experimental.UtilityClass;

/**
 * The label values Theatrum publishes on its Prometheus metrics.
 *
 * <p>They live in the domain because the vocabulary crosses the hexagon: a domain service reports
 * an encode job as {@code success} through {@code EncodeMetricsPort} while the RTMP and restream
 * adapters report a recording with the same words. Spelling one of them differently in one place
 * silently splits a time series in two, which is exactly the kind of drift a shared constant stops.
 * The metric <em>names</em> stay in the metrics adapter — only the label values are shared.
 */
@UtilityClass
public final class MetricConstants {

    /** {@code status} / {@code result}: how a job, an auth attempt or a recording ended. */
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";

    /** {@code status} on {@code theatrum_ffmpeg_exits}. */
    public static final String EXIT_CLEAN = "clean";
    public static final String EXIT_ERROR = "error";
    public static final String EXIT_KILLED = "killed";

    /** {@code mode} on {@code theatrum_recordings}: files moved to the record dir, or left in place. */
    public static final String RECORD_MODE_MOVE = "move";
    public static final String RECORD_MODE_IN_PLACE = "in_place";

    /** {@code stream_type} on the HTTP metrics. */
    public static final String STREAM_TYPE_LIVE = "live";
    public static final String STREAM_TYPE_VOD = "vod";

    /** {@code file_type} on the HTTP metrics. */
    public static final String FILE_TYPE_PLAYLIST = "playlist";
    public static final String FILE_TYPE_SEGMENT = "segment";
    public static final String FILE_TYPE_THUMBNAIL = "thumbnail";
    public static final String FILE_TYPE_OTHER = "other";

    /** {@code type} on the RTMP frame and byte counters. */
    public static final String FRAME_TYPE_AUDIO = "audio";
    public static final String FRAME_TYPE_VIDEO = "video";

    /** {@code type} on {@code theatrum_channels_configured} for a channel with no type set. */
    public static final String CHANNEL_TYPE_UNKNOWN = "unknown";
}
