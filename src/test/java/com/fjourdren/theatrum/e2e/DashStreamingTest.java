package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
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
 * Port of the live-streaming and recording halves of Go's {@code dash_test.go}: DASH-only,
 * DASH multi-quality, dual mode and DASH recording, all driven by a real RTMP publisher.
 * Go booted one server per case; the five channels share one context here.
 */
class DashStreamingTest {

    private static final String KEY = "dash-e2e-secret";

    private static final String CHANNELS = """
              "/dash/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    dash:
                      segment_duration: 2
                      window_size: 3
              "/dashmulti/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  qualities:
                    low:
                      width: 320
                      height: 240
                      framerate: 15
                      bitrate: "200k"
                      codec: "libx264"
                      audio:
                        bitrate: "64k"
                        codec: "aac"
                  distribution:
                    dash:
                      segment_duration: 2
                      window_size: 3
              "/dual/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
                    dash:
                      segment_duration: 2
                      window_size: 3
              "/dashrec/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    dash:
                      segment_duration: 2
                      window_size: 3
                  record:
                    enabled: true
              "/dashmoved/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    dash:
                      segment_duration: 2
                      window_size: 3
                  record:
                    enabled: true
                    path: "recordings/{username}"
            """.formatted(KEY);

    @TempDir
    static Path tempDir;

    static TestServerSupport server;

    @BeforeAll
    static void startServer() {
        TestServerSupport.requireFfmpeg();
        server = TestServerSupport.start(tempDir, CHANNELS);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private Process publish(String channel, String username, int seconds) {
        return server.publish(channel + "/" + username, TestServerSupport.xorToken(KEY, username), seconds);
    }

    private Path streamDir(String username) {
        return server.dataDir.resolve("live").resolve(username);
    }

    @Test
    void dashPassthroughWritesAFlatManifestAndCleansUp() {
        String username = "dashalice";
        Process publisher = publish("dash", username, 12);

        // DASH passthrough has a flat layout at {path}/ — no default/ subdirectory.
        Path manifest = streamDir(username).resolve(VideoConstants.DASH_MANIFEST);
        TestServerSupport.waitForFile(manifest, Duration.ofSeconds(20));
        TestServerSupport.waitForFile(streamDir(username).resolve("init-stream0.m4s"), Duration.ofSeconds(20));

        String manifestUrl = "/dash/%s/%s".formatted(username, VideoConstants.DASH_MANIFEST);
        server.waitForHttp(manifestUrl, Duration.ofSeconds(20));

        var response = server.get(manifestUrl);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("MPD");
        assertThat(response.header("Content-Type")).isEqualTo("application/dash+xml");
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache, no-store, must-revalidate");

        TestServerSupport.kill(publisher);
        TestServerSupport.waitForFileAbsent(manifest, Duration.ofSeconds(20));
    }

    @Test
    void dashMultiQualityWritesAManifestAtTheStreamRoot() {
        String username = "dashmulti";
        Process publisher = publish("dashmulti", username, 20);

        TestServerSupport.waitForFile(streamDir(username).resolve(VideoConstants.DASH_MANIFEST),
                Duration.ofSeconds(30));

        String manifestUrl = "/dashmulti/%s/%s".formatted(username, VideoConstants.DASH_MANIFEST);
        server.waitForHttp(manifestUrl, Duration.ofSeconds(20));
        assertThat(server.get(manifestUrl).body()).contains("MPD");

        TestServerSupport.kill(publisher);
    }

    @Test
    void dualModeWritesBothAManifestAndAMasterPlaylist() {
        String username = "dualalice";
        Process publisher = publish("dual", username, 12);

        Path manifest = streamDir(username).resolve(VideoConstants.DASH_MANIFEST);
        TestServerSupport.waitForFile(manifest, Duration.ofSeconds(20));
        TestServerSupport.waitForFile(streamDir(username).resolve(VideoConstants.MASTER_PLAYLIST),
                Duration.ofSeconds(20));

        var dash = server.get("/dual/%s/%s".formatted(username, VideoConstants.DASH_MANIFEST));
        assertThat(dash.status()).isEqualTo(200);
        assertThat(dash.body()).contains("MPD");

        var hls = server.get("/dual/%s/%s".formatted(username, VideoConstants.MASTER_PLAYLIST));
        assertThat(hls.status()).isEqualTo(200);
        assertThat(hls.body()).contains("EXTM3U");

        TestServerSupport.kill(publisher);
        TestServerSupport.waitForFileAbsent(manifest, Duration.ofSeconds(20));
    }

    @Test
    void dashInPlaceRecordingKeepsTheManifestInTheStreamPath() {
        String username = "dashrec";
        Process publisher = publish("dashrec", username, 10);

        Path manifest = streamDir(username).resolve(VideoConstants.DASH_MANIFEST);
        TestServerSupport.waitForFile(manifest, Duration.ofSeconds(20));

        TestServerSupport.kill(publisher);

        // FFmpeg finalizes the MPD on a clean exit; nothing else is generated and nothing moves.
        TestServerSupport.sleep(Duration.ofSeconds(5));
        assertThat(manifest).exists();
        assertThat(read(manifest)).contains("MPD");
    }

    @Test
    void dashRecordingWithAPathMovesTheManifestAndSegments() throws IOException {
        String username = "dashmoved";
        Process publisher = publish("dashmoved", username, 10);

        TestServerSupport.waitForFile(streamDir(username).resolve(VideoConstants.DASH_MANIFEST),
                Duration.ofSeconds(20));

        TestServerSupport.kill(publisher);

        Path recordDir = server.dataDir.resolve("recordings").resolve(username);
        Path movedManifest = recordDir.resolve(VideoConstants.DASH_MANIFEST);
        TestServerSupport.waitForFile(movedManifest, Duration.ofSeconds(25));

        try (var entries = Files.list(recordDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .anyMatch(name -> name.endsWith(".m4s"));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }
}
