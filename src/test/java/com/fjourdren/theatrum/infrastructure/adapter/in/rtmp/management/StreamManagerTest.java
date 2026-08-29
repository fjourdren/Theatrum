package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Dash;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Record;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.domain.service.ViewerTracker;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.config.RtmpConfig;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management.StreamProcessTest.FakeProcess;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.mockito.Mockito.mock;

class StreamManagerTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final Metrics metrics = new Metrics(registry);
    private final ViewerTracker viewerTracker = mock(ViewerTracker.class);

    private final List<List<String>> launched = new ArrayList<>();
    private final Deque<FakeProcess> processes = new ArrayDeque<>();

    private StreamManager manager() {
        return new StreamManager(viewerTracker, metrics, argv -> {
            launched.add(argv);
            FakeProcess next = processes.poll();
            return next == null ? new FakeProcess() : next;
        });
    }

    private static Stream hlsStream(boolean multiQuality, Record record) {
        Stream.Builder builder = Stream.builder()
                .type(StreamType.LIVE)
                .path("live/alice")
                .hls(new Hls(2, 3))
                .record(record);
        if (multiQuality) {
            builder.quality("low", new Quality(640, 360, 30, "800k", "libx264", new Audio("128k", "aac")))
                    .quality("high", new Quality(1920, 1080, 30, "5000k", "libx264", new Audio("128k", "aac")));
        }
        return builder.build();
    }

    @Test
    void hlsPassthroughCreatesTheOutputDirAndWrapsItInAMasterPlaylist(@TempDir Path tmpDir) throws IOException {
        Path streamRootDir = tmpDir.resolve("live/alice");
        Path outputDir = streamRootDir.resolve(VideoConstants.DEFAULT_QUALITY);

        StreamProcess sp = manager().getOrCreateStream("/user/alice", outputDir,
                hlsStream(false, Record.disabled()), null, "live/alice");

        assertThat(outputDir).isDirectory();
        assertThat(streamRootDir.resolve(VideoConstants.MASTER_PLAYLIST)).content()
                .contains(VideoConstants.DEFAULT_QUALITY + "/" + VideoConstants.SUB_PLAYLIST);
        assertThat(sp.isActive()).isTrue();
        assertThat(launched).hasSize(1);
        assertThat(launched.getFirst().getFirst()).isEqualTo("ffmpeg");
        assertThat(launched.getFirst()).anyMatch(arg -> arg.contains(outputDir.toString()));
    }

    @Test
    void hlsMultiQualityLetsFfmpegOwnTheMasterPlaylist(@TempDir Path tmpDir) throws IOException {
        Path outputDir = tmpDir.resolve("live/alice");

        manager().getOrCreateStream("/user/alice", outputDir, hlsStream(true, Record.disabled()),
                null, "live/alice");

        assertThat(outputDir).isDirectory();
        assertThat(tmpDir.resolve(VideoConstants.MASTER_PLAYLIST)).doesNotExist();
        assertThat(outputDir.resolve(VideoConstants.MASTER_PLAYLIST)).doesNotExist();
    }

    @Test
    void dashWritesNoMasterPlaylistWrapper(@TempDir Path tmpDir) throws IOException {
        Path outputDir = tmpDir.resolve("live/alice");
        Stream stream = Stream.builder().type(StreamType.LIVE).path("live/alice")
                .dash(new Dash(2, 3)).build();

        manager().getOrCreateStream("/user/alice", outputDir, stream, null, "live/alice");

        assertThat(outputDir).isDirectory();
        assertThat(tmpDir.resolve(VideoConstants.MASTER_PLAYLIST)).doesNotExist();
    }

    @Test
    void reusesTheProcessAlreadyRunningForAnInputPath(@TempDir Path tmpDir) throws IOException {
        Path outputDir = tmpDir.resolve("live/alice").resolve(VideoConstants.DEFAULT_QUALITY);
        StreamManager manager = manager();
        Stream stream = hlsStream(false, Record.disabled());

        StreamProcess first = manager.getOrCreateStream("/user/alice", outputDir, stream, null, "live/alice");
        StreamProcess second = manager.getOrCreateStream("/user/alice", outputDir, stream, null, "live/alice");

        assertThat(second).isSameAs(first);
        assertThat(launched).hasSize(1);
    }

    @Test
    void replacesAProcessThatIsNoLongerActive(@TempDir Path tmpDir) throws IOException {
        Path outputDir = tmpDir.resolve("live/alice").resolve(VideoConstants.DEFAULT_QUALITY);
        StreamManager manager = manager();
        // In-place recording so stopping the first process never deletes the stream directory.
        Stream stream = hlsStream(false, new Record(true, ""));

        StreamProcess first = manager.getOrCreateStream("/user/alice", outputDir, stream, null, "live/alice");
        first.stop(new RtmpConfig(1, 0));
        StreamProcess second = manager.getOrCreateStream("/user/alice", outputDir, stream, null, "live/alice");

        assertThat(second).isNotSameAs(first);
        assertThat(launched).hasSize(2);
        assertThat(manager.getActiveStreams()).containsExactly("/user/alice");
    }

    @Test
    void tracksActiveStreamsPerInputPath(@TempDir Path tmpDir) throws IOException {
        StreamManager manager = manager();
        Stream stream = hlsStream(false, Record.disabled());

        manager.getOrCreateStream("/user/alice", tmpDir.resolve("a/default"), stream, null, "live/alice");
        manager.getOrCreateStream("/user/bob", tmpDir.resolve("b/default"), stream, null, "live/bob");

        assertThat(manager.getActiveStreams()).containsExactlyInAnyOrder("/user/alice", "/user/bob");
    }

    @Test
    void hasNoActiveStreamsWhenNothingWasStarted() {
        assertThat(manager().getActiveStreams()).isEmpty();
    }

    @Test
    void failsWhenTheOutputDirectoryCannotBeCreated(@TempDir Path tmpDir) throws IOException {
        Path blocker = Files.writeString(tmpDir.resolve("blocked"), "not a directory");

        assertThatIOException().isThrownBy(() -> manager().getOrCreateStream("/user/alice",
                blocker.resolve("default"), hlsStream(false, Record.disabled()), null, "live/alice"));
    }
}
