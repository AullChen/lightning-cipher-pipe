package io.github.aullchen.lcp.compression;

import com.github.luben.zstd.*;
import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.core.stream.*;
import io.github.aullchen.lcp.core.protocol.MetadataCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZstdCompressionTest {
    final ZstdCompression codec = new ZstdCompression();
    @ParameterizedTest @ValueSource(ints = {1,2,3,4,5})
    void independentFramesRoundTripAtEveryLevel(int level) throws Exception {
        for (boolean random : new boolean[]{false,true}) {
            byte[] input = new byte[262144]; if (random) new Random(42).nextBytes(input);
            byte[] encoded = codec.compress(input, level);
            assertTrue(encoded.length <= codec.compressBound(input.length));
            if (!random) assertTrue(input.length / encoded.length > 50);
            assertArrayEquals(input, codec.decompress(encoded, input.length));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"tail", "concatenated", "truncated", "wrong-size", "large-output", "large-window", "skippable", "reserved"})
    void malformedOrUnboundedFramesAreRejected(String damage) throws Exception {
        byte[] input = new byte[262144]; byte[] valid = codec.compress(input, 3); byte[] damaged = valid.clone();
        int size = input.length;
        switch (damage) {
            case "tail" -> damaged = Arrays.copyOf(valid, valid.length + 1);
            case "concatenated" -> { damaged = Arrays.copyOf(valid, valid.length * 2); System.arraycopy(valid, 0, damaged, valid.length, valid.length); }
            case "truncated" -> damaged = Arrays.copyOf(valid, valid.length - 1);
            case "wrong-size" -> size--;
            case "large-output" -> size = 8 * 1024 * 1024 + 1;
            case "large-window" -> { damaged = new byte[]{0x28,(byte)0xb5,0x2f,(byte)0xfd,0,(byte)0x78,1,0,0}; }
            case "skippable" -> damaged = new byte[]{0x50,0x2a,0x4d,0x18,0,0,0,0};
            case "reserved" -> damaged[4] |= 8;
        }
        byte[] frame = damaged; int plainSize = size;
        assertThrows(TransferException.class, () -> codec.decompress(frame, plainSize));
    }
    @Test void unknownContentSizeStillUsesAuthenticatedOutputBound() throws Exception {
        byte[] input = new byte[262144]; new Random(1).nextBytes(input);
        try (var ctx = new ZstdCompressCtx()) {
            byte[] frame = ctx.setContentSize(false).compress(input);
            assertArrayEquals(input, codec.decompress(frame, input.length));
            assertThrows(TransferException.class, () -> codec.decompress(frame, input.length - 1));
        }
    }
    @Test void preparedChunkKeepsOneImmutableCompressionAcrossAttempts() throws Exception {
        byte[] plain = new byte[100]; Arrays.fill(plain, (byte)7);
        var prepared = new PreparedChunk(new Chunk(0, 0, 100, MetadataCodec.hash(plain), ByteBuffer.wrap(plain)), codec, 3);
        byte[] first = prepared.compressedBytes(); prepared.compressedBytes()[0] ^= 1; plain[0] = 1;
        assertArrayEquals(first, prepared.compressedBytes());
        assertEquals(100, prepared.receipt(UUID.randomUUID()).plainLength());
    }
    @Test void interruptionIsObservedWithoutPretendingToInterruptNativeCode() throws Exception {
        byte[] frame = codec.compress(new byte[8 * 1024 * 1024], 5);
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.IOException.class, () -> codec.decompress(frame, 8 * 1024 * 1024)); }
        finally { Thread.interrupted(); }
        assertEquals(8 * 1024 * 1024, codec.decompress(frame, 8 * 1024 * 1024).length);
        var immediate = new ZstdCompression(Duration.ofNanos(1));
        assertThrows(java.io.IOException.class, () -> immediate.decompress(frame, 8 * 1024 * 1024));
    }
    @Test void negotiationRejectsWorstCaseFrameOrInsufficientBudget() {
        var p = new Policy(0,1,262144,262144,262144,1,1,1,3,3,3);
        long frame = codec.compressBound(262144) + 4160;
        var limits = new Limits(frame,262144,10,2621440,1);
        long peak = CompressionPlan.peak(limits, codec);
        assertDoesNotThrow(() -> CompressionPlan.validate(p, limits, codec, peak));
        assertThrows(IllegalArgumentException.class, () -> CompressionPlan.validate(p, limits, codec, peak - 1));
        assertThrows(IllegalArgumentException.class, () -> CompressionPlan.validate(p, new Limits(frame-1,262144,10,2621440,1), codec, peak));
    }
}
