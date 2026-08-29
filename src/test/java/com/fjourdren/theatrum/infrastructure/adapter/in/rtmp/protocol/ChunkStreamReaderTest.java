package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkStreamReaderTest {

    private static ChunkStreamReader reader(Bytes bytes) {
        return new ChunkStreamReader(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static byte[] payload(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (seed + i);
        }
        return data;
    }

    @Test
    void readsAFmt0MessageInASingleChunk() throws IOException {
        byte[] data = payload(20, 1);
        var in = new Bytes().u8(0x03).u24(1000).u24(data.length).u8(20).u32le(7).raw(data);

        RtmpMessage msg = reader(in).readMessage();

        assertThat(msg.typeId()).isEqualTo(20);
        assertThat(msg.timestamp()).isEqualTo(1000L);
        assertThat(msg.messageStreamId()).isEqualTo(7);
        assertThat(msg.payload()).isEqualTo(data);
    }

    @Test
    void returnsNullOnCleanEof() throws IOException {
        assertThat(reader(new Bytes()).readMessage()).isNull();
    }

    @Test
    void throwsOnATruncatedChunk() {
        var in = new Bytes().u8(0x03).u24(0).u24(10).u8(9).u32le(1).raw(payload(4, 0));
        assertThatThrownBy(() -> reader(in).readMessage()).isInstanceOf(EOFException.class);
    }

    @Test
    void assemblesAMessageSplitAcrossFmt3ContinuationChunks() throws IOException {
        byte[] data = payload(300, 5); // 128 + 128 + 44 at the default chunk size
        var in = new Bytes()
                .u8(0x04)
                .u24(0)
                .u24(data.length)
                .u8(9)
                .u32le(1)
                .raw(Arrays.copyOfRange(data, 0, 128))
                .u8(0xC4)
                .raw(Arrays.copyOfRange(data, 128, 256))
                .u8(0xC4)
                .raw(Arrays.copyOfRange(data, 256, 300));

        RtmpMessage msg = reader(in).readMessage();

        assertThat(msg.typeId()).isEqualTo(9);
        assertThat(msg.payload()).isEqualTo(data);
    }

    @Test
    void appliesFmt1AndFmt2TimestampDeltas() throws IOException {
        byte[] first = payload(4, 0);
        byte[] second = payload(6, 10);
        byte[] third = payload(6, 20);
        byte[] fourth = payload(6, 30);
        var in = new Bytes()
                // fmt 0: absolute timestamp 100, type 8, stream 1
                .u8(0x06).u24(100).u24(first.length).u8(8).u32le(1).raw(first)
                // fmt 1: delta 50 -> 150, new length + type, stream id reused
                .u8(0x46).u24(50).u24(second.length).u8(9).raw(second)
                // fmt 2: delta 25 -> 175, length + type reused
                .u8(0x86).u24(25).raw(third)
                // fmt 3: reuses the previous delta -> 200
                .u8(0xC6).raw(fourth);

        ChunkStreamReader reader = reader(in);

        RtmpMessage m1 = reader.readMessage();
        assertThat(m1.timestamp()).isEqualTo(100L);
        assertThat(m1.typeId()).isEqualTo(8);

        RtmpMessage m2 = reader.readMessage();
        assertThat(m2.timestamp()).isEqualTo(150L);
        assertThat(m2.typeId()).isEqualTo(9);
        assertThat(m2.messageStreamId()).isEqualTo(1);
        assertThat(m2.payload()).isEqualTo(second);

        RtmpMessage m3 = reader.readMessage();
        assertThat(m3.timestamp()).isEqualTo(175L);
        assertThat(m3.typeId()).isEqualTo(9);
        assertThat(m3.payload()).isEqualTo(third);

        RtmpMessage m4 = reader.readMessage();
        assertThat(m4.timestamp()).isEqualTo(200L);
        assertThat(m4.payload()).isEqualTo(fourth);
    }

    @Test
    void readsAnExtendedTimestampOnTheHeaderAndOnEveryContinuationChunk() throws IOException {
        long timestamp = 0x0100_0000L; // > 0xFFFFFF, so the extended field is used
        byte[] data = payload(200, 3);
        var in = new Bytes()
                .u8(0x05)
                .u24(0xFFFFFF)
                .u24(data.length)
                .u8(9)
                .u32le(1)
                .u32(timestamp)
                .raw(Arrays.copyOfRange(data, 0, 128))
                .u8(0xC5)
                .u32(timestamp) // re-sent on the fmt 3 continuation
                .raw(Arrays.copyOfRange(data, 128, 200));

        RtmpMessage msg = reader(in).readMessage();

        assertThat(msg.timestamp()).isEqualTo(timestamp);
        assertThat(msg.payload()).isEqualTo(data);
    }

    @Test
    void readsAnExtendedTimestampDeltaOnAFmt1Header() throws IOException {
        byte[] first = payload(4, 0);
        byte[] second = payload(4, 9);
        long delta = 0x00FF_FFFFL + 10;
        var in = new Bytes()
                .u8(0x03).u24(1).u24(first.length).u8(8).u32le(1).raw(first)
                .u8(0x43).u24(0xFFFFFF).u24(second.length).u8(8).u32(delta).raw(second);

        ChunkStreamReader reader = reader(in);
        assertThat(reader.readMessage().timestamp()).isEqualTo(1L);
        assertThat(reader.readMessage().timestamp()).isEqualTo(1L + delta);
    }

    @Test
    void usesTheUpdatedChunkSizeMidStream() throws IOException {
        byte[] first = payload(4, 0);
        byte[] second = payload(10, 50);
        var in = new Bytes()
                .u8(0x02).u24(0).u24(first.length).u8(1).u32le(0).raw(first)
                // 10 bytes split as 4 + 4 + 2 once the chunk size drops to 4
                .u8(0x03).u24(0).u24(second.length).u8(9).u32le(1).raw(Arrays.copyOfRange(second, 0, 4))
                .u8(0xC3).raw(Arrays.copyOfRange(second, 4, 8))
                .u8(0xC3).raw(Arrays.copyOfRange(second, 8, 10));

        ChunkStreamReader reader = reader(in);
        assertThat(reader.readMessage().typeId()).isEqualTo(1);
        reader.setChunkSize(4);
        assertThat(reader.readMessage().payload()).isEqualTo(second);
    }

    @Test
    void decodesTheOneTwoAndThreeByteChunkStreamIdForms() throws IOException {
        byte[] data = payload(2, 1);
        var in = new Bytes()
                // 1 byte: csid 3 directly in the basic header
                .u8(0x03).u24(0).u24(data.length).u8(9).u32le(1).raw(data)
                // 2 bytes: csid marker 0 -> 64 + 200 = 264
                .u8(0x00).u8(200).u24(0).u24(data.length).u8(9).u32le(1).raw(data)
                // 3 bytes: csid marker 1 -> 64 + 5 + 2 * 256 = 581
                .u8(0x01).u8(5).u8(2).u24(0).u24(data.length).u8(9).u32le(1).raw(data)
                // fmt 3 on the 2-byte csid form: csid 264 keeps its own header state
                .u8(0xC0).u8(200).raw(data);

        ChunkStreamReader reader = reader(in);
        for (int i = 0; i < 4; i++) {
            RtmpMessage msg = reader.readMessage();
            assertThat(msg.payload()).isEqualTo(data);
            assertThat(msg.typeId()).isEqualTo(9);
        }
    }

    @Test
    void keepsPerCsidStateIndependent() throws IOException {
        byte[] videoA = payload(4, 1);
        byte[] videoB = payload(4, 2);
        var in = new Bytes()
                .u8(0x04).u24(10).u24(videoA.length).u8(9).u32le(1).raw(videoA)
                .u8(0x06).u24(999).u24(videoB.length).u8(8).u32le(2).raw(videoB)
                .u8(0xC4).raw(videoA) // fmt 3 on csid 4 -> timestamp 10 + delta 0
                .u8(0xC6).raw(videoB); // fmt 3 on csid 6 -> timestamp 999 + delta 0

        ChunkStreamReader reader = reader(in);
        reader.readMessage();
        reader.readMessage();
        RtmpMessage a = reader.readMessage();
        RtmpMessage b = reader.readMessage();

        assertThat(a.timestamp()).isEqualTo(10L);
        assertThat(a.typeId()).isEqualTo(9);
        assertThat(b.timestamp()).isEqualTo(999L);
        assertThat(b.typeId()).isEqualTo(8);
    }

    @Test
    void readsAZeroLengthMessage() throws IOException {
        var in = new Bytes().u8(0x03).u24(0).u24(0).u8(9).u32le(1);
        assertThat(reader(in).readMessage().payload()).isEmpty();
    }

    @Test
    void doesNotLetHighTimestampBitsLeakAsNegative() throws IOException {
        var in = new Bytes().u8(0x03).u24(0xFFFFFF).u24(0).u8(9).u32le(1).u32(0xFFFF_FFFFL);
        assertThat(reader(in).readMessage().timestamp()).isEqualTo(0xFFFF_FFFFL);
    }

    @Test
    void rejectsAnInvalidChunkSize() {
        ChunkStreamReader reader = reader(new Bytes());
        assertThatThrownBy(() -> reader.setChunkSize(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Tiny big-endian byte-stream builder for hand-written chunk headers. */
    private static final class Bytes {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        Bytes u24(int v) {
            return u8(v >> 16).u8(v >> 8).u8(v);
        }

        Bytes u32(long v) {
            return u8((int) (v >> 24)).u8((int) (v >> 16)).u8((int) (v >> 8)).u8((int) v);
        }

        Bytes u32le(long v) {
            return u8((int) v).u8((int) (v >> 8)).u8((int) (v >> 16)).u8((int) (v >> 24));
        }

        Bytes raw(byte[] data) {
            out.writeBytes(data);
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
