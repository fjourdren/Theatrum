package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import com.fjourdren.theatrum.application.port.in.LiveStreamVarsUseCase;
import com.fjourdren.theatrum.application.port.in.PathTemplateUseCase;
import com.fjourdren.theatrum.application.port.in.ResolveChannelUseCase;
import com.fjourdren.theatrum.application.port.in.ServeStreamUseCase;
import com.fjourdren.theatrum.application.port.in.TrackViewerUseCase;
import com.fjourdren.theatrum.domain.constant.MetricConstants;
import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.HttpConstants;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves the HLS/DASH files, thumbnails and viewer counters of one channel. */
@RequiredArgsConstructor
@Slf4j
@Component
public class StreamRequestHandler {

    private final ServeStreamUseCase streamService;
    private final ResolveChannelUseCase applicationService;
    private final PathTemplateUseCase templateService;
    private final LiveStreamVarsUseCase registry;
    private final TrackViewerUseCase viewerTracker;
    private final Metrics metrics;
    private final AppPaths appPaths;

    /**
     * @param vars the channel's URL variables plus {@code quality} and {@code resource};
     *             mutated in place with the stream's builtin variables, as the Go handler did
     */
    public void handle(Stream stream, Map<String, String> vars, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        long start = System.nanoTime();
        String resource = vars.getOrDefault(TemplateConstants.VAR_NAME_RESOURCE, "");

        if (resource.isEmpty() || resource.equals("/")) {
            HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
            return;
        }

        // CORS headers for HLS streaming, using public_path from the config.
        response.setHeader(HttpConstants.HEADER_ALLOW_ORIGIN, applicationService.getApplication().publicPath());
        response.setHeader(HttpConstants.HEADER_ALLOW_METHODS, HttpConstants.CORS_ALLOWED_METHODS);
        response.setHeader(HttpConstants.HEADER_ALLOW_HEADERS, HttpConstants.CORS_ALLOWED_HEADERS);

        if ("OPTIONS".equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String ext = extension(resource);
        boolean isLive = stream.type() == StreamType.LIVE || stream.type() == StreamType.RESTREAM;

        response.setContentType(switch (ext) {
            case VideoConstants.EXT_HLS_PLAYLIST -> HttpConstants.CONTENT_TYPE_HLS_PLAYLIST;
            case VideoConstants.EXT_MPEGTS_SEGMENT -> HttpConstants.CONTENT_TYPE_MPEGTS;
            case VideoConstants.EXT_DASH_MANIFEST -> HttpConstants.CONTENT_TYPE_DASH_MANIFEST;
            case VideoConstants.EXT_DASH_SEGMENT -> HttpConstants.CONTENT_TYPE_DASH_SEGMENT;
            case VideoConstants.EXT_THUMBNAIL -> HttpConstants.CONTENT_TYPE_PNG;
            // Go sniffed the extension string itself, which always yields text/plain.
            default -> HttpConstants.CONTENT_TYPE_TEXT_UTF8;
        });

        switch (ext) {
            // Live playlists/manifests update every segment and must not be cached; VOD ones are stable.
            case VideoConstants.EXT_HLS_PLAYLIST, VideoConstants.EXT_DASH_MANIFEST ->
                    response.setHeader(HttpConstants.HEADER_CACHE_CONTROL,
                            isLive ? HttpConstants.CACHE_NONE : HttpConstants.CACHE_TEN_MINUTES);
            // Live segments are short-lived; VOD segments never change.
            case VideoConstants.EXT_MPEGTS_SEGMENT, VideoConstants.EXT_DASH_SEGMENT ->
                    response.setHeader(HttpConstants.HEADER_CACHE_CONTROL,
                            isLive ? HttpConstants.CACHE_LIVE_SEGMENT : HttpConstants.CACHE_ONE_DAY);
            // Thumbnails are regenerated periodically.
            case VideoConstants.EXT_THUMBNAIL ->
                    response.setHeader(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_THUMBNAIL);
            default -> {
                response.setHeader(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_NONE);
                response.setHeader(HttpConstants.HEADER_PRAGMA, HttpConstants.NO_CACHE);
                response.setHeader(HttpConstants.HEADER_EXPIRES, HttpConstants.EXPIRES_IMMEDIATELY);
            }
        }

        // For live and restream streams the builtin variables were resolved once at publish time,
        // so look them up under the same stream key the RTMP side registered them under (user
        // variables only). A stream that is offline leaves them unresolved: the file will not
        // exist and the request 404s.
        if (isLive) {
            registry.getBuiltinVars(resolveTemplate(stream.path(), vars, "stream key")).ifPresent(vars::putAll);
        }
        String trackingKey = resolveTemplate(stream.path(), vars, "tracking key");

        if (resource.equals(VideoConstants.VIEWERS_FILE)) {
            if (!stream.viewers().enabled()) {
                HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
                return;
            }
            writeCounter(response, viewerTracker.getViewerCount(trackingKey));
            return;
        }

        if (resource.equals(VideoConstants.VIEWS_FILE)) {
            if (!stream.views().enabled()) {
                HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
                return;
            }
            writeCounter(response, viewerTracker.getViewCount(trackingKey));
            return;
        }

        if (resource.equals(VideoConstants.THUMBNAIL_FILE) && !stream.thumbnail().enabled()) {
            HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
            return;
        }

        if ((ext.equals(VideoConstants.EXT_MPEGTS_SEGMENT) || ext.equals(VideoConstants.EXT_DASH_SEGMENT))
                && (stream.viewers().enabled() || stream.views().enabled())) {
            String clientIp = ClientIp.from(
                    request.getHeader(HttpConstants.HEADER_X_FORWARDED_FOR), request.getRemoteAddr());
            viewerTracker.trackSegmentRequest(trackingKey, clientIp, stream.viewers(), stream.views());
        }

        Path storagePath;
        try {
            storagePath = streamService.getStreamStoragePath(stream, vars);
        } catch (RuntimeException e) {
            log.error("Error getting stream storage path: {}", e.getMessage());
            HttpErrors.send(response, HttpServletResponse.SC_BAD_REQUEST, HttpConstants.INVALID_PATH_MESSAGE);
            return;
        }

        // The resource comes straight from the URL: keep it inside the video directory.
        Path videoDir = appPaths.videoDir().toAbsolutePath().normalize();
        Path file = storagePath.resolve(resource).toAbsolutePath().normalize();
        if (!file.startsWith(videoDir)) {
            log.error("Rejected path outside the video directory: {}", file);
            HttpErrors.send(response, HttpServletResponse.SC_BAD_REQUEST, HttpConstants.INVALID_PATH_MESSAGE);
            return;
        }

        if (!Files.isRegularFile(file)) {
            log.info("File not found: {}", file);
            HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
            return;
        }

        String streamType = isLive ? MetricConstants.STREAM_TYPE_LIVE : MetricConstants.STREAM_TYPE_VOD;
        String fileType = switch (ext) {
            case VideoConstants.EXT_HLS_PLAYLIST, VideoConstants.EXT_DASH_MANIFEST ->
                    MetricConstants.FILE_TYPE_PLAYLIST;
            case VideoConstants.EXT_MPEGTS_SEGMENT, VideoConstants.EXT_DASH_SEGMENT ->
                    MetricConstants.FILE_TYPE_SEGMENT;
            case VideoConstants.EXT_THUMBNAIL -> MetricConstants.FILE_TYPE_THUMBNAIL;
            default -> MetricConstants.FILE_TYPE_OTHER;
        };

        response.setContentLengthLong(Files.size(file));
        long bytesWritten = Files.copy(file, response.getOutputStream());

        metrics.observeHttpRequestDuration(streamType, fileType, (System.nanoTime() - start) / 1e9);
        metrics.incHttpRequests(String.valueOf(response.getStatus()), streamType, fileType);
        metrics.addHttpResponseBytes(streamType, fileType, bytesWritten);
    }

    /** Resolves a path template, logging (but not failing on) template errors as the Go handler did. */
    private String resolveTemplate(String template, Map<String, String> vars, String what) {
        try {
            return templateService.replacePlaceholders(template, vars);
        } catch (RuntimeException e) {
            log.error("Error resolving {} template: {}", what, e.getMessage());
            return "";
        }
    }

    private static void writeCounter(HttpServletResponse response, long count) throws IOException {
        response.setContentType(HttpConstants.CONTENT_TYPE_TEXT);
        response.setHeader(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_NONE);
        response.getWriter().print(count);
    }

    /** Port of Go's {@code filepath.Ext}: the final dot and everything after it, or an empty string. */
    private static String extension(String resource) {
        int dot = resource.lastIndexOf('.');
        int slash = resource.lastIndexOf('/');
        return dot > slash ? resource.substring(dot) : "";
    }
}
