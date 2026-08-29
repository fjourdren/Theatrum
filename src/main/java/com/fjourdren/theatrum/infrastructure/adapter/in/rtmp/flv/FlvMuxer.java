package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.flv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serialises a single FLV tag (header + payload + PreviousTagSize) straight from a payload stream,
 * without the FLV file header {@link FlvWriter} manages.
 *
 * <p>An instance reuses one internal buffer across calls to avoid allocating per frame; the buffer
 * grows automatically when a payload exceeds its capacity. The static {@link #writeTag} is the
 * one-shot variant.
 */
public final class FlvMuxer {

    private final ByteArrayOutputStream buffer;

    public FlvMuxer(int capacity) {
        this.buffer = new ByteArrayOutputStream(capacity);
    }

    /**
     * Writes exactly one FLV tag to {@code out}.
     *
     * @param tagType   8 (audio), 9 (video) or 18 (metadata/AMF)
     * @param timestamp DTS in milliseconds, unsigned 32-bit (TimestampExtended handled internally)
     * @param payload   payload reader (AAC frame, H.264 NALs, AMF object ...)
     * @param out       destination (e.g. FFmpeg's stdin)
     */
    public static void writeTag(int tagType, long timestamp, InputStream payload, OutputStream out) throws IOException {
        byte[] data = payload.readAllBytes(); // slurp to know the size up-front (24-bit field)
        out.write(FlvWriter.tagHeader(tagType, data.length, timestamp));
        out.write(data);
        out.write(FlvWriter.previousTagSize(data.length));
    }

    /** Same as {@link #writeTag}, but reuses this muxer's buffer instead of allocating one. */
    public void write(int tagType, long timestamp, InputStream payload, OutputStream out) throws IOException {
        buffer.reset();
        payload.transferTo(buffer);
        out.write(FlvWriter.tagHeader(tagType, buffer.size(), timestamp));
        buffer.writeTo(out);
        out.write(FlvWriter.previousTagSize(buffer.size()));
    }
}
