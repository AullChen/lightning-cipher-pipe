package io.github.aullchen.lcp.examples;

import io.github.aullchen.lcp.api.*;
import io.github.aullchen.lcp.api.Metadata.*;
import io.github.aullchen.lcp.api.TransferStorage.*;
import io.github.aullchen.lcp.core.protocol.*;
import io.github.aullchen.lcp.core.stream.TransferVerifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FileSinkCrashTest extends StorageTestSupport {
    @ParameterizedTest @ValueSource(ints={1,2})
    void recoveryCannotPublishReceiptsWhenDurabilityBarrierFails(int failingForce) throws Exception {
        var t = FileSinkTest.transfer(); crash(t,"RECEIPT_WRITTEN");
        try (var sink = new FileSink(root.resolve("output"),4096,new FileSink.Io() {
            int calls;
            @Override public void force(FileChannel channel) throws IOException {
                if (++calls == failingForce) throw new IOException("Injected recovery force failure");
                channel.force(true);
            }
        })) {
            var session = sink.recover(t.request().transferId());
            assertEquals(State.RECOVERY_REQUIRED,session.state().state());
            assertEquals(0,session.state().committedChunks());
            assertEquals(ErrorCode.UNKNOWN_COMMIT,assertThrows(TransferException.class,() -> session.receipts(0,2)).code());
            assertThrows(TransferException.class,() -> session.commit(FileSinkTest.chunk(1,2,(byte)3,(byte)4)));
        }
        try (var sink = new FileSink(root.resolve("output"),4096)) {
            var session = sink.recover(t.request().transferId());
            assertEquals(State.RECOVERY_REQUIRED,session.state().state());
            assertEquals(2,session.receipts(0,2).entries().size());
            assertThrows(TransferException.class,() -> session.commit(FileSinkTest.chunk(1,2,(byte)3,(byte)4)));
        }
    }
    @ParameterizedTest @ValueSource(strings={"PAYLOAD_WRITTEN","PAYLOAD_FORCED","RECEIPT_WRITTEN","RECEIPT_FORCED","INDEX_PUBLISHED","ACK_RETURNED"})
    void processExitPreservesAcknowledgedChunksAndAllowsSafeReplay(String boundary) throws Exception {
        var t = FileSinkTest.transfer(); crash(t,boundary);
        Path output = root.resolve("output");
        try (var sink = new FileSink(output,4096)) {
            var s = sink.recover(t.request().transferId());
            var receipts = s.receipts(0,2).entries();
            assertTrue(receipts.size() >= 1); assertEquals(FileSinkTest.chunk(0,0,(byte)1,(byte)2).payloadHash(),receipts.get(0).payloadHash());
            int expected = boundary.startsWith("PAYLOAD") ? 1 : 2; assertEquals(expected,receipts.size());
            long before = s.state().revision();
            Receipt r = s.commit(FileSinkTest.chunk(1,2,(byte)3,(byte)4));
            if (expected == 2) assertEquals(before,s.state().revision());
            assertEquals(r,s.commit(FileSinkTest.chunk(1,2,(byte)3,(byte)4)));
            assertEquals(136,Files.size(output.resolve(t.request().transferId()+"/receipts.log")));
            MerkleFrontier tree = new MerkleFrontier();
            tree.append(0,0,2,MetadataCodec.hash(new byte[]{1,2})); tree.append(1,2,2,MetadataCodec.hash(new byte[]{3,4}));
            var finish = new FinishManifest(t.request().transferId(),t.response().bindingHash(),UUID.randomUUID(),2,4,tree.root());
            TransferVerifier.finish(s,finish,Clock.systemUTC(),Duration.ofSeconds(5));
            assertEquals(State.COMPLETED,s.state().state());
            assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(output.resolve(t.request().transferId()+"/payload.bin")));
        }
        try (var sink = new FileSink(output,4096)) { assertEquals(State.COMPLETED,sink.recover(t.request().transferId()).state().state()); }
    }
    private void crash(AcceptedTransfer transfer,String boundary) throws Exception {
        Path open = root.resolve("transfer.cbor"); Files.write(open,StorageCodec.open(transfer));
        Path output = root.resolve("output");
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",binary).toString(),"-cp",System.getProperty("java.class.path"),
                CrashWriter.class.getName(),output.toAbsolutePath().toString(),open.toAbsolutePath().toString(),boundary)
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            assertTrue(child.waitFor(15,TimeUnit.SECONDS)); assertEquals(71,child.exitValue(),Files.readString(root.resolve("child.log")));
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10,TimeUnit.SECONDS); } }
    }
    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            AcceptedTransfer t = StorageCodec.open(Files.readAllBytes(Path.of(args[1])));
            boolean[] armed = {false};
            try (var sink = new FileSink(Path.of(args[0]),4096,new FileSink.Io() {
                @Override public void after(FileSink.Boundary stage) {
                    if (armed[0] && stage.name().equals(args[2])) Runtime.getRuntime().halt(71);
                }
            })) {
                var s = sink.open(t); s.commit(FileSinkTest.chunk(0,0,(byte)1,(byte)2));
                // This chunk's ACK has already returned before the second write starts.
                armed[0] = true; s.commit(FileSinkTest.chunk(1,2,(byte)3,(byte)4));
                if (args[2].equals("ACK_RETURNED")) Runtime.getRuntime().halt(71);
            }
            throw new AssertionError("Crash boundary not reached");
        }
    }
}
