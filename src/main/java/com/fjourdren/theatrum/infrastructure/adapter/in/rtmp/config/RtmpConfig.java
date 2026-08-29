package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.config;

/**
 * @param reconnectDelay seconds to wait before cleaning up a disconnected stream
 * @param cleanupDelay   seconds to wait before removing stream files
 */
public record RtmpConfig(int reconnectDelay, int cleanupDelay) {
}
