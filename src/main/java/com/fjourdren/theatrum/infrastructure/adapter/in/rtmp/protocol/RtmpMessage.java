package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

/**
 * A fully assembled RTMP message.
 *
 * @param typeId RTMP message type (1 set chunk size, 8 audio, 9 video, 18 AMF0 data, 20 AMF0 command)
 * @param timestamp absolute timestamp in milliseconds (u32, so always held in a long)
 * @param messageStreamId message stream the message belongs to
 * @param payload message body, already reassembled across chunks
 */
public record RtmpMessage(int typeId, long timestamp, int messageStreamId, byte[] payload) {}
