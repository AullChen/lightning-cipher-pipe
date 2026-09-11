package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.Metadata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.aullchen.lcp.core.protocol.MetadataCodecTest.*;

class FrameCodecTest {
    @ParameterizedTest @ValueSource(strings = {"chunk", "finish"})
    void independentFramesAndShortReads(String name) throws IOException {
        byte[] expected = vector(name + "-frame.hex");
        FrameCodec.Frame frame = FrameCodec.decode(ByteBuffer.wrap(expected), LIMITS);
        assertEquals(name.equals("chunk") ? 0 : 1, frame.messageType());
        assertArrayEquals(vector(name + "-aad.cbor"), bytes(frame.aad()));
        assertArrayEquals(expected, FrameCodec.encode(frame.messageType(), bytes(frame.aad()), bytes(frame.enc()), bytes(frame.ciphertext()), LIMITS));
        var shortReads = new ByteArrayInputStream(expected) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(3, len)); }
        };
        var fromStream = FrameCodec.read(shortReads, expected.length, LIMITS);
        assertArrayEquals(bytes(frame.ciphertext()), bytes(fromStream.ciphertext()));
        assertTrue(frame.aad().isReadOnly());
        frame.aad().get(); assertEquals(vector(name + "-aad.cbor").length, frame.aad().remaining());
        // Decoder must ignore caller's byte order, position and unrelated capacity.
        var wrapped = ByteBuffer.allocate(expected.length + 10).order(ByteOrder.LITTLE_ENDIAN);
        wrapped.position(5); wrapped.put(expected); wrapped.flip(); wrapped.position(5);
        assertArrayEquals(bytes(frame.ciphertext()), bytes(FrameCodec.decode(wrapped, LIMITS).ciphertext()));
    }
    @Test void exactTransportReadsNeverRequestZeroBytes() throws IOException {
        byte[] frame = vector("chunk-frame.hex");
        var input = new FilterInputStream(new ByteArrayInputStream(frame)) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                assertTrue(len > 0, "Zero-length read can block at a TLS record boundary");
                return super.read(b, off, len);
            }
        };
        assertEquals(0, FrameCodec.read(input, frame.length, LIMITS).messageType());
    }
    static byte[] bytes(ByteBuffer buffer) { byte[] b = new byte[buffer.remaining()]; buffer.get(b); return b; }

    @ParameterizedTest @CsvSource({"0,0", "4,2", "5,2", "6,255", "10,255", "12,255"})
    void invalidPrefix(int offset, int value) {
        byte[] bad = vector("chunk-frame.hex"); bad[offset] = (byte) value;
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(ByteBuffer.wrap(bad), LIMITS));
    }

    @Test void everyTruncationAndTrailingBytesRejected() {
        byte[] good = vector("chunk-frame.hex");
        for (int n = 0; n < good.length; n++) {
            byte[] bad = Arrays.copyOf(good, n);
            assertThrows(ProtocolException.class, () -> FrameCodec.decode(ByteBuffer.wrap(bad), LIMITS));
        }
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(ByteBuffer.wrap(Arrays.copyOf(good, good.length + 1)), LIMITS));
        assertThrows(ProtocolException.class, () -> FrameCodec.read(new ByteArrayInputStream(Arrays.copyOf(good, good.length + 1)), good.length, LIMITS));
        assertThrows(ProtocolException.class, () -> FrameCodec.read(new ByteArrayInputStream(Arrays.copyOf(good, good.length - 1)), good.length, LIMITS));
    }

    @Test void unsignedOverflowAndLimitsBeforeBodyReads() {
        byte[] header = Arrays.copyOf(vector("chunk-frame.hex"), 16);
        ByteBuffer.wrap(header).putInt(6, -1).putInt(12, -1);
        var in = new ByteArrayInputStream(header);
        assertThrows(ProtocolException.class, () -> FrameCodec.read(in, 16, LIMITS));
        assertEquals(0, in.available());
        byte[] normal = Arrays.copyOf(vector("chunk-frame.hex"), 16);
        assertThrows(ProtocolException.class, () -> FrameCodec.prefix(ByteBuffer.wrap(normal), Long.MAX_VALUE, LIMITS));
        assertThrows(ProtocolException.class, () -> FrameCodec.prefix(ByteBuffer.wrap(normal), -1, LIMITS));
        ByteBuffer.wrap(normal).putInt(6, 4097);
        assertEquals(ProtocolException.Code.LIMIT_EXCEEDED, assertThrows(ProtocolException.class, () -> FrameCodec.prefix(ByteBuffer.wrap(normal), 16 + 4097 + 32 + 17, LIMITS)).code());
    }

    @Test void invalidAadRejectedBeforeCiphertextIsRead() {
        byte[] good = vector("chunk-frame.hex");
        int aadLength = ByteBuffer.wrap(good).getInt(6);
        byte[] prefixAndBadAad = Arrays.copyOf(good, 16 + aadLength);
        prefixAndBadAad[16] = (byte) 0xff;
        InputStream input = new InputStream() {
            int position;
            @Override public int read() {
                if (position >= prefixAndBadAad.length) throw new AssertionError("Body read before AAD rejection");
                return prefixAndBadAad[position++] & 255;
            }
        };
        assertThrows(ProtocolException.class, () -> FrameCodec.read(input, good.length, LIMITS));
    }

    @ParameterizedTest @CsvSource({"1000000,0,1,1,0", "0,1099511627776,1,1,0", "0,0,8388609,1,1", "0,0,1,2,1"})
    void checksAuthenticatedDescriptorShapeAndQuotas(long index, long offset, long plain, long compressed, int codec) {
        byte[] aad = MetadataCodec.encode(new ChunkAad(TRANSFER, binding(), index, offset, plain, compressed, codec));
        assertThrows(ProtocolException.class, () -> FrameCodec.encode(0, aad, new byte[32], new byte[17], LIMITS));
    }

    @Test void finishCiphertextBoundAndExactFrameCap() {
        byte[] aad = vector("finish-aad.cbor");
        assertThrows(ProtocolException.class, () -> FrameCodec.encode(1, aad, new byte[32], new byte[1041], LIMITS));
        byte[] good = vector("chunk-frame.hex");
        var exact = new Limits(good.length, 1, 1, 1, 1);
        assertNotNull(FrameCodec.decode(ByteBuffer.wrap(good), exact));
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(ByteBuffer.wrap(good), new Limits(good.length - 1, 1, 1, 1, 1)));
    }
}
