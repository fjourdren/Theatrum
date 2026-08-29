package com.fjourdren.theatrum.domain.model;

/** Viewer and view counts for a single stream. */
public record StreamStats(String trackingKey, int viewers, long views) {
}
