package com.fjourdren.theatrum.domain.model;

/**
 * @param segmentDuration segment length in seconds
 * @param windowSize      number of segments in the live playlist (default: 3)
 */
public record Hls(int segmentDuration, int windowSize) {
}
