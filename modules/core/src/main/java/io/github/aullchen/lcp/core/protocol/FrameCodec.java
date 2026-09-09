package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.Metadata.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Bounded framing only. Returned bytes are UNAUTHENTICATED until HPKE verification. */
public final class FrameCodec {
    public static final int PREFIX_BYTES = 16;
    private static final int MAGIC = 0x4c435053;
    private FrameCodec() {}

    public record Prefix(int messageType, int aadLength, int ciphertextLength, int totalLength) {}

    /** Read-only views; caller owns the input storage and must keep it immutable while in use. */
    public record Frame(int messageType, ByteBuffer aad, ByteBuffer enc, ByteBuffer ciphertext) {
        public Frame {
            aad = aad.asReadOnlyBuffer(); enc = enc.asReadOnlyBuffer(); ciphertext = ciphertext.asReadOnlyBuffer();
        }
        @Override public ByteBuffer aad() { return aad.asReadOnlyBuffer(); }
        @Override public ByteBuffer enc() { return enc.asReadOnlyBuffer(); }
        @Override public ByteBuffer ciphertext() { return ciphertext.asReadOnlyBuffer(); }
    }

    /** Validate a fixed prefix against the transport Content-Length before any body allocation. */
    public static Prefix prefix(ByteBuffer input, long contentLength, Limits limits) {
        Objects.requireNonNull(limits);
        if (contentLength > limits.maxFrameBytes()) throw ProtocolException.limit();
        if (contentLength < PREFIX_BYTES || input.remaining() != PREFIX_BYTES) throw ProtocolException.invalid();
        var b = input.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC || b.get() != 1) throw ProtocolException.invalid();
        int type = Byte.toUnsignedInt(b.get());
        long aad = Integer.toUnsignedLong(b.getInt());
        int enc = Short.toUnsignedInt(b.getShort());
        long cipher = Integer.toUnsignedLong(b.getInt());
        if (type > 1 || enc != 32 || aad == 0 || cipher < 16) throw ProtocolException.invalid();
        if (aad > 4096 || (type == 1 && cipher > 1040)) throw ProtocolException.limit();
        long total = PREFIX_BYTES + aad + enc + cipher;
        if (total > limits.maxFrameBytes()) throw ProtocolException.limit();
        if (total != contentLength) throw ProtocolException.invalid();
        return new Prefix(type, (int) aad, (int) cipher, (int) total);
    }

    public static Frame decode(ByteBuffer input, Limits limits) {
        var b = input.slice().order(ByteOrder.BIG_ENDIAN);
        if (b.remaining() < PREFIX_BYTES) throw ProtocolException.invalid();
        Prefix p = prefix(b.slice(0, PREFIX_BYTES), b.remaining(), limits);
        ByteBuffer aad = b.slice(PREFIX_BYTES, p.aadLength());
        checkAad(p, aad, limits);
        int encOffset = PREFIX_BYTES + p.aadLength();
        return new Frame(p.messageType(), aad, b.slice(encOffset, 32), b.slice(encOffset + 32, p.ciphertextLength()));
    }

    private static void checkAad(Prefix prefix, ByteBuffer aad, Limits limits) {
        byte[] bytes = new byte[aad.remaining()]; aad.duplicate().get(bytes);
        if (prefix.messageType() == 1) { MetadataCodec.decode(bytes, FinishAad.class); return; }
        ChunkAad chunk = MetadataCodec.decode(bytes, ChunkAad.class);
        if (chunk.chunkIndex() >= limits.maxChunks() || chunk.plainLength() > limits.maxPlainBytes()
                || chunk.offset() + chunk.plainLength() > limits.maxTransferBytes()) throw ProtocolException.limit();
        if (chunk.compressedLength() + 16 != prefix.ciphertextLength()) throw ProtocolException.invalid();
    }

    /** Caller reserves its byte budget first and supplies a finite body stream with a deadline. */
    public static Frame read(InputStream input, long contentLength, Limits limits) throws IOException {
        byte[] header = exact(input, PREFIX_BYTES);
        Prefix p = prefix(ByteBuffer.wrap(header), contentLength, limits);
        byte[] aad = exact(input, p.aadLength());
        checkAad(p, ByteBuffer.wrap(aad), limits);
        // Large allocation follows both prefix and small AAD validation.
        byte[] body = exact(input, 32 + p.ciphertextLength());
        if (input.read() != -1) throw ProtocolException.invalid();
        return new Frame(p.messageType(), ByteBuffer.wrap(aad), ByteBuffer.wrap(body, 0, 32).slice(),
                ByteBuffer.wrap(body, 32, p.ciphertextLength()).slice());
    }

    private static byte[] exact(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw ProtocolException.invalid();
        return bytes;
    }

    public static byte[] encode(int type, byte[] aad, byte[] enc, byte[] ciphertext, Limits limits) {
        long length = (long) PREFIX_BYTES + aad.length + enc.length + ciphertext.length;
        if (enc.length != 32 || type < 0 || type > 1) throw ProtocolException.invalid();
        var header = ByteBuffer.allocate(PREFIX_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC).put((byte) 1).put((byte) type).putInt(aad.length).putShort((short) enc.length).putInt(ciphertext.length).flip();
        Prefix p = prefix(header, length, limits); checkAad(p, ByteBuffer.wrap(aad), limits);
        return ByteBuffer.allocate(p.totalLength()).put(header).put(aad).put(enc).put(ciphertext).array();
    }
}
