package com.fjourdren.theatrum.infrastructure.adapter.out.encoder;

import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Dash;
import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Quality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegEncoderTest {

    @TempDir
    Path tempDir;

    private static Quality quality(int width, int height, String bitrate, String audioBitrate) {
        return new Quality(width, height, 30, bitrate, "libx264", new Audio(audioBitrate, "aac"));
    }

    private static Map<String, Quality> multiQualities() {
        var qualities = new LinkedHashMap<String, Quality>();
        qualities.put("low", quality(640, 360, "800k", "96k"));
        qualities.put("medium", quality(1280, 720, "2500k", "128k"));
        qualities.put("high", quality(1920, 1080, "5000k", "192k"));
        return qualities;
    }

    private static Map<String, Quality> lowOnly() {
        var qualities = new LinkedHashMap<String, Quality>();
        qualities.put("low", quality(640, 360, "800k", "96k"));
        return qualities;
    }

    private static String path(String... parts) {
        Path p = Path.of(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            p = p.resolve(parts[i]);
        }
        return p.toString();
    }

    // --- EncodeVideo, dry run (Go: TestFfmpegEncoder_EncodeVideo) ---

    private static Stream<Arguments> encodeCases() {
        return Stream.of(
                Arguments.of("HLS only encoding", "test_output.m3u8",
                        Distribution.ofHls(new Hls(10, 0))),
                Arguments.of("DASH only encoding", "test_output.mpd",
                        Distribution.ofDash(new Dash(4, 0))),
                Arguments.of("dual mode encoding", "test_output.mpd",
                        new Distribution(new Hls(2, 0), new Dash(2, 0))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodeCases")
    void encodeVideoDryRun(String name, String outputName, Distribution distribution) throws IOException {
        Path outputPath = tempDir.resolve("output").resolve(outputName);

        new FfmpegEncoder("ffmpeg", true)
                .encodeVideo(Path.of("input.mp4"), outputPath, multiQualities(), distribution);

        assertThat(outputPath.getParent()).isDirectory();
    }

    // --- muxing args (Go: TestAddMuxing_HLSOnly / _DASHOnly / _DualMode) ---

    @Test
    void hlsOnlyBuildsFullArgv() {
        List<String> args = FfmpegEncoder.buildArgs(
                Path.of("input.mp4"), Path.of("output/test.m3u8"),
                lowOnly(), Distribution.ofHls(new Hls(6, 0)));

        assertThat(args).containsExactly(
                "-i", "input.mp4",
                "-filter_complex", "[0:v]split=1[v0];[v0]scale=640:360[v0out]",
                "-map", "[v0out]",
                "-c:v:0", "libx264",
                "-b:v:0", "800k",
                "-maxrate:v:0", "533k",
                "-bufsize:v:0", "800k",
                "-map", "a:0",
                "-c:a:0", "aac",
                "-b:a:0", "96k",
                "-f", "hls",
                "-hls_time", "6",
                "-var_stream_map", "v:0,a:0,name:low",
                "-hls_segment_filename", path("output", "%v", "segment_%03d.ts"),
                "-master_pl_name", "master.m3u8",
                path("output", "%v", "playlist.m3u8"));
    }

    @Test
    void dashOnlyUsesDashMuxerWithoutHlsPlaylist() {
        List<String> args = FfmpegEncoder.buildArgs(
                Path.of("input.mp4"), Path.of("output/test.mpd"),
                lowOnly(), Distribution.ofDash(new Dash(4, 0)));

        assertThat(args).containsSequence(
                "-f", "dash",
                "-seg_duration", "4",
                "-use_template", "1",
                "-use_timeline", "1",
                "-init_seg_name", "init-stream$RepresentationID$.m4s",
                "-media_seg_name", "chunk-stream$RepresentationID$-$Number%05d$.m4s",
                "-adaptation_sets", "id=0,streams=v id=1,streams=a",
                path("output", "manifest.mpd"));
        assertThat(args).doesNotContain("-hls_playlist");
    }

    @Test
    void dualModeUsesDashMuxerWithHlsPlaylist() {
        List<String> args = FfmpegEncoder.buildArgs(
                Path.of("input.mp4"), Path.of("output/test.mpd"),
                lowOnly(), new Distribution(new Hls(2, 0), new Dash(2, 0)));

        assertThat(args).containsSequence("-f", "dash", "-seg_duration", "2");
        assertThat(args).containsSequence("-hls_playlist", "1");
        assertThat(args).endsWith(path("output", "manifest.mpd"));
    }

    @Test
    void dashSegmentDurationComesFromDashConfig() {
        List<String> args = FfmpegEncoder.buildArgs(
                Path.of("input.mp4"), Path.of("output/test.mpd"),
                lowOnly(), new Distribution(new Hls(9, 0), new Dash(3, 0)));

        assertThat(args).containsSequence("-seg_duration", "3");
    }
}
