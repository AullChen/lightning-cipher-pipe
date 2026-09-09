package io.github.aullchen.lcp.api;

import java.util.Arrays;
import java.util.Objects;

/** Immutable 32-byte protocol value (digest or challenge). */
public final class Bytes32 {
    private final byte[] bytes;

    public Bytes32(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != 32) throw new IllegalArgumentException("Expected 32 bytes");
        this.bytes = bytes.clone();
    }

    public byte[] bytes() { return bytes.clone(); }

    @Override public boolean equals(Object other) {
        return other instanceof Bytes32 value && Arrays.equals(bytes, value.bytes);
    }

    @Override public int hashCode() { return Arrays.hashCode(bytes); }

    @Override public String toString() { return "Bytes32[redacted]"; }
}
