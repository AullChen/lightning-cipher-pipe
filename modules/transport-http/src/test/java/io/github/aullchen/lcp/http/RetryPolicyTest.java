package io.github.aullchen.lcp.http;

import io.github.aullchen.lcp.api.TransferException;
import io.github.aullchen.lcp.api.TransferStorage.ErrorCode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    static class Time extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }
    @Test void threeBackoffsThenNoFifthSend() throws Exception {
        var time = new Time(); var delays = new ArrayList<Long>();
        var policy = new RetryPolicy(time,() -> 0.5,ms -> { delays.add(ms); time.now = time.now.plusMillis(ms); });
        var expiry = time.now.plusSeconds(60);
        for (int sends=1;sends<4;sends++) policy.pause(sends,expiry,0);
        assertEquals(List.of(500L,1000L,2000L),delays);
        assertEquals(ErrorCode.UNKNOWN_COMMIT,assertThrows(TransferException.class,() -> policy.pause(4,expiry,0)).code());
        assertEquals(3,delays.size());
    }
    @Test void jitterRetryAfterAndTtlAreClamped() throws Exception {
        var time = new Time(); var delays = new ArrayList<Long>();
        var policy = new RetryPolicy(time,() -> 0,ms -> { delays.add(ms); time.now = time.now.plusMillis(ms); });
        policy.pause(1,time.now.plusSeconds(60),0);
        policy.pause(1,time.now.plusSeconds(60),999999);
        assertEquals(ErrorCode.EXPIRED,assertThrows(TransferException.class,() -> policy.pause(1,time.now.plusMillis(10),1000)).code());
        assertEquals(List.of(250L,10000L,10L),delays);
        assertEquals(ErrorCode.EXPIRED,assertThrows(TransferException.class,() -> policy.pause(1,time.now,0)).code());
        assertEquals(3,delays.size());
    }
}
