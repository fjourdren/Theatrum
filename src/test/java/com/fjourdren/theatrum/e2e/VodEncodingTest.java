package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.domain.service.EncodeJobQueue;
import com.fjourdren.theatrum.domain.service.EncodeService;
import com.fjourdren.theatrum.domain.service.PathTemplateService;
import com.fjourdren.theatrum.domain.service.VideoUnencodedDetector;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.YamlConfigFile;
import com.fjourdren.theatrum.infrastructure.adapter.out.encoder.FfmpegEncoder;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.EncodeMetricsAdapter;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import com.fjourdren.theatrum.infrastructure.adapter.out.persistence.FileAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Port of Go's {@code vod_encoding_test.go}: the detector finds a raw video, the queue encodes it
 * with a real FFmpeg, and the result is a playable VOD playlist. No server is involved, so the
 * components are wired by hand exactly as the Go test does.
 */
class VodEncodingTest {

    private static final String CONFIG = """
            application:
              public_path: "http://127.0.0.1:8080"
              all_streams_playlist:
                enabled: false
                path: ""

            server:
              http: 8080
              rtmp: 1935
              rtmp_config:
                reconnect_delay: 1
                cleanup_delay: 1

            channels:
              "/videos/{name}":
                stream:
                  type: video_unencoded
                  path: "encoded/{name}"
                  video_input_path: "raw/{name}"
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
                      segment_duration: 4
            """;

    @TempDir
    Path tempDir;

    private AppPaths appPaths;

    @BeforeEach
    void setUp() {
        TestServerSupport.requireFfmpeg();
        appPaths = new AppPaths(tempDir.resolve("data"), tempDir.resolve("frontend"));
    }

    @Test
    void detectsARawVideoAndEncodesItIntoAVodPlaylist() throws IOException {
        Path rawDir = appPaths.videoDir().resolve("raw").resolve("testvideo");
        Files.createDirectories(rawDir);
        Files.copy(TestServerSupport.testVideo(), rawDir.resolve("test.mp4"));

        Path configPath = tempDir.resolve("config.yml");
        Files.writeString(configPath, CONFIG);

        var configuration = new YamlConfigFile().load(configPath);
        var storage = new FileAccess();
        var templateService = new PathTemplateService();
        var appService = new ApplicationService(configuration, storage, templateService, appPaths);
        var metrics = new Metrics(new SimpleMeterRegistry());
        var encodeQueue = new EncodeJobQueue(new EncodeService(new FfmpegEncoder()), storage,
                new EncodeMetricsAdapter(metrics));
        var detector = new VideoUnencodedDetector(appService, encodeQueue, storage, templateService, appPaths);

        encodeQueue.start();
        try {
            detector.detectAndQueueVideos();

            Path lowDir = appPaths.videoDir().resolve("encoded").resolve("testvideo").resolve("low");
            Path playlist = lowDir.resolve(VideoConstants.SUB_PLAYLIST);
            TestServerSupport.waitUntil(Duration.ofSeconds(120), "the encoded playlist to be finalized",
                    () -> Files.isRegularFile(playlist) && read(playlist).contains("#EXT-X-ENDLIST"));

            var encoded = TestServerSupport.parseM3u8(read(playlist));
            assertThat(encoded.vod()).isTrue();
            assertThat(encoded.segmentCount()).isPositive();
            assertThat(encoded.segments()).allSatisfy(segment ->
                    assertThat(lowDir.resolve(segment)).isRegularFile());
        } finally {
            encodeQueue.stop();
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
