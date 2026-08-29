package com.fjourdren.theatrum.infrastructure.adapter.in.restream;

import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Dash;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Record;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.domain.service.LiveStreamRegistry;
import com.fjourdren.theatrum.domain.service.PathTemplateService;
import com.fjourdren.theatrum.domain.service.ViewerTracker;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import com.fjourdren.theatrum.infrastructure.ffmpeg.OutputMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestreamManagerTest {

    private static final String SOURCE = "rtmp://origin/live/key";

    @TempDir
    Path videoDir;

    private ApplicationService appService;
    private LiveStreamRegistry registry;
    private ViewerTracker viewerTracker;
    private Metrics metrics;
    private final PathTemplateService templateService = new PathTemplateService();

    @BeforeEach
    void setUp() {
        appService = mock(ApplicationService.class);
        registry = new LiveStreamRegistry();
        viewerTracker = mock(ViewerTracker.class);
        metrics = mock(Metrics.class);
    }

    private RestreamManager managerFor(Map<String, Stream> channels) {
        return managerFor(channels, argv -> {
            throw new IOException("no process expected");
        });
    }

    private RestreamManager managerFor(Map<String, Stream> channels, RestreamManager.ProcessLauncher launcher) {
        when(appService.getChannels()).thenReturn(channels);
        return new RestreamManager(appService, templateService, registry, viewerTracker, metrics,
                new AppPaths(videoDir, videoDir.resolve("frontend")), launcher);
    }

    private static Stream.Builder restream(String path) {
        return Stream.builder()
                .type(StreamType.RESTREAM)
                .sourceUrl(SOURCE)
                .path(path)
                .hls(new Hls(2, 3));
    }

    private static Map<String, Quality> qualities() {
        var q = new LinkedHashMap<String, Quality>();
        q.put("low", new Quality(640, 360, 30, "800k", "h264", new Audio("aac", "128k")));
        return q;
    }

    @Nested
    class Planning {

        @Test
        void ignoresChannelsThatAreNotRestreams() {
            var channels = new LinkedHashMap<String, Stream>();
            channels.put("/live/{username}", Stream.builder().type(StreamType.LIVE)
                    .path("live/{username}").hls(new Hls(2, 3)).build());
            channels.put("/vod", Stream.builder().type(StreamType.VIDEO_ENCODED).path("vod").build());
            channels.put("/restream/one", restream("restream/one").build());

            assertThat(managerFor(channels).plan())
                    .extracting(RestreamManager.ChannelPlan::channelName)
                    .containsExactly("/restream/one");
        }

        @Test
        void hlsPassthroughWritesIntoDefaultSubdir() {
            var plan = onlyPlan(restream("restream/one").build());

            assertThat(plan.outputDir()).isEqualTo(videoDir.resolve("restream/one").resolve(VideoConstants.DEFAULT_QUALITY));
            assertThat(plan.streamRootDir()).isEqualTo(videoDir.resolve("restream/one"));
            assertThat(plan.outputMode()).isEqualTo(OutputMode.HLS);
        }

        @Test
        void hlsMultiQualityWritesIntoStreamRoot() {
            var plan = onlyPlan(restream("restream/one").qualities(qualities()).build());

            assertThat(plan.outputDir()).isEqualTo(videoDir.resolve("restream/one"));
            assertThat(plan.streamRootDir()).isEqualTo(plan.outputDir());
            assertThat(plan.outputMode()).isEqualTo(OutputMode.HLS);
        }

        @Test
        void dashOnlyWritesIntoStreamRoot() {
            var stream = Stream.builder().type(StreamType.RESTREAM).sourceUrl(SOURCE)
                    .path("restream/one").dash(new Dash(2, 5)).build();
            var plan = onlyPlan(stream);

            assertThat(plan.outputDir()).isEqualTo(videoDir.resolve("restream/one"));
            assertThat(plan.streamRootDir()).isEqualTo(plan.outputDir());
            assertThat(plan.outputMode()).isEqualTo(OutputMode.DASH);
        }

        @Test
        void dualModeWritesIntoStreamRoot() {
            var plan = onlyPlan(restream("restream/one").dash(new Dash(2, 3)).build());

            assertThat(plan.outputDir()).isEqualTo(videoDir.resolve("restream/one"));
            assertThat(plan.streamRootDir()).isEqualTo(plan.outputDir());
            assertThat(plan.outputMode()).isEqualTo(OutputMode.DUAL);
        }

        @Test
        void registersBuiltinVarsUnderTheRawPathTemplateAndReusesThemForTheRecordPath() {
            var stream = restream("restream/{%UUID%}")
                    .record(new Record(true, "recordings/{%UUID%}"))
                    .build();
            var plan = onlyPlan(stream);

            assertThat(plan.streamKey()).isEqualTo("restream/{%UUID%}");
            var uuid = registry.getBuiltinVars("restream/{%UUID%}").orElseThrow()
                    .get(TemplateConstants.FUNC_NAME_UUID);
            assertThat(uuid).isNotBlank();
            assertThat(plan.trackingKey()).isEqualTo("restream/" + uuid);
            assertThat(plan.streamRootDir()).isEqualTo(videoDir.resolve("restream/" + uuid));
            assertThat(plan.recordDir()).isEqualTo(videoDir.resolve("recordings/" + uuid));
        }

        @Test
        void recordingWithoutAPathHasNoRecordDir() {
            var plan = onlyPlan(restream("restream/one").record(new Record(true, "")).build());

            assertThat(plan.recordDir()).isNull();
        }

        @Test
        void disabledRecordingIgnoresTheConfiguredRecordPath() {
            var plan = onlyPlan(restream("restream/one").record(new Record(false, "recordings/one")).build());

            assertThat(plan.recordDir()).isNull();
        }

        @Test
        void skipsChannelWhenThePathCannotBeResolved() {
            assertThat(managerFor(Map.of("/restream/one", restream("restream/{%NOPE%}").build())).plan())
                    .isEmpty();
        }

        @Test
        void skipsChannelWhenTheRecordPathCannotBeResolved() {
            var stream = restream("restream/one").record(new Record(true, "recordings/{%NOPE%}")).build();

            assertThat(managerFor(Map.of("/restream/one", stream)).plan()).isEmpty();
        }

        private RestreamManager.ChannelPlan onlyPlan(Stream stream) {
            var plans = managerFor(Map.of("/restream/one", stream)).plan();
            assertThat(plans).hasSize(1);
            return plans.getFirst();
        }
    }

    @Nested
    class Backoff {

        @Test
        void doublesUntilCappedAtThirtySeconds() {
            var seen = new ArrayList<Duration>();
            var backoff = RestreamManager.INITIAL_BACKOFF;
            for (int i = 0; i < 7; i++) {
                seen.add(backoff);
                backoff = RestreamManager.nextBackoff(backoff);
            }

            assertThat(seen).containsExactly(
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                    Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(30),
                    Duration.ofSeconds(30));
        }
    }

    @Nested
    class Shutdown {

        @Test
        void withoutRecordingDeletesTheStreamRootAndUnregisters() throws IOException {
            var plan = planWithFiles(restream("restream/one").build());

            managerFor(Map.of()).handleShutdown(plan);

            assertThat(plan.streamRootDir()).doesNotExist();
            verify(viewerTracker).unregisterStream("restream/one");
            verify(metrics, never()).incRecordings(anyString(), anyString(), anyString());
        }

        @Test
        void inPlaceRecordingKeepsTheFiles() throws IOException {
            var plan = planWithFiles(restream("restream/one").record(new Record(true, "")).build());

            managerFor(Map.of()).handleShutdown(plan);

            assertThat(plan.streamRootDir().resolve("default/segment_000.ts")).exists();
            verify(metrics).incRecordings("in_place", "success", "restream/one");
        }

        @Test
        void recordingWithAPathMovesTheContents() throws IOException {
            var plan = planWithFiles(restream("restream/one")
                    .record(new Record(true, "recordings/one")).build());

            managerFor(Map.of()).handleShutdown(plan);

            assertThat(plan.streamRootDir()).doesNotExist();
            assertThat(plan.recordDir().resolve("default/segment_000.ts")).exists();
            assertThat(plan.recordDir().resolve(VideoConstants.VIEWS_FILE)).exists();
            verify(metrics).incRecordings("move", "success", "restream/one");
        }

        @Test
        void unregistersTheStreamKeyFromTheRegistry() throws IOException {
            var plan = planWithFiles(restream("restream/one").build());
            registry.getOrRegister(plan.streamKey(), Map.of());

            managerFor(Map.of()).handleShutdown(plan);

            assertThat(registry.getBuiltinVars(plan.streamKey())).isEmpty();
        }

        /** Builds a plan for {@code stream} with a populated stream root on disk. */
        private RestreamManager.ChannelPlan planWithFiles(Stream stream) throws IOException {
            var plan = managerFor(Map.of("/restream/one", stream)).plan().getFirst();
            Files.createDirectories(plan.outputDir());
            Files.writeString(plan.outputDir().resolve("segment_000.ts"), "ts");
            Files.writeString(plan.streamRootDir().resolve(VideoConstants.VIEWS_FILE), "7");
            return plan;
        }
    }

    @Nested
    class StartStop {

        @Test
        void stopIsSafeWhenStartWasNeverCalled() {
            assertThatNoException().isThrownBy(() -> managerFor(Map.of()).stop());
        }

        @Test
        @Timeout(20)
        void stopInterruptsFfmpegAndJoinsTheChannelThreads() throws Exception {
            var launched = new CountDownLatch(1);
            var argvSeen = new ArrayList<List<String>>();
            var manager = managerFor(Map.of("/restream/one", restream("restream/one").build()),
                    argv -> {
                        argvSeen.add(argv);
                        launched.countDown();
                        return new BlockingProcess();
                    });

            manager.start();
            assertThat(launched.await(10, TimeUnit.SECONDS)).isTrue();
            manager.stop();

            assertThat(argvSeen.getFirst()).startsWith("ffmpeg").contains("-i", SOURCE);
            // handleShutdown ran to completion on the channel thread before stop() returned.
            assertThat(videoDir.resolve("restream/one")).doesNotExist();
            verify(viewerTracker).unregisterStream("restream/one");
            verify(metrics).incLiveStreamsActive();
            verify(metrics).decLiveStreamsActive();
            verify(metrics).observeStreamDuration(anyString(), anyDouble());
        }
    }

    /** A process that never exits on its own; {@code waitFor} unblocks on interrupt or destroy. */
    private static final class BlockingProcess extends Process {
        private final CountDownLatch done = new CountDownLatch(1);

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            done.await();
            return 0;
        }

        @Override
        public int exitValue() {
            if (done.getCount() > 0) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
            done.countDown();
        }
    }
}
