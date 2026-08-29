package com.fjourdren.theatrum.infrastructure.ffmpeg;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@UtilityClass
public final class HlsPlaylist {

    /**
     * Writes a {@code master.m3u8} at {@code rootDir} referencing {@code default/playlist.m3u8}, so
     * passthrough (single-quality) streams stay discoverable through the master playlist.
     */
    public static void generateMasterPlaylistWrapper(Path rootDir) throws IOException {
        String content = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=0,CODECS="avc1.64001f,mp4a.40.2"
                """ + VideoConstants.DEFAULT_QUALITY + "/" + VideoConstants.SUB_PLAYLIST + "\n";

        Path playlistPath = rootDir.resolve(VideoConstants.MASTER_PLAYLIST);
        try {
            Files.writeString(playlistPath, content);
        } catch (IOException e) {
            throw new IOException("failed to write master playlist wrapper " + playlistPath, e);
        }
    }
}
