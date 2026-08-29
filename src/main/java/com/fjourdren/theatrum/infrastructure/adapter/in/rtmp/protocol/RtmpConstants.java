package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import lombok.experimental.UtilityClass;

/**
 * Wire-level constants of the RTMP specification, shared by the handshake, the chunk stream codec
 * and the connection state machine. They describe the protocol, not Theatrum: none of them is
 * configurable, and a value that lived in only one of the three classes would still have to agree
 * with the other two.
 */
@UtilityClass
public final class RtmpConstants {

    // ------------------------------------------------------------- Handshake

    /** The only handshake version Theatrum speaks (C0/S0). */
    public static final int VERSION = 3;

    /** Size of each of C1/C2/S1/S2. */
    public static final int HANDSHAKE_PACKET_SIZE = 1536;

    // ----------------------------------------------------------- Chunk stream

    /** Default chunk size until the peer sends a Set Chunk Size message. */
    public static final int DEFAULT_CHUNK_SIZE = 128;

    /** A chunk size is carried in a u24, so this is its ceiling. */
    public static final int MAX_CHUNK_SIZE = 0xFFFFFF;

    /** u24 sentinel meaning "the real timestamp follows in a 32 bit extended field". */
    public static final int EXTENDED_TIMESTAMP_MARKER = 0xFFFFFF;

    /** Socket buffer size used for both directions of a connection. */
    public static final int STREAM_BUFFER_SIZE = 8192;

    // --------------------------------------------------------- Message types

    public static final int TYPE_SET_CHUNK_SIZE = 1;
    public static final int TYPE_USER_CONTROL = 4;
    public static final int TYPE_WINDOW_ACK_SIZE = 5;
    public static final int TYPE_SET_PEER_BANDWIDTH = 6;
    public static final int TYPE_AUDIO = 8;
    public static final int TYPE_VIDEO = 9;
    public static final int TYPE_AMF0_DATA = 18;
    public static final int TYPE_AMF0_COMMAND = 20;

    // ------------------------------------------------------ Chunk stream ids

    /** Chunk stream 2 is reserved for protocol control, 3 for commands, 5 for stream level status. */
    public static final int CONTROL_CSID = 2;
    public static final int COMMAND_CSID = 3;
    public static final int STATUS_CSID = 5;

    // ----------------------------------------------------------- Session setup

    public static final long WINDOW_ACK_SIZE = 5_000_000L;
    public static final int PEER_BANDWIDTH_DYNAMIC = 2;
    public static final int EVENT_STREAM_BEGIN = 0;

    /** The single stream id Theatrum hands out; it only ever ingests one publish per connection. */
    public static final double PUBLISH_STREAM_ID = 1d;
}
