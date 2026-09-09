package io.github.aullchen.lcp.core.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Only the small definite-array/ASCII/bytes/unsigned-integer subset used by LCP. */
final class CanonicalCbor {
    private CanonicalCbor() {}

    static byte[] encode(Object value) {
        var out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    private static void head(ByteArrayOutputStream out, int major, long n) {
        if (n < 0) throw ProtocolException.invalid();
        if (n < 24) { out.write(major * 32 + (int) n); return; }
        int size = n <= 255 ? 1 : n <= 65535 ? 2 : n <= 0xffff_ffffL ? 4 : 8;
        out.write(major * 32 + (size == 1 ? 24 : size == 2 ? 25 : size == 4 ? 26 : 27));
        for (int shift = (size - 1) * 8; shift >= 0; shift -= 8) out.write((int) (n >>> shift) & 255);
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        if (value instanceof Number n) head(out, 0, n.longValue());
        else if (value instanceof byte[] bytes) { head(out, 2, bytes.length); out.writeBytes(bytes); }
        else if (value instanceof String text) {
            byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
            head(out, 3, bytes.length); out.writeBytes(bytes);
        } else if (value instanceof List<?> list) {
            head(out, 4, list.size()); list.forEach(item -> write(out, item));
        } else throw ProtocolException.invalid();
    }

    static Object decode(byte[] bytes, int maximum) {
        if (bytes.length > maximum) throw ProtocolException.limit();
        var reader = new Reader(bytes);
        Object value = reader.read(0);
        if (reader.position != bytes.length) throw ProtocolException.invalid();
        return value;
    }

    private static final class Reader {
        private final byte[] bytes;
        private int position;
        Reader(byte[] bytes) { this.bytes = bytes; }

        private int octet() {
            if (position == bytes.length) throw ProtocolException.invalid();
            return bytes[position++] & 255;
        }

        private long argument(int additional) {
            if (additional < 24) return additional;
            if (additional > 27) throw ProtocolException.invalid();
            int size = 1 << (additional - 24);
            long n = 0;
            for (int i = 0; i < size; i++) {
                int b = octet();
                if (size == 8 && i == 0 && b >= 128) throw ProtocolException.invalid();
                n = (n << 8) | b;
            }
            long min = size == 1 ? 24 : size == 2 ? 256 : size == 4 ? 65536 : 0x1_0000_0000L;
            if (n < min) throw ProtocolException.invalid();
            return n;
        }

        Object read(int depth) {
            if (depth > 8) throw ProtocolException.invalid();
            int first = octet(), major = first >>> 5;
            if (major != 0 && major != 2 && major != 3 && major != 4) throw ProtocolException.invalid();
            long n = argument(first & 31);
            if (major == 0) return n;
            if (major == 4) {
                if (n > 16 || n > bytes.length - position) throw ProtocolException.invalid();
                var items = new ArrayList<Object>((int) n);
                for (int i = 0; i < n; i++) items.add(read(depth + 1));
                return items;
            }
            if (n > (major == 2 ? 32 : 64) || n > bytes.length - position) throw ProtocolException.invalid();
            byte[] result = new byte[(int) n];
            System.arraycopy(bytes, position, result, 0, result.length); position += result.length;
            if (major == 2) return result;
            for (byte b : result) if (b < 0) throw ProtocolException.invalid();
            return new String(result, StandardCharsets.US_ASCII);
        }
    }
}
