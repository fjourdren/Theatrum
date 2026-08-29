package com.fjourdren.theatrum.infrastructure.ffmpeg;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class HlsPlaylistTest {

    @Test
    void generateMasterPlaylistWrapper(@TempDir Path dir) throws IOException {
        HlsPlaylist.generateMasterPlaylistWrapper(dir);

        String content = Files.readString(dir.resolve(VideoConstants.MASTER_PLAYLIST));

        assertThat(content)
                .contains("#EXTM3U")
                .contains("#EXT-X-STREAM-INF:BANDWIDTH=0")
                .contains(VideoConstants.DEFAULT_QUALITY + "/" + VideoConstants.SUB_PLAYLIST);
    }

    @Test
    void generateMasterPlaylistWrapperWritesExactContent(@TempDir Path dir) throws IOException {
        HlsPlaylist.generateMasterPlaylistWrapper(dir);

        assertThat(Files.readString(dir.resolve(VideoConstants.MASTER_PLAYLIST))).isEqualTo("""
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=0,CODECS="avc1.64001f,mp4a.40.2"
                default/playlist.m3u8
                """);
    }

    @Test
    void generateMasterPlaylistWrapperFailsOnMissingDirectory(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist");

        assertThatIOException()
                .isThrownBy(() -> HlsPlaylist.generateMasterPlaylistWrapper(missing))
                .withMessageContaining("failed to write master playlist wrapper");
    }
}
