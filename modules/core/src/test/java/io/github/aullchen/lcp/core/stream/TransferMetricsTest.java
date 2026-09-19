package io.github.aullchen.lcp.core.stream;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TransferMetricsTest {
    @Test void eofStopsBudgetBlockingButCompletionTimeIncludesVerification() {
        var time = new AtomicLong(); var metrics = new TransferMetrics(time::get);
        metrics.window(1, true); time.set(100); metrics.sourceEof();
        time.set(200); metrics.window(1, true); // draining the final batch must not reopen source admission
        time.set(1000); metrics.finish(); time.set(2000);
        assertEquals(100, metrics.snapshot().budgetBlockedNanos());
        assertEquals(1000, metrics.snapshot().elapsedNanos());
    }

    @Test void boundedSamplesAndMissingTiming() {
        var time = new AtomicLong(); var metrics = new TransferMetrics(time::get);
        assertNull(metrics.snapshot().ackP95Nanos());
        metrics.acknowledged(10000, null, 1L, false, false);
        metrics.acknowledged(10000, 1L, 1L, true, false);
        metrics.acknowledged(10000, 1L, 1L, false, true);
        metrics.acknowledged(10000, 9L, 9L, false, false);
        assertEquals(0, metrics.snapshot().samples());
        for (int i=1; i<=100; i++) metrics.acknowledged(i*10000, 1L, 1L, false, false);
        assertEquals(64, metrics.snapshot().samples());
        assertEquals(970000, metrics.snapshot().ackP95Nanos());
    }
    @Test void roundUsesElapsedTimeAndIntegratedWindowOccupancy() {
        var time = new AtomicLong(); var metrics = new TransferMetrics(time::get);
        metrics.window(2,false); metrics.beginAttempt(false);
        time.set(500_000_000); metrics.beginAttempt(true);
        time.set(1_500_000_000); metrics.endAttempt();
        metrics.prepared(100,50,1_000_000_000); metrics.confirmed(100); metrics.confirmed(100);
        metrics.busy(); metrics.budgetFailure();
        metrics.sent(120,false); metrics.sent(120,true);
        metrics.decision(true,false,7); metrics.decision(false,true,9);
        assertEquals(240,metrics.snapshot().sentFrameBytes()); assertEquals(120,metrics.snapshot().retriedFrameBytes());
        assertEquals(1,metrics.snapshot().rollbacks()); assertEquals(16,metrics.snapshot().decisionNanos());
        for (int i=0;i<16;i++) metrics.acknowledged(10000,1L,2L,false,false);
        assertNull(metrics.pollRound()); time.set(2_000_000_000);
        var round=metrics.pollRound();
        assertEquals(50,round.goodput()); assertEquals(.5,round.windowFullShare());
        assertEquals(.5,round.compressionBusy()); assertEquals(.5,round.wireRatio());
        assertEquals(.1,round.queueShare()); assertEquals(1,round.retries()); assertEquals(1,round.busy());
        assertEquals(100, metrics.snapshot().confirmedBytes()); assertNull(metrics.pollRound());
    }
    @Test void compressionTimingExcludesInputCopy() throws Exception {
        var time=new AtomicLong(10);
        var codec=new ChunkCompression() {
            public int code() { return 0; }
            public long compressBound(long n) { return n; }
            public long workspaceBytes() { return 0; }
            public byte[] compress(byte[] bytes,int level) { time.addAndGet(23); return bytes; }
            public byte[] decompress(byte[] bytes,int n) { return bytes; }
        };
        var chunk=new io.github.aullchen.lcp.api.Chunk(0,0,1,new io.github.aullchen.lcp.api.Bytes32(new byte[32]),java.nio.ByteBuffer.wrap(new byte[1]));
        assertEquals(23,new PreparedChunk(chunk,codec,1,time::get).compressionNanos());
    }
}
