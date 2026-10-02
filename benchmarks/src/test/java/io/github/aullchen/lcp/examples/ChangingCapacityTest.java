package io.github.aullchen.lcp.examples;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ChangingCapacityTest {
    @Test void reductionDrainsAcceptedRequestsAndRecoveryRestoresAdmission() {
        var time=new AtomicLong(); var gate=new ChangingCapacity(time::get);
        for (int i=0;i<4;i++) assertTrue(gate.enter());
        assertFalse(gate.enter());
        time.set(12_000_000_000L); assertEquals(1,gate.capacity());
        for (int i=0;i<3;i++) { gate.leave(); assertFalse(gate.enter()); }
        gate.leave(); assertTrue(gate.enter()); assertFalse(gate.enter());
        time.set(27_999_999_999L); assertEquals(1,gate.capacity());
        time.incrementAndGet(); assertEquals(4,gate.capacity());
        for (int i=0;i<3;i++) assertTrue(gate.enter());
        for (int i=0;i<4;i++) gate.leave();
        assertEquals(0,gate.active.get()); assertEquals(5,gate.rejected.get());
    }
}
