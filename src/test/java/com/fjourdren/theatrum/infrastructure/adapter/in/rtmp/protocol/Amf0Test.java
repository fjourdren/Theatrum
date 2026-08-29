package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Amf0Test {

    private static Object roundTrip(Object value) throws IOException {
        List<Object> decoded = Amf0.decodeAll(Amf0.encodeAll(value));
        assertThat(decoded).hasSize(1);
        return decoded.getFirst();
    }

    @Test
    void roundTripsNumbers() throws IOException {
        assertThat(roundTrip(3.5d)).isEqualTo(3.5d);
        assertThat(roundTrip(0d)).isEqualTo(0d);
        assertThat(roundTrip(-1234.5678d)).isEqualTo(-1234.5678d);
        assertThat(roundTrip(42)).isEqualTo(42d); // any Number is encoded as an IEEE754 double
        assertThat(roundTrip(4_000_000_000L)).isEqualTo(4_000_000_000d);
    }

    @Test
    void roundTripsBooleans() throws IOException {
        assertThat(roundTrip(Boolean.TRUE)).isEqualTo(true);
        assertThat(roundTrip(Boolean.FALSE)).isEqualTo(false);
    }

    @Test
    void roundTripsStrings() throws IOException {
        assertThat(roundTrip("")).isEqualTo("");
        assertThat(roundTrip("connect")).isEqualTo("connect");
        assertThat(roundTrip("rtmp://host/user/alice")).isEqualTo("rtmp://host/user/alice");
        assertThat(roundTrip("héllo → wörld")).isEqualTo("héllo → wörld");
    }

    @Test
    void roundTripsLongStringsAboveTheU16Limit() throws IOException {
        String big = "x".repeat(70_000);
        byte[] encoded = Amf0.encodeAll(big);
        assertThat(encoded[0]).isEqualTo((byte) 0x0C); // long string marker
        assertThat(roundTrip(big)).isEqualTo(big);
    }

    @Test
    void roundTripsNull() throws IOException {
        assertThat(roundTrip(null)).isNull();
    }

    @Test
    void roundTripsDates() throws IOException {
        Date date = new Date(1_700_000_000_000L);
        assertThat(roundTrip(date)).isEqualTo(date);
    }

    @Test
    void roundTripsObjectsPreservingKeyOrder() throws IOException {
        var object = new LinkedHashMap<String, Object>();
        object.put("app", "live");
        object.put("capabilities", 31d);
        object.put("secure", true);
        object.put("nested", Map.of("k", "v"));
        object.put("missing", null);

        Object decoded = roundTrip(object);

        assertThat(decoded).isInstanceOf(LinkedHashMap.class).isEqualTo(object);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        assertThat(map.keySet()).containsExactly("app", "capabilities", "secure", "nested", "missing");
    }

    @Test
    void roundTripsStrictArrays() throws IOException {
        List<Object> values = List.of("a", 1d, Boolean.TRUE);
        byte[] encoded = Amf0.encodeAll(values);
        assertThat(encoded[0]).isEqualTo((byte) 0x0A);
        assertThat(roundTrip(values)).isEqualTo(values);
    }

    @Test
    void decodesUndefinedAsNull() throws IOException {
        assertThat(Amf0.decodeAll(new byte[] {0x06})).containsExactly((Object) null);
    }

    @Test
    void decodesEcmaArrayAsAnOrderedMap() throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(0x08);
        out.writeBytes(new byte[] {0, 0, 0, 2}); // associative count (informational)
        writeKey(out, "duration");
        out.write(0x00);
        out.writeBytes(doubleBytes(12.5d));
        writeKey(out, "encoder");
        writeString(out, "Lavf");
        out.writeBytes(new byte[] {0, 0, 0x09});

        Object decoded = Amf0.decodeAll(out.toByteArray()).getFirst();

        assertThat(decoded).isInstanceOf(LinkedHashMap.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        assertThat(map).containsExactly(Map.entry("duration", 12.5d), Map.entry("encoder", "Lavf"));
    }

    @Test
    void decodesACapturedConnectCommand() throws IOException {
        var out = new ByteArrayOutputStream();
        writeString(out, "connect");
        out.write(0x00);
        out.writeBytes(doubleBytes(1d));
        out.write(0x03);
        writeKey(out, "app");
        writeString(out, "user");
        writeKey(out, "type");
        writeString(out, "nonprivate");
        writeKey(out, "flashVer");
        writeString(out, "FMLE/3.0 (compatible; Lavf61.7.100)");
        writeKey(out, "tcUrl");
        writeString(out, "rtmp://localhost:1935/user/alice");
        out.writeBytes(new byte[] {0, 0, 0x09});

        List<Object> values = Amf0.decodeAll(out.toByteArray());

        assertThat(values).hasSize(3);
        assertThat(values.get(0)).isEqualTo("connect");
        assertThat(values.get(1)).isEqualTo(1d);
        @SuppressWarnings("unchecked")
        Map<String, Object> command = (Map<String, Object>) values.get(2);
        assertThat(command.get("app")).isEqualTo("user");
        assertThat(command.get("tcUrl")).isEqualTo("rtmp://localhost:1935/user/alice");
        assertThat(command.keySet()).containsExactly("app", "type", "flashVer", "tcUrl");
    }

    @Test
    void decodesSeveralValuesFromOnePayload() throws IOException {
        byte[] encoded = Amf0.encodeAll("publish", 5d, null, "streamkey", "live");
        assertThat(Amf0.decodeAll(encoded)).containsExactly("publish", 5d, null, "streamkey", "live");
    }

    @Test
    void rejectsAnUnknownMarker() {
        assertThatThrownBy(() -> Amf0.decodeAll(new byte[] {0x11}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("0x11");
    }

    @Test
    void rejectsTruncatedData() {
        assertThatThrownBy(() -> Amf0.decodeAll(new byte[] {0x02, 0x00, 0x10, 'a'}))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsAnUnsupportedJavaType() {
        assertThatThrownBy(() -> Amf0.encodeAll(new Object()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void decodeAdvancesTheBufferPosition() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(Amf0.encodeAll("@setDataFrame", "onMetaData"));
        assertThat(Amf0.decode(buf)).isEqualTo("@setDataFrame");
        assertThat(buf.position()).isEqualTo(1 + 2 + "@setDataFrame".length());
        assertThat(Amf0.decode(buf)).isEqualTo("onMetaData");
        assertThat(buf.hasRemaining()).isFalse();
    }

    private static void writeKey(ByteArrayOutputStream out, String key) {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        out.write(raw.length >> 8);
        out.write(raw.length);
        out.writeBytes(raw);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        out.write(0x02);
        writeKey(out, value);
    }

    private static byte[] doubleBytes(double value) {
        return ByteBuffer.allocate(8).putDouble(value).array();
    }
}
