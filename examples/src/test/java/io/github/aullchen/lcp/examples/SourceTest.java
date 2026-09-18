package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceTest {
    @Test void changedChunkSizePreservesOldPendingBytesAndContinuousOffsets() throws Exception {
        var budget=new ByteBudget(100);
        var limits=new Limits(10000,32,100,1000,3);
        try (var chunks=new OrderedChunker(new GeneratorSource(31,42),8,limits,budget,33,Duration.ofSeconds(1),3)) {
            var first=chunks.next(8); var prepared=new PreparedChunk(first.chunk(),ChunkCompression.NONE,1);
            byte[] original=prepared.compressedBytes();
            var second=chunks.next(16); var third=chunks.next(4);
            assertEquals(8,second.chunk().offset()); assertEquals(16,second.chunk().plainLength());
            assertEquals(24,third.chunk().offset()); assertEquals(4,third.chunk().plainLength());
            assertArrayEquals(original,prepared.compressedBytes()); assertEquals(8,first.chunk().plainLength());
            first.close(); second.close(); third.close();
            try (var last=chunks.next(16)) { assertEquals(28,last.chunk().offset()); assertEquals(3,last.chunk().plainLength()); }
            assertTrue(chunks.sourceExhausted()); assertNull(chunks.next(16));
            assertEquals(31,chunks.finish(UUID.randomUUID(),ZERO,UUID.randomUUID()).totalPlainBytes());
        }
        assertEquals(0,budget.used());
    }

    @TempDir Path temp;
    static final Limits LIMITS = new Limits(10000, 1024, 1000, 10000, 1);
    static final Bytes32 ZERO = new Bytes32(new byte[32]);
    static OrderedChunker chunker(TransferSource source, Limits limits, ByteBudget budget) {
        return new OrderedChunker(source, 8, limits, budget, 17, Duration.ofMillis(100));
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 8, 9, 23, 64, 257})
    void fileAndGeneratorPreserveBytesAndFinalShortChunk(int length) throws Exception {
        byte[] expected;
        try (var source = new GeneratorSource(length, 42)) { expected = read(source, 3); }
        Path path = temp.resolve("input"); Files.write(path, expected);
        for (TransferSource source : List.of(new FileSource(path), new GeneratorSource(length, 42))) {
            var budget = new ByteBudget(17);
            try (var chunks = chunker(source, LIMITS, budget)) {
                var out = new ByteArrayOutputStream(); long count = 0;
                OrderedChunker.Pending pending;
                while ((pending = chunks.next()) != null) {
                    try (var owned = pending) {
                        var c = owned.chunk(); assertEquals(count++, c.index()); assertEquals(out.size(), c.offset());
                        byte[] bytes = new byte[(int) c.plainLength()]; c.bytes().get(bytes);
                        assertEquals(MetadataCodec.hash(bytes), c.payloadHash()); out.writeBytes(bytes);
                        assertEquals(17, budget.used()); assertTrue(c.bytes().isReadOnly());
                        assertThrows(IllegalStateException.class, chunks::next);
                    }
                    assertEquals(0, budget.used());
                }
                assertArrayEquals(expected, out.toByteArray());
                var finish = chunks.finish(UUID.randomUUID(), ZERO, UUID.randomUUID());
                assertEquals(length, finish.totalPlainBytes()); assertEquals((length + 7) / 8, finish.totalChunks());
                if (length == 0) assertEquals(MetadataCodec.hash(new byte[0]), finish.root());
            }
        }
        assertArrayEquals(expected, Files.readAllBytes(path));
    }
    static byte[] read(TransferSource source, int size) throws IOException {
        var out = new ByteArrayOutputStream(); var b = ByteBuffer.allocate(size);
        while (source.read(b) != -1) { b.flip(); while (b.hasRemaining()) out.write(b.get()); b.clear(); }
        return out.toByteArray();
    }
    @Test void unknownLengthShortAndZeroReadsFillChunk() throws Exception {
        var source = new TransferSource() {
            int calls, n;
            public int read(ByteBuffer b) { if (++calls == 1) return 0; if (n == 11) return -1; b.put((byte) n++); return 1; }
            public void close() {}
        };
        try (var chunks = chunker(source, LIMITS, new ByteBudget(17))) {
            try (var first = chunks.next()) { assertEquals(8, first.chunk().plainLength()); }
            try (var last = chunks.next()) { assertEquals(3, last.chunk().plainLength()); assertEquals(8, last.chunk().offset()); }
            assertNull(chunks.next());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void exactQuotaAllowsEofButExcessFails(boolean byteQuota) throws Exception {
        Limits limits = byteQuota ? new Limits(10000, 1024, 10, 8, 1) : new Limits(10000, 1024, 1, 100, 1);
        for (int length : new int[]{8, 9}) {
            var budget = new ByteBudget(17);
            try (var chunks = chunker(new GeneratorSource(length, 0), limits, budget)) {
                chunks.next().close();
                if (length == 8) assertNull(chunks.next());
                else { assertThrows(IOException.class, chunks::next); assertThrows(IllegalStateException.class, () -> chunks.finish(UUID.randomUUID(), ZERO, UUID.randomUUID())); }
                assertEquals(0, budget.used());
            }
        }
    }
    @Test void insufficientBudgetDoesNotConsumeSourceAndCloseDoesNotRevokeLease() throws Exception {
        var budget = new ByteBudget(17);
        try (var chunks = chunker(new GeneratorSource(9, 1), LIMITS, budget)) {
            try (var blocker = budget.reserve(1)) { assertThrows(IllegalStateException.class, chunks::next); }
            var pending = chunks.next(); assertEquals(0, pending.chunk().offset());
            chunks.close(); assertEquals(17, budget.used());
            assertThrows(IOException.class, chunks::next);
            pending.close(); pending.close(); assertEquals(0, budget.used());
        }
        assertThrows(IllegalArgumentException.class, () -> chunker(new GeneratorSource(1, 1), LIMITS, new ByteBudget(16)));
    }
    @Test void readFailureAndNoProgressReleaseReservation() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int behavior = mode;
            var source = new TransferSource() {
                public int read(ByteBuffer b) throws IOException { if (behavior == 0) throw new IOException("failure"); return behavior == 1 ? 0 : 1; }
                public void close() {}
            };
            var budget = new ByteBudget(17);
            try (var chunks = new OrderedChunker(source, 8, LIMITS, budget, 17, Duration.ofMillis(5))) {
                assertThrows(IOException.class, chunks::next); assertEquals(0, budget.used());
            }
        }
    }
    @Test void closeUnblocksSourceWithoutEarlyBudgetRelease() throws Exception {
        var entered = new CountDownLatch(1); var stop = new CountDownLatch(1);
        var source = new TransferSource() {
            public int read(ByteBuffer b) throws IOException {
                entered.countDown();
                try { if (!stop.await(2, TimeUnit.SECONDS)) throw new IOException("Test deadline"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IOException("Closed");
            }
            public void close() { stop.countDown(); }
        };
        var executor = Executors.newSingleThreadExecutor(); var budget = new ByteBudget(17);
        try (var chunks = chunker(source, LIMITS, budget)) {
            var task = executor.submit(() -> assertThrows(IOException.class, chunks::next));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(17, budget.used());
            chunks.close(); task.get(2, TimeUnit.SECONDS); assertEquals(0, budget.used());
        } finally { executor.shutdownNow(); }
    }
}
