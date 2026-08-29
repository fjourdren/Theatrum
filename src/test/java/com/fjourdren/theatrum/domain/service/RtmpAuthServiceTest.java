package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.exception.AuthenticationException;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RtmpAuthServiceTest {

    private static RtmpAuthService serviceWith(Map<String, Stream> channels) {
        return new RtmpAuthService(channels, new PathTemplateService());
    }

    private static RtmpAuthService emptyService() {
        return serviceWith(new LinkedHashMap<>());
    }

    /** Hex-encodes the low byte of each value, like Go's hex.EncodeToString. */
    private static String hex(int... bytes) {
        var sb = new StringBuilder();
        for (int b : bytes) {
            sb.append("%02x".formatted(b & 0xFF));
        }
        return sb.toString();
    }

    // --- IsAuthorized ---------------------------------------------------------

    private static Map<String, Stream> twoChannels() {
        var channels = new LinkedHashMap<String, Stream>();
        channels.put("/user/{username}", Stream.builder().type(StreamType.LIVE).path("live/{username}").build());
        channels.put("/premium/{room_id}/{user}",
                Stream.builder().type(StreamType.LIVE).path("premium/{room_id}/{user}").build());
        return channels;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "matching single var,   rtmp://localhost/user/alice,        true",
            "matching multi var,    rtmp://localhost/premium/42/bob,    true",
            "no match,              rtmp://localhost/unknown/foo,       false",
            "partial match,         rtmp://localhost/user/,             false",
            "empty tcurl,           '',                                 false",
            "just hostname,         rtmp://localhost,                   false",
    })
    void isAuthorized(String name, String tcUrl, boolean expected) {
        assertThat(serviceWith(twoChannels()).isAuthorized(tcUrl)).isEqualTo(expected);
    }

    // --- ExtractChannel -------------------------------------------------------

    @Nested
    class ExtractChannel {

        private final RtmpAuthService service = serviceWith(twoChannels());

        @Test
        void singleVariableExtraction() {
            var match = service.extractChannel("rtmp://localhost/user/alice");

            assertThat(match).isPresent();
            assertThat(match.get().stream().path()).isEqualTo("live/{username}");
            assertThat(match.get().vars()).containsEntry("username", "alice");
            assertThat(match.get().pattern()).isEqualTo("/user/{username}");
        }

        @Test
        void multiVariableExtraction() {
            var match = service.extractChannel("rtmp://localhost/premium/42/bob");

            assertThat(match).isPresent();
            assertThat(match.get().vars())
                    .containsEntry("room_id", "42")
                    .containsEntry("user", "bob");
        }

        @Test
        void noMatch() {
            assertThat(service.extractChannel("rtmp://localhost/unknown/foo")).isEmpty();
        }
    }

    // --- ValidateAuthentication ----------------------------------------------

    @Nested
    class ValidateAuthentication {

        private final RtmpAuthService service = emptyService();

        private final Stream stream = Stream.builder()
                .liveStreamKey("secretkey")
                .authTokenTemplate("{username}")
                .build();

        private final String validToken = emptyService().xorString("secretkey", "alice");

        @Test
        void acceptsValidToken() {
            service.validateAuthentication(stream, Map.of("username", "alice"), validToken);
        }

        @Test
        void rejectsInvalidToken() {
            assertThatThrownBy(() -> service.validateAuthentication(stream, Map.of("username", "alice"), "wrongtoken"))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("invalid authentication token");
        }

        @Test
        void rejectsEmptyPublishingName() {
            assertThatThrownBy(() -> service.validateAuthentication(stream, Map.of("username", "alice"), ""))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("empty publishingName provided");
        }

        @Test
        void rejectsMissingVars() {
            assertThatThrownBy(() -> service.validateAuthentication(stream, Map.of(), validToken))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("authentication failed: missing variables in URL: [username]");
        }

        @Test
        void rejectsEmptyLiveStreamKey() {
            var keyless = Stream.builder().authTokenTemplate("{username}").build();

            assertThatThrownBy(() -> service.validateAuthentication(keyless, Map.of("username", "alice"), validToken))
                    .isInstanceOf(AuthenticationException.class);
        }
    }

    @Nested
    class ValidateAuthenticationMultiVariable {

        private final RtmpAuthService service = emptyService();

        private final Stream stream = Stream.builder()
                .liveStreamKey("secretkey")
                .authTokenTemplate("{room_id}{username}")
                .build();

        /** The XOR input is "42alice" — room_id and username concatenated. */
        private final String validToken = emptyService().xorString("secretkey", "42alice");

        @Test
        void acceptsValidMultiVariableToken() {
            service.validateAuthentication(stream, Map.of("room_id", "42", "username", "alice"), validToken);
        }

        @Test
        void rejectsWrongVariableValues() {
            assertThatThrownBy(() -> service.validateAuthentication(
                    stream, Map.of("room_id", "99", "username", "alice"), validToken))
                    .isInstanceOf(AuthenticationException.class);
        }

        @Test
        void rejectsPartiallyMissingVariables() {
            assertThatThrownBy(() -> service.validateAuthentication(stream, Map.of("username", "alice"), validToken))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessageContaining("missing variables in URL: [room_id]");
        }
    }

    // --- BuildStreamPath ------------------------------------------------------

    @Test
    void buildStreamPathResolvesPlaceholders() {
        var stream = Stream.builder().path("live/{username}").build();

        assertThat(emptyService().buildStreamPath(stream, Map.of("username", "alice"))).isEqualTo("live/alice");
    }

    // --- xorString ------------------------------------------------------------

    static List<Arguments> xorCases() {
        return List.of(
                Arguments.of("basic XOR", "key", "abc",
                        hex('a' ^ 'k', 'b' ^ 'e', 'c' ^ 'y')),
                Arguments.of("key cycling", "ab", "wxyz",
                        hex('w' ^ 'a', 'x' ^ 'b', 'y' ^ 'a', 'z' ^ 'b')),
                Arguments.of("single char key", "x", "hello",
                        hex('h' ^ 'x', 'e' ^ 'x', 'l' ^ 'x', 'l' ^ 'x', 'o' ^ 'x')));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("xorCases")
    void xorString(String name, String key, String input, String expected) {
        assertThat(emptyService().xorString(key, input)).isEqualTo(expected);
    }

    // --- patternToRegex -------------------------------------------------------

    static List<Arguments> patternCases() {
        return List.of(
                Arguments.of("single variable", "/user/{username}", 1, "/user/alice", "/user/alice/extra"),
                Arguments.of("multiple variables", "/room/{room_id}/{user}", 2, "/room/42/bob", "/room/42"),
                Arguments.of("no variables", "/static/path", 0, "/static/path", "/static/other"),
                Arguments.of("special regex chars in pattern", "/path.with.dots/{id}", 1,
                        "/path.with.dots/123", "/pathXwithXdots/123"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("patternCases")
    void patternToRegex(String name, String pattern, int expectedVarCount, String shouldMatch, String shouldFail) {
        var service = emptyService();
        var compiled = service.patternToRegex(pattern);

        assertThat(compiled.varNames()).hasSize(expectedVarCount);
        assertThat(service.extractVariables(compiled, shouldMatch)).isPresent();
        assertThat(service.extractVariables(compiled, shouldFail)).isEmpty();
    }

    // --- extractVariables -----------------------------------------------------

    @Nested
    class ExtractVariables {

        private final RtmpAuthService service = emptyService();

        @Test
        void matchWithVariables() {
            var vars = service.extractVariables(service.patternToRegex("/user/{username}"), "/user/alice");

            assertThat(vars).contains(Map.of("username", "alice"));
        }

        @Test
        void noMatch() {
            assertThat(service.extractVariables(service.patternToRegex("/user/{username}"), "/other/path")).isEmpty();
        }

        @Test
        void multipleVariables() {
            var vars = service.extractVariables(service.patternToRegex("/room/{room_id}/{user}"), "/room/42/bob");

            assertThat(vars).contains(Map.of("room_id", "42", "user", "bob"));
        }
    }

    // --- extractPathFromTCURL -------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "valid URL,                  rtmp://localhost:1935/user/alice, /user/alice",
            "URL with port only,         rtmp://localhost:1935/app,        /app",
            "no path,                    rtmp://localhost,                 ''",
            "malformed URL returns input, ://invalid,                      ://invalid",
    })
    void extractPathFromTcUrl(String name, String tcUrl, String expected) {
        assertThat(emptyService().extractPathFromTcUrl(tcUrl)).isEqualTo(expected);
    }
}
