package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.stream.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.aullchen.lcp.examples.FileSinkTest.*;

class ConcurrentStorageTest extends StorageTestSupport {
    @Test void reservationsRejectDuplicateAndOverlapWhileDisjointWriteCompletes() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var io = new FileSink.Io() {
            @Override public void after(FileSink.Boundary boundary) throws IOException {
                if (boundary == FileSink.Boundary.PAYLOAD_WRITTEN && first.getAndSet(false)) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Barrier timed out"); }
                    catch (InterruptedException e) { throw new IOException(e); }
                }
            }
        };
        var pool = Executors.newSingleThreadExecutor();
        try (var sink = new FileSink(root,4096,io)) {
            var transfer = transfer(2); var session = sink.open(transfer);
            var block = chunk(0,0,(byte)1,(byte)2);
            var writer = pool.submit(() -> session.commit(block));
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                assertEquals(ErrorCode.BUSY, assertThrows(TransferException.class, () -> session.commit(block)).code());
                assertEquals(ErrorCode.CHUNK_CONFLICT, assertThrows(TransferException.class, () -> session.commit(chunk(0,4,(byte)3))).code());
                assertEquals(ErrorCode.CHUNK_CONFLICT, assertThrows(TransferException.class, () -> session.commit(chunk(1,1,(byte)3))).code());
                var second = session.commit(chunk(1,2,(byte)3));
                assertEquals(1, session.state().committedChunks());
                assertEquals(second, session.receipts(0,2).entries().get(0));
                var finish = new FinishManifest(transfer.request().transferId(),transfer.response().bindingHash(),UUID.randomUUID(),2,3,block.payloadHash());
                assertEquals(ErrorCode.BUSY, assertThrows(TransferException.class, () -> session.recordVerifying(finish)).code());
            } finally { release.countDown(); }
            var receipt = writer.get(3,TimeUnit.SECONDS);
            assertEquals(receipt,session.commit(block));
            assertEquals(2,session.state().committedChunks());
            ByteBuffer bytes = ByteBuffer.allocate(3); session.readPersisted(0,bytes);
            assertArrayEquals(new byte[]{1,2,3},bytes.array());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void fullWindowKeepsBudgetAndCompressedMaterialUntilExplicitRelease() throws Exception {
        var limits = new Limits(4096,4,16,64,2); var budget = new ByteBudget(32);
        try (var chunks = new OrderedChunker(new GeneratorSource(8,42),4,limits,budget,16,Duration.ofSeconds(1),2)) {
            var a = chunks.next(); var b = chunks.next();
            try {
                assertEquals(32,budget.used());
                var prepared = new PreparedChunk(a.chunk(),ChunkCompression.NONE,1);
                byte[] original = prepared.compressedBytes();
                for (int retry = 0; retry < 4; retry++) assertArrayEquals(original,prepared.compressedBytes());
                assertThrows(IllegalStateException.class,chunks::next);
                b.close(); assertEquals(16,budget.used());
                assertNull(chunks.next());
                assertThrows(IllegalStateException.class, () -> chunks.finish(UUID.randomUUID(),a.chunk().payloadHash(),UUID.randomUUID()));
            } finally { a.close(); b.close(); }
            assertEquals(0,budget.used());
        }
    }
}
