package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.handlers;

import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Dash;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Record;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.domain.service.LiveStreamRegistry;
import com.fjourdren.theatrum.domain.service.PathTemplateService;
import com.fjourdren.theatrum.domain.service.RtmpAuthService;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.config.RtmpConfig;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management.StreamManager;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management.StreamProcess;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TheatrumRtmpHandlerTest {

    private static final String PATTERN = "/user/{username}";
    private static final String TCURL = "rtmp://localhost/user/alice";
    private static final String KEY = "secret";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Metrics metrics = new Metrics(registry);
    private final PathTemplateService templateService = new PathTemplateService();
    private final LiveStreamRegistry liveStreamRegistry = new LiveStreamRegistry();
    private final StreamManager streamManager = mock(StreamManager.class);
    private final ByteArrayOutputStream ffmpegStdin = new ByteArrayOutputStream();
    private final StreamProcess streamProcess = mock(StreamProcess.class);

    @TempDir
    Path tmpDir;
    private AppPaths appPaths;

    @BeforeEach
    void setUp() throws IOException {
        appPaths = new AppPaths(tmpDir, tmpDir.resolve("frontend"));
        when(streamProcess.stdin()).thenReturn(ffmpegStdin);
        when(streamProcess.isActive()).thenReturn(true);
        when(streamProcess.inputPath()).thenReturn(TCURL);
        when(streamManager.getOrCreateStream(any(), any(), any(), any(), any())).thenReturn(streamProcess);
    }

    // ------------------------------------------------------------ fixtures

    private static Stream.Builder liveChannel(String path) {
        return Stream.builder()
                .type(StreamType.LIVE)
                .path(path)
                .liveStreamKey(KEY)
                .authTokenTemplate("{username}");
    }

    private TheatrumRtmpHandler handler(Stream channel, int reconnectDelay) {
        Map<String, Stream> channels = new LinkedHashMap<>();
        channels.put(PATTERN, channel);
        return new TheatrumRtmpHandler(
                new RtmpAuthService(channels, templateService), templateService, liveStreamRegistry,
                streamManager, new RtmpConfig(reconnectDelay, 1), metrics, appPaths);
    }

    private TheatrumRtmpHandler handler(Stream channel) {
        return handler(channel, 0);
    }

    /** The publishing token the client must send: hex(XOR(auth input, repeating live_stream_key)). */
    private static String token(String authInput) {
        byte[] data = authInput.getBytes(StandardCharsets.UTF_8);
        byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < data.length; i++) {
            data[i] ^= key[i % key.length];
        }
        return HexFormat.of().formatHex(data);
    }

    private static TheatrumRtmpHandler publish(TheatrumRtmpHandler handler) throws IOException {
        handler.onServe();
        handler.onConnect("user", TCURL, Map.of());
        handler.onPublish(token("alice"), "live");
        return handler;
    }

    private Path capturedOutputDir() throws IOException {
        ArgumentCaptor<Path> outputDir = ArgumentCaptor.forClass(Path.class);
        verify(streamManager).getOrCreateStream(eq(TCURL), outputDir.capture(), any(), any(), any());
        return outputDir.getValue();
    }

    private double counter(String name, String... tags) {
        var found = registry.find(name).tags(tags).counter();
        return found == null ? 0 : found.count();
    }

    // ----------------------------------------------------------- onConnect

    @Test
    void connectRefusesATcUrlThatMatchesNoChannel() {
        TheatrumRtmpHandler handler = handler(liveChannel("live/{username}").build());

        assertThatIOException().isThrownBy(() -> handler.onConnect("nope", "rtmp://localhost/nope/alice", Map.of()));

        assertThat(counter("theatrum_rtmp_auth", "result", "failure")).isEqualTo(1);
    }

    @Test
    void serveCountsTheConnection() {
        handler(liveChannel("live/{username}").build()).onServe();

        assertThat(counter("theatrum_rtmp_connections")).isEqualTo(1);
        assertThat(registry.get("theatrum_rtmp_connections_active").gauge().value()).isEqualTo(1);
    }

    // ----------------------------------------------------------- onPublish

    @Test
    void publishRefusesAnInvalidToken() throws IOException {
        TheatrumRtmpHandler handler = handler(liveChannel("live/{username}").build());
        handler.onConnect("user", TCURL, Map.of());

        assertThatIOException().isThrownBy(() -> handler.onPublish("deadbeef", "live"));

        assertThat(counter("theatrum_rtmp_auth", "result", "failure")).isEqualTo(1);
        verifyNoInteractions(streamManager);
    }

    @Test
    void publishWithoutAConnectIsRefused() {
        TheatrumRtmpHandler handler = handler(liveChannel("live/{username}").build());

        assertThatIOException().isThrownBy(() -> handler.onPublish(token("alice"), "live"));
    }

    @Test
    void hlsPassthroughWritesIntoTheDefaultSubdirectory() throws IOException {
        publish(handler(liveChannel("live/{username}").hls(new Hls(2, 3)).build()));

        assertThat(capturedOutputDir()).isEqualTo(tmpDir.resolve("live/alice/default"));
        assertThat(counter("theatrum_rtmp_auth", "result", "success")).isEqualTo(1);
        assertThat(registry.get("theatrum_live_streams_active").gauge().value()).isEqualTo(1);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> flatLayouts() {
        Quality low = new Quality(640, 360, 30, "800k", "libx264", new Audio("128k", "aac"));
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("hls multi-quality",
                        liveChannel("live/{username}").hls(new Hls(2, 3)).quality("low", low).build()),
                org.junit.jupiter.params.provider.Arguments.of("dash only",
                        liveChannel("live/{username}").dash(new Dash(2, 5)).build()),
                org.junit.jupiter.params.provider.Arguments.of("dual",
                        liveChannel("live/{username}").hls(new Hls(2, 5)).dash(new Dash(2, 5)).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flatLayouts")
    void flatLayoutHasNoQualitySubdirectory(String name, Stream channel) throws IOException {
        publish(handler(channel));

        assertThat(capturedOutputDir()).isEqualTo(tmpDir.resolve("live/alice"));
    }

    @Test
    void recordPathIsResolvedWithTheSameBuiltinsAsTheStreamPath() throws IOException {
        Stream channel = liveChannel("live/{username}/{%STARTING_DATE%}")
                .hls(new Hls(2, 3))
                .record(new Record(true, "rec/{username}/{%STARTING_DATE%}"))
                .build();

        publish(handler(channel));

        ArgumentCaptor<Path> outputDir = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<Path> recordDir = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<String> trackingKey = ArgumentCaptor.forClass(String.class);
        verify(streamManager).getOrCreateStream(eq(TCURL), outputDir.capture(), any(),
                recordDir.capture(), trackingKey.capture());

        String date = outputDir.getValue().getParent().getFileName().toString();
        assertThat(recordDir.getValue()).isEqualTo(tmpDir.resolve("rec/alice/" + date));
        assertThat(trackingKey.getValue()).isEqualTo("live/alice/" + date);
    }

    @Test
    void noRecordPathWhenRecordingIsInPlaceOrDisabled() throws IOException {
        publish(handler(liveChannel("live/{username}").hls(new Hls(2, 3)).record(new Record(true, "")).build()));

        verify(streamManager).getOrCreateStream(eq(TCURL), any(), any(), eq(null), eq("live/alice"));
    }

    @Test
    void reconnectionReusesTheBuiltinsAlreadyInTheRegistry() throws IOException {
        Stream channel = liveChannel("live/{username}/{%UUID%}").hls(new Hls(2, 3)).build();

        publish(handler(channel));
        Path first = capturedOutputDir();

        publish(handler(channel));
        ArgumentCaptor<Path> outputDir = ArgumentCaptor.forClass(Path.class);
        verify(streamManager, org.mockito.Mockito.times(2))
                .getOrCreateStream(eq(TCURL), outputDir.capture(), any(), any(), any());

        assertThat(outputDir.getAllValues()).containsExactly(first, first);
    }

    // -------------------------------------------------------------- onPlay

    @Test
    void playIsRefused() {
        assertThatIOException().isThrownBy(() -> handler(liveChannel("live/{username}").build()).onPlay("alice"));
    }

    // ------------------------------------------------------------ av frames

    @Test
    void audioAndVideoAreWrittenAsFlvAndCounted() throws IOException {
        TheatrumRtmpHandler handler = publish(handler(liveChannel("live/{username}").hls(new Hls(2, 3)).build()));

        handler.onSetDataFrame(0, new byte[] {1, 2});
        handler.onAudio(10, new byte[] {3, 4, 5});
        handler.onVideo(20, new byte[] {6, 7, 8, 9});

        assertThat(ffmpegStdin.toByteArray()).startsWith('F', 'L', 'V');
        // header (13) + script 2 + audio 3 + video 4 payloads, each with an 11-byte tag header
        // and a 4-byte PreviousTagSize.
        assertThat(ffmpegStdin.size()).isEqualTo(13 + (2 + 3 + 4) + 3 * (11 + 4));

        assertThat(counter("theatrum_rtmp_received_bytes",
                "channel", PATTERN, "type", "audio", "stream_path", "live/alice")).isEqualTo(3);
        assertThat(counter("theatrum_rtmp_received_frames",
                "channel", PATTERN, "type", "video", "stream_path", "live/alice")).isEqualTo(1);
    }

    @Test
    void framesBeforePublishAreDropped() throws IOException {
        TheatrumRtmpHandler handler = handler(liveChannel("live/{username}").build());

        handler.onSetDataFrame(0, new byte[] {1});
        handler.onAudio(0, new byte[] {2});
        handler.onVideo(0, new byte[] {3});

        assertThat(ffmpegStdin.size()).isZero();
    }

    // ------------------------------------------------------------- onClose

    @Test
    void closeStopsAStreamThatDidNotReconnect() throws Exception {
        TheatrumRtmpHandler handler = publish(handler(liveChannel("live/{username}/{%UUID%}").hls(new Hls(2, 3)).build()));

        handler.onClose();
        handler.cleanupTask.join();

        verify(streamProcess).stop(any(RtmpConfig.class));
        assertThat(registry.get("theatrum_live_streams_active").gauge().value()).isZero();
        assertThat(registry.get("theatrum_rtmp_connections_active").gauge().value()).isZero();
        assertThat(liveStreamRegistry.getBuiltinVars("live/alice/{%UUID%}")).isEmpty();
    }

    @Test
    void closeLeavesAReconnectedStreamRunning() throws Exception {
        TheatrumRtmpHandler handler = publish(handler(liveChannel("live/{username}").hls(new Hls(2, 3)).build()));
        when(streamProcess.isActive()).thenReturn(false);

        handler.onClose();
        handler.cleanupTask.join();

        verify(streamProcess, never()).stop(any());
        assertThat(registry.get("theatrum_live_streams_active").gauge().value()).isEqualTo(1);
    }

    @Test
    void closeWithoutAStreamOnlyDropsTheConnectionGauge() {
        TheatrumRtmpHandler handler = handler(liveChannel("live/{username}").build());
        handler.onServe();

        handler.onClose();

        assertThat(registry.get("theatrum_rtmp_connections_active").gauge().value()).isZero();
        assertThat(handler.cleanupTask).isNull();
    }
}
