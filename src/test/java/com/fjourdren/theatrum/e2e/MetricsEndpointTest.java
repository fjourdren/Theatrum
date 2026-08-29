package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Port of Go's {@code metrics_test.go}. Go asserted on {@code go_goroutines} because its E2E
 * harness used a throwaway registry while {@code /metrics} served the global one; the Java port
 * scrapes the same registry the stack writes to, so this asserts on real {@code theatrum_} series.
 */
class MetricsEndpointTest {

    private static final String CHANNELS = """
              "/user/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "test-key"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
            """;

    @TempDir
    static Path tempDir;

    static TestServerSupport server;

    @BeforeAll
    static void startServer() throws IOException {
        server = TestServerSupport.start(tempDir, CHANNELS);

        Path qualityDir = server.dataDir.resolve("live").resolve("testuser")
                .resolve(VideoConstants.DEFAULT_QUALITY);
        Files.createDirectories(qualityDir);
        Files.writeString(qualityDir.resolve(VideoConstants.SUB_PLAYLIST), "#EXTM3U\n");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void exposesTheatrumMetrics() {
        var response = server.get("/metrics");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("theatrum_channels_configured")
                .contains("theatrum_http_requests_in_flight");
    }

    @Test
    void keepsServingMetricsAfterStreamRequestsAndCountsThem() {
        for (int i = 0; i < 3; i++) {
            server.get("/user/testuser/%s/%s".formatted(VideoConstants.DEFAULT_QUALITY,
                    VideoConstants.SUB_PLAYLIST));
        }

        var response = server.get("/metrics");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("theatrum_http_requests_total");
    }
}
