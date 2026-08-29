package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of Go's {@code rtmp_auth_test.go}: XOR token authentication end to end. */
class RtmpAuthTest {

    private static final String KEY = "auth-test-secret";

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

    @Test
    void tokenIsTheHexOfTheAuthInputXoredWithTheKey() {
        // Worked example, verified against the running server.
        assertThat(TestServerSupport.xorToken("secret", "alice")).isEqualTo("12090a1100");
    }

    @Test
    void acceptsAValidToken() {
        String username = "validuser";
        Process publisher = server.publish("user/" + username, TestServerSupport.xorToken(KEY, username), 10);

        Path playlist = server.dataDir.resolve("live").resolve(username)
                .resolve(VideoConstants.DEFAULT_QUALITY).resolve(VideoConstants.SUB_PLAYLIST);
        TestServerSupport.waitForFile(playlist, Duration.ofSeconds(20));

        TestServerSupport.kill(publisher);
    }

    @Test
    void rejectsAnInvalidToken() throws Exception {
        String username = "baduser";
        Process publisher = server.publish("user/" + username, "invalid-token", 5);

        // FFmpeg is dropped at publish time, so it exits well before its -t duration.
        assertThat(publisher.waitFor(15, TimeUnit.SECONDS)).as("FFmpeg should be rejected").isTrue();

        assertThat(server.dataDir.resolve("live").resolve(username)
                .resolve(VideoConstants.DEFAULT_QUALITY).resolve(VideoConstants.SUB_PLAYLIST))
                .doesNotExist();
    }

    @Test
    void rejectsAnUnknownChannel() throws Exception {
        Process publisher = server.publish("nonexistent/channel", "anytoken", 5);

        assertThat(publisher.waitFor(15, TimeUnit.SECONDS)).as("FFmpeg should be rejected").isTrue();
        assertThat(Files.exists(server.dataDir.resolve("nonexistent"))).isFalse();
    }
}
