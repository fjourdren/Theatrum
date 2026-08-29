package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.flv;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Writes FLV tags to an output stream (typically FFmpeg's stdin).
 *
 * <p>All writes are serialised so tags from concurrent audio/video callbacks never interleave.
 * Timestamps are FLV's unsigned 32-bit DTS in milliseconds, carried as a {@code long}.
 */
public final class FlvWriter {

    private static final int TAG_AUDIO = 8;
    private static final int TAG_VIDEO = 9;
    private static final int TAG_SCRIPT = 18;

    private static final byte[] FLV_HEADER = {
            'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00
    };

    private final OutputStream writer;
    private boolean headerWritten;

    public FlvWriter(OutputStream writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /** Writes the 13-byte FLV header, at most once. */
    public synchronized void writeHeader() throws IOException {
        if (headerWritten) {
            return;
        }
        headerWritten = true;
        writer.write(FLV_HEADER);
    }

    /** Writes one FLV tag: 11-byte header, payload, then the 4-byte PreviousTagSize. */
    public synchronized void writeTag(int tagType, long timestamp, byte[] data) throws IOException {
        writer.write(tagHeader(tagType, data.length, timestamp));
        writer.write(data);
        writer.write(previousTagSize(data.length));
    }

    public void writeAudio(long timestamp, byte[] data) throws IOException {
        writeHeader();
        writeTag(TAG_AUDIO, timestamp, data);
    }

    public void writeVideo(long timestamp, byte[] data) throws IOException {
        writeHeader();
        writeTag(TAG_VIDEO, timestamp, data);
    }

    /** Writes a script tag (metadata / AMF). */
    public void writeScript(long timestamp, byte[] data) throws IOException {
        writeHeader();
        writeTag(TAG_SCRIPT, timestamp, data);
    }

    /** Builds the 11-byte FLV tag header. Byte casts truncate to the wire's unsigned fields. */
    static byte[] tagHeader(int tagType, int dataSize, long timestamp) {
        return new byte[] {
                (byte) tagType,
                (byte) (dataSize >>> 16), (byte) (dataSize >>> 8), (byte) dataSize,
                (byte) (timestamp >>> 16), (byte) (timestamp >>> 8), (byte) timestamp,
                (byte) (timestamp >>> 24), // TimestampExtended
                0, 0, 0                    // StreamID, always 0
        };
    }

    /** PreviousTagSize: payload size plus the 11-byte tag header, big-endian. */
    static byte[] previousTagSize(int dataSize) {
        int size = dataSize + 11;
        return new byte[] { (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size };
    }
}
