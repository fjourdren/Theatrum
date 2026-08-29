package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of Go's {@code rtmp_multiquality_test.go}: live transcoding into a quality subdirectory. */
class RtmpMultiQualityTest {

    private static final String KEY = "multi-secret";

    private static final String CHANNELS = """
              "/premium/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%s"
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

    @Test
    void writesAMasterPlaylistAndAQualitySubdirectory() {
        String username = "multiquality";
        Process publisher = server.publish("premium/" + username, TestServerSupport.xorToken(KEY, username), 20);

        Path streamDir = server.dataDir.resolve("live").resolve(username);
        TestServerSupport.waitForFile(streamDir.resolve(VideoConstants.MASTER_PLAYLIST), Duration.ofSeconds(30));
        TestServerSupport.waitForFile(streamDir.resolve("low").resolve(VideoConstants.SUB_PLAYLIST),
                Duration.ofSeconds(30));
        TestServerSupport.waitForFile(streamDir.resolve("low").resolve("segment_000.ts"), Duration.ofSeconds(30));

        String masterUrl = "/premium/%s/%s".formatted(username, VideoConstants.MASTER_PLAYLIST);
        server.waitForHttp(masterUrl, Duration.ofSeconds(20));

        var master = server.get(masterUrl);
        assertThat(master.status()).isEqualTo(200);
        var masterPlaylist = TestServerSupport.parseM3u8(master.body());
        assertThat(masterPlaylist.master()).isTrue();
        assertThat(masterPlaylist.streamPlaylists()).isNotEmpty();

        var low = server.get("/premium/%s/low/%s".formatted(username, VideoConstants.SUB_PLAYLIST));
        assertThat(low.status()).isEqualTo(200);
        assertThat(TestServerSupport.parseM3u8(low.body()).segmentCount()).isPositive();

        TestServerSupport.kill(publisher);
    }
}
