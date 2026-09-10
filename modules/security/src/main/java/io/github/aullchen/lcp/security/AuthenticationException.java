package io.github.aullchen.lcp.security;

/** Generic rejection without key material, payloads or filesystem paths. */
public final class AuthenticationException extends SecurityException {
    public AuthenticationException() { super("Peer or message authentication failed"); }
}
