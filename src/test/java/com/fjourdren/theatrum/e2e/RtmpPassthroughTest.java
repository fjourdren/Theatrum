package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Port of Go's {@code rtmp_passthrough_test.go}: a real FFmpeg publisher pushing the test video
 * over RTMP, transmuxed to HLS with codec copy and served over HTTP.
 */
class RtmpPassthroughTest {

    private static final String KEY = "e2e-secret-key";

    private static final String CHANNELS = """
              "/user/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
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

    private Path defaultQualityDir(String username) {
        return server.dataDir.resolve("live").resolve(username).resolve(VideoConstants.DEFAULT_QUALITY);
    }

    @Test
    void transmuxesToALivePlaylistAndCleansUpWhenThePublisherLeaves() {
        String username = "alice";
        Process publisher = server.publish("user/" + username, TestServerSupport.xorToken(KEY, username), 12);

        // Passthrough streams land in {path}/default/.
        Path segment = defaultQualityDir(username).resolve("segment_000.ts");
        TestServerSupport.waitForFile(defaultQualityDir(username).resolve(VideoConstants.SUB_PLAYLIST),
                Duration.ofSeconds(20));
        TestServerSupport.waitForFile(segment, Duration.ofSeconds(20));

        String playlistUrl = "/user/%s/%s/%s".formatted(username, VideoConstants.DEFAULT_QUALITY,
                VideoConstants.SUB_PLAYLIST);
        server.waitForHttp(playlistUrl, Duration.ofSeconds(20));

        var response = server.get(playlistUrl);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache, no-store, must-revalidate");

        var playlist = TestServerSupport.parseM3u8(response.body());
        assertThat(playlist.vod()).as("a live playlist has no #EXT-X-ENDLIST").isFalse();
        assertThat(playlist.segmentCount()).isPositive();

        TestServerSupport.kill(publisher);

        // reconnect_delay (1s) then cleanup_delay (1s) before the files go away.
        TestServerSupport.waitForFileAbsent(segment, Duration.ofSeconds(20));
    }

    @Test
    void servesSegmentsOverHttp() throws Exception {
        String username = "bob";
        Process publisher = server.publish("user/" + username, TestServerSupport.xorToken(KEY, username), 10);

        Path segment = defaultQualityDir(username).resolve("segment_000.ts");
        TestServerSupport.waitForFile(segment, Duration.ofSeconds(20));

        String segmentUrl = "/user/%s/%s/segment_000.ts".formatted(username, VideoConstants.DEFAULT_QUALITY);
        var response = server.waitForHttp(segmentUrl, Duration.ofSeconds(15));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("video/mp2t");

        assertThat(java.nio.file.Files.size(segment)).isPositive();
        assertThat(server.getBytes(segmentUrl)).isNotEmpty();

        TestServerSupport.kill(publisher);
    }
}
