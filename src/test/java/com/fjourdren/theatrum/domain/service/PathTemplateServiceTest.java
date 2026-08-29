package com.fjourdren.theatrum.domain.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathTemplateServiceTest {

    private final PathTemplateService service = new PathTemplateService();

    // --- MatchesTemplate ------------------------------------------------------

    static List<Arguments> matchesTemplateCases() {
        return List.of(
                Arguments.of("exact match with single placeholder",
                        "streams/{streamName}/playlist.m3u8", "streams/alice/playlist.m3u8", true),
                Arguments.of("exact match with single placeholder but no match",
                        "streams/{streamName}/playlist.m3u8", "streams/alice/playlist.m3u8/extra", false),
                Arguments.of("exact match with multiple placeholders",
                        "streams/{streamName}/quality/{quality}/segment.ts", "streams/alice/quality/high/segment.ts", true),
                Arguments.of("no placeholders - exact match",
                        "streams/static/playlist.m3u8", "streams/static/playlist.m3u8", true),
                Arguments.of("no placeholders - no match",
                        "streams/static/playlist.m3u8", "streams/other/playlist.m3u8", false),
                Arguments.of("placeholder value with underscores",
                        "streams/{streamName}/playlist.m3u8", "streams/stream_name_123/playlist.m3u8", true),
                Arguments.of("placeholder value with hyphens",
                        "streams/{streamName}/playlist.m3u8", "streams/stream-name-123/playlist.m3u8", true),
                Arguments.of("placeholder value with dots",
                        "streams/{streamName}/playlist.m3u8", "streams/stream.name.123/playlist.m3u8", true),
                Arguments.of("mismatched path structure",
                        "streams/{streamName}/playlist.m3u8", "videos/alice/playlist.m3u8", false),
                Arguments.of("extra path segments in input",
                        "streams/{streamName}/playlist.m3u8", "streams/alice/extra/playlist.m3u8", false),
                Arguments.of("missing path segments in input",
                        "streams/{streamName}/quality/{quality}/playlist.m3u8", "streams/alice/playlist.m3u8", false),
                Arguments.of("empty input", "streams/{streamName}/playlist.m3u8", "", false),
                Arguments.of("empty template", "", "streams/alice/playlist.m3u8", false),
                Arguments.of("both empty", "", "", true),
                Arguments.of("windows path separators in template",
                        "streams\\{streamName}\\playlist.m3u8", "streams/alice/playlist.m3u8", true),
                Arguments.of("windows path separators in input",
                        "streams/{streamName}/playlist.m3u8", "streams\\alice\\playlist.m3u8", true),
                Arguments.of("placeholder with special regex characters in surrounding text",
                        "streams.{streamName}+playlist.m3u8", "streams.alice+playlist.m3u8", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchesTemplateCases")
    void matchesTemplate(String name, String template, String input, boolean expected) {
        assertThat(service.matchesTemplate(template, input)).isEqualTo(expected);
    }

    // --- ExtractValues --------------------------------------------------------

    static List<Arguments> extractValuesCases() {
        return List.of(
                Arguments.of("single placeholder extraction",
                        "streams/{streamName}/playlist.m3u8", "streams/alice/playlist.m3u8",
                        Map.of("streamName", "alice", "FILENAME", "playlist.m3u8")),
                Arguments.of("multiple placeholders extraction",
                        "streams/{streamName}/quality/{quality}/segment.ts", "streams/alice/quality/high/segment.ts",
                        Map.of("streamName", "alice", "quality", "high", "FILENAME", "segment.ts")),
                Arguments.of("no placeholders",
                        "streams/static/playlist.m3u8", "streams/static/playlist.m3u8",
                        Map.of("FILENAME", "playlist.m3u8")),
                Arguments.of("placeholder with complex values",
                        "streams/{streamName}/playlist.m3u8", "streams/stream_name-123.test/playlist.m3u8",
                        Map.of("streamName", "stream_name-123.test", "FILENAME", "playlist.m3u8")),
                Arguments.of("no filename in path",
                        "streams/{streamName}", "streams/alice",
                        Map.of("streamName", "alice", "FILENAME", "alice")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extractValuesCases")
    void extractValues(String name, String template, String input, Map<String, String> expected) {
        assertThat(service.extractValues(template, input)).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void extractValuesMismatchedTemplate() {
        assertThatThrownBy(() -> service.extractValues("streams/{streamName}/playlist.m3u8", "videos/alice/playlist.m3u8"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("input does not match template");
    }

    // --- ReplacePlaceholders --------------------------------------------------

    static List<Arguments> replacePlaceholdersCases() {
        return List.of(
                Arguments.of("single placeholder replacement",
                        "streams/{streamName}/playlist.m3u8", Map.of("streamName", "alice"),
                        "streams/alice/playlist.m3u8"),
                Arguments.of("multiple placeholders replacement",
                        "streams/{streamName}/quality/{quality}/segment.ts",
                        Map.of("streamName", "alice", "quality", "high"),
                        "streams/alice/quality/high/segment.ts"),
                Arguments.of("no placeholders",
                        "streams/static/playlist.m3u8", Map.of(), "streams/static/playlist.m3u8"),
                Arguments.of("missing variable for placeholder",
                        "streams/{streamName}/playlist.m3u8", Map.of(), "streams/{streamName}/playlist.m3u8"),
                Arguments.of("extra variables",
                        "streams/{streamName}/playlist.m3u8", Map.of("streamName", "alice", "extra", "value"),
                        "streams/alice/playlist.m3u8"),
                Arguments.of("valid characters in value",
                        "streams/{streamName}/playlist.m3u8", Map.of("streamName", "alice_123-test.stream"),
                        "streams/alice_123-test.stream/playlist.m3u8"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("replacePlaceholdersCases")
    void replacePlaceholders(String name, String template, Map<String, String> vars, String expected) {
        assertThat(service.replacePlaceholders(template, vars)).isEqualTo(expected);
    }

    static List<Arguments> replacePlaceholdersErrorCases() {
        return List.of(
                Arguments.of("invalid characters in value", "alice@test",
                        "invalid characters in value: only a-z, A-Z, 0-9, _, - and . are allowed"),
                Arguments.of("consecutive dots in value", "alice..test", "consecutive dots are not allowed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("replacePlaceholdersErrorCases")
    void replacePlaceholdersRejectsBadValues(String name, String value, String message) {
        assertThatThrownBy(() -> service.replacePlaceholders("streams/{streamName}/playlist.m3u8", Map.of("streamName", value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Nested
    class BuiltinFunctions {

        @Test
        void uuidReplacementViaVars() {
            assertThat(service.replacePlaceholders("streams/{%UUID%}/playlist.m3u8",
                    Map.of("UUID", "550e8400-e29b-41d4-a716-446655440000")))
                    .isEqualTo("streams/550e8400-e29b-41d4-a716-446655440000/playlist.m3u8");
        }

        @Test
        void startingDateReplacementViaVars() {
            assertThat(service.replacePlaceholders("livestreams/{%STARTING_DATE%}/output",
                    Map.of("STARTING_DATE", "2026-02-07_15-30-00")))
                    .isEqualTo("livestreams/2026-02-07_15-30-00/output");
        }

        @Test
        void mixedBuiltinAndUserVariables() {
            assertThat(service.replacePlaceholders("livestreams/{username}/{%UUID%}/playlist.m3u8",
                    Map.of("username", "alice", "UUID", "abcd-1234")))
                    .isEqualTo("livestreams/alice/abcd-1234/playlist.m3u8");
        }

        @Test
        void unknownBuiltinFunctionReturnsError() {
            assertThatThrownBy(() -> service.replacePlaceholders("streams/{%UNKNOWN%}/playlist.m3u8", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unknown builtin function: UNKNOWN");
        }

        @Test
        void noConflictBetweenBuiltinAndUserVariableSyntax() {
            assertThat(service.replacePlaceholders("{name}/{%UUID%}", Map.of("name", "bob", "UUID", "abcd-1234")))
                    .isEqualTo("bob/abcd-1234");
        }

        @Test
        void knownBuiltinNotInVarsIsLeftAsIs() {
            // UUID not in vars -> left as literal for stream key computation
            assertThat(service.replacePlaceholders("livestreams/{username}/{%UUID%}", Map.of("username", "alice")))
                    .isEqualTo("livestreams/alice/{%UUID%}");
        }

        @Test
        void multipleOccurrencesOfSameBuiltinUseSameValueFromVars() {
            assertThat(service.replacePlaceholders("{%UUID%}/{%UUID%}", Map.of("UUID", "same-uuid")))
                    .isEqualTo("same-uuid/same-uuid");
        }
    }

    // --- GenerateBuiltinVars --------------------------------------------------

    @Nested
    class GenerateBuiltinVars {

        @Test
        void generatesUuidWhenTemplateContainsUuidBuiltin() {
            service.registerBuiltinFunc("UUID", () -> "test-uuid-value");
            assertThat(service.generateBuiltinVars("streams/{%UUID%}/output")).containsEntry("UUID", "test-uuid-value");
        }

        @Test
        void generatesStartingDateWhenTemplateContainsIt() {
            service.registerBuiltinFunc("STARTING_DATE", () -> "2026-02-07_15-30-00");
            assertThat(service.generateBuiltinVars("livestreams/{%STARTING_DATE%}/output"))
                    .containsEntry("STARTING_DATE", "2026-02-07_15-30-00");
        }

        @Test
        void returnsEmptyMapWhenNoBuiltinsInTemplate() {
            assertThat(service.generateBuiltinVars("streams/{username}/output")).isEmpty();
        }

        @Test
        void generatesEachBuiltinOnlyOnceEvenIfRepeated() {
            var callCount = new AtomicInteger();
            service.registerBuiltinFunc("UUID", () -> {
                callCount.incrementAndGet();
                return "uuid-value";
            });

            var vars = service.generateBuiltinVars("{%UUID%}/{%UUID%}");

            assertThat(callCount).hasValue(1);
            assertThat(vars).containsEntry("UUID", "uuid-value");
        }

        @Test
        void ignoresUnknownBuiltins() {
            assertThat(service.generateBuiltinVars("streams/{%UNKNOWN%}/output")).doesNotContainKey("UNKNOWN");
        }

        @Test
        void defaultBuiltinsAreRegistered() {
            var vars = service.generateBuiltinVars("{%STARTING_DATE%}/{%UUID%}");
            assertThat(vars.get("STARTING_DATE")).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");
            assertThat(vars.get("UUID")).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }

    // --- sanitizeValue (package-private, mirrors the Go unexported method) -----

    static List<Arguments> sanitizeValueCases() {
        return List.of(
                Arguments.of("valid alphanumeric", "alice123", "alice123"),
                Arguments.of("valid with underscores", "alice_test_123", "alice_test_123"),
                Arguments.of("valid with hyphens", "alice-test-123", "alice-test-123"),
                Arguments.of("valid with single dots", "alice.test.123", "alice.test.123"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sanitizeValueCases")
    void sanitizeValue(String name, String value, String expected) {
        assertThat(service.sanitizeValue(value)).isEqualTo(expected);
    }

    static List<Arguments> sanitizeValueErrorCases() {
        return List.of(
                Arguments.of("invalid with special characters", "alice@test"),
                Arguments.of("invalid with consecutive dots", "alice..test"),
                Arguments.of("invalid with spaces", "alice test"),
                Arguments.of("empty value", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sanitizeValueErrorCases")
    void sanitizeValueRejects(String name, String value) {
        assertThatThrownBy(() -> service.sanitizeValue(value)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- filepath.Clean semantics, exercised through replacePlaceholders -------

    static List<Arguments> cleanCases() {
        return List.of(
                Arguments.of("collapses duplicate slashes", "streams//alice///out", "streams/alice/out"),
                Arguments.of("drops dot segments", "./streams/./alice", "streams/alice"),
                Arguments.of("resolves parent segments", "streams/alice/../bob", "streams/bob"),
                Arguments.of("keeps leading parent on relative path", "../streams", "../streams"),
                Arguments.of("drops parent at root", "/../streams", "/streams"),
                Arguments.of("strips trailing slash", "streams/alice/", "streams/alice"),
                Arguments.of("empty becomes dot", "", "."),
                Arguments.of("backslashes become forward slashes", "streams\\alice", "streams/alice"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cleanCases")
    void replacePlaceholdersCleansPath(String name, String template, String expected) {
        assertThat(service.replacePlaceholders(template, Map.of())).isEqualTo(expected);
    }
}
