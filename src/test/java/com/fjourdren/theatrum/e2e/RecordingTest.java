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
 * Port of Go's {@code recording_test.go}. Go used one server per case; both channels live in one
 * config here so the suite boots a single context.
 *
 * <p>Go's record-path case only logged when the recording had not landed yet; this polls with a
 * deadline instead, so the assertion actually holds the port to the behaviour.
 */
class RecordingTest {

    private static final String KEY = "record-secret";

    private static final String CHANNELS = """
              "/inplace/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
                  record:
                    enabled: true
              "/recorded/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
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

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }

    @Test
    void inPlaceRecordingLeavesAVodPlaylistInTheStreamPath() {
        String username = "inplace";
        Process publisher = server.publish("inplace/" + username, TestServerSupport.xorToken(KEY, username), 10);

        Path qualityDir = server.dataDir.resolve("live").resolve(username).resolve(VideoConstants.DEFAULT_QUALITY);
        TestServerSupport.waitForFile(qualityDir.resolve("segment_000.ts"), Duration.ofSeconds(20));

        TestServerSupport.kill(publisher);

        Path playlist = qualityDir.resolve(VideoConstants.SUB_PLAYLIST);
        TestServerSupport.waitUntil(Duration.ofSeconds(20), "the in-place VOD playlist to be finalized",
                () -> read(playlist).contains("#EXT-X-ENDLIST"));

        var vod = TestServerSupport.parseM3u8(read(playlist));
        assertThat(vod.vod()).isTrue();
        assertThat(vod.segmentCount()).isPositive();
    }

    @Test
    void recordingWithAPathMovesTheStreamToTheRecordDirectory() throws IOException {
        String username = "recorded";
        Process publisher = server.publish("recorded/" + username, TestServerSupport.xorToken(KEY, username), 10);

        Path qualityDir = server.dataDir.resolve("live").resolve(username).resolve(VideoConstants.DEFAULT_QUALITY);
        TestServerSupport.waitForFile(qualityDir.resolve("segment_000.ts"), Duration.ofSeconds(20));

        TestServerSupport.kill(publisher);

        Path recordDir = server.dataDir.resolve("recordings").resolve(username)
                .resolve(VideoConstants.DEFAULT_QUALITY);
        Path recordedPlaylist = recordDir.resolve(VideoConstants.SUB_PLAYLIST);
        TestServerSupport.waitUntil(Duration.ofSeconds(25), "the recording to be moved to " + recordDir,
                () -> read(recordedPlaylist).contains("#EXT-X-ENDLIST"));

        try (var entries = Files.list(recordDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .anyMatch(name -> name.endsWith(".ts"));
        }
        assertThat(TestServerSupport.parseM3u8(read(recordedPlaylist)).vod()).isTrue();
    }
}
