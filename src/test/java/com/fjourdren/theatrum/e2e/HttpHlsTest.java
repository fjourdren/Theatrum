package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of Go's {@code http_hls_test.go}: serving HLS files that already exist on disk. */
class HttpHlsTest {

    private static final String CHANNELS = """
              "/vod/{name}":
                stream:
                  type: video_encoded
                  path: "videos/{name}"
                  qualities:
                    low:
                      width: 640
                      height: 360
                      framerate: 24
                      bitrate: "800k"
                      codec: "libx264"
                      audio:
                        bitrate: "96k"
                        codec: "aac"
                  distribution:
                    hls:
                      segment_duration: 4
            """;

    private static final String MASTER = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/playlist.m3u8
            """;

    private static final byte[] SEGMENT = "fake-ts-segment-data".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path tempDir;

    static TestServerSupport server;

    @BeforeAll
    static void startServer() throws IOException {
        server = TestServerSupport.start(tempDir, CHANNELS);

        Path streamDir = server.dataDir.resolve("videos").resolve("testvideo");
        Path qualityDir = streamDir.resolve("low");
        Files.createDirectories(qualityDir);

        Files.writeString(streamDir.resolve(VideoConstants.MASTER_PLAYLIST), MASTER);
        Files.writeString(qualityDir.resolve(VideoConstants.SUB_PLAYLIST), """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:4
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:4.000,
                segment_000.ts
                #EXT-X-ENDLIST
                """);
        Files.write(qualityDir.resolve("segment_000.ts"), SEGMENT);

        Path corsDir = server.dataDir.resolve("videos").resolve("corstest");
        Files.createDirectories(corsDir);
        Files.writeString(corsDir.resolve(VideoConstants.MASTER_PLAYLIST), MASTER);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void servesAPreExistingMasterPlaylist() {
        var response = server.get("/vod/testvideo/" + VideoConstants.MASTER_PLAYLIST);
        assertThat(response.status()).isEqualTo(200);

        var playlist = TestServerSupport.parseM3u8(response.body());
        assertThat(playlist.master()).isTrue();
        assertThat(playlist.streamPlaylists()).containsExactly("low/playlist.m3u8");
    }

    @Test
    void servesAPreExistingSubPlaylist() {
        var response = server.get("/vod/testvideo/low/" + VideoConstants.SUB_PLAYLIST);
        assertThat(response.status()).isEqualTo(200);

        var playlist = TestServerSupport.parseM3u8(response.body());
        assertThat(playlist.vod()).isTrue();
        assertThat(playlist.segmentCount()).isEqualTo(1);
    }

    @Test
    void servesASegmentByteForByte() {
        assertThat(server.get("/vod/testvideo/low/segment_000.ts").status()).isEqualTo(200);
        assertThat(server.getBytes("/vod/testvideo/low/segment_000.ts")).isEqualTo(SEGMENT);
    }

    @Test
    void answers404ForAMissingStream() {
        assertThat(server.get("/vod/nonexistent/" + VideoConstants.MASTER_PLAYLIST).status()).isEqualTo(404);
    }

    @Test
    void setsCorsHeaders() {
        var response = server.get("/vod/corstest/" + VideoConstants.MASTER_PLAYLIST);
        assertThat(response.header("Access-Control-Allow-Origin")).isNotEmpty();
    }

    @Test
    void marksVodPlaylistsAndSegmentsAsCacheable() {
        assertThat(server.get("/vod/testvideo/" + VideoConstants.MASTER_PLAYLIST).header("Cache-Control"))
                .isEqualTo("public, max-age=600");
        assertThat(server.get("/vod/testvideo/low/segment_000.ts").header("Cache-Control"))
                .isEqualTo("public, max-age=86400");
    }

    @Test
    void rejectsPathTraversalOutsideTheVideoDirectory() {
        // Deliberate difference from Go, which relied on http.ServeFile: the Java handler rejects
        // any resolved path that escapes the video directory.
        var response = server.get("/vod/testvideo/low/..%2F..%2F..%2F..%2Fetc%2Fpasswd");
        assertThat(response.status()).isIn(400, 404);
    }
}
