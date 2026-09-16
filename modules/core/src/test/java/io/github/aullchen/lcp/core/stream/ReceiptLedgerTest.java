package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.Receipt;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptLedgerTest {
    final UUID id = UUID.randomUUID();
    Receipt receipt(int i) { return new Receipt(id,i,i,1,MetadataCodec.hash(new byte[]{(byte)i})); }
    @Test void sparsePagesCoverAllAssignedDescriptorsIncludingHistoricalAcks() throws Exception {
        var ledger = new ReceiptLedger(id,300,300*52);
        for (int i=0;i<270;i++) ledger.assign(receipt(i));
        ledger.acknowledge(receipt(269));
        ledger.reconcile(new ReceiptPage(id,0,256,List.of(),256L,1),0,256,0);
        ledger.reconcile(new ReceiptPage(id,256,14,List.of(receipt(269)),270L,1),256,14,1);
        assertFalse(ledger.acknowledged(0)); assertTrue(ledger.acknowledged(269));
        assertEquals(ErrorCode.UNKNOWN_COMMIT,assertThrows(TransferException.class,
                () -> ledger.reconcile(new ReceiptPage(id,256,14,List.of(),270L,2),256,14,1)).code());
    }
    @Test void changedHashOffsetOrLengthCannotReplaceKnownDescriptor() {
        for (var changed : List.of(new Receipt(id,0,1,1,receipt(0).payloadHash()),new Receipt(id,0,0,2,receipt(0).payloadHash()),
                new Receipt(id,0,0,1,receipt(1).payloadHash()))) {
            var ledger = new ReceiptLedger(id,2,104); ledger.assign(receipt(0));
            assertThrows(TransferException.class, () -> ledger.reconcile(new ReceiptPage(id,0,1,List.of(changed),1L,0),0,1,0));
            assertFalse(ledger.acknowledged(0));
        }
    }
    @Test void pageIdentityRangeRevisionAndOrderingAreValidated() {
        var ledger = new ReceiptLedger(id,3,156); ledger.assign(receipt(0)); ledger.assign(receipt(1));
        for (var page : List.of(new ReceiptPage(UUID.randomUUID(),0,2,List.of(),2L,1),
                new ReceiptPage(id,1,2,List.of(),2L,1),new ReceiptPage(id,0,2,List.of(),null,1),
                new ReceiptPage(id,0,2,List.of(),2L,0),new ReceiptPage(id,0,2,List.of(receipt(1),receipt(0)),2L,1),
                new ReceiptPage(id,0,2,List.of(receipt(0),receipt(0)),2L,1),new ReceiptPage(id,0,2,List.of(receipt(2)),2L,1)))
            assertThrows(TransferException.class, () -> ledger.reconcile(page,0,2,1));
    }
    @Test void reconciliationAcknowledgesLostResponseWithoutResending() throws Exception {
        var ledger = new ReceiptLedger(id,1,52); ledger.assign(receipt(0));
        ledger.reconcile(new ReceiptPage(id,0,1,List.of(receipt(0)),null,1),0,1,0);
        assertTrue(ledger.acknowledged(0));
    }
    @Test void allocationAndAssignmentAreBoundedBeforeReadingPayload() {
        assertThrows(IllegalArgumentException.class, () -> new ReceiptLedger(id,2,52));
        assertThrows(IllegalArgumentException.class, () -> new ReceiptLedger(id,Long.MAX_VALUE,Long.MAX_VALUE));
        var ledger = new ReceiptLedger(id,1,52); ledger.assign(receipt(0));
        assertThrows(IllegalArgumentException.class, () -> ledger.assign(receipt(1)));
    }
}
