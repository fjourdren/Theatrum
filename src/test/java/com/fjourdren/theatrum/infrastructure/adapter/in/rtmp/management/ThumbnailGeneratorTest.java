package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management;

import com.fjourdren.theatrum.infrastructure.ffmpeg.OutputMode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailGeneratorTest {

    private static ThumbnailGenerator generator(Path streamRootDir, Path outputDir, OutputMode mode,
                                                boolean multiQuality, List<String> qualities) {
        return new ThumbnailGenerator(streamRootDir, outputDir, mode, multiQuality, qualities, 1);
    }

    private static Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "data");
        return file;
    }

    @Nested
    class PickBestQualityDir {

        @Test
        void prefersHighWhenEveryQualityExists(@TempDir Path tmpDir) throws IOException {
            Files.createDirectories(tmpDir.resolve("low"));
            Files.createDirectories(tmpDir.resolve("medium"));
            Files.createDirectories(tmpDir.resolve("high"));

            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, true, List.of("low", "medium", "high"));

            assertThat(tg.pickBestQualityDir()).isEqualTo(tmpDir.resolve("high"));
        }

        @Test
        void fallsBackToMediumWhenHighIsMissing(@TempDir Path tmpDir) throws IOException {
            Files.createDirectories(tmpDir.resolve("low"));
            Files.createDirectories(tmpDir.resolve("medium"));

            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, true, List.of("low", "medium", "high"));

            assertThat(tg.pickBestQualityDir()).isEqualTo(tmpDir.resolve("medium"));
        }

        @Test
        void returnsNullWhenThereAreNoQualities(@TempDir Path tmpDir) {
            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, true, List.of());

            assertThat(tg.pickBestQualityDir()).isNull();
        }

        @Test
        void fallsBackToTheFirstQualityDirectoryThatExists(@TempDir Path tmpDir) throws IOException {
            Files.createDirectories(tmpDir.resolve("mobile"));

            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, true, List.of("desktop", "mobile"));

            assertThat(tg.pickBestQualityDir()).isEqualTo(tmpDir.resolve("mobile"));
        }
    }

    @Nested
    class FindLatestSegment {

        @Test
        void hlsPassthroughPicksTheNewestTsInTheOutputDir(@TempDir Path tmpDir) throws Exception {
            Path outputDir = tmpDir.resolve("default");
            touch(outputDir.resolve("segment_001.ts"));
            Thread.sleep(10);
            Path newest = touch(outputDir.resolve("segment_002.ts"));

            var tg = generator(tmpDir, outputDir, OutputMode.HLS, false, List.of());

            assertThat(tg.findLatestSegment()).isEqualTo(newest);
        }

        @Test
        void dashPicksTheNewestChunkInTheStreamRootDir(@TempDir Path tmpDir) throws Exception {
            touch(tmpDir.resolve("chunk-stream0-00001.m4s"));
            Thread.sleep(10);
            Path newest = touch(tmpDir.resolve("chunk-stream0-00002.m4s"));

            var tg = generator(tmpDir, tmpDir, OutputMode.DASH, false, List.of());

            assertThat(tg.findLatestSegment()).isEqualTo(newest);
        }

        @Test
        void hlsMultiQualityPicksFromTheBestQualityDir(@TempDir Path tmpDir) throws IOException {
            Path segment = touch(tmpDir.resolve("high").resolve("segment_001.ts"));

            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, true, List.of("low", "medium", "high"));

            assertThat(tg.findLatestSegment()).isEqualTo(segment);
        }

        @Test
        void returnsNullWhenThereAreNoSegments(@TempDir Path tmpDir) {
            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, false, List.of());

            assertThat(tg.findLatestSegment()).isNull();
        }

        @Test
        void returnsNullWhenNoQualityDirectoryExists(@TempDir Path tmpDir) {
            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, true, List.of("low", "high"));

            assertThat(tg.findLatestSegment()).isNull();
        }
    }

    @Nested
    class FindInitSegment {

        @Test
        void prefersInitStream0(@TempDir Path tmpDir) throws IOException {
            touch(tmpDir.resolve("init-stream1.m4s"));
            Path video = touch(tmpDir.resolve("init-stream0.m4s"));

            var tg = generator(tmpDir, tmpDir, OutputMode.DASH, false, List.of());

            assertThat(tg.findInitSegment()).isEqualTo(video);
        }

        @Test
        void fallsBackToAnyInitSegment(@TempDir Path tmpDir) throws IOException {
            Path only = touch(tmpDir.resolve("init-stream3.m4s"));

            var tg = generator(tmpDir, tmpDir, OutputMode.DASH, false, List.of());

            assertThat(tg.findInitSegment()).isEqualTo(only);
        }

        @Test
        void returnsNullWhenThereIsNoInitSegment(@TempDir Path tmpDir) {
            var tg = generator(tmpDir, tmpDir, OutputMode.DASH, false, List.of());

            assertThat(tg.findInitSegment()).isNull();
        }
    }

    @Nested
    class BuildFfmpegArgs {

        @Test
        void tsSegmentsAreReadDirectly(@TempDir Path tmpDir) {
            Path segment = tmpDir.resolve("segment_001.ts");
            Path output = tmpDir.resolve("thumbnail.tmp.png");

            var tg = generator(tmpDir, tmpDir, OutputMode.HLS, false, List.of());

            assertThat(tg.buildFfmpegArgs(segment, output)).containsExactly(
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", segment.toString(),
                    "-frames:v", "1", "-update", "1", output.toString());
        }

        @Test
        void m4sSegmentsArePrefixedWithTheInitSegmentThroughConcat(@TempDir Path tmpDir) throws IOException {
            Path init = touch(tmpDir.resolve("init-stream0.m4s"));
            Path segment = tmpDir.resolve("chunk-stream0-00002.m4s");
            Path output = tmpDir.resolve("thumbnail.tmp.png");

            var tg = generator(tmpDir, tmpDir, OutputMode.DASH, false, List.of());

            assertThat(tg.buildFfmpegArgs(segment, output))
                    .contains("concat:" + init + "|" + segment)
                    .containsSequence("-frames:v", "1", "-update", "1");
        }

        @Test
        void m4sSegmentsFallBackToTheChunkAloneWhenNoInitSegmentExists(@TempDir Path tmpDir) {
            Path segment = tmpDir.resolve("chunk-stream0-00002.m4s");
            Path output = tmpDir.resolve("thumbnail.tmp.png");

            var tg = generator(tmpDir, tmpDir, OutputMode.DASH, false, List.of());

            assertThat(tg.buildFfmpegArgs(segment, output)).containsExactly(
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", segment.toString(),
                    "-frames:v", "1", "-update", "1", output.toString());
        }
    }

    @Test
    void startThenStopDoesNotHangOrThrow(@TempDir Path tmpDir) {
        var tg = generator(tmpDir, tmpDir, OutputMode.HLS, false, List.of());

        tg.start();
        tg.stop();

        assertThat(tmpDir).exists();
    }

    @Test
    void stopIsSafeWithoutStart(@TempDir Path tmpDir) {
        generator(tmpDir, tmpDir, OutputMode.HLS, false, List.of()).stop();
    }
}
