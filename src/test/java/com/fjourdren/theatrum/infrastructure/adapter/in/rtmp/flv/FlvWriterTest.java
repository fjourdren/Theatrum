package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.flv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlvWriterTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final FlvWriter writer = new FlvWriter(out);

    @Test
    void writeHeaderWritesFlvSignature() throws IOException {
        writer.writeHeader();

        assertThat(out.toByteArray()).isEqualTo(new byte[] {
                'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00
        });
    }

    @Test
    void writeHeaderOnlyWritesOnce() throws IOException {
        writer.writeHeader();
        writer.writeHeader();
        writer.writeHeader();

        assertThat(out.size()).isEqualTo(13);
    }

    @Test
    void writeTagProducesCorrectBytes() throws IOException {
        writer.writeTag(9, 1000, "hello".getBytes(StandardCharsets.UTF_8));

        // 11 byte header + 5 byte data + 4 byte previous tag size = 20
        assertThat(out.toByteArray()).isEqualTo(new byte[] {
                9,                          // tag type
                0x00, 0x00, 0x05,           // data size
                0x00, 0x03, (byte) 0xE8,    // timestamp lower 24 bits (1000)
                0x00,                       // timestamp extended
                0x00, 0x00, 0x00,           // stream id
                'h', 'e', 'l', 'l', 'o',    // payload
                0x00, 0x00, 0x00, 0x10      // previous tag size (11 + 5)
        });
    }

    @Test
    void writeTagSetsTimestampExtendedByte() throws IOException {
        writer.writeTag(9, 0x01ABCDEFL, new byte[] { 0x00 });

        assertThat(out.toByteArray()).isEqualTo(new byte[] {
                9,
                0x00, 0x00, 0x01,
                (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
                0x01,
                0x00, 0x00, 0x00,
                0x00,
                0x00, 0x00, 0x00, 0x0C
        });
    }

    @Test
    void writeTagHandlesUnsignedTimestampAbove2Pow31() throws IOException {
        writer.writeTag(9, 0xFFFFFFFFL, new byte[] { 0x00 });

        byte[] result = out.toByteArray();
        assertThat(Arrays.copyOfRange(result, 4, 8))
                .isEqualTo(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
    }

    @Test
    void writeAudioWritesHeaderThenAudioTag() throws IOException {
        writer.writeAudio(500, new byte[] { (byte) 0xAA, (byte) 0xBB });

        byte[] result = out.toByteArray();
        // flv header (13) + tag header (11) + data (2) + previous tag size (4)
        assertThat(result).hasSize(30);
        assertThat(result[13]).isEqualTo((byte) 8);
    }

    @Test
    void writeVideoWritesHeaderThenVideoTag() throws IOException {
        writer.writeVideo(1000, new byte[] { 0x01, 0x02, 0x03 });

        byte[] result = out.toByteArray();
        assertThat(result).hasSize(31);
        assertThat(result[13]).isEqualTo((byte) 9);
    }

    @Test
    void writeScriptWritesHeaderThenScriptTag() throws IOException {
        writer.writeScript(0, new byte[] { (byte) 0xFF });

        byte[] result = out.toByteArray();
        assertThat(result).hasSize(29);
        assertThat(result[13]).isEqualTo((byte) 18);
    }

    static List<Arguments> tagHeaderCases() {
        return List.of(
                Arguments.of("video small ts", 9, 100, 500L),
                Arguments.of("audio zero ts", 8, 50, 0L),
                Arguments.of("script large ts", 18, 200, 0x01234567L),
                Arguments.of("unsigned max ts", 9, 1, 0xFFFFFFFFL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tagHeaderCases")
    void tagHeaderLayout(String name, int tagType, int dataSize, long timestamp) throws IOException {
        writer.writeTag(tagType, timestamp, new byte[dataSize]);

        byte[] header = Arrays.copyOf(out.toByteArray(), 11);
        assertThat(header[0]).isEqualTo((byte) tagType);
        assertThat(readUint24(header, 1)).isEqualTo(dataSize);
        assertThat(readUint24(header, 4)).isEqualTo((int) (timestamp & 0xFFFFFF));
        assertThat(header[7]).isEqualTo((byte) (timestamp >>> 24));
        assertThat(Arrays.copyOfRange(header, 8, 11)).isEqualTo(new byte[] { 0, 0, 0 });
    }

    private static int readUint24(byte[] b, int offset) {
        return (b[offset] & 0xFF) << 16 | (b[offset + 1] & 0xFF) << 8 | (b[offset + 2] & 0xFF);
    }
}
