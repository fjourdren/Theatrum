package com.fjourdren.theatrum.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DistributionTest {

    static List<Arguments> hlsEnabledCases() {
        return List.of(
                Arguments.of("hls configured", new Distribution(new Hls(2, 0), null), true),
                Arguments.of("hls nil", new Distribution(null, null), false),
                Arguments.of("dash only", new Distribution(null, new Dash(2, 0)), false),
                Arguments.of("dual mode", new Distribution(new Hls(2, 0), new Dash(2, 0)), true),
                Arguments.of("empty distribution", Distribution.none(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hlsEnabledCases")
    void hlsEnabled(String name, Distribution distribution, boolean expected) {
        assertThat(distribution.hlsEnabled()).isEqualTo(expected);
    }

    static List<Arguments> dashEnabledCases() {
        return List.of(
                Arguments.of("dash configured", new Distribution(null, new Dash(4, 0)), true),
                Arguments.of("dash nil", new Distribution(null, null), false),
                Arguments.of("hls only", new Distribution(new Hls(2, 0), null), false),
                Arguments.of("dual mode", new Distribution(new Hls(2, 0), new Dash(2, 0)), true),
                Arguments.of("empty distribution", Distribution.none(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dashEnabledCases")
    void dashEnabled(String name, Distribution distribution, boolean expected) {
        assertThat(distribution.dashEnabled()).isEqualTo(expected);
    }

    static List<Arguments> dualModeCases() {
        return List.of(
                Arguments.of("both enabled", new Distribution(new Hls(2, 0), new Dash(2, 0)), true),
                Arguments.of("hls only", new Distribution(new Hls(2, 0), null), false),
                Arguments.of("dash only", new Distribution(null, new Dash(4, 0)), false),
                Arguments.of("neither", Distribution.none(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dualModeCases")
    void isDualMode(String name, Distribution distribution, boolean expected) {
        assertThat(distribution.isDualMode()).isEqualTo(expected);
    }

    static List<Arguments> segmentDurationCases() {
        return List.of(
                Arguments.of("hls only", new Distribution(new Hls(6, 0), null), 6),
                Arguments.of("dash only", new Distribution(null, new Dash(4, 0)), 4),
                Arguments.of("dual mode returns hls value", new Distribution(new Hls(2, 0), new Dash(2, 0)), 2),
                Arguments.of("neither returns zero", Distribution.none(), 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("segmentDurationCases")
    void segmentDuration(String name, Distribution distribution, int expected) {
        assertThat(distribution.segmentDuration()).isEqualTo(expected);
    }

    static List<Arguments> windowSizeCases() {
        return List.of(
                Arguments.of("hls only", new Distribution(new Hls(2, 5), null), 5),
                Arguments.of("dash only", new Distribution(null, new Dash(4, 7)), 7),
                Arguments.of("dual mode returns hls value", new Distribution(new Hls(2, 4), new Dash(2, 4)), 4),
                Arguments.of("neither returns default 3", Distribution.none(), 3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("windowSizeCases")
    void windowSize(String name, Distribution distribution, int expected) {
        assertThat(distribution.windowSize()).isEqualTo(expected);
    }
}
