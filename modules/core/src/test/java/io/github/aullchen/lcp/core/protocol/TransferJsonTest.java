package io.github.aullchen.lcp.core.protocol;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferJsonTest {
    static final Receipt RECEIPT = new Receipt(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), 0, 0, 1, new Bytes32(new byte[32]));
    @ParameterizedTest @ValueSource(strings = {"extra", "duplicate", "numeric", "leading-zero", "negative", "overflow", "null", "bad-hash", "unknown-disposition", "trailing"})
    void malformedAckIsRejected(String mutation) {
        String valid = new String(TransferJson.ack(RECEIPT, false, null, 1L), StandardCharsets.UTF_8);
        String damaged = switch (mutation) {
            case "extra" -> valid.replace("{\"receipt\"", "{\"extra\":null,\"receipt\"");
            case "duplicate" -> valid.replace("\"chunkIndex\":\"0\"", "\"chunkIndex\":\"0\",\"chunkIndex\":\"0\"");
            case "numeric" -> valid.replace("\"chunkIndex\":\"0\"", "\"chunkIndex\":0");
            case "leading-zero" -> valid.replace("\"chunkIndex\":\"0\"", "\"chunkIndex\":\"00\"");
            case "negative" -> valid.replace("\"chunkIndex\":\"0\"", "\"chunkIndex\":\"-1\"");
            case "overflow" -> valid.replace("\"chunkIndex\":\"0\"", "\"chunkIndex\":\"9223372036854775808\"");
            case "null" -> valid.replace("\"chunkIndex\":\"0\"", "\"chunkIndex\":null");
            case "bad-hash" -> valid.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            case "unknown-disposition" -> valid.replace("APPLIED", "OTHER");
            default -> valid + "{}";
        };
        assertThrows(IllegalArgumentException.class, () -> TransferJson.ack(damaged.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void repeatedAckAndSparsePagePreserveNullAndRangeCursor() {
        var ack = TransferJson.ack(TransferJson.ack(RECEIPT, true, 1L, 2L));
        assertEquals(RECEIPT, ack.receipt()); assertNull(ack.queueMicros()); assertNull(ack.persistMicros());
        var page = new ReceiptPage(RECEIPT.transferId(), 3, 5, List.of(), 8L, 1);
        assertEquals(page, TransferJson.page(TransferJson.page(page)));
    }
    @Test void protocolErrorsHaveConsistentStatusAndRetryability() {
        for (ErrorCode c : List.of(ErrorCode.MISSING_CHUNKS, ErrorCode.UNKNOWN_COMMIT, ErrorCode.BUSY, ErrorCode.INTEGRITY_MISMATCH))
            assertEquals(c, TransferJson.error(TransferJson.error(c)));
        String bad = new String(TransferJson.error(ErrorCode.UNKNOWN_COMMIT), StandardCharsets.UTF_8).replace("false", "true");
        assertThrows(IllegalArgumentException.class, () -> TransferJson.error(bad.getBytes(StandardCharsets.UTF_8)));
    }
}
