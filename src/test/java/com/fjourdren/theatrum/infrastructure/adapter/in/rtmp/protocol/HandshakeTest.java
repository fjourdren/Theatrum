package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandshakeTest {

    private static final int PACKET_SIZE = 1536;

    private static byte[] randomPacket(int seed) {
        byte[] packet = new byte[PACKET_SIZE];
        new Random(seed).nextBytes(packet);
        return packet;
    }

    private static byte[] clientBytes(int version, byte[] c1, byte[] c2) {
        var out = new ByteArrayOutputStream();
        out.write(version);
        out.writeBytes(c1);
        out.writeBytes(c2);
        return out.toByteArray();
    }

    @Test
    void answersC0C1WithS0S1S2AndConsumesC2() throws IOException {
        byte[] c1 = randomPacket(1);
        byte[] c2 = randomPacket(2);
        var in = new ByteArrayInputStream(clientBytes(3, c1, c2));
        var out = new ByteArrayOutputStream();

        Handshake.serverHandshake(in, out);

        byte[] response = out.toByteArray();
        assertThat(response).hasSize(1 + PACKET_SIZE + PACKET_SIZE);
        assertThat(response[0]).isEqualTo((byte) 0x03);

        byte[] s1 = Arrays.copyOfRange(response, 1, 1 + PACKET_SIZE);
        assertThat(Arrays.copyOfRange(s1, 4, 8)).containsOnly((byte) 0); // 4 zero bytes after the time
        assertThat(Arrays.copyOfRange(s1, 8, PACKET_SIZE)).isNotEqualTo(new byte[PACKET_SIZE - 8]); // random

        byte[] s2 = Arrays.copyOfRange(response, 1 + PACKET_SIZE, response.length);
        assertThat(s2).isEqualTo(c1); // S2 echoes C1

        assertThat(in.available()).isZero(); // C2 was consumed
    }

    @Test
    void producesADifferentS1PerHandshake() throws IOException {
        byte[] c1 = randomPacket(1);
        var first = new ByteArrayOutputStream();
        var second = new ByteArrayOutputStream();
        Handshake.serverHandshake(new ByteArrayInputStream(clientBytes(3, c1, randomPacket(2))), first);
        Handshake.serverHandshake(new ByteArrayInputStream(clientBytes(3, c1, randomPacket(2))), second);

        assertThat(first.toByteArray()).isNotEqualTo(second.toByteArray());
    }

    @Test
    void rejectsAnUnsupportedVersion() {
        var in = new ByteArrayInputStream(clientBytes(4, randomPacket(1), randomPacket(2)));
        assertThatThrownBy(() -> Handshake.serverHandshake(in, new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("4");
    }

    @Test
    void failsOnAnEmptyStream() {
        assertThatThrownBy(() -> Handshake.serverHandshake(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void failsOnATruncatedC1() {
        var in = new ByteArrayInputStream(new byte[] {0x03, 0x01, 0x02});
        assertThatThrownBy(() -> Handshake.serverHandshake(in, new ByteArrayOutputStream()))
                .isInstanceOf(EOFException.class);
    }
}
