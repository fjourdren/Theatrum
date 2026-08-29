package com.fjourdren.theatrum.infrastructure.adapter.in.web;

import lombok.experimental.UtilityClass;

/**
 * Header names, media types and cache policies of the HTTP delivery surface.
 *
 * <p>Gathered here because the same values are set from several handlers and have to agree: a
 * player picks its parser from the {@code Content-Type}, and a live playlist served with the VOD
 * cache policy is served stale. The values match what the Go server sent, byte for byte.
 */
@UtilityClass
public final class HttpConstants {

    // --------------------------------------------------------------- Headers

    public static final String HEADER_CACHE_CONTROL = "Cache-Control";
    public static final String HEADER_PRAGMA = "Pragma";
    public static final String HEADER_EXPIRES = "Expires";
    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String HEADER_X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    public static final String HEADER_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    public static final String HEADER_ALLOW_METHODS = "Access-Control-Allow-Methods";
    public static final String HEADER_ALLOW_HEADERS = "Access-Control-Allow-Headers";

    // ---------------------------------------------------------- Header values

    public static final String CORS_ALLOWED_METHODS = "GET, OPTIONS";
    public static final String CORS_ALLOWED_HEADERS = "Origin, Content-Type";
    public static final String NOSNIFF = "nosniff";
    public static final String NO_CACHE = "no-cache";
    public static final String EXPIRES_IMMEDIATELY = "0";

    // -------------------------------------------------------- Cache policies

    /** Never cache: live playlists, viewer counters and anything unrecognised. */
    public static final String CACHE_NONE = "no-cache, no-store, must-revalidate";

    /** Ten minutes: VOD playlists and manifests, and the frontend HTML. */
    public static final String CACHE_TEN_MINUTES = "public, max-age=600";

    /** A day: VOD segments, and the frontend JS/CSS. */
    public static final String CACHE_ONE_DAY = "public, max-age=86400";

    /** A year: frontend images, which are replaced rather than edited. */
    public static final String CACHE_ONE_YEAR = "public, max-age=31536000";

    /** Ten seconds: a live segment outlives its playlist entry by about that much. */
    public static final String CACHE_LIVE_SEGMENT = "public, max-age=10";

    /** Two seconds: thumbnails are regenerated continuously while a stream runs. */
    public static final String CACHE_THUMBNAIL = "public, max-age=2";

    // ----------------------------------------------------------- Media types

    public static final String CONTENT_TYPE_HLS_PLAYLIST = "application/vnd.apple.mpegurl";

    /** What the all-streams playlist is served as; Go used this older spelling for that route only. */
    public static final String CONTENT_TYPE_M3U = "application/x-mpegURL";

    public static final String CONTENT_TYPE_MPEGTS = "video/mp2t";
    public static final String CONTENT_TYPE_DASH_MANIFEST = "application/dash+xml";
    public static final String CONTENT_TYPE_DASH_SEGMENT = "video/iso.segment";
    public static final String CONTENT_TYPE_PNG = "image/png";
    public static final String CONTENT_TYPE_TEXT = "text/plain";
    public static final String CONTENT_TYPE_TEXT_UTF8 = "text/plain; charset=utf-8";

    /** {@code http.Error} in Go wrote this exact spelling, without the space. */
    public static final String CONTENT_TYPE_ERROR = "text/plain;charset=utf-8";

    /** Prometheus text exposition format 0.0.4, as {@code promhttp.Handler()} served it. */
    public static final String CONTENT_TYPE_PROMETHEUS = "text/plain; version=0.0.4; charset=utf-8";

    // --------------------------------------------------------------- Messages

    public static final String NOT_FOUND_MESSAGE = "File not found";
    public static final String INVALID_PATH_MESSAGE = "Invalid path";

    // ----------------------------------------------------------------- Routes

    public static final String METRICS_PATH = "/metrics";
}
