package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.Bytes32;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import java.time.Instant;
import java.util.List;

/** Versioned local CBOR records. Not an alternative wire representation. */
public final class StorageCodec {
    private StorageCodec() {}
    public static byte[] open(AcceptedTransfer t) {
        validate(t);
        return CanonicalCbor.encode(List.of(1, MetadataCodec.fields(t.request()), MetadataCodec.fields(t.response()),
                t.sourceTlsSpki().bytes(), t.targetTlsSpki().bytes()));
    }
    public static AcceptedTransfer open(byte[] bytes) {
        try {
            var a = array(CanonicalCbor.decode(bytes, 65536), 5);
            version(a);
            var t = new AcceptedTransfer((OpenRequest) MetadataCodec.object(a.get(1), OpenRequest.class),
                    (OpenResponse) MetadataCodec.object(a.get(2), OpenResponse.class),
                    new Bytes32((byte[]) a.get(3)), new Bytes32((byte[]) a.get(4)));
            validate(t); return t;
        } catch (RuntimeException e) { throw ProtocolException.invalid(); }
    }
    public static void validate(AcceptedTransfer t) {
        if (!MetadataCodec.openRequestHash(t.request()).equals(t.response().openRequestHash())
                || !MetadataCodec.bindingHash(t.request(), t.response().accepted(), t.sourceTlsSpki(), t.targetTlsSpki()).equals(t.response().bindingHash()))
            throw ProtocolException.invalid();
    }
    public static byte[] state(SessionState s) {
        var r = s.result();
        return CanonicalCbor.encode(List.of(1, s.revision(), s.state().name(),
                s.finish() == null ? List.of() : MetadataCodec.fields(s.finish()),
                r == null ? List.of() : List.of(r.handleId(), r.totalChunks(), r.totalPlainBytes(), r.root().bytes(), r.manifestHash().bytes(), r.completedAt().toEpochMilli()),
                s.error() == null ? "" : s.error().name(),
                s.cancel() == null ? List.of() : MetadataCodec.fields(s.cancel())));
    }
    public static SessionState state(byte[] bytes, AcceptedTransfer t) {
        try {
            var a = array(CanonicalCbor.decode(bytes, 65536), 7); version(a);
            var f = ((List<?>) a.get(3)).isEmpty() ? null : (FinishManifest) MetadataCodec.object(a.get(3), FinishManifest.class);
            var r = (List<?>) a.get(4);
            VerifiedResult result = null;
            if (!r.isEmpty()) {
                array(r, 6);
                result = new VerifiedResult((String) r.get(0), (Long) r.get(1), (Long) r.get(2),
                        new Bytes32((byte[]) r.get(3)), new Bytes32((byte[]) r.get(4)), Instant.ofEpochMilli((Long) r.get(5)));
            }
            var c = ((List<?>) a.get(6)).isEmpty() ? null : (CancelCommand) MetadataCodec.object(a.get(6), CancelCommand.class);
            var state = State.valueOf((String) a.get(2));
            var error = "".equals(a.get(5)) ? null : ErrorCode.valueOf((String) a.get(5));
            if ((state == State.VERIFYING || state == State.COMPLETED) && f == null
                    || (state == State.COMPLETED) != (result != null)
                    || (state == State.CANCELLED) != (c != null)
                    || ((state == State.FAILED || state == State.RECOVERY_REQUIRED) != (error != null))) throw ProtocolException.invalid();
            if (f != null && (!f.transferId().equals(t.request().transferId()) || !f.bindingHash().equals(t.response().bindingHash()))) throw ProtocolException.invalid();
            if (c != null && (!c.transferId().equals(t.request().transferId()) || !c.bindingHash().equals(t.response().bindingHash()))) throw ProtocolException.invalid();
            if (result != null && (!result.handleId().equals(t.response().accepted().handleId()) || result.totalChunks() != f.totalChunks()
                    || result.totalPlainBytes() != f.totalPlainBytes() || !result.root().equals(f.root()) || !result.manifestHash().equals(MetadataCodec.manifestHash(f)))) throw ProtocolException.invalid();
            return new SessionState(t, state, (Long) a.get(1), 0, 0, f, result, error, c);
        } catch (RuntimeException e) { throw ProtocolException.invalid(); }
    }
    private static List<?> array(Object v, int size) {
        if (!(v instanceof List<?> a) || a.size() != size) throw ProtocolException.invalid();
        return a;
    }
    private static void version(List<?> a) { if (!Long.valueOf(1).equals(a.get(0))) throw ProtocolException.invalid(); }
}
