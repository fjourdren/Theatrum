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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Port of Go's {@code viewer_tracking_test.go}: viewer and view counters driven by real segment
 * requests over HTTP. Go used one server per case; the four channels share one context here.
 */
class ViewerTrackingTest {

    private static final String KEY = "viewer-secret";

    private static final String TRACKING = """
                  viewers:
                    enabled: true
                    window: 1
                  views:
                    enabled: true
                    window: 0
            """;

    private static final String CHANNELS = """
              "/tracked/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
            %2$s
              "/notrack/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
              "/persist/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
            %2$s
                  record:
                    enabled: true
              "/multiview/{username}":
                stream:
                  type: live
                  path: "live/{username}"
                  live_stream_key: "%1$s"
                  auth_token_template: "{username}"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 3
            %2$s
            """.formatted(KEY, TRACKING);

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

    private Process publishAndWaitForFirstSegment(String channel, String username, int seconds) {
        Process publisher = server.publish(channel + "/" + username, TestServerSupport.xorToken(KEY, username), seconds);
        TestServerSupport.waitForFile(server.dataDir.resolve("live").resolve(username)
                .resolve(VideoConstants.DEFAULT_QUALITY).resolve("segment_000.ts"), Duration.ofSeconds(20));
        return publisher;
    }

    private static String segmentUrl(String channel, String username) {
        return "/%s/%s/%s/segment_000.ts".formatted(channel, username, VideoConstants.DEFAULT_QUALITY);
    }

    private void requestAs(String url, String clientIp) {
        assertThat(server.get(url, Map.of("X-Forwarded-For", clientIp)).status()).isEqualTo(200);
    }

    private long counter(String channel, String username, String file) {
        var response = server.get("/%s/%s/%s".formatted(channel, username, file));
        assertThat(response.status()).isEqualTo(200);
        return Long.parseLong(response.body().strip());
    }

    @Test
    void countsViewersOnlyAfterTheDelayedWindowAndCountsViewsImmediately() {
        String username = "tracked";
        Process publisher = publishAndWaitForFirstSegment("tracked", username, 20);
        String url = segmentUrl("tracked", username);

        // Four rounds 500ms apart: each session stays alive (< the 1s window) while its start
        // drifts past 1s ago, which is what makes a viewer countable.
        List<String> ips = List.of("10.0.0.1", "10.0.0.2", "10.0.0.3");
        for (int round = 0; round < 4; round++) {
            ips.forEach(ip -> requestAs(url, ip));
            if (round < 3) {
                TestServerSupport.sleep(Duration.ofMillis(500));
            }
        }

        assertThat(counter("tracked", username, VideoConstants.VIEWERS_FILE)).isGreaterThanOrEqualTo(1);
        // views window is 0, so every new client counts on its first request.
        assertThat(counter("tracked", username, VideoConstants.VIEWS_FILE)).isGreaterThanOrEqualTo(1);

        TestServerSupport.kill(publisher);
    }

    @Test
    void answers404WhenTrackingIsDisabled() {
        String username = "notrack";
        Process publisher = publishAndWaitForFirstSegment("notrack", username, 10);

        assertThat(server.get("/notrack/%s/%s".formatted(username, VideoConstants.VIEWERS_FILE)).status())
                .isEqualTo(404);
        assertThat(server.get("/notrack/%s/%s".formatted(username, VideoConstants.VIEWS_FILE)).status())
                .isEqualTo(404);

        TestServerSupport.kill(publisher);
    }

    @Test
    void persistsTheViewCountToDiskWhenTheStreamEnds() {
        String username = "persistuser";
        Process publisher = publishAndWaitForFirstSegment("persist", username, 15);
        String url = segmentUrl("persist", username);

        requestAs(url, "10.0.1.1");
        requestAs(url, "10.0.1.2");
        assertThat(counter("persist", username, VideoConstants.VIEWS_FILE)).isGreaterThanOrEqualTo(1);

        TestServerSupport.kill(publisher);

        // record.enabled with no path keeps everything, views.txt included, in the stream path.
        Path viewsFile = server.dataDir.resolve("live").resolve(username).resolve(VideoConstants.VIEWS_FILE);
        TestServerSupport.waitForFile(viewsFile, Duration.ofSeconds(20));
        assertThat(readLong(viewsFile)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void countsOneViewPerDistinctClient() {
        String username = "multiview";
        Process publisher = publishAndWaitForFirstSegment("multiview", username, 15);
        String url = segmentUrl("multiview", username);

        List<String> ips = List.of("192.168.1.1", "192.168.1.2", "192.168.1.3", "192.168.1.4", "192.168.1.5");
        ips.forEach(ip -> requestAs(url, ip));

        assertThat(counter("multiview", username, VideoConstants.VIEWS_FILE))
                .isGreaterThanOrEqualTo(ips.size());

        TestServerSupport.kill(publisher);
    }

    private static long readLong(Path path) {
        try {
            return Long.parseLong(Files.readString(path).strip());
        } catch (IOException | NumberFormatException e) {
            throw new AssertionError("could not read a view count from " + path, e);
        }
    }
}
