package com.fjourdren.theatrum.domain.model;

public enum StreamType {

    VIDEO_UNENCODED("video_unencoded"),
    VIDEO_ENCODED("video_encoded"),
    LIVE("live"),
    RESTREAM("restream");

    private final String value;

    StreamType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Returns the matching type, or null when the value is unknown (mirrors Go's untyped string cast). */
    public static StreamType fromValue(String value) {
        for (StreamType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
