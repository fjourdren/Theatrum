package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.infrastructure.adapter.in.restream.RestreamLifecycle;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.RtmpLifecycle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole stack through the real {@link com.fjourdren.theatrum.TheatrumApplication} context —
 * where wiring bugs live. Go had no equivalent (its server was hand-wired in the harness), so this
 * covers the Spring-specific seams: {@code server.port} coming from the config file rather than
 * Spring's default, the {@code @Primary} AppPaths override reaching every collaborator, and the
 * frontend/metrics/stream/counter routes all answering from one context.
 */
class FullStackWiringTest {

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
              "/live/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "wiring-secret"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
                  viewers:
                    enabled: true
                    window: 1
                  views:
                    enabled: true
                    window: 0
            """;

    @TempDir
    static Path tempDir;

    static TestServerSupport server;

    @BeforeAll
    static void startServer() throws IOException {
        server = TestServerSupport.start(tempDir, CHANNELS);

        Path streamDir = server.dataDir.resolve("videos").resolve("alice");
        Files.createDirectories(streamDir);
        Files.writeString(streamDir.resolve(VideoConstants.MASTER_PLAYLIST), """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                low/playlist.m3u8
                """);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void bindsHttpToTheConfiguredPortNotSpringsDefault() {
        // server.port must come from the config file; 8080 is Spring's default and would be a bug.
        assertThat(server.get("/").status()).isEqualTo(200);
        assertThat(server.httpPort).isNotEqualTo(8080);
    }

    @Test
    void servesTheFrontendIndex() {
        var response = server.get("/");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("<html><body>test</body></html>");
    }

    @Test
    void servesPrometheusMetrics() {
        var response = server.get("/metrics");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("theatrum_");
    }

    @Test
    void servesAChannelsMasterPlaylist() {
        var response = server.get("/vod/alice/" + VideoConstants.MASTER_PLAYLIST);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("application/vnd.apple.mpegurl");
        assertThat(TestServerSupport.parseM3u8(response.body()).master()).isTrue();
    }

    @Test
    void servesViewerAndViewCounters() {
        assertThat(server.get("/live/alice/" + VideoConstants.VIEWERS_FILE).body()).isEqualTo("0");
        assertThat(server.get("/live/alice/" + VideoConstants.VIEWS_FILE).body()).isEqualTo("0");
    }

    @Test
    void answers404RatherThan500ForAMissingFile() {
        // The handler sets a media type before it knows the file exists; sendError would turn this
        // 404 into a 500 while Spring's error dispatch tried to serialise with that content type.
        var response = server.get("/vod/nobody/" + VideoConstants.MASTER_PLAYLIST);
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).contains("File not found");
    }

    @Test
    void listensOnTheConfiguredRtmpPort() {
        TestServerSupport.waitForTcp(server.rtmpPort, Duration.ofSeconds(5));
        assertThat(server.bean(RtmpLifecycle.class).getActiveStreams()).isEmpty();
    }

    @Test
    void injectsTheTemporaryAppPathsEverywhere() {
        assertThat(server.bean(AppPaths.class).videoDir()).isEqualTo(server.dataDir);
        assertThat(server.bean(AppPaths.class).frontendDir()).isEqualTo(server.frontendDir);
        assertThat(server.bean(RestreamLifecycle.class)).isNotNull();
    }
}
