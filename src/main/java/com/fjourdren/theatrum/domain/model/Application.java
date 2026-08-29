package com.fjourdren.theatrum.domain.model;

/** Application-wide configuration. */
public record Application(String publicPath, AllStreamsPlaylist allStreamsPlaylist) {
}
