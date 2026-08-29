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
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import com.fjourdren.theatrum.infrastructure.ffmpeg.OutputMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StreamProcessTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final Metrics metrics = new Metrics(registry);
    private final ViewerTracker viewerTracker = mock(ViewerTracker.class);

    /** A {@link Process} stand-in so tests never spawn FFmpeg. */
    static final class FakeProcess extends Process {

        private final CountDownLatch exited = new CountDownLatch(1);
        private final ClosableStream stdin = new ClosableStream();
        final AtomicBoolean forciblyDestroyed = new AtomicBoolean();
        private volatile int exitCode;

        static final class ClosableStream extends ByteArrayOutputStream {
            final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                closed.set(true);
            }
        }

        void exitWith(int code) {
            exitCode = code;
            exited.countDown();
        }

        boolean stdinClosed() {
            return stdin.closed.get();
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
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
            exited.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (exited.getCount() > 0) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            exitWith(143);
        }

        @Override
        public Process destroyForcibly() {
            forciblyDestroyed.set(true);
            exitWith(137);
            return this;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() > 0;
        }
    }

    private static Stream hlsStream(boolean multiQuality, Record record) {
        Stream.Builder builder = Stream.builder()
                .type(StreamType.LIVE)
                .path("live/alice")
                .hls(new Hls(4, 3))
                .record(record);
        if (multiQuality) {
            builder.quality("low", new Quality(640, 360, 30, "800k", "libx264", new Audio("128k", "aac")))
                    .quality("high", new Quality(1920, 1080, 30, "5000k", "libx264", new Audio("128k", "aac")));
        }
        return builder.build();
    }

    private static Stream dashStream(Record record) {
        return Stream.builder()
                .type(StreamType.LIVE)
                .path("live/alice")
                .dash(new Dash(4, 3))
                .record(record)
                .build();
    }

    private StreamProcess process(FakeProcess fake, Path outputDir, Path streamRootDir, Stream stream,
                                  OutputMode mode, Path recordPath) {
        return new StreamProcess(fake, "/user/alice", outputDir, streamRootDir, stream, mode,
                recordPath, "live/alice", viewerTracker, metrics, null);
    }

    private static Path writeSegments(Path dir, int count) throws IOException {
        Files.createDirectories(dir);
        for (int i = 0; i < count; i++) {
            Files.writeString(dir.resolve("segment_%03d.ts".formatted(i)), "seg");
        }
        return dir;
    }

    private double recordings(String mode, String status) {
        return registry.get("theatrum_recordings")
                .tags("mode", mode, "status", status, "stream_path", "live/alice")
                .counter().count();
    }

    @Nested
    class MoveContents {

        @Test
        void movesEveryFileAndDirectory(@TempDir Path tmpDir) throws IOException {
            Path src = Files.createDirectories(tmpDir.resolve("src"));
            Path dst = Files.createDirectories(tmpDir.resolve("dst"));
            Files.writeString(src.resolve("a.ts"), "a");
            Files.createDirectories(src.resolve("sub"));
            Files.writeString(src.resolve("sub").resolve("b.ts"), "b");

            StreamProcess.moveContents(src, dst);

            assertThat(dst.resolve("a.ts")).hasContent("a");
            assertThat(dst.resolve("sub").resolve("b.ts")).hasContent("b");
            assertThat(src).isEmptyDirectory();
        }
    }

    @Nested
    class DeleteRecursively {

        @Test
        void removesNestedTrees(@TempDir Path tmpDir) throws IOException {
            Path root = Files.createDirectories(tmpDir.resolve("root").resolve("deep"));
            Files.writeString(root.resolve("file.ts"), "x");

            StreamProcess.deleteRecursively(tmpDir.resolve("root"));

            assertThat(tmpDir.resolve("root")).doesNotExist();
        }

        @Test
        void isANoOpForAMissingDirectory(@TempDir Path tmpDir) throws IOException {
            StreamProcess.deleteRecursively(tmpDir.resolve("nope"));
        }
    }

    @Nested
    class SaveRecording {

        @Test
        void hlsPassthroughMovesSegmentsUnderDefaultAndRebuildsTheMasterPlaylist(@TempDir Path tmpDir)
                throws IOException {
            Path streamRootDir = tmpDir.resolve("live/alice");
            Path outputDir = writeSegments(streamRootDir.resolve(VideoConstants.DEFAULT_QUALITY), 2);
            Files.writeString(streamRootDir.resolve(VideoConstants.VIEWS_FILE), "42");
            Files.writeString(streamRootDir.resolve(VideoConstants.THUMBNAIL_FILE), "png");
            Path recordDir = tmpDir.resolve("recordings/alice");

            process(new FakeProcess(), outputDir, streamRootDir, hlsStream(false, new Record(true, "x")),
                    OutputMode.HLS, recordDir).saveRecording();

            Path defaultDir = recordDir.resolve(VideoConstants.DEFAULT_QUALITY);
            assertThat(defaultDir.resolve("segment_000.ts")).exists();
            assertThat(defaultDir.resolve(VideoConstants.SUB_PLAYLIST)).content()
                    .contains("#EXT-X-PLAYLIST-TYPE:VOD");
            assertThat(recordDir.resolve(VideoConstants.MASTER_PLAYLIST)).content()
                    .contains(VideoConstants.DEFAULT_QUALITY + "/" + VideoConstants.SUB_PLAYLIST);
            assertThat(recordDir.resolve(VideoConstants.VIEWS_FILE)).hasContent("42");
            assertThat(recordDir.resolve(VideoConstants.THUMBNAIL_FILE)).hasContent("png");
            assertThat(streamRootDir).doesNotExist();
            assertThat(recordings("move", "success")).isEqualTo(1.0);
        }

        @Test
        void hlsMultiQualityWritesAVodPlaylistPerQualityDir(@TempDir Path tmpDir) throws IOException {
            Path streamRootDir = tmpDir.resolve("live/alice");
            writeSegments(streamRootDir.resolve("low"), 2);
            writeSegments(streamRootDir.resolve("high"), 3);
            Path recordDir = tmpDir.resolve("recordings/alice");

            process(new FakeProcess(), streamRootDir, streamRootDir, hlsStream(true, new Record(true, "x")),
                    OutputMode.HLS, recordDir).saveRecording();

            assertThat(recordDir.resolve("low").resolve(VideoConstants.SUB_PLAYLIST)).exists();
            assertThat(recordDir.resolve("high").resolve(VideoConstants.SUB_PLAYLIST)).content()
                    .contains("#EXT-X-TARGETDURATION:4")
                    .contains("segment_002.ts");
            assertThat(recordDir.resolve(VideoConstants.DEFAULT_QUALITY)).doesNotExist();
            assertThat(streamRootDir).doesNotExist();
            assertThat(recordings("move", "success")).isEqualTo(1.0);
        }

        @Test
        void dashMovesEverythingWithoutGeneratingPlaylists(@TempDir Path tmpDir) throws IOException {
            Path streamRootDir = Files.createDirectories(tmpDir.resolve("live/alice"));
            Files.writeString(streamRootDir.resolve(VideoConstants.DASH_MANIFEST), "mpd");
            Files.writeString(streamRootDir.resolve("chunk-stream0-00001.m4s"), "chunk");
            Path recordDir = tmpDir.resolve("recordings/alice");

            process(new FakeProcess(), streamRootDir, streamRootDir, dashStream(new Record(true, "x")),
                    OutputMode.DASH, recordDir).saveRecording();

            assertThat(recordDir.resolve(VideoConstants.DASH_MANIFEST)).hasContent("mpd");
            assertThat(recordDir.resolve("chunk-stream0-00001.m4s")).exists();
            assertThat(recordDir.resolve(VideoConstants.SUB_PLAYLIST)).doesNotExist();
            assertThat(streamRootDir).doesNotExist();
            assertThat(recordings("move", "success")).isEqualTo(1.0);
        }

        @Test
        void countsAFailureWhenTheStreamDirectoryIsGone(@TempDir Path tmpDir) {
            Path streamRootDir = tmpDir.resolve("live/gone");
            Path recordDir = tmpDir.resolve("recordings/gone");

            process(new FakeProcess(), streamRootDir, streamRootDir, dashStream(new Record(true, "x")),
                    OutputMode.DASH, recordDir).saveRecording();

            assertThat(recordings("move", "failure")).isEqualTo(1.0);
        }
    }

    @Nested
    class SaveInPlace {

        @Test
        void hlsPassthroughWritesTheVodPlaylistAndMasterWrapperWhereTheyAre(@TempDir Path tmpDir)
                throws IOException {
            Path streamRootDir = tmpDir.resolve("live/alice");
            Path outputDir = writeSegments(streamRootDir.resolve(VideoConstants.DEFAULT_QUALITY), 2);

            process(new FakeProcess(), outputDir, streamRootDir, hlsStream(false, new Record(true, "")),
                    OutputMode.HLS, null).saveInPlace();

            assertThat(outputDir.resolve(VideoConstants.SUB_PLAYLIST)).content()
                    .contains("#EXT-X-ENDLIST");
            assertThat(streamRootDir.resolve(VideoConstants.MASTER_PLAYLIST)).exists();
            assertThat(outputDir.resolve("segment_000.ts")).exists();
            assertThat(recordings("in_place", "success")).isEqualTo(1.0);
        }

        @Test
        void hlsMultiQualityWritesOneVodPlaylistPerQualityDir(@TempDir Path tmpDir) throws IOException {
            Path streamRootDir = tmpDir.resolve("live/alice");
            writeSegments(streamRootDir.resolve("low"), 1);
            writeSegments(streamRootDir.resolve("high"), 1);

            process(new FakeProcess(), streamRootDir, streamRootDir, hlsStream(true, new Record(true, "")),
                    OutputMode.HLS, null).saveInPlace();

            assertThat(streamRootDir.resolve("low").resolve(VideoConstants.SUB_PLAYLIST)).exists();
            assertThat(streamRootDir.resolve("high").resolve(VideoConstants.SUB_PLAYLIST)).exists();
            assertThat(streamRootDir.resolve(VideoConstants.MASTER_PLAYLIST)).doesNotExist();
            assertThat(recordings("in_place", "success")).isEqualTo(1.0);
        }

        @Test
        void dashLeavesTheManifestFfmpegAlreadyFinalised(@TempDir Path tmpDir) throws IOException {
            Path streamRootDir = Files.createDirectories(tmpDir.resolve("live/alice"));
            Files.writeString(streamRootDir.resolve(VideoConstants.DASH_MANIFEST), "mpd");

            process(new FakeProcess(), streamRootDir, streamRootDir, dashStream(new Record(true, "")),
                    OutputMode.DASH, null).saveInPlace();

            assertThat(streamRootDir.resolve(VideoConstants.DASH_MANIFEST)).hasContent("mpd");
            assertThat(streamRootDir.resolve(VideoConstants.SUB_PLAYLIST)).doesNotExist();
            assertThat(recordings("in_place", "success")).isEqualTo(1.0);
        }
    }

    @Nested
    class Stop {

        private final RtmpConfig config = new RtmpConfig(1, 0);

        @Test
        void closesStdinUnregistersTrackingAndObservesTheDuration(@TempDir Path tmpDir) throws IOException {
            Path streamRootDir = Files.createDirectories(tmpDir.resolve("live/alice"));
            FakeProcess fake = new FakeProcess();
            fake.exitWith(0);
            StreamProcess sp = process(fake, streamRootDir, streamRootDir,
                    hlsStream(false, Record.disabled()), OutputMode.HLS, null);

            sp.stop(config);

            assertThat(fake.stdinClosed()).isTrue();
            assertThat(sp.isActive()).isFalse();
            assertThat(fake.forciblyDestroyed).isFalse();
            verify(viewerTracker).unregisterStream("live/alice");
            assertThat(registry.get("theatrum_stream_duration")
                    .tag("stream_path", "live/alice").timer().count()).isEqualTo(1L);
        }

        @Test
        void killsFfmpegWhenItOutlastsTheCleanupDelay(@TempDir Path tmpDir) throws IOException {
            Path streamRootDir = Files.createDirectories(tmpDir.resolve("live/alice"));
            FakeProcess fake = new FakeProcess();
            StreamProcess sp = process(fake, streamRootDir, streamRootDir,
                    hlsStream(false, Record.disabled()), OutputMode.HLS, null);

            sp.stop(config);

            assertThat(fake.forciblyDestroyed).isTrue();
            assertThat(registry.get("theatrum_ffmpeg_exits")
                    .tags("status", "killed", "stream_path", "live/alice").counter().count()).isEqualTo(1.0);
        }

        @Test
        void isIdempotent(@TempDir Path tmpDir) throws IOException {
            Path streamRootDir = Files.createDirectories(tmpDir.resolve("live/alice"));
            FakeProcess fake = new FakeProcess();
            fake.exitWith(0);
            StreamProcess sp = process(fake, streamRootDir, streamRootDir,
                    hlsStream(false, Record.disabled()), OutputMode.HLS, null);

            sp.stop(config);
            sp.stop(config);

            verify(viewerTracker).unregisterStream("live/alice");
            assertThat(registry.get("theatrum_stream_duration")
                    .tag("stream_path", "live/alice").timer().count()).isEqualTo(1L);
        }
    }

    @Nested
    class Monitor {

        @Test
        void recordsACleanExitAndDropsTheStreamFromTheManager(@TempDir Path tmpDir) {
            FakeProcess fake = new FakeProcess();
            fake.exitWith(0);
            StreamManager manager = new StreamManager(viewerTracker, metrics, argv -> fake);
            StreamProcess sp = process(fake, tmpDir, tmpDir, hlsStream(false, Record.disabled()),
                    OutputMode.HLS, null);

            sp.monitor(manager);

            assertThat(sp.isActive()).isFalse();
            assertThat(registry.get("theatrum_ffmpeg_exits")
                    .tags("status", "clean", "stream_path", "live/alice").counter().count()).isEqualTo(1.0);
        }

        @Test
        void recordsAnErrorExitOnANonZeroStatus(@TempDir Path tmpDir) {
            FakeProcess fake = new FakeProcess();
            fake.exitWith(1);
            StreamManager manager = new StreamManager(viewerTracker, metrics, argv -> fake);
            StreamProcess sp = process(fake, tmpDir, tmpDir, hlsStream(false, Record.disabled()),
                    OutputMode.HLS, null);

            sp.monitor(manager);

            assertThat(registry.get("theatrum_ffmpeg_exits")
                    .tags("status", "error", "stream_path", "live/alice").counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    void exposesItsInputPathAndStdin(@TempDir Path tmpDir) {
        FakeProcess fake = new FakeProcess();
        StreamProcess sp = process(fake, tmpDir, tmpDir, hlsStream(false, Record.disabled()),
                OutputMode.HLS, null);

        assertThat(sp.inputPath()).isEqualTo("/user/alice");
        assertThat(sp.stdin()).isSameAs(fake.getOutputStream());
        assertThat(sp.isActive()).isTrue();
    }
}
