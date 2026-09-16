package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.Receipt;
import io.github.aullchen.lcp.api.TransferStorage.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import static io.github.aullchen.lcp.api.TransferStorage.ErrorCode.UNKNOWN_COMMIT;

/** Compact, bounded descriptor history, owned by the single source coordinator. */
public final class ReceiptLedger {
    private static final int SLOT = 49;
    private final UUID id;
    private final long maxChunks;
    private final ByteBuffer slots;
    private int assigned;
    public ReceiptLedger(UUID id, long maxChunks, long metadataBudget) {
        if (maxChunks < 1 || maxChunks > Integer.MAX_VALUE / SLOT || maxChunks > metadataBudget / 52)
            throw new IllegalArgumentException("Receipt history exceeds metadata budget");
        this.id = java.util.Objects.requireNonNull(id); this.maxChunks = maxChunks;
        slots = ByteBuffer.allocate(Math.toIntExact(maxChunks * SLOT));
    }
    public long assigned() { return assigned; }
    public void assign(Receipt r) {
        if (!id.equals(r.transferId()) || r.chunkIndex() != assigned || assigned == maxChunks)
            throw new IllegalArgumentException("Descriptors must be assigned in source order");
        int p = assigned++ * SLOT;
        slots.putLong(p,r.offset()); slots.putLong(p+8,r.plainLength()); slots.put(p+16,r.payloadHash().bytes());
    }
    private Receipt receipt(long index) throws TransferException {
        if (index < 0 || index >= assigned) throw new TransferException(UNKNOWN_COMMIT);
        int p = (int) index * SLOT; byte[] hash = new byte[32]; slots.get(p+16,hash);
        return new Receipt(id,index,slots.getLong(p),slots.getLong(p+8),new Bytes32(hash));
    }
    public boolean acknowledged(long index) {
        if (index < 0 || index >= assigned) throw new IllegalArgumentException("Unassigned index");
        return slots.get((int)index*SLOT+48) != 0;
    }
    public void acknowledge(Receipt r) throws TransferException {
        if (!receipt(r.chunkIndex()).equals(r)) throw new TransferException(UNKNOWN_COMMIT);
        slots.put((int)r.chunkIndex()*SLOT+48,(byte)1);
    }
    /** Missing historical ACKs or changed descriptors stop recovery; an empty sparse page is not EOF. */
    public void reconcile(ReceiptPage page, long from, int limit, long minimumRevision) throws TransferException {
        long end = from + limit;
        Long next = end < maxChunks ? end : null;
        if (!id.equals(page.transferId()) || page.from() != from || page.limit() != limit || from < 0 || limit < 1
                || limit > 256 || end > assigned || page.revision() < minimumRevision
                || !java.util.Objects.equals(next,page.nextFrom())) throw new TransferException(UNKNOWN_COMMIT);
        int entry = 0;
        for (long i = from; i < end; i++) {
            Receipt r = entry < page.entries().size() ? page.entries().get(entry) : null;
            if (r != null && r.chunkIndex() == i) {
                if (!receipt(i).equals(r)) throw new TransferException(UNKNOWN_COMMIT);
                entry++;
            } else if (acknowledged(i)) throw new TransferException(UNKNOWN_COMMIT);
        }
        if (entry != page.entries().size()) throw new TransferException(UNKNOWN_COMMIT);
        for (Receipt r : page.entries()) acknowledge(r);
    }
}
