package com.fjourdren.theatrum.domain.model;

/** Recording settings. {@code path} may be empty for in-place recording. */
public record Record(boolean enabled, String path) {

    public static Record disabled() {
        return new Record(false, "");
    }
}
