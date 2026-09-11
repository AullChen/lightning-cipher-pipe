package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FileSinkTest {
    @TempDir(factory = StorageTemp.class, cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER) Path root;
    @org.junit.jupiter.api.AfterEach void cleanup() throws Exception {
        Path owned = root.toAbsolutePath().normalize();
        assertTrue(owned.startsWith(Path.of("target", "storage-tests").toAbsolutePath().normalize()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Files.exists(owned)) {
            try (var paths = Files.walk(owned)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (java.io.IOException e) {
                if (System.nanoTime() >= deadline) throw e;
                Thread.sleep(25);
            }
        }
    }
    public static class StorageTemp implements org.junit.jupiter.api.io.TempDirFactory {
        @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
                org.junit.jupiter.api.extension.ExtensionContext context) throws Exception {
            return Files.createTempDirectory(Files.createDirectories(Path.of("target", "storage-tests")), "sink-");
        }
    }
    static AcceptedTransfer transfer() {
        UUID id = UUID.randomUUID(); Bytes32 hash = MetadataCodec.hash(new byte[0]);
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        Policy p = new Policy(0, 1, 4, 4, 4, 1, 1, 1, 1, 1, 1);
        Limits l = new Limits(4096, 4, 16, 64, 1);
        OpenRequest r = new OpenRequest(id, "route", "source", "target", hash, "source-key", hash,
                "target-key", hash, List.of(0), p, l, now, now.plusSeconds(3600));
        Accepted a = new Accepted(id, hash, id.toString(), 0, p, l, r.expiresAt());
        return new AcceptedTransfer(r, new OpenResponse(a, MetadataCodec.openRequestHash(r), MetadataCodec.bindingHash(r, a, hash, hash)), hash, hash);
    }
    static Chunk chunk(long index, long offset, byte... data) {
        return new Chunk(index, offset, data.length, MetadataCodec.hash(data), ByteBuffer.wrap(data));
    }
    @Test void durableReceiptAndShortWritesSurviveReopen() throws Exception {
        var t = transfer(); var c = chunk(0, 0, (byte) 1, (byte) 2, (byte) 3);
        var shortIo = new FileSink.Io() {
            @Override public int write(FileChannel channel, ByteBuffer b, long offset) throws java.io.IOException {
                int limit = b.limit(); b.limit(b.position() + 1);
                try { return channel.write(b, offset); } finally { b.limit(limit); }
            }
        };
        try (var sink = new FileSink(root, 4096, shortIo)) {
            var s = sink.open(t); assertEquals(s, sink.open(t));
            assertEquals(c.payloadHash(), s.commit(c).payloadHash());
            long revision = s.state().revision();
            assertEquals(s.commit(c), s.commit(c)); assertEquals(revision, s.state().revision());
            assertEquals(68, Files.size(root.resolve(t.request().transferId() + "/receipts.log")));
        }
        try (var sink = new FileSink(root, 4096)) {
            var s = sink.recover(t.request().transferId()); assertEquals(1, s.state().committedChunks());
            ByteBuffer b = ByteBuffer.allocate(3); assertEquals(3, s.readPersisted(0, b)); assertArrayEquals(new byte[]{1,2,3}, b.array());
            assertEquals(c.payloadHash(), s.receipts(0, 1).entries().get(0).payloadHash());
            assertEquals(2L, s.receipts(1, 1).nextFrom());
        }
    }
    @Test void incompleteTailIsRemovedButWholeCorruptRecordFreezes() throws Exception {
        var t = transfer(); Path log = root.resolve(t.request().transferId() + "/receipts.log");
        try (var sink = new FileSink(root, 4096)) { sink.open(t).commit(chunk(0, 0, (byte)1)); }
        Files.write(log, new byte[]{1,2,3}, StandardOpenOption.APPEND);
        try (var sink = new FileSink(root, 4096)) { assertEquals(State.TRANSFERRING, sink.recover(t.request().transferId()).state().state()); assertEquals(68, Files.size(log)); }
        byte[] bytes = Files.readAllBytes(log); bytes[32] ^= 1; Files.write(log, bytes);
        try (var sink = new FileSink(root, 4096)) {
            var s = sink.recover(t.request().transferId()); assertEquals(State.RECOVERY_REQUIRED, s.state().state());
            assertThrows(TransferException.class, () -> s.commit(chunk(1, 1, (byte)2)));
        }
    }
    @ParameterizedTest @EnumSource(FileSink.Boundary.class)
    void uncertainWritesFreezeWithoutAcknowledging(FileSink.Boundary boundary) throws Exception {
        var t = transfer();
        try (var sink = new FileSink(root, 4096, new FileSink.Io() {
            @Override public void after(FileSink.Boundary b) throws java.io.IOException { if (b == boundary) throw new java.io.IOException("Injected storage fault"); }
        })) {
            var s = sink.open(t);
            assertEquals(ErrorCode.UNKNOWN_COMMIT, assertThrows(TransferException.class, () -> s.commit(chunk(0, 0, (byte)1))).code());
            assertEquals(State.RECOVERY_REQUIRED, s.state().state());
        }
        try (var sink = new FileSink(root, 4096)) { assertEquals(State.RECOVERY_REQUIRED, sink.recover(t.request().transferId()).state().state()); }
    }
    @Test void overlapsQuotaAndConflictsCannotOverwrite() throws Exception {
        var t = transfer();
        try (var sink = new FileSink(root, 4096)) {
            var s = sink.open(t); s.commit(chunk(1, 1, (byte)4, (byte)5));
            assertEquals(ErrorCode.CHUNK_CONFLICT, assertThrows(TransferException.class, () -> s.commit(chunk(0, 0, (byte)2, (byte)3))).code());
            assertThrows(TransferException.class, () -> s.commit(chunk(1, 1, (byte)6)));
            assertThrows(TransferException.class, () -> s.commit(chunk(16, 3, (byte)6)));
            assertEquals(1, s.state().committedChunks());
        }
        Path other = root.resolve("budget");
        try (var sink = new FileSink(other, 52)) { assertThrows(TransferException.class, () -> sink.open(transfer())); }
    }
    @Test void missingPersistedBytesFreeze() throws Exception {
        var t = transfer();
        try (var sink = new FileSink(root, 4096)) { sink.open(t).commit(chunk(0, 0, (byte)1)); }
        Files.write(root.resolve(t.request().transferId() + "/payload.bin"), new byte[0]);
        try (var sink = new FileSink(root, 4096)) { assertEquals(State.RECOVERY_REQUIRED, sink.recover(t.request().transferId()).state().state()); }
    }
    @Test void finishAndTerminalGatesAreDurable() throws Exception {
        var t = transfer(); var hash = MetadataCodec.hash(new byte[0]);
        var f = new FinishManifest(t.request().transferId(), t.response().bindingHash(), UUID.randomUUID(), 0, 0, hash);
        try (var sink = new FileSink(root, 4096)) {
            var s = sink.open(t); s.recordVerifying(f); s.recordVerifying(f);
            assertThrows(TransferException.class, () -> s.commit(chunk(0, 0, (byte)1)));
            s.recordCompleted(new VerifiedResult(t.response().accepted().handleId(), 0, 0, hash, MetadataCodec.manifestHash(f), t.request().createdAt()));
        }
        try (var sink = new FileSink(root, 4096)) { assertEquals(State.COMPLETED, sink.recover(t.request().transferId()).state().state()); }
    }
    @Test void unknownDirectoryIsNotOverwritten() throws Exception {
        Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
        assertThrows(java.io.IOException.class, () -> new FileSink(root, 4096));
    }
    @Test void lockRejectsAnotherProcessThenReleases() throws Exception {
        try (var sink = new FileSink(root, 4096)) { Process locked = child(); assertTrue(locked.waitFor(15, TimeUnit.SECONDS)); assertEquals(23, locked.exitValue()); }
        Process p = child(); assertTrue(p.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
    }
    private Process child() throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(), "-cp",
                System.getProperty("java.class.path"), LockProbe.class.getName(), root.toString()).redirectErrorStream(true).start();
    }
    public static class LockProbe {
        public static void main(String[] args) throws Exception {
            try (var sink = new FileSink(Path.of(args[0]), 4096)) { }
            catch (java.io.IOException e) { System.exit(23); }
        }
    }
}



