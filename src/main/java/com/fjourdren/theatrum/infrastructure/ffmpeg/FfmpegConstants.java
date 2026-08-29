package com.fjourdren.theatrum.infrastructure.ffmpeg;

import lombok.experimental.UtilityClass;

/**
 * The handful of FFmpeg values shared between the argument builders here, the encoder adapter and
 * the RTMP thumbnail generator.
 *
 * <p>Only values that appear in more than one place live here. The flag names themselves
 * ({@code -f}, {@code -hls_time}, …) stay inline in the builders: an argument vector is read by
 * comparing it against a real FFmpeg command line, and a wall of constant references would make
 * that comparison harder, not easier.
 */
@UtilityClass
public final class FfmpegConstants {

    /** The binary, resolved on {@code PATH}. */
    public static final String BINARY = "ffmpeg";

    /**
     * FFmpeg's own placeholder for the quality name in a multi-quality output path. It expands
     * inside FFmpeg, so it is never a real path segment Theatrum creates.
     */
    public static final String QUALITY_DIR_PLACEHOLDER = "%v";
}
