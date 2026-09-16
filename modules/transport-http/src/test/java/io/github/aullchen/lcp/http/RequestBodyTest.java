package io.github.aullchen.lcp.http;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestBodyTest {
    @Test void retiringRequestCannotKeepReadingReleasedFrame() throws Exception {
        byte[] frame = new byte[100000]; java.util.Arrays.fill(frame,(byte)7);
        var publisher = new RequestBody(frame);
        var subscription = new CompletableFuture<Flow.Subscription>();
        var first = new CompletableFuture<ByteBuffer>(); var ended = new CompletableFuture<Throwable>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { subscription.complete(s); }
            public void onNext(ByteBuffer bytes) { first.complete(bytes); }
            public void onError(Throwable failure) { ended.complete(failure); }
            public void onComplete() { ended.complete(null); }
        });
        subscription.get(2,TimeUnit.SECONDS).request(1);
        var emitted = first.get(2,TimeUnit.SECONDS);
        publisher.close(); java.util.Arrays.fill(frame,(byte)0);
        while (emitted.hasRemaining()) assertEquals((byte)7,emitted.get());
        subscription.get().request(Long.MAX_VALUE);
        assertNotNull(ended.get(2,TimeUnit.SECONDS));
        assertEquals(100000,publisher.contentLength());
    }
}
