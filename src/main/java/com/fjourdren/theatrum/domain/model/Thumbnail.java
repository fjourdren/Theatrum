package com.fjourdren.theatrum.domain.model;

/**
 * @param interval seconds between thumbnail captures
 */
public record Thumbnail(boolean enabled, int interval) {

    public static Thumbnail disabled() {
        return new Thumbnail(false, 0);
    }
}
