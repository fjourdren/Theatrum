package com.fjourdren.theatrum.infrastructure.ffmpeg;

import com.fjourdren.theatrum.domain.model.Quality;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared FFmpeg argument builders for multi-quality (adaptive bitrate) output.
 * Every builder returns a new list; the argument list passed in is never mutated.
 * Qualities are iterated in map order, so a {@link java.util.LinkedHashMap} makes the
 * generated arguments deterministic.
 */
@UtilityClass
public final class FfmpegArgs {

    /** Builds {@code -filter_complex} for split+scale across qualities. */
    public static List<String> addFilter(List<String> args, Map<String, Quality> qualities) {
        var filter = new StringBuilder("[0:v]split=").append(qualities.size());
        for (int i = 0; i < qualities.size(); i++) {
            filter.append("[v").append(i).append(']');
        }
        filter.append(';');

        int index = 0;
        for (Quality quality : qualities.values()) {
            filter.append("[v").append(index).append("]scale=")
                    .append(quality.width()).append(':').append(quality.height())
                    .append("[v").append(index).append("out];");
            index++;
        }
        filter.setLength(filter.length() - 1); // drop the trailing semicolon

        var out = new ArrayList<>(args);
        out.add("-filter_complex");
        out.add(filter.toString());
        return out;
    }

    /** Adds per-quality video codec parameters. */
    public static List<String> addVideoCodec(List<String> args, Map<String, Quality> qualities) {
        return videoCodec(args, qualities, false);
    }

    /** Adds per-quality video codec parameters with live-streaming presets. */
    public static List<String> addVideoCodecLive(List<String> args, Map<String, Quality> qualities) {
        return videoCodec(args, qualities, true);
    }

    private static List<String> videoCodec(List<String> args, Map<String, Quality> qualities, boolean live) {
        var out = new ArrayList<>(args);
        int index = 0;
        for (Quality quality : qualities.values()) {
            out.add("-map");
            out.add("[v" + index + "out]");

            out.add("-c:v:" + index);
            out.add("libx264");
            out.add("-b:v:" + index);
            out.add(quality.bitrate());
            out.add("-maxrate:v:" + index);
            out.add(maxrate(quality.bitrate()));
            out.add("-bufsize:v:" + index);
            out.add(quality.bitrate());

            if (live) {
                out.add("-preset:v:" + index);
                out.add("veryfast");
                out.add("-tune:v:" + index);
                out.add("zerolatency");
            }
            index++;
        }
        return out;
    }

    /** Adds per-quality audio codec parameters. */
    public static List<String> addAudioCodec(List<String> args, Map<String, Quality> qualities) {
        var out = new ArrayList<>(args);
        int index = 0;
        for (Quality quality : qualities.values()) {
            out.add("-map");
            out.add("a:0");
            out.add("-c:a:" + index);
            out.add(quality.audio().codec());
            out.add("-b:a:" + index);
            out.add(quality.audio().bitrate());
            index++;
        }
        return out;
    }

    /** Builds the {@code -var_stream_map} value for multi-quality output. */
    public static String buildVarStreamMap(Map<String, Quality> qualities) {
        var streamMap = new StringBuilder();
        int index = 0;
        for (String qualityName : qualities.keySet()) {
            if (index > 0) {
                streamMap.append(' ');
            }
            streamMap.append("v:").append(index).append(",a:").append(index).append(",name:").append(qualityName);
            index++;
        }
        return streamMap.toString();
    }

    /** Two thirds of the configured bitrate. An unparseable bitrate yields 0, as in Go. */
    private static String maxrate(String bitrate) {
        double value = 0;
        try {
            value = Double.parseDouble(bitrate.endsWith("k")
                    ? bitrate.substring(0, bitrate.length() - 1)
                    : bitrate);
        } catch (NumberFormatException ignored) {
            // Go discards the ParseFloat error and keeps the zero value
        }
        return String.format(Locale.ROOT, "%.0fk", value * 0.6666667);
    }
}
