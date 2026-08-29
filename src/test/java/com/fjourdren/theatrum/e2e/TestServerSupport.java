package com.fjourdren.theatrum.e2e;

import com.fjourdren.theatrum.TheatrumApplication;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.YamlConfigFile;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The E2E harness: a full Theatrum stack on free ports, backed by a temp directory.
 *
 * <p>Port of Go's {@code setupTestServer} / {@code helpers_test.go}. HTTP serving in the Java port
 * lives entirely in Spring MVC (the controller compiles the channel patterns itself), so the stack
 * is booted through the real {@link TheatrumApplication} context rather than hand-wired — which is
 * also where wiring bugs live. Everything else mirrors the Go harness: a generated {@code
 * config.yml}, an {@link AppPaths} pointing at a temp dir, and deadline polling instead of sleeps.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class TestServerSupport implements AutoCloseable {

    /** Go defaulted both delays to 1 second in tests; anything larger makes the suite crawl. */
    private static final int DEFAULT_RECONNECT_DELAY = 1;
    private static final int DEFAULT_CLEANUP_DELAY = 1;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ConfigurableApplicationContext context;
    private final List<Process> spawned = new ArrayList<>();

    final int httpPort;
    final int rtmpPort;
    final Path dataDir;
    final Path frontendDir;
    final Path configPath;

    // ---------------------------------------------------------------- lifecycle

    /** Starts a stack whose {@code channels:} block is {@code channelsYaml}. */
    static TestServerSupport start(Path tempDir, String channelsYaml) {
        int httpPort = freePort();
        int rtmpPort = freePort();

        Path dataDir = tempDir.resolve("data");
        Path frontendDir = tempDir.resolve("frontend");
        Path configPath = tempDir.resolve("config.yml");
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(frontendDir);
            Files.writeString(frontendDir.resolve("index.html"), "<html><body>test</body></html>");
            Files.writeString(configPath, config(channelsYaml, httpPort, rtmpPort));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        LoadedConfiguration configuration = new YamlConfigFile().load(configPath);

        // Mirrors TheatrumApplication.main: the config file is parsed before the context exists,
        // so its HTTP port becomes server.port and the parsed result is handed in as a singleton.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TheatrumApplication.class, TestPaths.class)
                .web(WebApplicationType.SERVLET)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .properties(Map.of(
                        "server.port", configuration.server().httpPort(),
                        "theatrum.test.video-dir", dataDir.toString(),
                        "theatrum.test.frontend-dir", frontendDir.toString()))
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("loadedConfiguration", configuration))
                .run();

        waitForTcp(httpPort, Duration.ofSeconds(10));
        waitForTcp(rtmpPort, Duration.ofSeconds(10));

        return new TestServerSupport(context, httpPort, rtmpPort, dataDir, frontendDir, configPath);
    }

    @Override
    public void close() {
        spawned.forEach(TestServerSupport::kill);
        spawned.clear();
        context.close();
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    /**
     * The AppPaths the whole stack must use. {@code BeanConfig} declares a
     * non-primary one pointing at the working directory, so {@code @Primary} here wins.
     */
    @Configuration
    static class TestPaths {
        @Bean
        @Primary
        AppPaths testAppPaths(@Value("${theatrum.test.video-dir}") String videoDir,
                              @Value("${theatrum.test.frontend-dir}") String frontendDir) {
            return new AppPaths(Path.of(videoDir), Path.of(frontendDir));
        }
    }

    private static String config(String channelsYaml, int httpPort, int rtmpPort) {
        return """
                application:
                  public_path: "http://127.0.0.1:%d"
                  all_streams_playlist:
                    enabled: false
                    path: ""

                server:
                  http: %d
                  rtmp: %d
                  rtmp_config:
                    reconnect_delay: %d
                    cleanup_delay: %d

                channels:
                %s
                """.formatted(httpPort, httpPort, rtmpPort,
                DEFAULT_RECONNECT_DELAY, DEFAULT_CLEANUP_DELAY, channelsYaml);
    }

    // ---------------------------------------------------------------- ffmpeg

    /** Go's {@code TestMain} skipped the whole suite when FFmpeg was missing. */
    static void requireFfmpeg() {
        Assumptions.assumeTrue(ffmpegAvailable(), "FFmpeg not found in PATH, skipping E2E tests");
    }

    static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The test video, resolved from the classpath rather than by walking up directories. */
    static Path testVideo() {
        var url = TestServerSupport.class.getResource("/testdata/test.mp4");
        if (url == null) {
            throw new IllegalStateException("test video not found on the classpath at /testdata/test.mp4");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Publishes the test video to {@code rtmp://127.0.0.1:<rtmpPort>/<appPath>/<publishName>}. */
    Process publish(String appPath, String publishName, int durationSeconds) {
        String url = "rtmp://127.0.0.1:%d/%s/%s".formatted(rtmpPort, appPath, publishName);
        return spawn(List.of("ffmpeg",
                "-re",
                "-stream_loop", "-1",
                "-t", String.valueOf(durationSeconds),
                "-i", testVideo().toString(),
                "-c", "copy",
                "-f", "flv",
                url));
    }

    private Process spawn(List<String> argv) {
        try {
            Process process = new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            spawned.add(process);
            return process;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start " + argv.getFirst(), e);
        }
    }

    static void kill(Process process) {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** XOR authentication token: {@code hex(authInput XOR liveStreamKey)}, key repeating. */
    static String xorToken(String liveStreamKey, String authInput) {
        byte[] key = liveStreamKey.getBytes(StandardCharsets.UTF_8);
        byte[] result = authInput.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < result.length; i++) {
            result[i] ^= key[i % key.length];
        }
        return HexFormat.of().formatHex(result);
    }

    // ---------------------------------------------------------------- polling

    static void waitForTcp(int port, Duration timeout) {
        waitUntil(timeout, "TCP 127.0.0.1:" + port, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return true;
            } catch (IOException e) {
                return false;
            }
        });
    }

    static void waitForFile(Path path, Duration timeout) {
        waitUntil(timeout, "file " + path, () -> Files.isRegularFile(path));
    }

    static void waitForFileAbsent(Path path, Duration timeout) {
        waitUntil(timeout, "removal of " + path, () -> !Files.exists(path));
    }

    /** Polls a GET until it answers 200, like Go's {@code waitForHTTP}. */
    Response waitForHttp(String path, Duration timeout) {
        var last = new Response[1];
        waitUntil(timeout, "HTTP 200 at " + path, () -> {
            try {
                last[0] = get(path);
                return last[0].status() == HttpURLConnection.HTTP_OK;
            } catch (RuntimeException e) {
                return false;
            }
        });
        return last[0];
    }

    /** Polls {@code condition} until it holds or the deadline passes — never a bare sleep. */
    static void waitUntil(Duration timeout, String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(100));
        }
        if (condition.getAsBoolean()) {
            return;
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for " + what);
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // ---------------------------------------------------------------- HTTP

    /** One HTTP response: status, body and headers, all already read. */
    record Response(int status, String body, HttpHeadersView headers) {
        String header(String name) {
            return headers.first(name);
        }
    }

    record HttpHeadersView(java.net.http.HttpHeaders raw) {
        String first(String name) {
            return raw.firstValue(name).orElse("");
        }
    }

    Response get(String path) {
        return get(path, Map.of());
    }

    Response get(String path, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET();
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), new HttpHeadersView(response.headers()));
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Byte-exact GET, for comparing segment payloads. */
    byte[] getBytes(String path) {
        try {
            return HTTP.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + httpPort + (path.startsWith("/") ? path : "/" + path));
    }

    // ---------------------------------------------------------------- M3U8

    /** Port of Go's {@code M3U8Info} / {@code parseM3U8}. */
    record M3u8(boolean vod, boolean master, List<String> segments, List<String> streamPlaylists) {
        int segmentCount() {
            return segments.size();
        }
    }

    static M3u8 parseM3u8(String content) {
        boolean vod = false;
        boolean master = false;
        var segments = new ArrayList<String>();
        var playlists = new ArrayList<String>();

        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.equals("#EXT-X-ENDLIST")) {
                vod = true;
            }
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                master = true;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.endsWith(".ts")) {
                segments.add(line);
            } else if (line.endsWith(".m3u8")) {
                playlists.add(line);
            }
        }
        return new M3u8(vod, master, List.copyOf(segments), List.copyOf(playlists));
    }

    // ---------------------------------------------------------------- misc

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to allocate a free port", e);
        }
    }
}
