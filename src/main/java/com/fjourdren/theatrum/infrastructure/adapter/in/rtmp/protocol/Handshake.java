package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import lombok.experimental.UtilityClass;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server side of the simple RTMP handshake (C0/C1/C2 against S0/S1/S2).
 *
 * <p>No digest/HMAC variant: OBS and FFmpeg both fall back to the simple form when the server
 * does not advertise a complex handshake, and Theatrum only ingests.
 */
@UtilityClass
public final class Handshake {

    public static void serverHandshake(InputStream in, OutputStream out) throws IOException {
        int version = in.read();
        if (version < 0) {
            throw new EOFException("connection closed before C0");
        }
        if (version != RtmpConstants.VERSION) {
            throw new IOException("unsupported RTMP version: " + version);
        }
        byte[] c1 = readFully(in, RtmpConstants.HANDSHAKE_PACKET_SIZE);

        byte[] s1 = new byte[RtmpConstants.HANDSHAKE_PACKET_SIZE];
        ThreadLocalRandom.current().nextBytes(s1);
        int time = (int) (System.currentTimeMillis() / 1000);
        for (int i = 0; i < 4; i++) {
            s1[i] = (byte) (time >> (24 - i * 8));
            s1[4 + i] = 0; // zero field, a peer that reads it as a version must see 0
        }

        out.write(RtmpConstants.VERSION);
        out.write(s1);
        out.write(c1); // S2 echoes C1
        out.flush();

        readFully(in, RtmpConstants.HANDSHAKE_PACKET_SIZE); // C2, never validated
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] packet = in.readNBytes(length);
        if (packet.length < length) {
            throw new EOFException("incomplete RTMP handshake packet: " + packet.length + "/" + length + " bytes");
        }
        return packet;
    }
}
