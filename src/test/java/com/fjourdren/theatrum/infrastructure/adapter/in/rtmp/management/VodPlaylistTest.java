package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VodPlaylistTest {

    @Test
    void buildVodPlaylistProducesAValidVodManifest() {
        List<String> segments = List.of("segment_000.ts", "segment_001.ts", "segment_002.ts");

        String playlist = VodPlaylist.buildVodPlaylist(segments, 4);

        assertThat(playlist).startsWith("#EXTM3U\n")
                .contains("#EXT-X-VERSION:3")
                .contains("#EXT-X-TARGETDURATION:4")
                .contains("#EXT-X-PLAYLIST-TYPE:VOD")
                .contains("#EXT-X-MEDIA-SEQUENCE:0")
                .contains("#EXTINF:4.000000,")
                .endsWith("#EXT-X-ENDLIST\n");
        assertThat(playlist.indexOf("segment_000.ts"))
                .isLessThan(playlist.indexOf("segment_001.ts"));
        assertThat(playlist.indexOf("segment_001.ts"))
                .isLessThan(playlist.indexOf("segment_002.ts"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "segment_000.ts, 0",
            "segment_001.ts, 1",
            "segment_123.ts, 123",
            "other_file.ts,  0",
            "segment_005,    0",
            "seg_001.ts,     0"
    })
    void extractSegmentNumber(String filename, int expected) {
        assertThat(VodPlaylist.extractSegmentNumber(filename)).isEqualTo(expected);
    }

    @Nested
    class FindSegments {

        @Test
        void findsAndSortsSegments(@TempDir Path dir) throws IOException {
            for (String name : List.of("segment_002.ts", "segment_000.ts", "segment_001.ts")) {
                Files.writeString(dir.resolve(name), "data");
            }
            Files.writeString(dir.resolve("playlist.m3u8"), "#EXTM3U");
            Files.createDirectory(dir.resolve("subdir"));

            assertThat(VodPlaylist.findSegments(dir))
                    .containsExactly("segment_000.ts", "segment_001.ts", "segment_002.ts");
        }

        @Test
        void emptyDirectory(@TempDir Path dir) throws IOException {
            assertThat(VodPlaylist.findSegments(dir)).isEmpty();
        }

        @Test
        void nonexistentDirectory() {
            assertThatThrownBy(() -> VodPlaylist.findSegments(Path.of("/nonexistent/path")))
                    .isInstanceOf(IOException.class);
        }
    }

    @Nested
    class GenerateVodPlaylist {

        @Test
        void writesPlaylistFile(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("segment_000.ts"), "data");
            Files.writeString(dir.resolve("segment_001.ts"), "data");

            VodPlaylist.generateVodPlaylist(dir, 4);

            assertThat(dir.resolve(VideoConstants.SUB_PLAYLIST))
                    .content()
                    .contains("#EXTM3U")
                    .contains("segment_000.ts")
                    .contains("segment_001.ts");
        }

        @Test
        void failsWhenNoSegmentsFound(@TempDir Path dir) {
            assertThatThrownBy(() -> VodPlaylist.generateVodPlaylist(dir, 4))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no segments found");
        }
    }
}
