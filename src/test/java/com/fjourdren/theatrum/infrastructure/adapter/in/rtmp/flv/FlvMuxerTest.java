package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.flv;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FlvMuxerTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Test
    void writeTagCopiesPayloadFromStream() throws IOException {
        FlvMuxer.writeTag(9, 1000, new ByteArrayInputStream(new byte[] { 0x01, 0x02, 0x03 }), out);

        // 11 header + 3 payload + 4 previous tag size = 18
        assertThat(out.toByteArray()).isEqualTo(new byte[] {
                9,
                0x00, 0x00, 0x03,
                0x00, 0x03, (byte) 0xE8,
                0x00,
                0x00, 0x00, 0x00,
                0x01, 0x02, 0x03,
                0x00, 0x00, 0x00, 0x0E    // 11 + 3
        });
    }

    @Test
    void writeTagWritesAudioTagType() throws IOException {
        FlvMuxer.writeTag(8, 0, new ByteArrayInputStream(new byte[] { (byte) 0xAA }), out);

        assertThat(out.toByteArray()[0]).isEqualTo((byte) 8);
    }

    @Test
    void writeTagWritesScriptTagType() throws IOException {
        FlvMuxer.writeTag(18, 0, new ByteArrayInputStream(new byte[] { (byte) 0xFF }), out);

        assertThat(out.toByteArray()[0]).isEqualTo((byte) 18);
    }

    @Test
    void bufferedWriteProducesValidTag() throws IOException {
        new FlvMuxer(1024).write(9, 500, new ByteArrayInputStream(new byte[] { 0x01, 0x02, 0x03, 0x04 }), out);

        byte[] result = out.toByteArray();
        assertThat(result).hasSize(19); // 11 header + 4 data + 4 previous tag size
        assertThat(result[0]).isEqualTo((byte) 9);
    }

    @Test
    void bufferIsReusedAcrossWrites() throws IOException {
        FlvMuxer muxer = new FlvMuxer(64);

        muxer.write(9, 100, new ByteArrayInputStream(new byte[] { 0x01 }), out);
        muxer.write(8, 200, new ByteArrayInputStream(new byte[] { 0x02, 0x03 }), out);

        byte[] result = out.toByteArray();
        assertThat(result).hasSize(33); // (11 + 1 + 4) + (11 + 2 + 4)

        // second tag must not carry leftovers of the first one
        assertThat(Arrays.copyOfRange(result, 16, 33)).isEqualTo(new byte[] {
                8,
                0x00, 0x00, 0x02,
                0x00, 0x00, (byte) 0xC8,
                0x00,
                0x00, 0x00, 0x00,
                0x02, 0x03,
                0x00, 0x00, 0x00, 0x0D    // 11 + 2
        });
    }

    @Test
    void bufferGrowsForPayloadLargerThanCapacity() throws IOException {
        byte[] payload = new byte[100];
        Arrays.fill(payload, (byte) 0x7F);

        new FlvMuxer(4).write(9, 0, new ByteArrayInputStream(payload), out);

        byte[] result = out.toByteArray();
        assertThat(result).hasSize(115); // 11 + 100 + 4
        assertThat(Arrays.copyOfRange(result, 11, 111)).isEqualTo(payload);
    }
}
