package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds VOD-ready M3U8 playlists from the segments left on disk when a recording ends. */
@UtilityClass
public final class VodPlaylist {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("^segment_(\\d+)\\.ts$");

    /** Scans {@code dir} for segments and writes a VOD playlist next to them. */
    public static void generateVodPlaylist(Path dir, int segmentDuration) throws IOException {
        List<String> segments = findSegments(dir);
        if (segments.isEmpty()) {
            throw new IOException("no segments found in " + dir);
        }

        Files.writeString(dir.resolve(VideoConstants.SUB_PLAYLIST), buildVodPlaylist(segments, segmentDuration));
    }

    /** Returns the segment filenames in {@code dir}, sorted by their numeric index. */
    static List<String> findSegments(Path dir) throws IOException {
        List<String> segments = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (SEGMENT_PATTERN.matcher(name).matches()) {
                    segments.add(name);
                }
            }
        }
        segments.sort(Comparator.comparingInt(VodPlaylist::extractSegmentNumber));
        return segments;
    }

    /** Extracts the numeric index from a segment filename, or 0 when it doesn't match. */
    static int extractSegmentNumber(String filename) {
        Matcher matcher = SEGMENT_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            return 0;
        }
        return Integer.parseInt(matcher.group(1));
    }

    static String buildVodPlaylist(List<String> segments, int segmentDuration) {
        StringBuilder playlist = new StringBuilder()
                .append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-TARGETDURATION:").append(segmentDuration).append('\n')
                .append("#EXT-X-PLAYLIST-TYPE:VOD\n")
                .append("#EXT-X-MEDIA-SEQUENCE:0\n");

        for (String segment : segments) {
            playlist.append("#EXTINF:").append(segmentDuration).append(".000000,\n")
                    .append(segment).append('\n');
        }

        return playlist.append("#EXT-X-ENDLIST\n").toString();
    }
}
