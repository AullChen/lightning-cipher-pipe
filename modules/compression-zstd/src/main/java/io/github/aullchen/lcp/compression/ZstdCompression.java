package io.github.aullchen.lcp.compression;

import com.github.luben.zstd.*;
import io.github.aullchen.lcp.core.stream.ChunkCompression;
import io.github.aullchen.lcp.api.TransferException;
import io.github.aullchen.lcp.api.TransferStorage.ErrorCode;
import java.io.IOException;
import java.time.Duration;

/** One standard ZSTD frame, no dictionary, at most 8 MiB output/window and one native worker. */
public final class ZstdCompression implements ChunkCompression {
    private static final int MAX = 8 * 1024 * 1024;
    private final long timeoutNanos;
    public ZstdCompression() { this(Duration.ofSeconds(10)); }
    public ZstdCompression(Duration timeout) {
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("Invalid codec deadline");
    }
    public int code() { return 1; }
    public long compressBound(long size) {
        if (size < 0 || size > MAX) throw new IllegalArgumentException("Invalid chunk size");
        return Zstd.compressBound(size);
    }
    // Includes bounded hash/chain tables, window and context scratch. No native worker pool.
    public long workspaceBytes() { return 32L * 1024 * 1024; }
    private void deadline(long start) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - start >= timeoutNanos)
            throw new IOException("Codec deadline or interruption");
    }
    public byte[] compress(byte[] plain, int level) throws IOException {
        if (plain.length == 0 || plain.length > MAX || level < 1 || level > 5) throw new TransferException(ErrorCode.LIMIT_EXCEEDED);
        long start = System.nanoTime(); deadline(start);
        try (var ctx = new ZstdCompressCtx()) {
            ctx.setLevel(level).setWindowLog(23).setHashLog(20).setChainLog(20).setWorkers(0).setContentSize(true);
            byte[] result = ctx.compress(plain); deadline(start); return result;
        } catch (ZstdException e) { throw new TransferException(ErrorCode.INVALID_MESSAGE); }
    }
    public byte[] decompress(byte[] compressed, int plainBytes) throws IOException {
        if (plainBytes <= 0 || plainBytes > MAX || compressed.length > 16 * 1024 * 1024) throw new TransferException(ErrorCode.LIMIT_EXCEEDED);
        long start = System.nanoTime(); deadline(start);
        try {
            // Inspect only standard frame header fields; block parsing remains in libzstd.
            if (compressed.length < 6 || compressed[0] != 0x28 || (compressed[1] & 255) != 0xb5
                    || compressed[2] != 0x2f || (compressed[3] & 255) != 0xfd) throw new TransferException(ErrorCode.INVALID_MESSAGE);
            int descriptor = compressed[4] & 255;
            if ((descriptor & 8) != 0 || Zstd.getDictIdFromFrame(compressed) != 0) throw new TransferException(ErrorCode.INVALID_MESSAGE);
            long contentSize = Zstd.getFrameContentSize(compressed);
            // Unknown content size is legal: the authenticated AAD still bounds output.
            if (contentSize >= 0 && contentSize != plainBytes) throw new TransferException(ErrorCode.INVALID_MESSAGE);
            if ((descriptor & 32) == 0) {
                int wd = compressed[5] & 255;
                long base = 1L << (10 + (wd >>> 3));
                if (base + (base >>> 3) * (wd & 7) > MAX) throw new TransferException(ErrorCode.LIMIT_EXCEEDED);
            } else if (contentSize < 0 || contentSize > MAX) throw new TransferException(ErrorCode.LIMIT_EXCEEDED);
            if (Zstd.findFrameCompressedSize(compressed) != compressed.length) throw new TransferException(ErrorCode.INVALID_MESSAGE);
            byte[] output = new byte[plainBytes];
            try (var ctx = new ZstdDecompressCtx()) {
                if (ctx.decompressByteArray(output, 0, output.length, compressed, 0, compressed.length) != plainBytes)
                    throw new TransferException(ErrorCode.INVALID_MESSAGE);
            }
            deadline(start); return output;
        } catch (ZstdException e) { throw new TransferException(ErrorCode.INVALID_MESSAGE); }
    }
}
