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
 * Port of Go's {@code restream_test.go}: channels that pull from an external source rather than
 * being pushed to. The source here is the test video on disk — any FFmpeg-readable URL works.
 * All four channels auto-start on boot, so one context covers every case.
 */
class RestreamTest {

    private static final String CHANNELS = """
              "/restream/mystream":
                stream:
                  type: restream
                  source_url: "%1$s"
                  path: "restream/mystream"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 5
              "/restream/premium":
                stream:
                  type: restream
                  source_url: "%1$s"
                  path: "restream/premium"
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
                      segment_duration: 2
                      window_size: 5
              "/restream/dash":
                stream:
                  type: restream
                  source_url: "%1$s"
                  path: "restream/dash"
                  distribution:
                    dash:
                      segment_duration: 2
                      window_size: 5
              "/restream/tracked":
                stream:
                  type: restream
                  source_url: "%1$s"
                  path: "restream/tracked"
                  distribution:
                    hls:
                      segment_duration: 2
                      window_size: 5
                  viewers:
                    enabled: true
                    window: 1
                  views:
                    enabled: true
                    window: 1
            """;

    @TempDir
    static Path tempDir;

    static TestServerSupport server;

    @BeforeAll
    static void startServer() {
        TestServerSupport.requireFfmpeg();
        server = TestServerSupport.start(tempDir, CHANNELS.formatted(TestServerSupport.testVideo()));
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    private Path restreamDir(String name) {
        return server.dataDir.resolve("restream").resolve(name);
    }

    @Test
    void hlsPassthroughServesAMasterAndSubPlaylist() {
        Path defaultDir = restreamDir("mystream").resolve(VideoConstants.DEFAULT_QUALITY);
        TestServerSupport.waitForFile(defaultDir.resolve(VideoConstants.SUB_PLAYLIST), Duration.ofSeconds(30));
        TestServerSupport.waitForFile(defaultDir.resolve("segment_000.ts"), Duration.ofSeconds(30));

        var master = server.waitForHttp("/restream/mystream/" + VideoConstants.MASTER_PLAYLIST,
                Duration.ofSeconds(20));
        assertThat(master.status()).isEqualTo(200);
        assertThat(TestServerSupport.parseM3u8(master.body()).master()).isTrue();

        String subUrl = "/restream/mystream/%s/%s".formatted(VideoConstants.DEFAULT_QUALITY,
                VideoConstants.SUB_PLAYLIST);
        var sub = server.get(subUrl);
        assertThat(sub.status()).isEqualTo(200);
        assertThat(TestServerSupport.parseM3u8(sub.body()).segmentCount()).isPositive();
        // A restream is treated as live for caching purposes.
        assertThat(sub.header("Cache-Control")).isEqualTo("no-cache, no-store, must-revalidate");
    }

    @Test
    void hlsMultiQualityServesAMasterPlaylistWithVariants() {
        TestServerSupport.waitForFile(restreamDir("premium").resolve(VideoConstants.MASTER_PLAYLIST),
                Duration.ofSeconds(40));
        TestServerSupport.waitForFile(restreamDir("premium").resolve("low").resolve(VideoConstants.SUB_PLAYLIST),
                Duration.ofSeconds(40));

        var master = server.waitForHttp("/restream/premium/" + VideoConstants.MASTER_PLAYLIST,
                Duration.ofSeconds(20));
        assertThat(master.status()).isEqualTo(200);

        var playlist = TestServerSupport.parseM3u8(master.body());
        assertThat(playlist.master()).isTrue();
        assertThat(playlist.streamPlaylists()).isNotEmpty();
    }

    @Test
    void dashPassthroughServesAManifest() {
        TestServerSupport.waitForFile(restreamDir("dash").resolve(VideoConstants.DASH_MANIFEST),
                Duration.ofSeconds(30));

        var response = server.waitForHttp("/restream/dash/" + VideoConstants.DASH_MANIFEST,
                Duration.ofSeconds(20));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("application/dash+xml");
    }

    @Test
    void exposesViewerAndViewCountersStartingAtZero() {
        TestServerSupport.waitForFile(
                restreamDir("tracked").resolve(VideoConstants.DEFAULT_QUALITY).resolve(VideoConstants.SUB_PLAYLIST),
                Duration.ofSeconds(30));

        var viewers = server.get("/restream/tracked/" + VideoConstants.VIEWERS_FILE);
        assertThat(viewers.status()).isEqualTo(200);
        assertThat(viewers.body()).isEqualTo("0");

        var views = server.get("/restream/tracked/" + VideoConstants.VIEWS_FILE);
        assertThat(views.status()).isEqualTo(200);
        assertThat(views.body()).isEqualTo("0");
    }
}
