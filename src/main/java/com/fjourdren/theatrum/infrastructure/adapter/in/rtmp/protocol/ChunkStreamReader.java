package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the RTMP chunk stream and reassembles complete messages.
 *
 * <p>Not thread safe: one reader belongs to one connection, read from a single thread.
 */
public final class ChunkStreamReader {

    private final InputStream in;
    private final Map<Integer, ChunkState> states = new HashMap<>();
    private int chunkSize = RtmpConstants.DEFAULT_CHUNK_SIZE;

    public ChunkStreamReader(InputStream in) {
        this.in = in instanceof BufferedInputStream buffered
                ? buffered
                : new BufferedInputStream(in, RtmpConstants.STREAM_BUFFER_SIZE);
    }

    /** Applies the chunk size the peer announced with a Set Chunk Size message. */
    public void setChunkSize(int chunkSize) {
        if (chunkSize <= 0 || chunkSize > RtmpConstants.MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("invalid chunk size: " + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    public int chunkSize() {
        return chunkSize;
    }

    /**
     * Blocks until a full message is assembled.
     *
     * @return the message, or {@code null} when the peer closed the connection between messages
     */
    public RtmpMessage readMessage() throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                return null;
            }
            int fmt = (first >> 6) & 0x03;
            int csid = readChunkStreamId(first & 0x3F);
            ChunkState state = states.computeIfAbsent(csid, id -> new ChunkState());

            readMessageHeader(fmt, state);

            if (state.payload == null) {
                state.payload = new byte[state.length];
                state.position = 0;
            }
            int slice = Math.min(chunkSize, state.payload.length - state.position);
            if (slice > 0) {
                readFully(state.payload, state.position, slice);
                state.position += slice;
            }
            if (state.position < state.payload.length) {
                continue;
            }

            var message = new RtmpMessage(state.typeId, state.timestamp, state.messageStreamId, state.payload);
            state.payload = null;
            return message;
        }
    }

    private int readChunkStreamId(int marker) throws IOException {
        return switch (marker) {
            case 0 -> 64 + readU8();
            case 1 -> {
                int low = readU8();
                int high = readU8();
                yield 64 + low + high * 256;
            }
            default -> marker;
        };
    }

    private void readMessageHeader(int fmt, ChunkState state) throws IOException {
        switch (fmt) {
            case 0 -> {
                long timestamp = readU24();
                state.length = readU24();
                state.typeId = readU8();
                state.messageStreamId = readU32LittleEndian();
                state.extended = timestamp == RtmpConstants.EXTENDED_TIMESTAMP_MARKER;
                state.timestamp = state.extended ? readU32() : timestamp;
                state.delta = 0;
                state.payload = null;
            }
            case 1 -> {
                long delta = readU24();
                state.length = readU24();
                state.typeId = readU8();
                state.extended = delta == RtmpConstants.EXTENDED_TIMESTAMP_MARKER;
                state.delta = state.extended ? readU32() : delta;
                state.timestamp += state.delta;
                state.payload = null;
            }
            case 2 -> {
                long delta = readU24();
                state.extended = delta == RtmpConstants.EXTENDED_TIMESTAMP_MARKER;
                state.delta = state.extended ? readU32() : delta;
                state.timestamp += state.delta;
                state.payload = null;
            }
            // fmt 3 reuses the whole previous header. The extended timestamp field is repeated on
            // every chunk of a message whose header carried one, so it must be read back here too.
            default -> {
                boolean newMessage = state.payload == null;
                if (state.extended) {
                    long extended = readU32();
                    if (newMessage) {
                        state.delta = extended;
                    }
                }
                if (newMessage) {
                    state.timestamp += state.delta;
                }
            }
        }
    }

    private void readFully(byte[] destination, int offset, int length) throws IOException {
        if (in.readNBytes(destination, offset, length) < length) {
            throw new EOFException("unexpected end of RTMP chunk stream");
        }
    }

    private int readU8() throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("unexpected end of RTMP chunk stream");
        }
        return value;
    }

    private int readU24() throws IOException {
        return (readU8() << 16) | (readU8() << 8) | readU8();
    }

    private long readU32() throws IOException {
        return ((long) readU8() << 24) | ((long) readU8() << 16) | ((long) readU8() << 8) | readU8();
    }

    private int readU32LittleEndian() throws IOException {
        return readU8() | (readU8() << 8) | (readU8() << 16) | (readU8() << 24);
    }

    /** Per chunk-stream header state, carried across chunks that omit fields. */
    private static final class ChunkState {
        private long timestamp;
        private long delta;
        private int length;
        private int typeId;
        private int messageStreamId;
        private boolean extended;
        private byte[] payload;
        private int position;
    }
}
