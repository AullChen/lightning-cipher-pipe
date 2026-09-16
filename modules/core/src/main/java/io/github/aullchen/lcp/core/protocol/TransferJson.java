package io.github.aullchen.lcp.core.protocol;

import com.fasterxml.jackson.core.*;
import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Bounded control responses. Unsigned values are canonical decimal strings. */
public final class TransferJson {
    private TransferJson() {}
    private static final JsonFactory JSON = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(128)
                    .maxNameLength(64).maxNumberLength(19).build()).build();
    public record Status(UUID transferId, State state, long revision, long committedChunks, long committedPlainBytes,
                         Instant expiresAt, FinishView finish, VerifiedResult result, ErrorCode error) {}
    public record FinishView(UUID commandId, long totalChunks, long totalPlainBytes, Bytes32 root, Bytes32 manifestHash) {}
    public record ChunkAck(Receipt receipt, String disposition, Long queueMicros, Long persistMicros) {}
    public static int httpStatus(ErrorCode c) {
        return switch (c) {
            case AUTH_FAILED -> 401; case FORBIDDEN -> 403; case NOT_FOUND -> 404;
            case INVALID_MESSAGE -> 400; case LIMIT_EXCEEDED -> 413; case EXPIRED -> 410;
            case BUSY -> 429; case UNAVAILABLE, UNKNOWN_COMMIT -> 503; case INTEGRITY_MISMATCH -> 422;
            default -> 409;
        };
    }
    public static boolean retryable(ErrorCode c) { return c == ErrorCode.BUSY || c == ErrorCode.UNAVAILABLE || c == ErrorCode.MISSING_CHUNKS; }
    private static Map<String,Object> object(Object... pairs) {
        Map<String,Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    private static Map<String,Object> receipt(Receipt r) {
        return object("transferId", r.transferId(), "chunkIndex", r.chunkIndex(), "offset", r.offset(), "plainLength", r.plainLength(), "payloadHash", r.payloadHash());
    }
    private static Map<String,Object> errorObject(ErrorCode c) {
        return object("code", c.name(), "retryable", retryable(c), "requestId", UUID.randomUUID(), "message", c.name());
    }
    public static byte[] error(ErrorCode c) { return encode(errorObject(c)); }
    public static byte[] ack(Receipt r, boolean repeated, Long queueMicros, Long persistMicros) {
        return encode(object("receipt", receipt(r), "disposition", repeated ? "ALREADY_APPLIED" : "APPLIED",
                "queueMicros", repeated ? null : queueMicros, "persistMicros", repeated ? null : persistMicros));
    }
    public static byte[] page(ReceiptPage p) {
        return encode(object("transferId", p.transferId(), "from", p.from(), "limit", p.limit(), "entries", p.entries().stream().map(TransferJson::receipt).toList(), "nextFrom", p.nextFrom(), "revision", p.revision()));
    }
    private static Object result(VerifiedResult r) {
        return r == null ? null : object("handleId", r.handleId(), "totalChunks", r.totalChunks(), "totalPlainBytes", r.totalPlainBytes(), "root", r.root(), "manifestHash", r.manifestHash(), "completedAt", r.completedAt().toEpochMilli());
    }
    public static byte[] status(SessionState s) {
        var f = s.finish();
        return encode(object("transferId", s.transfer().request().transferId(), "state", s.state().name(), "revision", s.revision(),
                "committedChunks", s.committedChunks(), "committedPlainBytes", s.committedPlainBytes(),
                "expiresAt", s.transfer().response().accepted().expiresAt().toEpochMilli(),
                "finish", f == null ? null : object("commandId", f.commandId(), "totalChunks", f.totalChunks(), "totalPlainBytes", f.totalPlainBytes(), "root", f.root(), "manifestHash", MetadataCodec.manifestHash(f)),
                "result", result(s.result()), "error", s.error() == null ? null : errorObject(s.error())));
    }
    private static byte[] encode(Object v) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(bytes)) { write(g, v); }
        catch (IOException e) { throw new IllegalStateException(e); }
        if (bytes.size() > MetadataCodec.CONTROL_LIMIT) throw ProtocolException.limit();
        return bytes.toByteArray();
    }
    private static void write(JsonGenerator g, Object v) throws IOException {
        if (v == null) g.writeNull();
        else if (v instanceof Map<?,?> m) { g.writeStartObject(); for (var e : m.entrySet()) { g.writeFieldName((String)e.getKey()); write(g, e.getValue()); } g.writeEndObject(); }
        else if (v instanceof List<?> l) { g.writeStartArray(); for (Object x : l) write(g, x); g.writeEndArray(); }
        else if (v instanceof Boolean b) g.writeBoolean(b);
        else if (v instanceof Bytes32 b) g.writeString(Base64.getUrlEncoder().withoutPadding().encodeToString(b.bytes()));
        else g.writeString(v.toString());
    }
    private static Object read(JsonParser p) throws IOException {
        if (p.currentToken() == JsonToken.START_OBJECT) {
            Map<String,Object> m = new LinkedHashMap<>();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (m.size() == 16 || p.currentToken() != JsonToken.FIELD_NAME) throw ProtocolException.invalid();
                String key = p.currentName(); p.nextToken(); m.put(key, read(p));
            }
            return m;
        }
        if (p.currentToken() == JsonToken.START_ARRAY) {
            List<Object> a = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) { if (a.size() == 256) throw ProtocolException.limit(); a.add(read(p)); }
            return a;
        }
        if (p.currentToken() == JsonToken.VALUE_STRING) return p.getText();
        if (p.currentToken() == JsonToken.VALUE_NULL) return null;
        if (p.currentToken() == JsonToken.VALUE_TRUE) return true;
        if (p.currentToken() == JsonToken.VALUE_FALSE) return false;
        throw ProtocolException.invalid();
    }
    private static Object parse(byte[] bytes) {
        if (bytes.length > MetadataCodec.CONTROL_LIMIT) throw ProtocolException.limit();
        try (var p = JSON.createParser(bytes)) { p.nextToken(); Object v = read(p); if (p.nextToken() != null) throw ProtocolException.invalid(); return v; }
        catch (IOException | RuntimeException e) { throw ProtocolException.invalid(); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> fields(Object v, String... keys) {
        if (!(v instanceof Map<?,?> m) || !m.keySet().equals(Set.of(keys))) throw ProtocolException.invalid(); return (Map<String,Object>) m;
    }
    private static String string(Object v) { if (!(v instanceof String s)) throw ProtocolException.invalid(); return s; }
    private static long u(Object v) {
        String s = string(v); if (!s.matches("0|[1-9][0-9]{0,18}")) throw ProtocolException.invalid();
        try { return Long.parseLong(s); } catch (NumberFormatException e) { throw ProtocolException.invalid(); }
    }
    private static UUID uuid(Object v) {
        try { String s = string(v); UUID id = UUID.fromString(s); if (!id.toString().equals(s)) throw ProtocolException.invalid(); return id; }
        catch (IllegalArgumentException e) { throw ProtocolException.invalid(); }
    }
    private static Bytes32 hash(Object v) {
        try {
            String s = string(v); byte[] bytes = Base64.getUrlDecoder().decode(s);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(s)) throw ProtocolException.invalid(); return new Bytes32(bytes);
        } catch (IllegalArgumentException e) { throw ProtocolException.invalid(); }
    }
    private static Receipt receipt(Object v) {
        var m = fields(v, "transferId", "chunkIndex", "offset", "plainLength", "payloadHash");
        return new Receipt(uuid(m.get("transferId")), u(m.get("chunkIndex")), u(m.get("offset")), u(m.get("plainLength")), hash(m.get("payloadHash")));
    }
    public static ChunkAck ack(byte[] bytes) {
        var m = fields(parse(bytes), "receipt", "disposition", "queueMicros", "persistMicros");
        String disposition = string(m.get("disposition"));
        if (!Set.of("APPLIED", "ALREADY_APPLIED").contains(disposition)) throw ProtocolException.invalid();
        Long queue = m.get("queueMicros") == null ? null : u(m.get("queueMicros"));
        Long persist = m.get("persistMicros") == null ? null : u(m.get("persistMicros"));
        if (disposition.equals("ALREADY_APPLIED") && (queue != null || persist != null)) throw ProtocolException.invalid();
        return new ChunkAck(receipt(m.get("receipt")), disposition, queue, persist);
    }
    public static ReceiptPage page(byte[] bytes) {
        var m = fields(parse(bytes), "transferId", "from", "limit", "entries", "nextFrom", "revision");
        long from = u(m.get("from")), limit = u(m.get("limit")); UUID id = uuid(m.get("transferId"));
        if (limit < 1 || limit > 256 || !(m.get("entries") instanceof List<?> entries)) throw ProtocolException.invalid();
        List<Receipt> receipts = new ArrayList<>(); long previous = from - 1;
        for (Object entry : entries) {
            Receipt r = receipt(entry);
            if (!r.transferId().equals(id) || r.chunkIndex() <= previous || r.chunkIndex() < from || r.chunkIndex() - from >= limit) throw ProtocolException.invalid();
            previous = r.chunkIndex(); receipts.add(r);
        }
        Long next = m.get("nextFrom") == null ? null : u(m.get("nextFrom"));
        if (next != null && (next < from || next - from != limit)) throw ProtocolException.invalid();
        return new ReceiptPage(id, from, (int)limit, receipts, next, u(m.get("revision")));
    }
    private static ErrorCode errorObject(Object v) {
        var m = fields(v, "code", "retryable", "requestId", "message");
        ErrorCode c;
        try { c = ErrorCode.valueOf(string(m.get("code"))); } catch (IllegalArgumentException e) { throw ProtocolException.invalid(); }
        if (!Boolean.valueOf(retryable(c)).equals(m.get("retryable"))) throw ProtocolException.invalid();
        uuid(m.get("requestId")); string(m.get("message")); return c;
    }
    public static ErrorCode error(byte[] bytes) { return errorObject(parse(bytes)); }
    public static Status status(byte[] bytes) {
        var m = fields(parse(bytes), "transferId", "state", "revision", "committedChunks", "committedPlainBytes", "expiresAt", "finish", "result", "error");
        FinishView f = null; VerifiedResult r = null;
        if (m.get("finish") != null) {
            var x = fields(m.get("finish"), "commandId", "totalChunks", "totalPlainBytes", "root", "manifestHash");
            f = new FinishView(uuid(x.get("commandId")), u(x.get("totalChunks")), u(x.get("totalPlainBytes")), hash(x.get("root")), hash(x.get("manifestHash")));
        }
        if (m.get("result") != null) {
            var x = fields(m.get("result"), "handleId", "totalChunks", "totalPlainBytes", "root", "manifestHash", "completedAt");
            r = new VerifiedResult(string(x.get("handleId")), u(x.get("totalChunks")), u(x.get("totalPlainBytes")), hash(x.get("root")), hash(x.get("manifestHash")), Instant.ofEpochMilli(u(x.get("completedAt"))));
        }
        State state;
        try { state = State.valueOf(string(m.get("state"))); } catch (IllegalArgumentException e) { throw ProtocolException.invalid(); }
        ErrorCode error = m.get("error") == null ? null : errorObject(m.get("error"));
        if ((state == State.COMPLETED) != (r != null) || (state == State.VERIFYING || state == State.COMPLETED) && f == null
                || (state == State.FAILED || state == State.RECOVERY_REQUIRED) != (error != null)) throw ProtocolException.invalid();
        if (r != null && (r.totalChunks() != f.totalChunks() || r.totalPlainBytes() != f.totalPlainBytes()
                || !r.root().equals(f.root()) || !r.manifestHash().equals(f.manifestHash())
                || r.totalChunks() != u(m.get("committedChunks")) || r.totalPlainBytes() != u(m.get("committedPlainBytes")))) throw ProtocolException.invalid();
        return new Status(uuid(m.get("transferId")), state, u(m.get("revision")), u(m.get("committedChunks")), u(m.get("committedPlainBytes")), Instant.ofEpochMilli(u(m.get("expiresAt"))), f, r, error);
    }
    public static byte[] cancel(CancelCommand command) {
        return encode(object("transferId",command.transferId(),"commandId",command.commandId(),"bindingHash",command.bindingHash()));
    }
    public static CancelCommand cancel(byte[] bytes) {
        var m = fields(parse(bytes), "transferId", "commandId", "bindingHash");
        return new CancelCommand(uuid(m.get("transferId")), uuid(m.get("commandId")), hash(m.get("bindingHash")));
    }
}
