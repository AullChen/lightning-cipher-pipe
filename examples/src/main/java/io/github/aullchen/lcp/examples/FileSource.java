package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.TransferSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Caller must keep the input immutable during transfer; this is not a filesystem snapshot.
 * FileChannel.close interrupts an outstanding read through asynchronous close. */
public final class FileSource implements TransferSource {
    private final FileChannel channel;
    public FileSource(Path path) throws IOException { channel = FileChannel.open(path, StandardOpenOption.READ); }
    @Override public int read(ByteBuffer dst) throws IOException { return channel.read(dst); }
    @Override public void close() throws IOException { channel.close(); }
}
