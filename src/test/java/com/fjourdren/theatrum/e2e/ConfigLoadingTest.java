package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.application.port.out.exception.ConfigurationException;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.YamlConfigFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Port of Go's {@code config_loading_test.go} plus the config half of {@code dash_test.go}: the
 * real YAML adapter loading real files off disk. No server is started, exactly as in Go.
 */
class ConfigLoadingTest {

    @TempDir
    Path tempDir;

    private LoadedConfiguration load(String yaml) throws IOException {
        Path configPath = tempDir.resolve("config.yml");
        Files.writeString(configPath, yaml);
        return new YamlConfigFile().load(configPath);
    }

    private void assertRejected(String yaml) throws IOException {
        Path configPath = tempDir.resolve("config.yml");
        Files.writeString(configPath, yaml);
        assertThatThrownBy(() -> new YamlConfigFile().load(configPath))
                .isInstanceOf(ConfigurationException.class);
    }

    private static String withChannel(String channelBlock) {
        return """
                application:
                  public_path: "http://localhost:8080"
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
                %s
                """.formatted(channelBlock);
    }

    @Test
    void loadsAValidConfig() throws IOException {
        LoadedConfiguration configuration = load(withChannel("""
                  "/user/{username}":
                    stream:
                      type: live
                      path: "live/{username}"
                      live_stream_key: "test-secret"
                      auth_token_template: "{username}"
                      distribution:
                        hls:
                          segment_duration: 2
                          window_size: 3
                """));

        assertThat(configuration.application().publicPath()).isEqualTo("http://localhost:8080");
        assertThat(configuration.server().httpPort()).isEqualTo(8080);
        assertThat(configuration.server().rtmpPort()).isEqualTo(1935);
        assertThat(configuration.channels()).hasSize(1);
    }

    @Test
    void rejectsInvalidYaml() throws IOException {
        assertRejected("{{invalid yaml");
    }

    @Test
    void rejectsALiveChannelWithoutAuthTokenTemplate() throws IOException {
        assertRejected(withChannel("""
                  "/user/{username}":
                    stream:
                      type: live
                      path: "live/{username}"
                      live_stream_key: "test-secret"
                      distribution:
                        hls:
                          segment_duration: 2
                """));
    }

    @Test
    void rejectsAPathTraversalInAStreamPath() throws IOException {
        assertRejected(withChannel("""
                  "/user/{username}":
                    stream:
                      type: live
                      path: "../../../etc/passwd"
                      live_stream_key: "test-secret"
                      auth_token_template: "{username}"
                      distribution:
                        hls:
                          segment_duration: 2
                """));
    }

    @Test
    void loadsADashOnlyChannel() throws IOException {
        LoadedConfiguration configuration = load(withChannel("""
                  "/user/{username}":
                    stream:
                      type: live
                      path: "live/{username}"
                      live_stream_key: "secret"
                      auth_token_template: "{username}"
                      distribution:
                        dash:
                          segment_duration: 4
                          window_size: 5
                """));

        assertThat(configuration.application().publicPath()).isEqualTo("http://localhost:8080");
        assertThat(configuration.server().httpPort()).isEqualTo(8080);

        var distribution = configuration.channels().get("/user/{username}").distribution();
        assertThat(distribution.dashEnabled()).isTrue();
        assertThat(distribution.hlsEnabled()).isFalse();
    }

    @Test
    void loadsADualModeChannel() throws IOException {
        LoadedConfiguration configuration = load(withChannel("""
                  "/user/{username}":
                    stream:
                      type: live
                      path: "live/{username}"
                      live_stream_key: "secret"
                      auth_token_template: "{username}"
                      distribution:
                        hls:
                          segment_duration: 2
                          window_size: 3
                        dash:
                          segment_duration: 2
                          window_size: 3
                """));

        assertThat(configuration.channels().get("/user/{username}").distribution().isDualMode()).isTrue();
    }

    @Test
    void rejectsDualModeWithMismatchedWindowSize() throws IOException {
        assertRejected(withChannel("""
                  "/user/{username}":
                    stream:
                      type: live
                      path: "live/{username}"
                      live_stream_key: "secret"
                      auth_token_template: "{username}"
                      distribution:
                        hls:
                          segment_duration: 2
                          window_size: 3
                        dash:
                          segment_duration: 2
                          window_size: 5
                """));
    }

    @Test
    void rejectsDualModeWithMismatchedSegmentDuration() throws IOException {
        assertRejected(withChannel("""
                  "/user/{username}":
                    stream:
                      type: live
                      path: "live/{username}"
                      live_stream_key: "secret"
                      auth_token_template: "{username}"
                      distribution:
                        hls:
                          segment_duration: 2
                        dash:
                          segment_duration: 4
                """));
    }
}
