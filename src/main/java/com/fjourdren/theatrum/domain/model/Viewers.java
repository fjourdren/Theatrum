package com.fjourdren.theatrum.domain.model;

/**
 * @param window seconds a client must watch continuously before being counted
 */
public record Viewers(boolean enabled, int window) {

    public static Viewers disabled() {
        return new Viewers(false, 0);
    }
}
