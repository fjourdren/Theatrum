package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AllStreamsPlaylist;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.domain.model.Thumbnail;
import com.fjourdren.theatrum.domain.model.Viewers;
import com.fjourdren.theatrum.domain.model.Views;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.domain.service.LiveStreamRegistry;
import com.fjourdren.theatrum.domain.service.PathTemplateService;
import com.fjourdren.theatrum.domain.service.StreamService;
import com.fjourdren.theatrum.domain.service.ViewerTracker;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Port of Go's {@code streamHandler_test.go}, plus the file-serving branches Go covered only
 * end to end and the status/byte capture cases from {@code metrics_test.go}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamRequestHandlerTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private ViewerTracker viewerTracker;

    private final LiveStreamRegistry registry = new LiveStreamRegistry();
    private final PathTemplateService templateService = new PathTemplateService();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Metrics metrics = new Metrics(meterRegistry);

    private AppPaths appPaths;
    private StreamRequestHandler handler;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        appPaths = new AppPaths(tempDir.resolve("data"), tempDir.resolve("frontend"));
        when(applicationService.getApplication())
                .thenReturn(new Application("*", new AllStreamsPlaylist(false, "")));

        handler = new StreamRequestHandler(new StreamService(templateService, appPaths), applicationService,
                templateService, registry, viewerTracker, metrics, appPaths);
        response = new MockHttpServletResponse();
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static Map<String, String> vars(String... keyValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Stream stream(StreamType type, String path) {
        return Stream.builder().type(type).path(path).build();
    }

    private Path writeVideoFile(String relativePath, String content) throws IOException {
        Path file = appPaths.videoDir().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void emptyResourceReturns404() throws IOException {
        handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", ""),
                get("/stream/"), response);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void slashResourceReturns404() throws IOException {
        handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "/"),
                get("/stream/"), response);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void setsCorsHeaders() throws IOException {
        handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "master.m3u8"),
                get("/stream/master.m3u8"), response);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET, OPTIONS");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualTo("Origin, Content-Type");
    }

    @Test
    void optionsReturns200() throws IOException {
        handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "master.m3u8"),
                new MockHttpServletRequest("OPTIONS", "/stream/master.m3u8"), response);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Cache headers are set before file serving, so they are observable even when the file does
     * not exist and the handler goes on to 404.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "LIVE,           playlist.m3u8,   'no-cache, no-store, must-revalidate'",
            "VIDEO_ENCODED,  playlist.m3u8,   'public, max-age=600'",
            "LIVE,           segment_000.ts,  'public, max-age=10'",
            "VIDEO_ENCODED,  segment_000.ts,  'public, max-age=86400'",
            "RESTREAM,       manifest.mpd,    'no-cache, no-store, must-revalidate'",
            "VIDEO_ENCODED,  chunk_0.m4s,     'public, max-age=86400'",
            "LIVE,           thumbnail.png,   'public, max-age=2'",
    })
    void cacheHeaders(StreamType type, String resource, String expectedCacheControl) throws IOException {
        Stream stream = Stream.builder().type(type).path("test")
                .thumbnail(new Thumbnail(true, 5)).build();

        handler.handle(stream, vars("resource", resource), get("/stream/" + resource), response);

        assertThat(response.getHeader("Cache-Control")).isEqualTo(expectedCacheControl);
    }

    @Test
    void unknownExtensionGetsNoCacheHeaders() throws IOException {
        handler.handle(stream(StreamType.VIDEO_ENCODED, "test"), vars("resource", "notes.txt"),
                get("/stream/notes.txt"), response);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache, no-store, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("Expires")).isEqualTo("0");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "playlist.m3u8,  application/vnd.apple.mpegurl",
            "segment_000.ts, video/mp2t",
            "manifest.mpd,   application/dash+xml",
            "chunk_0.m4s,    video/iso.segment",
            "thumbnail.png,  image/png",
    })
    void setsContentTypeByExtension(String resource, String expectedContentType) throws IOException {
        Stream stream = Stream.builder().type(StreamType.VIDEO_ENCODED).path("test")
                .thumbnail(new Thumbnail(true, 5)).build();
        // The file must exist: on a 404 the error reply overwrites Content-Type with text/plain,
        // exactly as Go's http.Error does, so the by-extension type is only observable on a hit.
        writeVideoFile("test/" + resource, "payload");

        handler.handle(stream, vars("resource", resource), get("/stream/" + resource), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(expectedContentType);
    }

    @Nested
    class ViewersEndpoint {

        @Test
        void disabledReturns404() throws IOException {
            Stream stream = Stream.builder().type(StreamType.LIVE).path("test")
                    .viewers(Viewers.disabled()).build();

            handler.handle(stream, vars("resource", VideoConstants.VIEWERS_FILE),
                    get("/stream/" + VideoConstants.VIEWERS_FILE), response);

            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void enabledReturnsTheCount() throws IOException {
            Stream stream = Stream.builder().type(StreamType.LIVE).path("live/{username}")
                    .viewers(new Viewers(true, 30)).build();
            when(viewerTracker.getViewerCount("live/alice")).thenReturn(42);

            handler.handle(stream, vars("username", "alice", "resource", VideoConstants.VIEWERS_FILE),
                    get("/live/alice/" + VideoConstants.VIEWERS_FILE), response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("42");
            assertThat(response.getContentType()).startsWith("text/plain");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache, no-store, must-revalidate");
        }
    }

    @Nested
    class ViewsEndpoint {

        @Test
        void disabledReturns404() throws IOException {
            Stream stream = Stream.builder().type(StreamType.LIVE).path("test")
                    .views(Views.disabled()).build();

            handler.handle(stream, vars("resource", VideoConstants.VIEWS_FILE),
                    get("/stream/" + VideoConstants.VIEWS_FILE), response);

            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void enabledReturnsTheCount() throws IOException {
            Stream stream = Stream.builder().type(StreamType.VIDEO_ENCODED).path("videos/{name}")
                    .views(new Views(true, 30)).build();
            when(viewerTracker.getViewCount("videos/clip")).thenReturn(7L);

            handler.handle(stream, vars("name", "clip", "resource", VideoConstants.VIEWS_FILE),
                    get("/videos/clip/" + VideoConstants.VIEWS_FILE), response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("7");
        }
    }

    @Test
    void thumbnailDisabledReturns404() throws IOException {
        Stream stream = Stream.builder().type(StreamType.LIVE).path("test")
                .thumbnail(Thumbnail.disabled()).build();

        handler.handle(stream, vars("resource", VideoConstants.THUMBNAIL_FILE),
                get("/stream/" + VideoConstants.THUMBNAIL_FILE), response);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Nested
    class TrackingKey {

        @Test
        void mergesBuiltinVarsFromTheRegistryForLiveStreams() throws IOException {
            Stream stream = Stream.builder()
                    .type(StreamType.LIVE)
                    .path("live/{username}/{%STARTING_DATE%}")
                    .viewers(new Viewers(true, 30))
                    .build();

            // The stream key is the path with user vars only, leaving the builtin unresolved:
            // the same formula the RTMP side registers under.
            registry.getOrRegister("live/alice/{%STARTING_DATE%}", Map.of("STARTING_DATE", "2026-02-14_12-30-45"));

            handler.handle(stream, vars("username", "alice", "resource", "segment_000.ts"),
                    get("/live/alice/segment_000.ts"), response);

            verify(viewerTracker).trackSegmentRequest(eq("live/alice/2026-02-14_12-30-45"), eq("127.0.0.1"),
                    eq(new Viewers(true, 30)), eq(Views.disabled()));
        }

        @Test
        void usesTheResolvedPathForNonLiveStreams() throws IOException {
            Stream stream = Stream.builder().type(StreamType.VIDEO_ENCODED).path("videos/{name}")
                    .views(new Views(true, 30)).build();

            handler.handle(stream, vars("name", "clip", "resource", "segment_000.ts"),
                    get("/videos/clip/segment_000.ts"), response);

            verify(viewerTracker).trackSegmentRequest(eq("videos/clip"), eq("127.0.0.1"),
                    eq(Viewers.disabled()), eq(new Views(true, 30)));
        }

        @Test
        void prefersTheForwardedClientIp() throws IOException {
            Stream stream = Stream.builder().type(StreamType.LIVE).path("live")
                    .viewers(new Viewers(true, 30)).build();
            MockHttpServletRequest request = get("/live/segment_000.ts");
            request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

            handler.handle(stream, vars("resource", "segment_000.ts"), request, response);

            verify(viewerTracker).trackSegmentRequest(eq("live"), eq("203.0.113.7"), any(), any());
        }

        @Test
        void doesNotTrackWhenBothCountersAreDisabled() throws IOException {
            handler.handle(stream(StreamType.LIVE, "live"), vars("resource", "segment_000.ts"),
                    get("/live/segment_000.ts"), response);

            verify(viewerTracker, never()).trackSegmentRequest(any(), any(), any(), any());
        }

        @Test
        void doesNotTrackNonSegmentResources() throws IOException {
            Stream stream = Stream.builder().type(StreamType.LIVE).path("live")
                    .viewers(new Viewers(true, 30)).build();

            handler.handle(stream, vars("resource", "master.m3u8"), get("/live/master.m3u8"), response);

            verify(viewerTracker, never()).trackSegmentRequest(any(), any(), any(), any());
        }
    }

    @Nested
    class FileServing {

        @Test
        void servesAnExistingFile() throws IOException {
            writeVideoFile("videos/test/master.m3u8", "#EXTM3U\n");

            handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "master.m3u8"),
                    get("/videos/test/master.m3u8"), response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("#EXTM3U\n");
        }

        @Test
        void servesFromTheQualitySubdirectoryForHlsStreams() throws IOException {
            writeVideoFile("live/alice/low/playlist.m3u8", "#EXTM3U\n");
            Stream stream = Stream.builder().type(StreamType.LIVE).path("live/{username}")
                    .hls(new Hls(2, 3)).build();

            handler.handle(stream, vars("username", "alice", "quality", "low", "resource", "playlist.m3u8"),
                    get("/live/alice/low/playlist.m3u8"), response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("#EXTM3U\n");
        }

        @Test
        void missingFileReturns404() throws IOException {
            handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "master.m3u8"),
                    get("/videos/test/master.m3u8"), response);

            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void unresolvableTemplateReturns400() throws IOException {
            handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/{name}"),
                    vars("name", "not a valid value!", "resource", "master.m3u8"),
                    get("/videos/x/master.m3u8"), response);

            assertThat(response.getStatus()).isEqualTo(400);
        }

        @Test
        void pathTraversalOutsideTheVideoDirectoryReturns400() throws IOException {
            Files.createDirectories(tempDir.resolve("data"));
            Files.writeString(tempDir.resolve("secret.txt"), "top secret");

            handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"),
                    vars("resource", "../../../secret.txt"),
                    get("/videos/test/../../../secret.txt"), response);

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).doesNotContain("top secret");
        }
    }

    /**
     * Port of Go's {@code metrics_test.go} ResponseWriter cases. Java has no wrapper: the servlet
     * response carries the status (200 by default) and the copy loop returns the byte count.
     */
    @Nested
    class StatusAndByteCapture {

        @Test
        void defaultStatusIs200() throws IOException {
            writeVideoFile("videos/test/master.m3u8", "#EXTM3U\n");

            handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "master.m3u8"),
                    get("/videos/test/master.m3u8"), response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(meterRegistry.get("theatrum_http_requests")
                    .tags("status_code", "200", "stream_type", "vod", "file_type", "playlist")
                    .counter().count()).isEqualTo(1.0);
        }

        @Test
        void anExplicitErrorStatusIsCaptured() throws IOException {
            handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "master.m3u8"),
                    get("/videos/test/master.m3u8"), response);

            assertThat(response.getStatus()).isEqualTo(404);
        }

        @Test
        void bytesWrittenAccumulateAcrossWrites() throws IOException {
            String content = "x".repeat(50_000); // larger than any single copy buffer
            writeVideoFile("videos/test/segment_000.ts", content);

            handler.handle(stream(StreamType.VIDEO_ENCODED, "videos/test"), vars("resource", "segment_000.ts"),
                    get("/videos/test/segment_000.ts"), response);

            assertThat(response.getContentAsByteArray()).hasSize(50_000);
            assertThat(meterRegistry.get("theatrum_http_response_bytes")
                    .tags("stream_type", "vod", "file_type", "segment")
                    .counter().count()).isEqualTo(50_000.0);
        }

        @Test
        void recordsRequestDuration() throws IOException {
            writeVideoFile("videos/test/thumbnail.png", "PNG");
            Stream stream = Stream.builder().type(StreamType.LIVE).path("videos/test")
                    .thumbnail(new Thumbnail(true, 5)).build();

            handler.handle(stream, vars("resource", "thumbnail.png"),
                    get("/videos/test/thumbnail.png"), response);

            assertThat(meterRegistry.get("theatrum_http_request_duration")
                    .tags("stream_type", "live", "file_type", "thumbnail")
                    .timer().count()).isEqualTo(1);
        }
    }

    @Test
    void missingPlaylistReplies404AsPlainTextNotAServerError() throws IOException {
        // Regression: the handler sets application/vnd.apple.mpegurl before it knows the file is
        // missing. Routing that 404 through HttpServletResponse.sendError handed the response to
        // Spring's error dispatch, which could not serialise its error attributes with that preset
        // content type and turned the 404 into a 500. Caught by booting the real server.
        handler.handle(stream(StreamType.LIVE, "live/{username}"),
                vars("username", "alice", "resource", VideoConstants.MASTER_PLAYLIST),
                get("/user/alice/master.m3u8"), response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString()).contains("File not found");
    }
}
