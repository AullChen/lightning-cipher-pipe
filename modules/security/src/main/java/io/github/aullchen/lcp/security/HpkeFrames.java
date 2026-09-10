package io.github.aullchen.lcp.security;

import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.*;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import java.nio.ByteBuffer;
import java.util.UUID;

/** RFC 9180 Auth, X25519/HKDF-SHA256/ChaCha20-Poly1305 only. Fresh context for every message. */
public final class HpkeFrames {
    private HpkeFrames() {}
    private static HPKE suite() { return new HPKE(HPKE.mode_auth, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305); }
    static byte[][] seal(HpkeKey sender, X25519PublicKeyParameters target, byte[] info, byte[] aad, byte[] plaintext) {
        try { return suite().seal(target, info, aad, plaintext, null, null, sender.pair()); }
        catch (InvalidCipherTextException | RuntimeException e) { throw new AuthenticationException(); }
    }
    static byte[] open(HpkeKey target, X25519PublicKeyParameters sender, byte[] info, byte[] aad, byte[] enc, byte[] ciphertext) {
        try { return suite().open(enc, target.pair(), info, aad, ciphertext, null, null, sender); }
        catch (InvalidCipherTextException | RuntimeException e) { throw new AuthenticationException(); }
    }
    private static void sender(BoundTransfer context, HpkeKey key, TlsIdentity source, TlsIdentity target) {
        context.checkWriter(source, target);
        if (!key.publicHash().equals(context.request().sourcePublicKeyHash())) throw new AuthenticationException();
    }
    private static void chunk(BoundTransfer context, ChunkAad aad, UUID urlTransfer, long urlIndex) {
        var limits = context.accepted().limits();
        if (!aad.transferId().equals(urlTransfer) || !aad.transferId().equals(context.request().transferId())
                || aad.chunkIndex() != urlIndex || !aad.bindingHash().equals(context.bindingHash())
                || aad.compressionCode() != context.accepted().compressionCode()
                || aad.chunkIndex() >= limits.maxChunks() || aad.plainLength() > limits.maxPlainBytes()
                || aad.offset() + aad.plainLength() > limits.maxTransferBytes()) throw new AuthenticationException();
    }
    public static byte[] sealChunk(BoundTransfer context, HpkeKey key, TlsIdentity source, TlsIdentity target, ChunkAad aad, byte[] compressed) {
        sender(context, key, source, target); chunk(context, aad, aad.transferId(), aad.chunkIndex());
        if (compressed.length != aad.compressedLength()) throw new AuthenticationException();
        return sealFrame(context, key, 0, MetadataCodec.encode(aad), compressed);
    }
    public static byte[] sealFinish(BoundTransfer context, HpkeKey key, TlsIdentity source, TlsIdentity target, FinishManifest manifest) {
        sender(context, key, source, target);
        if (!manifest.transferId().equals(context.request().transferId()) || !manifest.bindingHash().equals(context.bindingHash())) throw new AuthenticationException();
        byte[] aad = MetadataCodec.encode(new FinishAad(manifest.transferId(), manifest.bindingHash(), manifest.commandId()));
        return sealFrame(context, key, 1, aad, MetadataCodec.encode(manifest));
    }
    private static byte[] sealFrame(BoundTransfer context, HpkeKey key, int type, byte[] aad, byte[] plaintext) {
        if ((long) plaintext.length + 16 + 32 + 16 + aad.length > context.accepted().limits().maxFrameBytes()
                || (type == 1 && plaintext.length > 1024)) throw new AuthenticationException();
        byte[][] sealed = seal(key, context.targetKey(), MetadataCodec.hpkeInfo(context.bindingHash(), type), aad, plaintext);
        return FrameCodec.encode(type, aad, sealed[1], sealed[0], context.accepted().limits());
    }
    private static byte[] decrypt(BoundTransfer context, HpkeKey key, TlsIdentity source, TlsIdentity target, FrameCodec.Frame frame) {
        context.checkWriter(source, target);
        if (!key.publicHash().equals(context.request().targetPublicKeyHash())) throw new AuthenticationException();
        return open(key, context.sourceKey(), MetadataCodec.hpkeInfo(context.bindingHash(), frame.messageType()), bytes(frame.aad()), bytes(frame.enc()), bytes(frame.ciphertext()));
    }
    /** Returns authenticated compressed bytes; decompression and persistence still follow. */
    public static byte[] openChunk(BoundTransfer context, HpkeKey key, TlsIdentity source, TlsIdentity target,
                                    UUID urlTransfer, long urlIndex, ByteBuffer wire) {
        var frame = FrameCodec.decode(wire, context.accepted().limits());
        if (frame.messageType() != 0) throw new AuthenticationException();
        byte[] plaintext = decrypt(context, key, source, target, frame);
        var aad = MetadataCodec.decode(bytes(frame.aad()), ChunkAad.class);
        chunk(context, aad, urlTransfer, urlIndex);
        if (plaintext.length != aad.compressedLength()) throw new AuthenticationException();
        return plaintext;
    }
    public static FinishManifest openFinish(BoundTransfer context, HpkeKey key, TlsIdentity source, TlsIdentity target,
                                             UUID urlTransfer, ByteBuffer wire) {
        var frame = FrameCodec.decode(wire, context.accepted().limits());
        if (frame.messageType() != 1) throw new AuthenticationException();
        byte[] plaintext = decrypt(context, key, source, target, frame);
        var aad = MetadataCodec.decode(bytes(frame.aad()), FinishAad.class);
        var manifest = MetadataCodec.decode(plaintext, FinishManifest.class);
        if (!aad.transferId().equals(urlTransfer) || !aad.transferId().equals(context.request().transferId())
                || !aad.bindingHash().equals(context.bindingHash()) || !manifest.transferId().equals(aad.transferId())
                || !manifest.bindingHash().equals(aad.bindingHash()) || !manifest.commandId().equals(aad.commandId())) throw new AuthenticationException();
        return manifest;
    }
    private static byte[] bytes(ByteBuffer b) { byte[] value = new byte[b.remaining()]; b.get(value); return value; }
}
