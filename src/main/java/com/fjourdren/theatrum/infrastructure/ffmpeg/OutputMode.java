package com.fjourdren.theatrum.infrastructure.ffmpeg;

import com.fjourdren.theatrum.domain.model.Distribution;

/** The muxer FFmpeg uses for a stream. */
public enum OutputMode {

    /** HLS-only: mpegts segments, quality subdirs. */
    HLS,

    /** DASH-only: m4s segments, flat layout. */
    DASH,

    /** DASH muxer with {@code -hls_playlist 1}: both MPD and M3U8 over shared m4s segments. */
    DUAL;

    public static OutputMode determine(Distribution dist) {
        if (dist.isDualMode()) {
            return DUAL;
        }
        return dist.dashEnabled() ? DASH : HLS;
    }
}
