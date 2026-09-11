package io.github.aullchen.lcp.api;

import io.github.aullchen.lcp.api.TransferStorage.ErrorCode;
import java.io.IOException;

public final class TransferException extends IOException {
    private final ErrorCode code;
    public TransferException(ErrorCode code) { super(code.name()); this.code = code; }
    public ErrorCode code() { return code; }
}
