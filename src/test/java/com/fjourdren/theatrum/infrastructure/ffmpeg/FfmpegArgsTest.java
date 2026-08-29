package com.fjourdren.theatrum.infrastructure.ffmpeg;

import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Quality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegArgsTest {

    private static Quality quality(int width, int height, String bitrate, String audioBitrate) {
        return new Quality(width, height, 30, bitrate, "libx264", new Audio(audioBitrate, "aac"));
    }

    /** Insertion order is the FFmpeg argument order (Go used a random-order map). */
    private static Map<String, Quality> threeQualities() {
        var qualities = new LinkedHashMap<String, Quality>();
        qualities.put("low", quality(640, 360, "800k", "96k"));
        qualities.put("medium", quality(1280, 720, "2500k", "128k"));
        qualities.put("high", quality(1920, 1080, "5000k", "192k"));
        return qualities;
    }

    private static Map<String, Quality> oneQuality() {
        var qualities = new LinkedHashMap<String, Quality>();
        qualities.put("low", quality(640, 360, "800k", "96k"));
        return qualities;
    }

    @Test
    void addFilterSingleQuality() {
        assertThat(FfmpegArgs.addFilter(List.of(), oneQuality()))
                .containsExactly("-filter_complex", "[0:v]split=1[v0];[v0]scale=640:360[v0out]");
    }

    @Test
    void addFilterThreeQualitiesKeepsInsertionOrder() {
        assertThat(FfmpegArgs.addFilter(List.of(), threeQualities()))
                .containsExactly("-filter_complex",
                        "[0:v]split=3[v0][v1][v2];"
                                + "[v0]scale=640:360[v0out];"
                                + "[v1]scale=1280:720[v1out];"
                                + "[v2]scale=1920:1080[v2out]");
    }

    @Test
    void addFilterAppendsToExistingArgs() {
        assertThat(FfmpegArgs.addFilter(List.of("-re"), oneQuality())).startsWith("-re", "-filter_complex");
    }

    @Test
    void addVideoCodec() {
        assertThat(FfmpegArgs.addVideoCodec(List.of(), threeQualities())).containsExactly(
                "-map", "[v0out]",
                "-c:v:0", "libx264", "-b:v:0", "800k", "-maxrate:v:0", "533k", "-bufsize:v:0", "800k",
                "-map", "[v1out]",
                "-c:v:1", "libx264", "-b:v:1", "2500k", "-maxrate:v:1", "1667k", "-bufsize:v:1", "2500k",
                "-map", "[v2out]",
                "-c:v:2", "libx264", "-b:v:2", "5000k", "-maxrate:v:2", "3333k", "-bufsize:v:2", "5000k");
    }

    @Test
    void addVideoCodecLiveAddsPresetAndTune() {
        assertThat(FfmpegArgs.addVideoCodecLive(List.of(), oneQuality())).containsExactly(
                "-map", "[v0out]",
                "-c:v:0", "libx264", "-b:v:0", "800k", "-maxrate:v:0", "533k", "-bufsize:v:0", "800k",
                "-preset:v:0", "veryfast", "-tune:v:0", "zerolatency");
    }

    @ParameterizedTest
    @CsvSource({
            "800k, 533k",
            "2500k, 1667k",
            "5000k, 3333k",
            "1200, 800k",
            "'', 0k",
            "notanumber, 0k"
    })
    void maxrateIsTwoThirdsOfBitrate(String bitrate, String expectedMaxrate) {
        var qualities = new LinkedHashMap<String, Quality>();
        qualities.put("q", quality(640, 360, bitrate, "96k"));

        assertThat(FfmpegArgs.addVideoCodec(List.of(), qualities))
                .containsSequence("-maxrate:v:0", expectedMaxrate);
    }

    @Test
    void addAudioCodec() {
        assertThat(FfmpegArgs.addAudioCodec(List.of(), threeQualities())).containsExactly(
                "-map", "a:0", "-c:a:0", "aac", "-b:a:0", "96k",
                "-map", "a:0", "-c:a:1", "aac", "-b:a:1", "128k",
                "-map", "a:0", "-c:a:2", "aac", "-b:a:2", "192k");
    }

    @Test
    void buildVarStreamMap() {
        assertThat(FfmpegArgs.buildVarStreamMap(threeQualities()))
                .isEqualTo("v:0,a:0,name:low v:1,a:1,name:medium v:2,a:2,name:high");
    }

    @Test
    void buildVarStreamMapSingleQualityHasNoSeparator() {
        assertThat(FfmpegArgs.buildVarStreamMap(oneQuality())).isEqualTo("v:0,a:0,name:low");
    }

    @Test
    void buildersDoNotMutateTheInputList() {
        var args = List.of("-re");
        FfmpegArgs.addFilter(args, oneQuality());
        FfmpegArgs.addVideoCodecLive(args, oneQuality());
        FfmpegArgs.addAudioCodec(args, oneQuality());
        assertThat(args).containsExactly("-re");
    }
}
