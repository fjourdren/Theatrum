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

/** Port of the HTTP-serving half of Go's {@code dash_test.go}. */
class HttpDashTest {

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
                    dash:
                      segment_duration: 4
            """;

    private static final byte[] INIT_SEGMENT = "fake-init-segment".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHUNK_SEGMENT = "fake-chunk-segment".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path tempDir;

    static TestServerSupport server;

    @BeforeAll
    static void startServer() throws IOException {
        server = TestServerSupport.start(tempDir, CHANNELS);

        Path streamDir = server.dataDir.resolve("videos").resolve("dashtest");
        Files.createDirectories(streamDir);
        Files.writeString(streamDir.resolve(VideoConstants.DASH_MANIFEST), """
                <?xml version="1.0" encoding="utf-8"?>
                <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
                  <Period>
                    <AdaptationSet mimeType="video/mp4">
                      <Representation id="0" bandwidth="800000" width="640" height="360">
                        <SegmentTemplate initialization="init-stream0.m4s" media="chunk-stream0-$Number%05d$.m4s"/>
                      </Representation>
                    </AdaptationSet>
                  </Period>
                </MPD>
                """);
        Files.write(streamDir.resolve("init-stream0.m4s"), INIT_SEGMENT);
        Files.write(streamDir.resolve("chunk-stream0-00001.m4s"), CHUNK_SEGMENT);

        Path cacheDir = server.dataDir.resolve("videos").resolve("cachetest");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve(VideoConstants.DASH_MANIFEST), "<?xml version=\"1.0\"?><MPD/>");
        Files.writeString(cacheDir.resolve("chunk-stream0-00001.m4s"), "segment");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void servesTheDashManifest() {
        var response = server.get("/vod/dashtest/" + VideoConstants.DASH_MANIFEST);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("MPD");
        assertThat(response.header("Content-Type")).isEqualTo("application/dash+xml");
    }

    @Test
    void servesTheInitSegment() {
        var response = server.get("/vod/dashtest/init-stream0.m4s");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("video/iso.segment");
        assertThat(server.getBytes("/vod/dashtest/init-stream0.m4s")).isEqualTo(INIT_SEGMENT);
    }

    @Test
    void servesAChunkSegment() {
        var response = server.get("/vod/dashtest/chunk-stream0-00001.m4s");
        assertThat(response.status()).isEqualTo(200);
        assertThat(server.getBytes("/vod/dashtest/chunk-stream0-00001.m4s")).isEqualTo(CHUNK_SEGMENT);
    }

    @Test
    void marksVodManifestsAndSegmentsAsCacheable() {
        assertThat(server.get("/vod/cachetest/" + VideoConstants.DASH_MANIFEST).header("Cache-Control"))
                .contains("public").contains("max-age=600");
        assertThat(server.get("/vod/cachetest/chunk-stream0-00001.m4s").header("Cache-Control"))
                .contains("public").contains("max-age=86400");
    }

    @Test
    void answers404ForAMissingManifest() {
        assertThat(server.get("/vod/nonexistent/" + VideoConstants.DASH_MANIFEST).status()).isEqualTo(404);
    }
}
