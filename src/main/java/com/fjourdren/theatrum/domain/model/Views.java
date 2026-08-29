package com.fjourdren.theatrum.domain.model;

/**
 * @param window seconds a client must watch continuously before a view is counted
 */
public record Views(boolean enabled, int window) {

    public static Views disabled() {
        return new Views(false, 0);
    }
}
