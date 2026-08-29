package com.fjourdren.theatrum.domain.constant;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public final class VideoConstants {

    public static final String DEFAULT_QUALITY = "default";

    // ------------------------------------------------------------ Extensions

    /** The only container Theatrum accepts as an unencoded source. */
    public static final String EXT_VIDEO = ".mp4";

    public static final String EXT_HLS_PLAYLIST = ".m3u8";
    public static final String EXT_MPEGTS_SEGMENT = ".ts";
    public static final String EXT_DASH_MANIFEST = ".mpd";
    public static final String EXT_DASH_SEGMENT = ".m4s";
    public static final String EXT_THUMBNAIL = ".png";

    // ----------------------------------------------------------- File names

    public static final String MASTER_PLAYLIST = "master" + EXT_HLS_PLAYLIST;
    public static final String SUB_PLAYLIST = "playlist" + EXT_HLS_PLAYLIST;
    public static final String SEGMENT_NAME = "segment_%03d" + EXT_MPEGTS_SEGMENT;
    public static final String DASH_MANIFEST = "manifest" + EXT_DASH_MANIFEST;
    public static final String DASH_INIT_SEG_NAME = "init-stream$RepresentationID$" + EXT_DASH_SEGMENT;
    public static final String DASH_SEG_NAME = "chunk-stream$RepresentationID$-$Number%05d$" + EXT_DASH_SEGMENT;
    public static final String VIEWERS_FILE = "viewers.txt";
    public static final String VIEWS_FILE = "views.txt";
    public static final String THUMBNAIL_FILE = "thumbnail" + EXT_THUMBNAIL;

    // ---------------------------------------------------------------- Globs

    /** Matches the segments FFmpeg writes for an HLS output. */
    public static final String MPEGTS_SEGMENT_GLOB = "*" + EXT_MPEGTS_SEGMENT;

    /** Matches the media segments FFmpeg writes for a DASH output. */
    public static final String DASH_SEGMENT_GLOB = "chunk-stream*" + EXT_DASH_SEGMENT;

    /** Matches the init segments FFmpeg writes for a DASH output. */
    public static final String DASH_INIT_SEGMENT_GLOB = "init-stream*" + EXT_DASH_SEGMENT;

    /** The video representation a thumbnail is grabbed from when several exist. */
    public static final String DASH_FIRST_INIT_SEGMENT = "init-stream0";

    public static final List<String> VALID_VIDEO_EXTENSIONS = List.of(EXT_VIDEO);
    public static final List<String> VALID_MASTER_PLAYLIST_EXTENSIONS = List.of(EXT_HLS_PLAYLIST);
}
