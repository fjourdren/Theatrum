package com.fjourdren.theatrum.infrastructure.ffmpeg;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Dash;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Record;
import com.fjourdren.theatrum.domain.model.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegCommandTest {

    private static final Path OUTPUT_DIR = Path.of("/tmp/output");
    private static final String URL = "rtmp://external/live/key";

    private static Quality quality(int width, int height, String bitrate, String audioBitrate) {
        return new Quality(width, height, 30, bitrate, "libx264", new Audio(audioBitrate, "aac"));
    }

    private static Map<String, Quality> lowOnly() {
        var qualities = new LinkedHashMap<String, Quality>();
        qualities.put("low", quality(640, 360, "800k", "96k"));
        return qualities;
    }

    private static String join(List<String> args) {
        return String.join(" ", args);
    }

    private static String path(String... parts) {
        Path p = OUTPUT_DIR;
        for (String part : parts) {
            p = p.resolve(part);
        }
        return p.toString();
    }

    // --- OutputMode.determine (Go: TestDetermineOutputMode) ---

    @Test
    void determineHlsOnly() {
        assertThat(OutputMode.determine(com.fjourdren.theatrum.domain.model.Distribution.ofHls(new Hls(2, 0))))
                .isEqualTo(OutputMode.HLS);
    }

    @Test
    void determineDashOnly() {
        assertThat(OutputMode.determine(com.fjourdren.theatrum.domain.model.Distribution.ofDash(new Dash(4, 0))))
                .isEqualTo(OutputMode.DASH);
    }

    @Test
    void determineDual() {
        assertThat(OutputMode.determine(
                new com.fjourdren.theatrum.domain.model.Distribution(new Hls(2, 0), new Dash(2, 0))))
                .isEqualTo(OutputMode.DUAL);
    }

    @Test
    void determineNeitherDefaultsToHls() {
        assertThat(OutputMode.determine(com.fjourdren.theatrum.domain.model.Distribution.none()))
                .isEqualTo(OutputMode.HLS);
    }

    // --- flag helpers (Go: TestDashExtraWindowSize / TestHlsFlags) ---

    @ParameterizedTest
    @CsvSource({"true, 999999", "false, 0"})
    void dashExtraWindowSize(boolean recording, String expected) {
        assertThat(FfmpegCommand.dashExtraWindowSize(recording)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "true, temp_file+independent_segments",
            "false, delete_segments+temp_file+independent_segments"
    })
    void hlsFlags(boolean recording, String expected) {
        assertThat(FfmpegCommand.hlsFlags(recording)).isEqualTo(expected);
    }

    // --- inputArgs (Go: TestInputArgs) ---

    @Test
    void inputArgsWithUrl() {
        assertThat(FfmpegCommand.inputArgs("rtmp://server/live")).containsExactly("-i", "rtmp://server/live");
    }

    @Test
    void inputArgsEmptyUrl() {
        assertThat(FfmpegCommand.inputArgs("")).containsExactly("-f", "flv", "-i", "pipe:0");
        assertThat(FfmpegCommand.inputArgs(null)).containsExactly("-f", "flv", "-i", "pipe:0");
    }

    // --- HLS ---

    @Test
    void hlsPassthrough() {
        var stream = Stream.builder().hls(new Hls(2, 3)).build();

        String args = join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.HLS));

        assertThat(args).contains("-f hls", "-c:v copy", VideoConstants.SUB_PLAYLIST);
    }

    @Test
    void hlsMultiQuality() {
        var stream = Stream.builder().hls(new Hls(2, 3)).qualities(lowOnly()).build();

        String args = join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.HLS));

        assertThat(args).contains("-f hls", "-master_pl_name", "-var_stream_map");
    }

    @Test
    void hlsPassthroughExactArgv() {
        var stream = Stream.builder().hls(new Hls(2, 3)).build();

        assertThat(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.HLS)).containsExactly(
                "ffmpeg",
                "-re",
                "-fflags", "+nobuffer",
                "-flags", "low_delay",
                "-f", "flv", "-i", "pipe:0",
                "-c:v", "copy",
                "-c:a", "copy",
                "-f", "hls",
                "-hls_time", "2",
                "-hls_list_size", "3",
                "-hls_flags", "delete_segments+temp_file+independent_segments",
                "-hls_segment_type", "mpegts",
                "-hls_allow_cache", "0",
                "-hls_segment_filename", path(VideoConstants.SEGMENT_NAME),
                path(VideoConstants.SUB_PLAYLIST));
    }

    /** Deterministic argument ordering: LinkedHashMap insertion order, unlike Go's random map. */
    @Test
    void hlsMultiQualityExactArgvFollowsInsertionOrder() {
        var qualities = new LinkedHashMap<String, Quality>();
        qualities.put("low", quality(640, 360, "800k", "96k"));
        qualities.put("medium", quality(1280, 720, "2500k", "128k"));
        qualities.put("high", quality(1920, 1080, "5000k", "192k"));
        var stream = Stream.builder().hls(new Hls(2, 3)).qualities(qualities).build();

        assertThat(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.HLS)).containsExactly(
                "ffmpeg",
                "-re",
                "-fflags", "+nobuffer",
                "-flags", "low_delay",
                "-f", "flv", "-i", "pipe:0",
                "-filter_complex", "[0:v]split=3[v0][v1][v2];"
                        + "[v0]scale=640:360[v0out];"
                        + "[v1]scale=1280:720[v1out];"
                        + "[v2]scale=1920:1080[v2out]",
                "-map", "[v0out]",
                "-c:v:0", "libx264", "-b:v:0", "800k", "-maxrate:v:0", "533k", "-bufsize:v:0", "800k",
                "-preset:v:0", "veryfast", "-tune:v:0", "zerolatency",
                "-map", "[v1out]",
                "-c:v:1", "libx264", "-b:v:1", "2500k", "-maxrate:v:1", "1667k", "-bufsize:v:1", "2500k",
                "-preset:v:1", "veryfast", "-tune:v:1", "zerolatency",
                "-map", "[v2out]",
                "-c:v:2", "libx264", "-b:v:2", "5000k", "-maxrate:v:2", "3333k", "-bufsize:v:2", "5000k",
                "-preset:v:2", "veryfast", "-tune:v:2", "zerolatency",
                "-map", "a:0", "-c:a:0", "aac", "-b:a:0", "96k",
                "-map", "a:0", "-c:a:1", "aac", "-b:a:1", "128k",
                "-map", "a:0", "-c:a:2", "aac", "-b:a:2", "192k",
                "-f", "hls",
                "-hls_time", "2",
                "-hls_list_size", "3",
                "-hls_flags", "delete_segments+temp_file+independent_segments",
                "-hls_segment_type", "mpegts",
                "-hls_allow_cache", "0",
                "-var_stream_map", "v:0,a:0,name:low v:1,a:1,name:medium v:2,a:2,name:high",
                "-master_pl_name", VideoConstants.MASTER_PLAYLIST,
                "-hls_segment_filename", path("%v", VideoConstants.SEGMENT_NAME),
                path("%v", VideoConstants.SUB_PLAYLIST));
    }

    @Test
    void hlsRecordingKeepsSegments() {
        var stream = Stream.builder().hls(new Hls(2, 3)).record(new Record(true, "")).build();

        assertThat(join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.HLS)))
                .contains("-hls_flags temp_file+independent_segments");
    }

    // --- DASH ---

    @Test
    void dashPassthrough() {
        var stream = Stream.builder().dash(new Dash(4, 5)).build();

        String args = join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DASH));

        assertThat(args).contains("-f dash", "-c:v copy", VideoConstants.DASH_MANIFEST);
        assertThat(args).doesNotContain("-hls_playlist");
    }

    @Test
    void dashMultiQuality() {
        var stream = Stream.builder().dash(new Dash(4, 5)).qualities(lowOnly()).build();

        String args = join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DASH));

        assertThat(args).contains("-f dash", "-adaptation_sets");
        assertThat(args).doesNotContain("-hls_playlist");
    }

    @Test
    void dashPassthroughExactArgv() {
        var stream = Stream.builder().dash(new Dash(4, 5)).build();

        assertThat(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DASH)).containsExactly(
                "ffmpeg",
                "-re",
                "-fflags", "+nobuffer",
                "-flags", "low_delay",
                "-f", "flv", "-i", "pipe:0",
                "-c:v", "copy",
                "-c:a", "copy",
                "-f", "dash",
                "-seg_duration", "4",
                "-window_size", "5",
                "-extra_window_size", "0",
                "-streaming", "1",
                "-ldash", "1",
                "-use_template", "1",
                "-use_timeline", "0",
                "-remove_at_exit", "0",
                "-init_seg_name", VideoConstants.DASH_INIT_SEG_NAME,
                "-media_seg_name", VideoConstants.DASH_SEG_NAME,
                path(VideoConstants.DASH_MANIFEST));
    }

    @Test
    void dashUsesDashTimingsOverHlsInDualMode() {
        var stream = Stream.builder().hls(new Hls(2, 3)).dash(new Dash(4, 5)).build();

        assertThat(join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DUAL)))
                .contains("-seg_duration 4", "-window_size 5");
    }

    @Test
    void dashFallsBackToHlsTimingsWhenDashDisabled() {
        var stream = Stream.builder().hls(new Hls(2, 3)).build();

        assertThat(join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DASH)))
                .contains("-seg_duration 2", "-window_size 3");
    }

    @Test
    void dashWithRecording() {
        var stream = Stream.builder().dash(new Dash(4, 5)).record(new Record(true, "")).build();

        assertThat(join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DASH)))
                .contains("-extra_window_size 999999");
    }

    @Test
    void dashWithoutRecording() {
        var stream = Stream.builder().dash(new Dash(4, 5)).record(new Record(false, "")).build();

        assertThat(join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DASH)))
                .contains("-extra_window_size 0");
    }

    // --- Dual ---

    @Test
    void dualPassthrough() {
        var stream = Stream.builder().hls(new Hls(2, 3)).dash(new Dash(2, 3)).build();

        String args = join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DUAL));

        assertThat(args).contains("-f dash", "-hls_playlist 1", VideoConstants.DASH_MANIFEST);
    }

    @Test
    void dualMultiQuality() {
        var stream = Stream.builder().hls(new Hls(2, 3)).dash(new Dash(2, 3)).qualities(lowOnly()).build();

        String args = join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DUAL));

        assertThat(args).contains("-f dash", "-hls_playlist 1", "-adaptation_sets");
    }

    /** -adaptation_sets precedes -hls_playlist, which precedes the manifest path. */
    @Test
    void dualMultiQualityTailOrder() {
        var stream = Stream.builder().hls(new Hls(2, 3)).dash(new Dash(2, 3)).qualities(lowOnly()).build();

        assertThat(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.DUAL))
                .endsWith("-media_seg_name", VideoConstants.DASH_SEG_NAME,
                        "-adaptation_sets", "id=0,streams=v id=1,streams=a",
                        "-hls_playlist", "1",
                        path(VideoConstants.DASH_MANIFEST));
    }

    // --- URL input (restream) ---

    @Test
    void urlInputHlsPassthrough() {
        var stream = Stream.builder().hls(new Hls(2, 3)).build();

        String args = join(FfmpegCommand.create(URL, OUTPUT_DIR, stream, OutputMode.HLS));

        assertThat(args).contains("-i " + URL, "-f hls");
        assertThat(args).doesNotContain("-f flv", "pipe:0");
    }

    @Test
    void urlInputHlsMultiQuality() {
        var stream = Stream.builder().hls(new Hls(2, 3)).qualities(lowOnly()).build();

        String args = join(FfmpegCommand.create(URL, OUTPUT_DIR, stream, OutputMode.HLS));

        assertThat(args).contains("-i " + URL, "-var_stream_map", "-master_pl_name");
    }

    @Test
    void urlInputDashPassthrough() {
        var stream = Stream.builder().dash(new Dash(4, 5)).build();

        String args = join(FfmpegCommand.create(URL, OUTPUT_DIR, stream, OutputMode.DASH));

        assertThat(args).contains("-i " + URL, "-f dash");
    }

    @Test
    void urlInputDualPassthrough() {
        var stream = Stream.builder().hls(new Hls(2, 3)).dash(new Dash(2, 3)).build();

        String args = join(FfmpegCommand.create(URL, OUTPUT_DIR, stream, OutputMode.DUAL));

        assertThat(args).contains("-i " + URL, "-hls_playlist 1");
    }

    @Test
    void emptySourceUrlUsesStdin() {
        var stream = Stream.builder().hls(new Hls(2, 3)).build();

        String args = join(FfmpegCommand.create("", OUTPUT_DIR, stream, OutputMode.HLS));

        assertThat(args).contains("-f flv", "-i pipe:0");
    }
}
