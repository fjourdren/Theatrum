package com.fjourdren.theatrum.domain.model;

/**
 * @param segmentDuration segment length in seconds
 * @param windowSize      number of segments in the live manifest (default: 3)
 */
public record Dash(int segmentDuration, int windowSize) {
}
