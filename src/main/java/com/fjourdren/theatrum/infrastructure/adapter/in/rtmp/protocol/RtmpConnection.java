package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One RTMP ingest connection: handshake, then read messages and dispatch them to the handler.
 *
 * <p>Publish only. {@code play} is always refused, matching the Go server.
 */
@Slf4j
public final class RtmpConnection implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final RtmpEventHandler handler;
    private final ChunkStreamReader reader;
    private final ChunkStreamWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean running = true;

    public RtmpConnection(Socket socket, RtmpEventHandler handler) throws IOException {
        this(socket, socket.getInputStream(), socket.getOutputStream(), handler);
    }

    /** Test seam: drive the protocol without a real socket. */
    RtmpConnection(InputStream in, OutputStream out, RtmpEventHandler handler) {
        this(null, in, out, handler);
    }

    private RtmpConnection(Socket socket, InputStream in, OutputStream out, RtmpEventHandler handler) {
        this.socket = socket;
        this.in = new BufferedInputStream(in, RtmpConstants.STREAM_BUFFER_SIZE);
        this.out = new BufferedOutputStream(out, RtmpConstants.STREAM_BUFFER_SIZE);
        this.handler = handler;
        this.reader = new ChunkStreamReader(this.in);
        this.writer = new ChunkStreamWriter(this.out);
    }

    /** Serves the connection until the peer disconnects or the session is refused. */
    public void serve() {
        handler.onServe();
        try {
            Handshake.serverHandshake(in, out);
            RtmpMessage message;
            while (running && (message = reader.readMessage()) != null) {
                handleMessage(message);
            }
        } catch (IOException e) {
            log.debug("RTMP connection ended: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("RTMP connection failed", e);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            log.debug("Error closing RTMP socket: {}", e.getMessage());
        }
        handler.onClose();
    }

    private void handleMessage(RtmpMessage message) throws IOException {
        switch (message.typeId()) {
            case RtmpConstants.TYPE_SET_CHUNK_SIZE -> {
                if (message.payload().length >= 4) {
                    // The high bit is reserved and must be masked off before use.
                    reader.setChunkSize((int) (readU32(message.payload()) & 0x7FFF_FFFFL));
                }
            }
            case RtmpConstants.TYPE_AMF0_COMMAND -> handleCommand(message);
            case RtmpConstants.TYPE_AMF0_DATA -> handleData(message);
            case RtmpConstants.TYPE_AUDIO -> handler.onAudio(message.timestamp(), message.payload());
            case RtmpConstants.TYPE_VIDEO -> handler.onVideo(message.timestamp(), message.payload());
            // Acknowledgement, window ack size, peer bandwidth and user control need no reply from
            // an ingest-only server.
            default -> log.trace("Ignoring RTMP message type {}", message.typeId());
        }
    }

    private void handleCommand(RtmpMessage message) throws IOException {
        List<Object> values;
        try {
            values = Amf0.decodeAll(message.payload());
        } catch (IOException e) {
            log.warn("Discarding malformed RTMP command: {}", e.getMessage());
            return;
        }
        if (values.isEmpty() || !(values.getFirst() instanceof String name)) {
            return;
        }
        double transactionId = values.size() > 1 && values.get(1) instanceof Double id ? id : 0d;

        switch (name) {
            case "connect" -> handleConnect(transactionId, argument(values, 2));
            case "createStream" -> {
                handler.onCreateStream();
                sendCommand(RtmpConstants.COMMAND_CSID, 0, "_result", transactionId, null,
                        RtmpConstants.PUBLISH_STREAM_ID);
            }
            case "publish" -> handlePublish(message.messageStreamId(), values);
            case "play" -> handlePlay(message.messageStreamId(), values);
            // Clients wait on a result for these before publishing; the answer carries no payload.
            case "releaseStream", "FCPublish" ->
                    sendCommand(RtmpConstants.COMMAND_CSID, 0, "_result", transactionId, null, null);
            // The publisher is going away: the connection close that follows drives the cleanup.
            case "deleteStream", "FCUnpublish", "closeStream" -> log.debug("RTMP {} received", name);
            default -> log.debug("Ignoring unsupported RTMP command '{}'", name);
        }
    }

    private void handleConnect(double transactionId, Object commandObject) throws IOException {
        Map<String, Object> properties = asProperties(commandObject);
        String app = asString(properties.get("app"));
        String tcUrl = asString(properties.get("tcUrl"));

        try {
            handler.onConnect(app, tcUrl, properties);
        } catch (IOException e) {
            sendCommand(RtmpConstants.COMMAND_CSID, 0, "_error", transactionId, null,
                    status("error", "NetConnection.Connect.Rejected", e.getMessage()));
            running = false;
            return;
        }

        sendWindowAckSize();
        sendSetPeerBandwidth();
        sendStreamBegin();
        sendCommand(RtmpConstants.COMMAND_CSID, 0, "_result", transactionId, connectProperties(),
                status("status", "NetConnection.Connect.Success", "Connection succeeded."));
    }

    private void handlePublish(int messageStreamId, List<Object> values) throws IOException {
        String publishingName = asString(argument(values, 3));
        String publishingType = asString(argument(values, 4));
        try {
            handler.onPublish(publishingName, publishingType);
        } catch (IOException e) {
            sendStatus(messageStreamId, "error", "NetStream.Publish.BadName", e.getMessage());
            running = false;
            return;
        }
        sendStatus(messageStreamId, "status", "NetStream.Publish.Start", "Publishing " + publishingName);
    }

    private void handlePlay(int messageStreamId, List<Object> values) throws IOException {
        String streamName = asString(argument(values, 3));
        String reason = "play connections are not allowed";
        try {
            handler.onPlay(streamName);
        } catch (IOException e) {
            reason = e.getMessage();
        }
        log.info("Play connection refused: {}", streamName);
        sendStatus(messageStreamId, "error", "NetStream.Play.Failed", reason);
        running = false;
    }

    /**
     * FFmpeg's FLV muxer expects the metadata exactly as the client sent it, so the remaining
     * bytes are forwarded verbatim rather than decoded and re-encoded.
     */
    private void handleData(RtmpMessage message) throws IOException {
        ByteBuffer payload = ByteBuffer.wrap(message.payload());
        Object name;
        try {
            name = Amf0.decode(payload);
        } catch (IOException e) {
            log.warn("Discarding malformed RTMP data message: {}", e.getMessage());
            return;
        }
        if (!"@setDataFrame".equals(name)) {
            log.debug("Ignoring RTMP data message '{}'", name);
            return;
        }
        byte[] metadata = new byte[payload.remaining()];
        payload.get(metadata);
        handler.onSetDataFrame(message.timestamp(), metadata);
    }

    private void sendWindowAckSize() throws IOException {
        writer.write(RtmpConstants.CONTROL_CSID,
                new RtmpMessage(RtmpConstants.TYPE_WINDOW_ACK_SIZE, 0, 0, u32(RtmpConstants.WINDOW_ACK_SIZE)));
    }

    private void sendSetPeerBandwidth() throws IOException {
        byte[] payload = new byte[5];
        System.arraycopy(u32(RtmpConstants.WINDOW_ACK_SIZE), 0, payload, 0, 4);
        payload[4] = RtmpConstants.PEER_BANDWIDTH_DYNAMIC;
        writer.write(RtmpConstants.CONTROL_CSID, new RtmpMessage(RtmpConstants.TYPE_SET_PEER_BANDWIDTH, 0, 0, payload));
    }

    private void sendStreamBegin() throws IOException {
        byte[] payload = new byte[6]; // u16 event type + u32 stream id, both 0
        payload[1] = RtmpConstants.EVENT_STREAM_BEGIN;
        writer.write(RtmpConstants.CONTROL_CSID, new RtmpMessage(RtmpConstants.TYPE_USER_CONTROL, 0, 0, payload));
    }

    private void sendStatus(int messageStreamId, String level, String code, String description) throws IOException {
        sendCommand(RtmpConstants.STATUS_CSID, messageStreamId, "onStatus", 0d, null, status(level, code, description));
    }

    private void sendCommand(int csid, int messageStreamId, Object... values) throws IOException {
        writer.write(csid,
                new RtmpMessage(RtmpConstants.TYPE_AMF0_COMMAND, 0, messageStreamId, Amf0.encodeAll(values)));
    }

    private static Map<String, Object> connectProperties() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fmsVer", "FMS/3,0,1,123");
        properties.put("capabilities", 31d);
        return properties;
    }

    private static Map<String, Object> status(String level, String code, String description) {
        var info = new LinkedHashMap<String, Object>();
        info.put("level", level);
        info.put("code", code);
        info.put("description", description == null ? "" : description);
        return info;
    }

    private static Object argument(List<Object> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static Map<String, Object> asProperties(Object value) {
        if (value instanceof Map<?, ?> map) {
            var properties = new LinkedHashMap<String, Object>();
            map.forEach((key, entry) -> properties.put(String.valueOf(key), entry));
            return properties;
        }
        return new LinkedHashMap<>();
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : "";
    }

    private static long readU32(byte[] data) {
        return ((long) (data[0] & 0xFF) << 24)
                | ((long) (data[1] & 0xFF) << 16)
                | ((long) (data[2] & 0xFF) << 8)
                | (data[3] & 0xFF);
    }

    private static byte[] u32(long value) {
        return new byte[] {
            (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value
        };
    }
}
