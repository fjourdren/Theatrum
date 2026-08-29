package com.fjourdren.theatrum.domain.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class UrlUtilsTest {

    static Stream<Arguments> joinCases() {
        return Stream.of(
                Arguments.of("base only",
                        "http://localhost:8080", new String[]{}, "http://localhost:8080"),
                Arguments.of("base with single part",
                        "http://localhost:8080", new String[]{"streams"}, "http://localhost:8080/streams"),
                Arguments.of("base with multiple parts",
                        "http://localhost:8080", new String[]{"api", "v1", "streams"},
                        "http://localhost:8080/api/v1/streams"),
                Arguments.of("trailing slash on base",
                        "http://localhost:8080/", new String[]{"streams"}, "http://localhost:8080/streams"),
                Arguments.of("leading slash on parts",
                        "http://localhost:8080", new String[]{"/streams/"}, "http://localhost:8080/streams"),
                Arguments.of("empty parts skipped",
                        "http://localhost:8080", new String[]{"", "streams", "", "live"},
                        "http://localhost:8080/streams/live"),
                Arguments.of("protocol preserved",
                        "https://example.com", new String[]{"path"}, "https://example.com/path"),
                Arguments.of("no protocol",
                        "/base", new String[]{"path"}, "/base/path")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("joinCases")
    void joinUrl(String name, String base, String[] parts, String expected) {
        assertThat(UrlUtils.joinUrl(base, parts)).isEqualTo(expected);
    }
}
