package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes RTMP messages out, splitting payloads at the peer's chunk size.
 *
 * <p>Uses fmt 0 for the first chunk and fmt 3 for continuations, which is all a server needs to
 * emit: header compression only saves bytes on the high-rate media stream we never send.
 * Safe to call from several threads.
 */
@RequiredArgsConstructor
public final class ChunkStreamWriter {

    private final OutputStream out;
    private volatile int chunkSize = RtmpConstants.DEFAULT_CHUNK_SIZE;

    public void setChunkSize(int chunkSize) {
        if (chunkSize <= 0 || chunkSize > RtmpConstants.MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("invalid chunk size: " + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    public void write(int csid, RtmpMessage message) throws IOException {
        byte[] payload = message.payload();
        boolean extended = message.timestamp() >= RtmpConstants.EXTENDED_TIMESTAMP_MARKER;
        int size = chunkSize;

        synchronized (out) {
            writeBasicHeader(0, csid);
            writeU24(extended ? RtmpConstants.EXTENDED_TIMESTAMP_MARKER : (int) message.timestamp());
            writeU24(payload.length);
            out.write(message.typeId() & 0xFF);
            writeU32LittleEndian(message.messageStreamId());
            if (extended) {
                writeU32(message.timestamp());
            }

            int offset = 0;
            while (true) {
                int slice = Math.min(size, payload.length - offset);
                out.write(payload, offset, slice);
                offset += slice;
                if (offset >= payload.length) {
                    break;
                }
                writeBasicHeader(3, csid);
                if (extended) {
                    writeU32(message.timestamp());
                }
            }
            out.flush();
        }
    }

    private void writeBasicHeader(int fmt, int csid) throws IOException {
        if (csid < 64) {
            out.write((fmt << 6) | csid);
        } else if (csid < 320) {
            out.write(fmt << 6);
            out.write(csid - 64);
        } else {
            out.write((fmt << 6) | 1);
            out.write((csid - 64) & 0xFF);
            out.write(((csid - 64) >> 8) & 0xFF);
        }
    }

    private void writeU24(int value) throws IOException {
        out.write(value >> 16);
        out.write(value >> 8);
        out.write(value);
    }

    private void writeU32(long value) throws IOException {
        for (int shift = 24; shift >= 0; shift -= 8) {
            out.write((int) (value >> shift));
        }
    }

    private void writeU32LittleEndian(int value) throws IOException {
        for (int shift = 0; shift <= 24; shift += 8) {
            out.write(value >> shift);
        }
    }
}
