package com.fjourdren.theatrum.infrastructure.adapter.out.persistence;

import com.fjourdren.theatrum.domain.model.FileMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FileAccessTest {

    private final FileAccess fileAccess = new FileAccess();

    @TempDir
    Path tmp;

    private Path touch(String relative) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        return file;
    }

    /** Patterns are absolute so the walk root and the depth budget line up with the temp dir. */
    private String pattern(String suffix) {
        return tmp + "/" + suffix;
    }

    // ---------------------------------------------------------------- searchFiles

    @Test
    void extractsPlaceholderVariablesAndFilename() throws IOException {
        touch("data/john/stream1/video1.mp4");
        touch("data/alice/stream2/video2.mp4");

        var matches = fileAccess.searchFiles(pattern("data/{username}/{stream_name}"), List.of());

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).path()).isEqualTo(tmp.resolve("data/alice/stream2/video2.mp4"));
        assertThat(matches.get(0).vars()).isEqualTo(
                Map.of("username", "alice", "stream_name", "stream2", "FILENAME", "video2.mp4"));
        assertThat(matches.get(1).path()).isEqualTo(tmp.resolve("data/john/stream1/video1.mp4"));
        assertThat(matches.get(1).vars()).isEqualTo(
                Map.of("username", "john", "stream_name", "stream1", "FILENAME", "video1.mp4"));
    }

    @Test
    void filtersByExtensionCaseInsensitively() throws IOException {
        touch("data/john/clip.MP4");
        touch("data/john/notes.txt");
        touch("data/john/noext");

        var matches = fileAccess.searchFiles(pattern("data/{username}"), List.of(".mp4"));

        assertThat(matches).singleElement()
                .extracting(FileMatch::path).isEqualTo(tmp.resolve("data/john/clip.MP4"));
    }

    @Test
    void emptyExtensionListKeepsEveryFile() throws IOException {
        touch("data/john/clip.mp4");
        touch("data/john/notes.txt");

        var matches = fileAccess.searchFiles(pattern("data/{username}"), List.of());

        assertThat(matches).hasSize(2);
    }

    @Test
    void matchesExplicitFilenameAndRejectsSiblingsAndDeeperNamesakes() throws IOException {
        touch("data/john/master.m3u8");
        touch("data/john/other.m3u8");
        touch("data/john/sub/master.m3u8");

        var matches = fileAccess.searchFiles(pattern("data/{username}/master.m3u8"), List.of());

        assertThat(matches).singleElement()
                .extracting(FileMatch::path).isEqualTo(tmp.resolve("data/john/master.m3u8"));
        assertThat(matches.getFirst().vars())
                .isEqualTo(Map.of("username", "john", "FILENAME", "master.m3u8"));
    }

    /** Ported quirk: a pattern not ending in '/' ends in a *filename*, even when it names a directory. */
    @Test
    void trailingLiteralSegmentIsTreatedAsAFilenameNotADirectory() throws IOException {
        touch("data/john/default/playlist.m3u8");
        touch("data/alice/default");

        var matches = fileAccess.searchFiles(pattern("data/{username}/default"), List.of());

        assertThat(matches).singleElement()
                .extracting(FileMatch::path).isEqualTo(tmp.resolve("data/alice/default"));
    }

    @Test
    void prunesDirectoriesDeeperThanThePattern() throws IOException {
        touch("data/john/clip.mp4");
        touch("data/john/nested/deep.mp4");

        var matches = fileAccess.searchFiles(pattern("data/{username}"), List.of());

        assertThat(matches).singleElement()
                .extracting(FileMatch::path).isEqualTo(tmp.resolve("data/john/clip.mp4"));
    }

    @Test
    void literalPatternWithoutPlaceholdersMatchesTheFileItself() throws IOException {
        touch("data/master.m3u8");
        touch("data/other.m3u8");

        var matches = fileAccess.searchFiles(pattern("data/master.m3u8"), List.of());

        assertThat(matches).singleElement()
                .extracting(FileMatch::path).isEqualTo(tmp.resolve("data/master.m3u8"));
        assertThat(matches.getFirst().vars()).isEqualTo(Map.of("FILENAME", "master.m3u8"));
    }

    @Test
    void returnsEmptyListWhenNothingMatches() throws IOException {
        touch("data/john/clip.mp4");

        assertThat(fileAccess.searchFiles(pattern("data/{username}/master.m3u8"), List.of())).isEmpty();
    }

    @Test
    void propagatesWalkErrorWhenRootIsMissing() {
        assertThatExceptionOfType(NoSuchFileException.class)
                .isThrownBy(() -> fileAccess.searchFiles(pattern("missing/{username}"), List.of()));
    }

    @Test
    void rejectsUnclosedPlaceholder() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fileAccess.searchFiles("data/{username", List.of()))
                .withMessage("unclosed placeholder in pattern \"data/{username\"");
    }

    @Test
    void rejectsEmptyPlaceholder() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fileAccess.searchFiles("data/{}", List.of()))
                .withMessage("empty placeholder in pattern \"data/{}\"");
    }

    // ------------------------------------------------------------ validatePattern

    static List<Arguments> invalidPatterns() {
        return List.of(
                Arguments.of("data/../etc", "pattern cannot contain '..' (path traversal attempt)"),
                Arguments.of("data\\evil", "pattern cannot contain backslashes (use forward slashes)"),
                Arguments.of("data//videos", "pattern cannot contain empty segments"),
                Arguments.of("data/videos/", "pattern cannot contain empty segments"),
                Arguments.of("data/%00", "pattern contains potentially dangerous character: %00"),
                Arguments.of("data/%2etest", "pattern contains potentially dangerous character: %2e"),
                Arguments.of("data/%2ftest", "pattern contains potentially dangerous character: %2f"),
                Arguments.of("data/%5ctest", "pattern contains potentially dangerous character: %5c"),
                Arguments.of("data/~root", "pattern contains potentially dangerous character: ~"),
                Arguments.of("data/a|b", "pattern contains potentially dangerous character: |"),
                Arguments.of("data/a>b", "pattern contains potentially dangerous character: >"),
                Arguments.of("data/a<b", "pattern contains potentially dangerous character: <"),
                Arguments.of("data/*", "pattern contains potentially dangerous character: *"),
                Arguments.of("data/a?", "pattern contains potentially dangerous character: ?"),
                Arguments.of("data/{{username}}", "nested placeholders are not allowed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPatterns")
    void rejectsDangerousPatterns(String pattern, String message) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fileAccess.searchFiles(pattern, List.of()))
                .withMessage(message);
    }

    /** Ported quirk: Go compares the segment *value* to segments[0], so a leading slash whitelists every empty segment. */
    @Test
    void leadingSlashMakesEmptySegmentsAcceptable() {
        assertThatExceptionOfType(NoSuchFileException.class)
                .isThrownBy(() -> fileAccess.searchFiles("/nonexistent-theatrum//dir", List.of()));
    }

    // -------------------------------------------------------------- thin wrappers

    @Test
    void writeReadSizeDeleteRoundTrip() throws IOException {
        Path file = tmp.resolve("views.txt");

        fileAccess.writeFile(file, "42".getBytes(UTF_8));
        assertThat(new String(fileAccess.readFile(file), UTF_8)).isEqualTo("42");
        assertThat(fileAccess.getFileSize(file)).isEqualTo(2L);

        fileAccess.deleteFile(file);
        assertThat(file).doesNotExist();
    }

    @Test
    void writeFileDoesNotCreateParentDirectories() {
        assertThatIOException()
                .isThrownBy(() -> fileAccess.writeFile(tmp.resolve("a/b/c.txt"), "x".getBytes(UTF_8)));
    }

    @Test
    void deleteFileFailsOnMissingFile() {
        assertThatExceptionOfType(NoSuchFileException.class)
                .isThrownBy(() -> fileAccess.deleteFile(tmp.resolve("nope.txt")));
    }

    @Test
    void listFilesMatchesGlobSorted() throws IOException {
        touch("seg/2.ts");
        touch("seg/1.ts");
        touch("seg/playlist.m3u8");

        assertThat(fileAccess.listFiles(tmp + "/seg/*.ts"))
                .containsExactly(tmp.resolve("seg/1.ts"), tmp.resolve("seg/2.ts"));
    }

    @Test
    void listFilesReturnsEmptyWhenDirectoryIsMissing() throws IOException {
        assertThat(fileAccess.listFiles(tmp + "/missing/*.ts")).isEmpty();
    }
}
