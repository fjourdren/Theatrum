package com.fjourdren.theatrum.domain.model;

/**
 * @param reconnectDelay seconds to wait before cleaning up a disconnected stream (default: 30)
 * @param cleanupDelay   seconds to wait before removing stream files (default: 30)
 */
public record Rtmp(int reconnectDelay, int cleanupDelay) {
}
