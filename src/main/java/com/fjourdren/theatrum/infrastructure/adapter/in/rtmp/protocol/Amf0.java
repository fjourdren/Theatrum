package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import lombok.experimental.UtilityClass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMF0 codec covering the markers RTMP command and data messages actually use.
 *
 * <p>Decoded values map to {@code Double}, {@code Boolean}, {@code String},
 * {@code LinkedHashMap<String, Object>} (objects and ECMA arrays, order preserved),
 * {@code List<Object>} (strict arrays), {@code Date} and {@code null}.
 */
@UtilityClass
public final class Amf0 {

    private static final int NUMBER = 0x00;
    private static final int BOOLEAN = 0x01;
    private static final int STRING = 0x02;
    private static final int OBJECT = 0x03;
    private static final int NULL = 0x05;
    private static final int UNDEFINED = 0x06;
    private static final int ECMA_ARRAY = 0x08;
    private static final int OBJECT_END = 0x09;
    private static final int STRICT_ARRAY = 0x0A;
    private static final int DATE = 0x0B;
    private static final int LONG_STRING = 0x0C;

    private static final int MAX_SHORT_STRING = 0xFFFF;

    /** Decodes every value in {@code data}; an AMF0 message body is a bare sequence of values. */
    public static List<Object> decodeAll(byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        var values = new ArrayList<>();
        while (buf.hasRemaining()) {
            values.add(decode(buf));
        }
        return values;
    }

    /** Decodes a single value and leaves the buffer positioned on the next one. */
    public static Object decode(ByteBuffer buf) throws IOException {
        try {
            return decodeValue(buf);
        } catch (BufferUnderflowException e) {
            throw new IOException("truncated AMF0 data", e);
        }
    }

    /** Encodes values back to back, the layout an AMF0 message body expects. */
    public static byte[] encodeAll(Object... values) throws IOException {
        var out = new ByteArrayOutputStream();
        for (Object value : values) {
            encode(out, value);
        }
        return out.toByteArray();
    }

    private static Object decodeValue(ByteBuffer buf) throws IOException {
        int marker = u8(buf);
        return switch (marker) {
            case NUMBER -> buf.getDouble();
            case BOOLEAN -> u8(buf) != 0;
            case STRING -> utf8(buf, u16(buf));
            case LONG_STRING -> utf8(buf, u32(buf));
            case OBJECT -> decodeProperties(buf);
            case ECMA_ARRAY -> {
                u32(buf); // associative count, informational only: the terminator delimits the body
                yield decodeProperties(buf);
            }
            case STRICT_ARRAY -> {
                long count = u32(buf);
                var values = new ArrayList<>();
                for (long i = 0; i < count; i++) {
                    values.add(decodeValue(buf));
                }
                yield values;
            }
            case DATE -> {
                double millis = buf.getDouble();
                buf.getShort(); // timezone, always 0 per the spec
                yield new Date((long) millis);
            }
            case NULL, UNDEFINED -> null;
            default -> throw new IOException("unsupported AMF0 marker: 0x%02X".formatted(marker));
        };
    }

    private static Map<String, Object> decodeProperties(ByteBuffer buf) throws IOException {
        var properties = new LinkedHashMap<String, Object>();
        while (true) {
            int keyLength = u16(buf);
            if (keyLength == 0) {
                int end = u8(buf);
                if (end != OBJECT_END) {
                    throw new IOException("malformed AMF0 object end marker: 0x%02X".formatted(end));
                }
                return properties;
            }
            properties.put(utf8(buf, keyLength), decodeValue(buf));
        }
    }

    private static void encode(ByteArrayOutputStream out, Object value) throws IOException {
        switch (value) {
            case null -> out.write(NULL);
            case Number number -> {
                out.write(NUMBER);
                writeDouble(out, number.doubleValue());
            }
            case Boolean bool -> {
                out.write(BOOLEAN);
                out.write(bool ? 1 : 0);
            }
            case String string -> writeString(out, string);
            case Date date -> {
                out.write(DATE);
                writeDouble(out, date.getTime());
                out.writeBytes(new byte[] {0, 0});
            }
            case Map<?, ?> map -> {
                out.write(OBJECT);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    writeUtf8(out, String.valueOf(entry.getKey()));
                    encode(out, entry.getValue());
                }
                out.writeBytes(new byte[] {0, 0, OBJECT_END});
            }
            case List<?> list -> {
                out.write(STRICT_ARRAY);
                writeU32(out, list.size());
                for (Object element : list) {
                    encode(out, element);
                }
            }
            default -> throw new IOException("unsupported AMF0 value type: " + value.getClass().getName());
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_SHORT_STRING) {
            out.write(LONG_STRING);
            writeU32(out, raw.length);
        } else {
            out.write(STRING);
            out.write(raw.length >> 8);
            out.write(raw.length);
        }
        out.writeBytes(raw);
    }

    /** Object keys are always length-prefixed on 16 bits, with no marker. */
    private static void writeUtf8(ByteArrayOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_SHORT_STRING) {
            throw new IOException("AMF0 object key exceeds " + MAX_SHORT_STRING + " bytes");
        }
        out.write(raw.length >> 8);
        out.write(raw.length);
        out.writeBytes(raw);
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) {
        long bits = Double.doubleToLongBits(value);
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (bits >> shift));
        }
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            out.write((int) (value >> shift));
        }
    }

    private static String utf8(ByteBuffer buf, long length) throws IOException {
        if (length < 0 || length > buf.remaining()) {
            throw new IOException("AMF0 string length " + length + " exceeds the remaining " + buf.remaining() + " bytes");
        }
        byte[] raw = new byte[(int) length];
        buf.get(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static int u8(ByteBuffer buf) {
        return buf.get() & 0xFF;
    }

    private static int u16(ByteBuffer buf) {
        return buf.getShort() & 0xFFFF;
    }

    private static long u32(ByteBuffer buf) {
        return buf.getInt() & 0xFFFF_FFFFL;
    }
}
