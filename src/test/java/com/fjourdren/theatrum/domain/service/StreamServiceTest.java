package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Dash;
import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamServiceTest {

    private static final AppPaths PATHS = new AppPaths(Path.of("data"), Path.of("frontend"));

    private final StreamService service = new StreamService(new PathTemplateService(), PATHS);

    private static final Distribution HLS = Distribution.ofHls(new Hls(6, 0));
    private static final Distribution DASH = Distribution.ofDash(new Dash(6, 0));
    private static final Distribution DUAL = new Distribution(new Hls(2, 0), new Dash(2, 0));

    private static Stream stream(String path, Distribution distribution) {
        return Stream.builder().path(path).distribution(distribution).build();
    }

    static List<Arguments> storagePathCases() {
        return List.of(
                Arguments.of("default quality injected",
                        stream("videos/{username}", HLS),
                        Map.of("username", "alice"),
                        "videos/alice/default"),
                Arguments.of("explicit quality in vars",
                        stream("videos/{username}", HLS),
                        Map.of("username", "alice", "quality", "high"),
                        "videos/alice/high"),
                Arguments.of("quality placeholder in path",
                        stream("videos/{username}/{quality}", HLS),
                        Map.of("username", "alice", "quality", "medium"),
                        "videos/alice/medium"),
                Arguments.of("master.m3u8 resource excludes quality dir",
                        stream("videos/{username}", HLS),
                        Map.of("username", "alice", "resource", VideoConstants.MASTER_PLAYLIST),
                        "videos/alice"),
                Arguments.of("dash manifest excludes quality dir",
                        stream("videos/{username}", DASH),
                        Map.of("username", "alice", "resource", VideoConstants.DASH_MANIFEST),
                        "videos/alice"),
                Arguments.of("dash-enabled stream skips quality subdir",
                        stream("videos/{username}", DASH),
                        Map.of("username", "alice", "quality", "high"),
                        "videos/alice"),
                Arguments.of("dual mode skips quality subdir",
                        stream("videos/{username}", DUAL),
                        Map.of("username", "alice", "quality", "high"),
                        "videos/alice"),
                Arguments.of("dual mode dash manifest excludes quality dir",
                        stream("videos/{username}", DUAL),
                        Map.of("username", "alice", "resource", VideoConstants.DASH_MANIFEST),
                        "videos/alice"),
                Arguments.of("dual mode master playlist excludes quality dir",
                        stream("videos/{username}", DUAL),
                        Map.of("username", "alice", "resource", VideoConstants.MASTER_PLAYLIST),
                        "videos/alice"),
                Arguments.of("dash m4s segment excludes quality dir",
                        stream("videos/{username}", Distribution.ofDash(new Dash(4, 0))),
                        Map.of("username", "alice", "resource", "chunk-stream0-00001.m4s"),
                        "videos/alice"),
                Arguments.of("thumbnail.png resource excludes quality dir",
                        stream("videos/{username}", HLS),
                        Map.of("username", "alice", "resource", VideoConstants.THUMBNAIL_FILE),
                        "videos/alice"),
                Arguments.of("regular segment includes quality dir",
                        stream("videos/{username}", HLS),
                        Map.of("username", "alice", "resource", "segment_000.ts"),
                        "videos/alice/default"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storagePathCases")
    void getStreamStoragePath(String name, Stream stream, Map<String, String> vars, String expectedSuffix) {
        Path got = service.getStreamStoragePath(stream, vars);

        assertThat(got.toString()).endsWith(expectedSuffix).startsWith("data/");
    }

    @Test
    void getStreamStoragePathDoesNotMutateCallerVars() {
        Map<String, String> vars = new java.util.HashMap<>(Map.of("username", "alice"));

        service.getStreamStoragePath(stream("videos/{username}", HLS), vars);

        assertThat(vars).containsOnlyKeys("username");
    }

    @Test
    void getStreamStoragePathPropagatesSanitizationErrors() {
        assertThatThrownBy(() -> service.getStreamStoragePath(
                stream("videos/{username}", Distribution.none()),
                Map.of("username", "alice/../etc")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
