package io.github.aullchen.lcp.core.protocol;

/** A malformed or oversized message; rejecting it does not change transfer state. */
public final class ProtocolException extends IllegalArgumentException {
    public enum Code { INVALID_MESSAGE, LIMIT_EXCEEDED }
    private final Code code;

    public ProtocolException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
    static ProtocolException invalid() { return new ProtocolException(Code.INVALID_MESSAGE, "Invalid protocol metadata"); }
    static ProtocolException limit() { return new ProtocolException(Code.LIMIT_EXCEEDED, "Protocol size limit exceeded"); }
}
