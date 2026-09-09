package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.api.Metadata.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Canonical lcp-stream/1 metadata; this codec does not establish trust or persist Open. */
public final class MetadataCodec {
    public static final int CONTROL_LIMIT = 65536;
    private MetadataCodec() {}

    public static byte[] encode(Object value) { return CanonicalCbor.encode(fields(value)); }

    public static <T> T decode(byte[] bytes, Class<T> type) {
        int limit = type == FinishManifest.class ? 1024
                : type == ChunkAad.class || type == FinishAad.class ? 4096 : CONTROL_LIMIT;
        try { return type.cast(object(CanonicalCbor.decode(bytes, limit), type)); }
        catch (ProtocolException e) { throw e; }
        catch (IllegalArgumentException | ClassCastException | ArithmeticException | NullPointerException e) { throw ProtocolException.invalid(); }
    }

    public static Bytes32 hash(byte[] bytes) {
        try { return new Bytes32(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static Bytes32 openRequestHash(OpenRequest request) { return hash(encode(request)); }
    public static Bytes32 manifestHash(FinishManifest manifest) { return hash(encode(manifest)); }

    public static Bytes32 bindingHash(OpenRequest request, Accepted accepted, Bytes32 sourceTlsSpki, Bytes32 targetTlsSpki) {
        if (!request.transferId().equals(accepted.transferId())) throw ProtocolException.invalid();
        return hash(CanonicalCbor.encode(List.of("lcp-stream-binding/1", fields(request), fields(accepted), sourceTlsSpki.bytes(), targetTlsSpki.bytes())));
    }

    public static byte[] hpkeInfo(Bytes32 binding, int type) {
        if (type < 0 || type > 1) throw ProtocolException.invalid();
        return CanonicalCbor.encode(List.of("lcp-stream-hpke/1", binding.bytes(), type));
    }

    static byte[] uuid(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    static UUID uuid(Object value) {
        if (!(value instanceof byte[] bytes) || bytes.length != 16) throw ProtocolException.invalid();
        var b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong());
    }
    private static Bytes32 digest(Object v) { return new Bytes32((byte[]) v); }
    private static long u(Object v) { if (!(v instanceof Long n) || n < 0) throw ProtocolException.invalid(); return n; }
    private static int small(Object v) { return Math.toIntExact(u(v)); }
    private static Instant time(Object v) { return Instant.ofEpochMilli(u(v)); }

    static List<?> fields(Object v) {
        if (v instanceof Limits x) return List.of(x.maxFrameBytes(), x.maxPlainBytes(), x.maxChunks(), x.maxTransferBytes(), x.maxInFlightChunks());
        if (v instanceof Policy x) return List.of(x.mode(), x.policyVersion(), x.minChunkBytes(), x.maxChunkBytes(), x.initialChunkBytes(), x.minWindow(), x.maxWindow(), x.initialWindow(), x.minZstdLevel(), x.maxZstdLevel(), x.initialZstdLevel());
        if (v instanceof OpenRequest x) return List.of("lcp-stream-open/1", uuid(x.transferId()), x.routeId(), x.sourceNodeId(), x.targetNodeId(), x.sourceChallenge().bytes(), x.sourceKeyId(), x.sourcePublicKeyHash().bytes(), x.targetKeyId(), x.targetPublicKeyHash().bytes(), x.compressionOffers(), fields(x.policy()), fields(x.limits()), x.createdAt().toEpochMilli(), x.expiresAt().toEpochMilli());
        if (v instanceof Accepted x) return List.of("lcp-stream-accepted/1", uuid(x.transferId()), x.targetChallenge().bytes(), x.handleId(), x.compressionCode(), fields(x.policy()), fields(x.limits()), x.expiresAt().toEpochMilli());
        if (v instanceof OpenResponse x) return List.of(fields(x.accepted()), x.openRequestHash().bytes(), x.bindingHash().bytes());
        if (v instanceof ChunkAad x) return List.of("lcp-stream-chunk/1", uuid(x.transferId()), x.bindingHash().bytes(), x.chunkIndex(), x.offset(), x.plainLength(), x.compressedLength(), x.compressionCode());
        if (v instanceof FinishAad x) return List.of("lcp-stream-finish-aad/1", uuid(x.transferId()), x.bindingHash().bytes(), uuid(x.commandId()));
        if (v instanceof FinishManifest x) return List.of("lcp-stream-finish/1", uuid(x.transferId()), x.bindingHash().bytes(), uuid(x.commandId()), x.totalChunks(), x.totalPlainBytes(), x.root().bytes());
        if (v instanceof CancelCommand x) return List.of("lcp-stream-cancel/1", uuid(x.transferId()), uuid(x.commandId()), x.bindingHash().bytes());
        throw ProtocolException.invalid();
    }

    private static List<?> array(Object value, int size, String domain) {
        if (!(value instanceof List<?> list) || list.size() != size) throw ProtocolException.invalid();
        if (domain != null && !domain.equals(list.get(0))) throw ProtocolException.invalid();
        return domain == null ? list : list.subList(1, size);
    }

    static Object object(Object value, Class<?> type) {
        if (type == Limits.class) {
            var a = array(value, 5, null); return new Limits(u(a.get(0)), u(a.get(1)), u(a.get(2)), u(a.get(3)), u(a.get(4)));
        }
        if (type == Policy.class) {
            var a = array(value, 11, null); return new Policy(small(a.get(0)), small(a.get(1)), u(a.get(2)), u(a.get(3)), u(a.get(4)), u(a.get(5)), u(a.get(6)), u(a.get(7)), small(a.get(8)), small(a.get(9)), small(a.get(10)));
        }
        if (type == OpenRequest.class) {
            var a = array(value, 15, "lcp-stream-open/1");
            if (!(a.get(9) instanceof List<?> offers)) throw ProtocolException.invalid();
            return new OpenRequest(uuid(a.get(0)), (String) a.get(1), (String) a.get(2), (String) a.get(3), digest(a.get(4)), (String) a.get(5), digest(a.get(6)), (String) a.get(7), digest(a.get(8)), offers.stream().map(MetadataCodec::small).toList(), (Policy) object(a.get(10), Policy.class), (Limits) object(a.get(11), Limits.class), time(a.get(12)), time(a.get(13)));
        }
        if (type == Accepted.class) {
            var a = array(value, 8, "lcp-stream-accepted/1");
            return new Accepted(uuid(a.get(0)), digest(a.get(1)), (String) a.get(2), small(a.get(3)), (Policy) object(a.get(4), Policy.class), (Limits) object(a.get(5), Limits.class), time(a.get(6)));
        }
        if (type == OpenResponse.class) {
            var a = array(value, 3, null); return new OpenResponse((Accepted) object(a.get(0), Accepted.class), digest(a.get(1)), digest(a.get(2)));
        }
        if (type == ChunkAad.class) {
            var a = array(value, 8, "lcp-stream-chunk/1"); return new ChunkAad(uuid(a.get(0)), digest(a.get(1)), u(a.get(2)), u(a.get(3)), u(a.get(4)), u(a.get(5)), small(a.get(6)));
        }
        if (type == FinishAad.class) {
            var a = array(value, 4, "lcp-stream-finish-aad/1"); return new FinishAad(uuid(a.get(0)), digest(a.get(1)), uuid(a.get(2)));
        }
        if (type == FinishManifest.class) {
            var a = array(value, 7, "lcp-stream-finish/1"); return new FinishManifest(uuid(a.get(0)), digest(a.get(1)), uuid(a.get(2)), u(a.get(3)), u(a.get(4)), digest(a.get(5)));
        }
        if (type == CancelCommand.class) {
            var a = array(value, 4, "lcp-stream-cancel/1"); return new CancelCommand(uuid(a.get(0)), uuid(a.get(1)), digest(a.get(2)));
        }
        throw ProtocolException.invalid();
    }
}
