package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RtmpConnectionTest {

    private static final int HANDSHAKE_BYTES = 1 + 1536 + 1536;
    private static final int CONTROL_CSID = 2;
    private static final int COMMAND_CSID = 3;
    private static final int STREAM_CSID = 8;

    /** Builds the byte stream a publishing client would send. */
    private static final class Client {
        private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        private final ChunkStreamWriter writer = new ChunkStreamWriter(raw);

        Client() {
            raw.write(0x03);
            raw.writeBytes(new byte[1536]); // C1
            raw.writeBytes(new byte[1536]); // C2
        }

        Client command(int csid, int streamId, Object... values) throws IOException {
            writer.write(csid, new RtmpMessage(20, 0, streamId, Amf0.encodeAll(values)));
            return this;
        }

        Client message(int csid, int typeId, long timestamp, int streamId, byte[] payload) throws IOException {
            writer.write(csid, new RtmpMessage(typeId, timestamp, streamId, payload));
            return this;
        }

        Client setChunkSize(int size) throws IOException {
            byte[] payload = {
                (byte) (size >> 24), (byte) (size >> 16), (byte) (size >> 8), (byte) size
            };
            writer.write(CONTROL_CSID, new RtmpMessage(1, 0, 0, payload));
            writer.setChunkSize(size);
            return this;
        }

        byte[] bytes() {
            return raw.toByteArray();
        }
    }

    /** Records every callback the connection fires. */
    private static final class RecordingHandler implements RtmpEventHandler {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        String app;
        String tcUrl;
        Map<String, Object> commandObject;
        String publishingName;
        String publishingType;
        String playStreamName;
        byte[] metadata;
        final List<byte[]> audio = new ArrayList<>();
        final List<byte[]> video = new ArrayList<>();
        IOException failConnect;
        IOException failPublish;
        IOException failPlay = new IOException("play connections are not allowed");

        @Override
        public void onServe() {
            calls.add("serve");
        }

        @Override
        public void onConnect(String app, String tcUrl, Map<String, Object> commandObject) throws IOException {
            calls.add("connect");
            this.app = app;
            this.tcUrl = tcUrl;
            this.commandObject = commandObject;
            if (failConnect != null) {
                throw failConnect;
            }
        }

        @Override
        public void onCreateStream() {
            calls.add("createStream");
        }

        @Override
        public void onPublish(String publishingName, String publishingType) throws IOException {
            calls.add("publish");
            this.publishingName = publishingName;
            this.publishingType = publishingType;
            if (failPublish != null) {
                throw failPublish;
            }
        }

        @Override
        public void onPlay(String streamName) throws IOException {
            calls.add("play");
            this.playStreamName = streamName;
            throw failPlay;
        }

        @Override
        public void onSetDataFrame(long timestamp, byte[] payload) {
            calls.add("setDataFrame");
            this.metadata = payload;
        }

        @Override
        public void onAudio(long timestamp, byte[] payload) {
            calls.add("audio");
            audio.add(payload);
        }

        @Override
        public void onVideo(long timestamp, byte[] payload) {
            calls.add("video");
            video.add(payload);
        }

        @Override
        public void onClose() {
            calls.add("close");
            closes.incrementAndGet();
        }
    }

    private static ByteArrayOutputStream serve(Client client, RecordingHandler handler) {
        var out = new ByteArrayOutputStream();
        new RtmpConnection(new ByteArrayInputStream(client.bytes()), out, handler).serve();
        return out;
    }

    /** Server replies, minus the handshake. */
    private static List<RtmpMessage> replies(ByteArrayOutputStream out) throws IOException {
        byte[] bytes = out.toByteArray();
        assertThat(bytes.length).isGreaterThanOrEqualTo(HANDSHAKE_BYTES);
        var reader = new ChunkStreamReader(
                new ByteArrayInputStream(bytes, HANDSHAKE_BYTES, bytes.length - HANDSHAKE_BYTES));
        var messages = new ArrayList<RtmpMessage>();
        RtmpMessage msg;
        while ((msg = reader.readMessage()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    private static List<List<Object>> commands(ByteArrayOutputStream out) throws IOException {
        var commands = new ArrayList<List<Object>>();
        for (RtmpMessage msg : replies(out)) {
            if (msg.typeId() == 20) {
                commands.add(Amf0.decodeAll(msg.payload()));
            }
        }
        return commands;
    }

    private static Map<String, Object> connectCommandObject() {
        var object = new LinkedHashMap<String, Object>();
        object.put("app", "user/alice");
        object.put("type", "nonprivate");
        object.put("tcUrl", "rtmp://localhost:1935/user/alice");
        return object;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static byte[] payload(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (seed + i);
        }
        return data;
    }

    @Test
    void drivesAFullPublishSession() throws IOException {
        byte[] metadataTail = Amf0.encodeAll("onMetaData", Map.of("width", 1280d));
        var setDataFrame = new ByteArrayOutputStream();
        setDataFrame.writeBytes(Amf0.encodeAll("@setDataFrame"));
        setDataFrame.writeBytes(metadataTail);

        byte[] audio = payload(30, 7);
        byte[] video = payload(200, 3); // larger than one chunk at the negotiated size

        var client = new Client()
                .command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject())
                .command(COMMAND_CSID, 0, "releaseStream", 2d, null, "streamkey")
                .command(COMMAND_CSID, 0, "FCPublish", 3d, null, "streamkey")
                .command(COMMAND_CSID, 0, "createStream", 4d, null)
                .command(STREAM_CSID, 1, "publish", 5d, null, "streamkey", "live");
        client.setChunkSize(64)
                .message(STREAM_CSID, 18, 0, 1, setDataFrame.toByteArray())
                .message(STREAM_CSID, 8, 100, 1, audio)
                .message(STREAM_CSID, 9, 120, 1, video)
                .command(COMMAND_CSID, 0, "FCUnpublish", 6d, null, "streamkey")
                .command(STREAM_CSID, 1, "closeStream", 7d, null)
                .command(COMMAND_CSID, 0, "deleteStream", 8d, null, 1d);

        var handler = new RecordingHandler();
        var out = serve(client, handler);

        assertThat(handler.calls)
                .containsExactly("serve", "connect", "createStream", "publish", "setDataFrame", "audio", "video", "close");
        assertThat(handler.app).isEqualTo("user/alice");
        assertThat(handler.tcUrl).isEqualTo("rtmp://localhost:1935/user/alice");
        assertThat(handler.commandObject).containsEntry("type", "nonprivate");
        assertThat(handler.publishingName).isEqualTo("streamkey");
        assertThat(handler.publishingType).isEqualTo("live");
        assertThat(handler.metadata).isEqualTo(metadataTail); // "@setDataFrame" stripped, rest untouched
        assertThat(handler.audio).singleElement().isEqualTo(audio);
        assertThat(handler.video).singleElement().isEqualTo(video);
        assertThat(handler.closes).hasValue(1);

        // Protocol control handshake comes before the connect result, like every real server.
        List<RtmpMessage> replies = replies(out);
        assertThat(replies.stream().map(RtmpMessage::typeId).limit(4)).containsExactly(5, 6, 4, 20);

        List<List<Object>> commands = commands(out);
        List<Object> connectResult = commands.getFirst();
        assertThat(connectResult.get(0)).isEqualTo("_result");
        assertThat(connectResult.get(1)).isEqualTo(1d);
        assertThat(asMap(connectResult.get(2))).containsKeys("fmsVer", "capabilities");
        assertThat(asMap(connectResult.get(3)))
                .containsEntry("level", "status")
                .containsEntry("code", "NetConnection.Connect.Success");

        List<Object> createStreamResult = commands.stream()
                .filter(c -> "_result".equals(c.get(0)) && Double.valueOf(4d).equals(c.get(1)))
                .findFirst()
                .orElseThrow();
        assertThat(createStreamResult.get(3)).isEqualTo(1d); // stream id

        List<Object> publishStatus = commands.stream()
                .filter(c -> "onStatus".equals(c.get(0)))
                .findFirst()
                .orElseThrow();
        assertThat(asMap(publishStatus.get(3)))
                .containsEntry("level", "status")
                .containsEntry("code", "NetStream.Publish.Start");
    }

    @Test
    void repliesToReleaseStreamAndFcPublish() throws IOException {
        var client = new Client()
                .command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject())
                .command(COMMAND_CSID, 0, "releaseStream", 2d, null, "streamkey")
                .command(COMMAND_CSID, 0, "FCPublish", 3d, null, "streamkey");

        var out = serve(client, new RecordingHandler());

        assertThat(commands(out).stream().map(c -> c.get(1)))
                .containsExactly(1d, 2d, 3d); // one _result per transaction
    }

    @Test
    void refusesPublishWhenTheHandlerFails() throws IOException {
        var handler = new RecordingHandler();
        handler.failPublish = new IOException("authentication failed");
        var client = new Client()
                .command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject())
                .command(COMMAND_CSID, 0, "createStream", 4d, null)
                .command(STREAM_CSID, 1, "publish", 5d, null, "badkey", "live")
                .message(STREAM_CSID, 9, 0, 1, payload(10, 1));

        var out = serve(client, handler);

        assertThat(handler.calls).containsExactly("serve", "connect", "createStream", "publish", "close");
        assertThat(handler.video).isEmpty(); // the connection stops after a refused publish
        List<Object> status = commands(out).stream().filter(c -> "onStatus".equals(c.get(0))).findFirst().orElseThrow();
        assertThat(asMap(status.get(3)))
                .containsEntry("level", "error")
                .containsEntry("description", "authentication failed");
    }

    @Test
    void refusesConnectWhenTheHandlerFails() throws IOException {
        var handler = new RecordingHandler();
        handler.failConnect = new IOException("unauthorized TCURL");
        var client = new Client()
                .command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject())
                .command(COMMAND_CSID, 0, "createStream", 4d, null);

        var out = serve(client, handler);

        assertThat(handler.calls).containsExactly("serve", "connect", "close");
        List<Object> error = commands(out).getFirst();
        assertThat(error.get(0)).isEqualTo("_error");
        assertThat(asMap(error.get(3)))
                .containsEntry("level", "error")
                .containsEntry("code", "NetConnection.Connect.Rejected");
    }

    @Test
    void refusesPlay() throws IOException {
        var handler = new RecordingHandler();
        var client = new Client()
                .command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject())
                .command(COMMAND_CSID, 0, "createStream", 4d, null)
                .command(STREAM_CSID, 1, "play", 5d, null, "somestream")
                .message(STREAM_CSID, 9, 0, 1, payload(10, 1));

        var out = serve(client, handler);

        assertThat(handler.calls).containsExactly("serve", "connect", "createStream", "play", "close");
        assertThat(handler.playStreamName).isEqualTo("somestream");
        assertThat(handler.video).isEmpty();
        List<Object> status = commands(out).stream().filter(c -> "onStatus".equals(c.get(0))).findFirst().orElseThrow();
        assertThat(asMap(status.get(3)))
                .containsEntry("level", "error")
                .containsEntry("code", "NetStream.Play.Failed");
    }

    @Test
    void ignoresUnknownCommandsAndMessageTypes() throws IOException {
        var handler = new RecordingHandler();
        var client = new Client()
                .command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject())
                .command(COMMAND_CSID, 0, "getStreamLength", 2d, null, "streamkey")
                .message(CONTROL_CSID, 6, 0, 0, payload(5, 0)) // set peer bandwidth
                .message(CONTROL_CSID, 3, 0, 0, payload(4, 0)) // acknowledgement
                .message(STREAM_CSID, 18, 0, 1, Amf0.encodeAll("onTextData", Map.of("text", "hi")))
                .message(STREAM_CSID, 9, 0, 1, payload(10, 1));

        serve(client, handler);

        assertThat(handler.calls).containsExactly("serve", "connect", "video", "close");
    }

    @Test
    void closesOnceOnAHandshakeFailure() {
        var handler = new RecordingHandler();
        var out = new ByteArrayOutputStream();
        new RtmpConnection(new ByteArrayInputStream(new byte[] {0x04}), out, handler).serve();

        assertThat(handler.closes).hasValue(1);
        assertThat(handler.calls).containsExactly("serve", "close");
    }

    @Test
    void closeIsIdempotent() {
        var handler = new RecordingHandler();
        var connection = new RtmpConnection(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), handler);
        connection.serve();
        connection.close();
        connection.close();

        assertThat(handler.closes).hasValue(1);
    }

    @Test
    void appliesTheNegotiatedChunkSizeToIncomingMedia() throws IOException {
        byte[] video = payload(700, 11);
        var handler = new RecordingHandler();
        var client = new Client().command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject());
        client.setChunkSize(256).message(STREAM_CSID, 9, 0, 1, video);

        serve(client, handler);

        assertThat(handler.video).singleElement().isEqualTo(video);
    }

    @Test
    void forwardsMetadataBytesVerbatimIncludingTheEcmaArrayMarker() throws IOException {
        var ecmaArray = new ByteArrayOutputStream();
        ecmaArray.write(0x08);
        ecmaArray.writeBytes(new byte[] {0, 0, 0, 1});
        ecmaArray.writeBytes(new byte[] {0, 5, 'w', 'i', 'd', 't', 'h'});
        ecmaArray.write(0x00);
        ecmaArray.writeBytes(new byte[] {0x40, (byte) 0x94, 0, 0, 0, 0, 0, 0}); // 1280.0
        ecmaArray.writeBytes(new byte[] {0, 0, 0x09});

        var expected = new ByteArrayOutputStream();
        expected.writeBytes(Amf0.encodeAll("onMetaData"));
        expected.writeBytes(ecmaArray.toByteArray());

        var full = new ByteArrayOutputStream();
        full.writeBytes(Amf0.encodeAll("@setDataFrame"));
        full.writeBytes(expected.toByteArray());

        var handler = new RecordingHandler();
        var client = new Client().command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject());
        client.message(STREAM_CSID, 18, 0, 1, full.toByteArray());

        serve(client, handler);

        assertThat(handler.metadata).isEqualTo(expected.toByteArray());
    }

    @Test
    void deliversEmptyMediaPayloads() throws IOException {
        var handler = new RecordingHandler();
        var client = new Client().command(COMMAND_CSID, 0, "connect", 1d, connectCommandObject());
        client.message(STREAM_CSID, 8, 0, 1, new byte[0]);

        serve(client, handler);

        assertThat(handler.audio).singleElement().isEqualTo(new byte[0]);
    }
}
