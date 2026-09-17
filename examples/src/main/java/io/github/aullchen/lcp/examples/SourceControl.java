package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.core.protocol.SourceControlCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;

/** Process-exclusive, atomic source intent/result file. Never stores chunk material. */
public final class SourceControl implements AutoCloseable {
    private final Path path;
    private final FileChannel lockChannel;
    private final FileLock lock;
    public SourceControl(Path path) throws IOException {
        this.path=path.toAbsolutePath().normalize();
        Files.createDirectories(this.path.getParent()); safe(this.path.getParent());
        lockChannel=FileChannel.open(this.path.resolveSibling(this.path.getFileName()+".lock"),CREATE,WRITE,LinkOption.NOFOLLOW_LINKS);
        try {
            lock=lockChannel.tryLock(); if (lock==null) throw new IOException("Control record is locked");
        } catch (IOException | OverlappingFileLockException e) { lockChannel.close(); throw new IOException("Control record is locked",e); }
    }
    private static void safe(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("Untrusted control path");
    }
    public SourceControlCodec.Record read() throws IOException {
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return null;
        safe(path);
        try (var channel=FileChannel.open(path,READ,LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size()>65536) throw new IOException("Oversized control record");
            var bytes=ByteBuffer.allocate((int)channel.size());
            while (bytes.hasRemaining()) if (channel.read(bytes)<=0) throw new IOException("Truncated control record");
            try { return SourceControlCodec.decode(bytes.array()); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid control record",e); }
        }
    }
    public void save(SourceControlCodec.Record record) throws IOException {
        byte[] bytes=SourceControlCodec.encode(record); safe(path.getParent());
        if (Files.exists(path,LinkOption.NOFOLLOW_LINKS)) read();
        Path temporary=Files.createTempFile(path.getParent(),".lcp-control-",".tmp");
        try {
            try (var channel=FileChannel.open(temporary,WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) if (channel.write(buffer)<=0) throw new IOException("No control write progress");
                channel.force(true);
            }
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }
}
