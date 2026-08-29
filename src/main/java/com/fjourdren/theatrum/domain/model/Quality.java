package com.fjourdren.theatrum.domain.model;

/** Video quality settings for a stream. */
public record Quality(int width, int height, int framerate, String bitrate, String codec, Audio audio) {
}
