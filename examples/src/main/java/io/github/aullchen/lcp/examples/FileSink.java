package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import io.github.aullchen.lcp.core.protocol.StorageCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;
import static java.nio.file.StandardOpenOption.*;
import static io.github.aullchen.lcp.api.TransferStorage.ErrorCode.*;

/** Single-process, bounded-index reference Sink for a trusted local filesystem. */
public final class FileSink implements TransferSink {
    private static final int RECORD = 68, SLOT = 48;
    enum Boundary { PAYLOAD_WRITTEN, PAYLOAD_FORCED, RECEIPT_WRITTEN, RECEIPT_FORCED, INDEX_PUBLISHED }
    interface Io {
        default int write(FileChannel channel, ByteBuffer bytes, long offset) throws IOException { return channel.write(bytes, offset); }
        default void after(Boundary boundary) throws IOException {}
    }
    private final Path root;
    private final long metadataBudget;
    private final Io io;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Session current;
    private boolean closed;

    public FileSink(Path root, long metadataBudget) throws IOException { this(root, metadataBudget, new Io() {}); }
    FileSink(Path root, long metadataBudget, Io io) throws IOException {
        if (metadataBudget < SLOT + 4 || metadataBudget > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid index budget");
        this.metadataBudget = metadataBudget; this.io = Objects.requireNonNull(io);
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        safe(this.root, true);
        lockChannel = FileChannel.open(this.root.resolve(".lcp.lock"), CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Sink root is locked");
        } catch (IOException | OverlappingFileLockException e) { lockChannel.close(); throw new IOException("Sink root is locked", e); }
        lock = acquired;
        try {
            Path system = this.root.resolve(".lcp-system");
            Files.createDirectories(system); safe(system, true);
            Path probe = system.resolve(UUID.randomUUID() + ".probe");
            if (Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unrecognized atomic probe");
            atomic(probe, new byte[] {1}); atomic(probe, new byte[] {2}); Files.delete(probe);
            inspectRoot(null);
        } catch (IOException | RuntimeException e) { lock.release(); lockChannel.close(); throw e; }
    }
    private static void safe(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toAbsolutePath().normalize())
                || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Untrusted storage path");
    }
    private static byte[] readSmall(Path path) throws IOException {
        safe(path, false);
        try (var c = FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS)) {
            if (c.size() > 65536) throw new IOException("Oversized storage record");
            var b = ByteBuffer.allocate((int) c.size()); readFully(c, b, 0); return b.array();
        }
    }
    private static void readFully(FileChannel c, ByteBuffer b, long offset) throws IOException {
        while (b.hasRemaining()) { int n = c.read(b, offset); if (n <= 0) throw new IOException("Truncated storage record"); offset += n; }
    }
    private static void atomic(Path path, byte[] bytes) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) safe(path, false);
        Path temp = Files.createTempFile(path.getParent(), ".lcp-", ".tmp");
        try {
            try (var c = FileChannel.open(temp, WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer b = ByteBuffer.wrap(bytes);
                while (b.hasRemaining()) if (c.write(b) <= 0) throw new IOException("No storage progress");
                c.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private void ready() throws IOException { if (closed) throw new IOException("Sink is closed"); safe(root, true); }
    /** Scan only small control records, never retain a map of all transfer IDs. */
    private void inspectRoot(UUID allowed) throws IOException {
        int active = 0;
        try (var paths = Files.newDirectoryStream(root)) {
            for (Path p : paths) {
                if (p.getFileName().toString().equals(".lcp-system")) { safe(p, true); continue; }
                if (p.getFileName().toString().equals(".lcp.lock")) { safe(p, false); continue; }
                UUID id;
                try { id = UUID.fromString(p.getFileName().toString()); }
                catch (IllegalArgumentException e) { throw new IOException("Unrecognized entry in Sink root", e); }
                if (!id.toString().equals(p.getFileName().toString())) throw new IOException("Invalid transfer directory");
                safe(p, true);
                var t = StorageCodec.open(readSmall(p.resolve("open.cbor")));
                if (!t.request().transferId().equals(id)) throw new IOException("Transfer directory mismatch");
                var s = StorageCodec.state(readSmall(p.resolve("state.cbor")), t);
                if (active(s.state())) {
                    if (++active > 1 || allowed != null && !id.equals(allowed)) throw new TransferException(BUSY);
                }
            }
        } catch (IllegalArgumentException e) { throw new IOException("Invalid durable control record", e); }
    }
    private static boolean active(State s) { return s == State.OPEN || s == State.TRANSFERRING || s == State.VERIFYING; }
    private void capacity(AcceptedTransfer t) throws TransferException {
        if (t.response().accepted().limits().maxChunks() > metadataBudget / (SLOT + 4)) throw new TransferException(LIMIT_EXCEEDED);
    }
    @Override public synchronized SinkSession open(AcceptedTransfer t) throws IOException {
        ready(); StorageCodec.validate(t); capacity(t);
        UUID id = t.request().transferId(); Path dir = root.resolve(id.toString());
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            SinkSession session = recover(id);
            if (!session.state().transfer().equals(t)) throw new TransferException(OPEN_CONFLICT);
            return session;
        }
        if (current != null) throw new TransferException(BUSY);
        inspectRoot(id);
        Files.createDirectory(dir);
        // An interrupted Open leaves an unowned/incomplete directory: startup refuses to guess.
        atomic(dir.resolve("open.cbor"), StorageCodec.open(t));
        try (var payload = FileChannel.open(dir.resolve("payload.bin"), CREATE_NEW, WRITE);
             var receipts = FileChannel.open(dir.resolve("receipts.log"), CREATE_NEW, WRITE)) {
            payload.force(true); receipts.force(true);
        }
        atomic(dir.resolve("state.cbor"), StorageCodec.state(new SessionState(t, State.OPEN, 0, 0, 0, null, null, null, null)));
        current = new Session(dir, t); return current;
    }
    @Override public synchronized SinkSession recover(UUID id) throws IOException {
        ready(); Objects.requireNonNull(id);
        if (current != null) {
            if (current.transfer.request().transferId().equals(id)) return current;
            throw new TransferException(BUSY);
        }
        Path dir = root.resolve(id.toString());
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new TransferException(NOT_FOUND);
        safe(dir, true);
        AcceptedTransfer t;
        try { t = StorageCodec.open(readSmall(dir.resolve("open.cbor"))); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid Open record", e); }
        if (!t.request().transferId().equals(id)) throw new IOException("Transfer directory mismatch");
        capacity(t); current = new Session(dir, t); return current;
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        try { if (current != null) current.close(); }
        finally { closed = true; try { lock.release(); } finally { lockChannel.close(); } }
    }

    private final class Session implements SinkSession {
        private final Path dir;
        private final AcceptedTransfer transfer;
        private final Limits limits;
        private final ByteBuffer index;
        private final int[] ordered;
        private final FileChannel payload, log;
        private SessionState saved;
        private int count;
        private long total;
        private boolean ended, frozen;
        Session(Path dir, AcceptedTransfer transfer) throws IOException {
            this.dir = dir; this.transfer = transfer; limits = transfer.response().accepted().limits();
            int slots = Math.toIntExact(limits.maxChunks());
            index = ByteBuffer.allocate(Math.multiplyExact(slots, SLOT)); ordered = new int[slots];
            try { saved = StorageCodec.state(readSmall(dir.resolve("state.cbor")), transfer); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid state record", e); }
            safe(dir.resolve("payload.bin"), false); safe(dir.resolve("receipts.log"), false);
            payload = FileChannel.open(dir.resolve("payload.bin"), READ, WRITE, LinkOption.NOFOLLOW_LINKS);
            try { log = FileChannel.open(dir.resolve("receipts.log"), READ, WRITE, LinkOption.NOFOLLOW_LINKS); }
            catch (IOException e) { payload.close(); throw e; }
            try { replay(); }
            catch (IOException | RuntimeException e) {
                try { failure(State.RECOVERY_REQUIRED, UNKNOWN_COMMIT); }
                catch (IOException persist) { e.addSuppressed(persist); log.close(); payload.close(); throw new IOException("Cannot freeze corrupt storage", e); }
                frozen = true;
            }
        }
        private void replay() throws IOException {
            long size = log.size(), complete = size / RECORD * RECORD;
            if (complete / RECORD > limits.maxChunks()) throw new IOException("Receipt count exceeds index");
            for (long pos = 0; pos < complete; pos += RECORD) {
                ByteBuffer b = ByteBuffer.allocate(RECORD); readFully(log, b, pos); byte[] bytes = b.array(); b.flip();
                CRC32C crc = new CRC32C(); crc.update(bytes, 0, 64);
                if (b.getInt() != 0x4c435052 || b.getInt() != 1 || (int) crc.getValue() != b.getInt(64)) throw new IOException("Corrupt receipt");
                long i = b.getLong(), offset = b.getLong(), length = b.getLong(); byte[] hash = new byte[32]; b.get(hash);
                Receipt r = new Receipt(transfer.request().transferId(), i, offset, length, new Bytes32(hash));
                validateRange(r);
                if (receipt(i) != null || payload.size() < offset + length) throw new IOException("Inconsistent receipt");
                insert(r);
            }
            if (size != complete) { log.truncate(complete); log.force(true); }
            if (saved.state() == State.COMPLETED && (saved.result().totalChunks() != count || saved.result().totalPlainBytes() != total
                    || payload.size() != total)) throw new IOException("Completed output length mismatch");
        }
        private void check() throws IOException { if (ended) throw new IOException("Session is closed"); safe(dir, true); }
        private void writable() throws IOException {
            check();
            if (frozen || !active(saved.state()) || saved.state() == State.VERIFYING) throw new TransferException(STATE_CONFLICT);
        }
        private Receipt receipt(long i) {
            if (i < 0 || i >= limits.maxChunks()) return null;
            int p = (int) i * SLOT; long length = index.getLong(p);
            if (length == 0) return null;
            byte[] hash = new byte[32]; index.get(p + 16, hash);
            return new Receipt(transfer.request().transferId(), i, index.getLong(p + 8), length, new Bytes32(hash));
        }
        private int position(long offset) {
            int lo = 0, hi = count;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (index.getLong(ordered[mid] * SLOT + 8) < offset) lo = mid + 1; else hi = mid; }
            return lo;
        }
        private void validateRange(Receipt r) throws IOException {
            if (r.chunkIndex() >= limits.maxChunks() || r.plainLength() > limits.maxPlainBytes()
                    || r.offset() > limits.maxTransferBytes() - r.plainLength()) throw new TransferException(LIMIT_EXCEEDED);
            int p = position(r.offset());
            if (p > 0) { Receipt before = receipt(ordered[p - 1]); if (before.offset() + before.plainLength() > r.offset()) throw new TransferException(CHUNK_CONFLICT); }
            if (p < count && receipt(ordered[p]).offset() < r.offset() + r.plainLength()) throw new TransferException(CHUNK_CONFLICT);
        }
        private void insert(Receipt r) {
            int p = position(r.offset()), i = (int) r.chunkIndex();
            System.arraycopy(ordered, p, ordered, p + 1, count - p); ordered[p] = i;
            index.putLong(i * SLOT, r.plainLength()).putLong(i * SLOT + 8, r.offset()); index.put(i * SLOT + 16, r.payloadHash().bytes());
            count++; total = Math.addExact(total, r.plainLength());
        }
        @Override public synchronized SessionState state() throws IOException {
            check(); return new SessionState(transfer, saved.state(), Math.addExact(saved.revision(), count), count, total,
                    saved.finish(), saved.result(), saved.error(), saved.cancel());
        }
        private void save(State state, FinishManifest finish, VerifiedResult result, ErrorCode error, CancelCommand cancel) throws IOException {
            var next = new SessionState(transfer, state, Math.addExact(saved.revision(), 1), count, total, finish, result, error, cancel);
            try { atomic(dir.resolve("state.cbor"), StorageCodec.state(next)); saved = next; }
            catch (IOException e) { frozen = true; throw e; }
        }
        private void failure(State state, ErrorCode code) throws IOException { save(state, saved.finish(), null, code, null); }
        @Override public synchronized void recordTransferring() throws IOException {
            writable(); if (saved.state() == State.OPEN) save(State.TRANSFERRING, null, null, null, null);
        }
        private void writeAll(FileChannel channel, ByteBuffer bytes, long offset) throws IOException {
            while (bytes.hasRemaining()) { int n = io.write(channel, bytes, offset); if (n <= 0) throw new IOException("No storage progress"); offset += n; }
        }
        @Override public synchronized Receipt commit(Chunk chunk) throws IOException {
            check();
            Receipt r = new Receipt(transfer.request().transferId(), chunk.index(), chunk.offset(), chunk.plainLength(), chunk.payloadHash());
            Receipt old = receipt(chunk.index());
            if (old != null && old.equals(r) && (active(saved.state()) || saved.state() == State.COMPLETED) && !frozen) return old;
            if (old != null && saved.state() == State.COMPLETED) throw new TransferException(CHUNK_CONFLICT);
            writable();
            if (old != null) throw new TransferException(CHUNK_CONFLICT);
            validateRange(r);
            // The Sink also checks the descriptor against bytes before any side effect.
            var digest = sha256(); digest.update(chunk.bytes());
            if (!new Bytes32(digest.digest()).equals(r.payloadHash())) throw new TransferException(INTEGRITY_MISMATCH);
            recordTransferring();
            try {
                writeAll(payload, chunk.bytes(), chunk.offset()); io.after(Boundary.PAYLOAD_WRITTEN);
                payload.force(true); io.after(Boundary.PAYLOAD_FORCED);
                ByteBuffer b = ByteBuffer.allocate(RECORD).putInt(0x4c435052).putInt(1).putLong(r.chunkIndex()).putLong(r.offset()).putLong(r.plainLength()).put(r.payloadHash().bytes());
                CRC32C crc = new CRC32C(); crc.update(b.array(), 0, 64); b.putInt((int) crc.getValue()).flip();
                writeAll(log, b, log.size()); io.after(Boundary.RECEIPT_WRITTEN);
                log.force(true); io.after(Boundary.RECEIPT_FORCED);
                insert(r); io.after(Boundary.INDEX_PUBLISHED); return r;
            } catch (IOException e) {
                frozen = true;
                try { failure(State.RECOVERY_REQUIRED, UNKNOWN_COMMIT); } catch (IOException persist) { e.addSuppressed(persist); }
                throw new TransferException(UNKNOWN_COMMIT);
            }
        }
        @Override public synchronized ReceiptPage receipts(long from, int limit) throws IOException {
            check(); if (from < 0 || from > limits.maxChunks() || limit < 1 || limit > 256) throw new TransferException(INVALID_MESSAGE);
            long end = Math.min(limits.maxChunks(), from + limit); List<Receipt> entries = new ArrayList<>();
            for (long i = from; i < end; i++) { Receipt r = receipt(i); if (r != null) entries.add(r); }
            return new ReceiptPage(transfer.request().transferId(), from, limit, entries, end < limits.maxChunks() ? end : null, state().revision());
        }
        @Override public synchronized int readPersisted(long offset, ByteBuffer dst) throws IOException {
            check(); if (offset < 0) throw new IllegalArgumentException("Negative offset"); return payload.read(dst, offset);
        }
        @Override public synchronized void recordVerifying(FinishManifest f) throws IOException {
            check();
            if (saved.cancel() != null && saved.cancel().commandId().equals(f.commandId())) throw new TransferException(COMMAND_CONFLICT);
            if (saved.finish() != null) { if (!saved.finish().equals(f)) throw new TransferException(COMMAND_CONFLICT); if (saved.state() == State.VERIFYING || saved.state() == State.COMPLETED) return; }
            writable();
            if (!f.transferId().equals(transfer.request().transferId()) || !f.bindingHash().equals(transfer.response().bindingHash())) throw new TransferException(INVALID_MESSAGE);
            if (f.totalChunks() != count || f.totalPlainBytes() != total) throw new TransferException(MISSING_CHUNKS);
            long offset = 0;
            for (int i = 0; i < count; i++) { Receipt r = receipt(i); if (r == null || r.offset() != offset) throw new TransferException(MISSING_CHUNKS); offset += r.plainLength(); }
            save(State.VERIFYING, f, null, null, null);
        }
        @Override public synchronized void recordCompleted(VerifiedResult result) throws IOException {
            check();
            if (saved.state() == State.COMPLETED && saved.result().equals(result)) return;
            if (frozen || saved.state() != State.VERIFYING) throw new TransferException(STATE_CONFLICT);
            FinishManifest f = saved.finish();
            if (!result.handleId().equals(transfer.response().accepted().handleId()) || result.totalChunks() != f.totalChunks()
                    || result.totalPlainBytes() != f.totalPlainBytes() || !result.root().equals(f.root())
                    || !result.manifestHash().equals(MetadataCodec.manifestHash(f)) || payload.size() != f.totalPlainBytes()) throw new TransferException(INTEGRITY_MISMATCH);
            save(State.COMPLETED, f, result, null, null);
        }
        @Override public synchronized void recordFailure(FailureKind kind, ErrorCode code) throws IOException {
            check(); if (!active(saved.state()) && saved.state() != State.RECOVERY_REQUIRED) throw new TransferException(STATE_CONFLICT);
            failure(State.valueOf(kind.name()), code);
        }
        @Override public synchronized SessionState cancel(CancelCommand command) throws IOException {
            check();
            if (!command.transferId().equals(transfer.request().transferId()) || !command.bindingHash().equals(transfer.response().bindingHash())) throw new TransferException(INVALID_MESSAGE);
            if (saved.cancel() != null) { if (!saved.cancel().equals(command)) throw new TransferException(COMMAND_CONFLICT); return state(); }
            if (saved.finish() != null && saved.finish().commandId().equals(command.commandId())) throw new TransferException(COMMAND_CONFLICT);
            writable(); save(State.CANCELLED, null, null, null, command); return state();
        }
        @Override public void close() throws IOException {
            synchronized (FileSink.this) { synchronized (this) {
                if (ended) return; ended = true;
                try { log.close(); } finally { payload.close(); if (current == this) current = null; }
            } }
        }
    }
    private static java.security.MessageDigest sha256() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
