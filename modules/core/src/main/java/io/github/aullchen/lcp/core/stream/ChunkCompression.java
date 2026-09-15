package io.github.aullchen.lcp.core.stream;

import java.io.IOException;

/** Independent chunks only. Callers reserve bounds before invoking a codec. */
public interface ChunkCompression {
    int code();
    long compressBound(long plainBytes);
    long workspaceBytes();
    byte[] compress(byte[] plain, int level) throws IOException;
    byte[] decompress(byte[] compressed, int plainBytes) throws IOException;

    ChunkCompression NONE = new ChunkCompression() {
        public int code() { return 0; }
        public long compressBound(long size) { return size; }
        public long workspaceBytes() { return 0; }
        public byte[] compress(byte[] plain, int level) { return plain; }
        public byte[] decompress(byte[] bytes, int size) throws IOException {
            if (bytes.length != size) throw new IOException("NONE length mismatch");
            return bytes;
        }
    };
}
