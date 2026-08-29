package com.fjourdren.theatrum.domain.model;

import com.fjourdren.theatrum.domain.constant.ConfigConstants;

/** Distribution formats configured for a stream. A null component means that format is disabled. */
public record Distribution(Hls hls, Dash dash) {

    public static Distribution none() {
        return new Distribution(null, null);
    }

    public static Distribution ofHls(Hls hls) {
        return new Distribution(hls, null);
    }

    public static Distribution ofDash(Dash dash) {
        return new Distribution(null, dash);
    }

    public boolean hlsEnabled() {
        return hls != null;
    }

    public boolean dashEnabled() {
        return dash != null;
    }

    public boolean isDualMode() {
        return hlsEnabled() && dashEnabled();
    }

    /**
     * Segment duration from whichever format is configured. In dual mode both must match
     * (enforced by config validation), so either is fine.
     */
    public int segmentDuration() {
        if (hls != null) {
            return hls.segmentDuration();
        }
        if (dash != null) {
            return dash.segmentDuration();
        }
        return 0;
    }

    /** Window size from whichever format is configured, defaulting to 3. */
    public int windowSize() {
        if (hls != null) {
            return hls.windowSize();
        }
        if (dash != null) {
            return dash.windowSize();
        }
        return ConfigConstants.DEFAULT_WINDOW_SIZE;
    }
}
