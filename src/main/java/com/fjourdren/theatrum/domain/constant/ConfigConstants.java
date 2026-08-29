package com.fjourdren.theatrum.domain.constant;

import lombok.experimental.UtilityClass;

/**
 * Values a config may leave out. Go filled these in from zero-valued struct fields; here the
 * config mapper applies them while reading {@code config.yml}, and the domain models that
 * document them ({@code Rtmp}, {@code Hls}, {@code Dash}) read the same constants — a default
 * written twice is a default that drifts.
 */
@UtilityClass
public final class ConfigConstants {

    /** Default when {@code reconnect_delay} / {@code cleanup_delay} is not set, in seconds. */
    public static final int DEFAULT_RTMP_DELAY = 30;

    /** Default number of segments in a live playlist/manifest when {@code window_size} is not set. */
    public static final int DEFAULT_WINDOW_SIZE = 3;
}
