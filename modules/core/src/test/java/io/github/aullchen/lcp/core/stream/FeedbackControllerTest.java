package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.Metadata.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackControllerTest {
    private static Policy policy() { return new Policy(1,1,262144,8388608,1048576,1,16,4,1,5,3); }
    private static TransferMetrics.Round round(long bytes, long p95, double queue, double compression,
                                               double full, boolean eof, boolean blocked, long retries, long busy, long failures) {
        return new TransferMetrics.Round(2_000_000_000L,bytes,16,p95,queue,compression,.8,full,eof,blocked,retries,busy,failures);
    }
    private static TransferMetrics.Round normal(long bytes) { return round(bytes,100,.05,.4,.9,false,false,0,0,0); }
    @ParameterizedTest @CsvSource({
        // queue, compression, full, eof, blocked, retry, busy, expected window, level, chunk
        "0.26,0.4,0.9,false,false,0,0,2,3,1048576",
        "0.05,0.4,0.9,false,false,0,1,2,3,1048576",
        "0.05,0.4,0.8,false,false,0,0,5,3,1048576",
        "0.05,0.8,0.7,false,false,0,0,4,2,1048576",
        "0.05,0.5,0.9,false,true,0,0,4,4,1048576",
        "0.05,0.6,0.9,true,false,0,0,4,3,2097152",
        "0.05,0.6,0.7,false,false,1,0,4,3,524288",
        "0.25,0.6,0.7,false,false,0,0,4,3,1048576"
    }) void decisionsRespectPriority(double queue,double compression,double full,boolean eof,boolean blocked,long retry,long busy,
                                      int window,int level,int chunk) {
        var controller=new FeedbackController(policy());
        var actual=controller.observe(round(1000,100,queue,compression,full,eof,blocked,retry,busy,0));
        assertEquals(new FeedbackController.Parameters(chunk,window,level),actual);
    }
    @ParameterizedTest @CsvSource({"1050,120,0,true", "1049,100,0,false", "1100,121,0,false", "1100,100,1,false"})
    void evaluateTwoRoundsThenCoolDown(long bytes,long p95,long failures,boolean accepted) {
        var controller=new FeedbackController(policy()); controller.observe(normal(1000));
        assertEquals(5,controller.parameters().window()); assertTrue(controller.trialActive());
        controller.observe(round(bytes,p95,.05,.4,.9,false,false,0,0,failures));
        assertTrue(controller.trialActive());
        controller.observe(round(bytes,p95,.05,.4,.9,false,false,0,0,0));
        assertFalse(controller.trialActive()); assertEquals(accepted?5:4,controller.parameters().window());
        assertEquals(2,controller.cooldownRounds());
        controller.observe(normal(1000)); controller.observe(normal(1000));
        assertFalse(controller.trialActive()); assertEquals(0,controller.cooldownRounds());
        controller.observe(normal(1000));
        assertEquals(4,controller.parameters().zstdLevel()); // rotation proceeds to compression
    }
    @Test void trialsRotateThroughAllThreeDimensions() {
        var c=new FeedbackController(policy());
        c.observe(normal(1000));
        c.observe(normal(1100)); c.observe(normal(1100));
        c.observe(normal(1100)); c.observe(normal(1100));
        c.observe(normal(1100)); assertEquals(4,c.parameters().zstdLevel());
        c.observe(normal(1200)); c.observe(normal(1200));
        c.observe(normal(1200)); c.observe(normal(1200));
        c.observe(normal(1200));
        assertEquals(new FeedbackController.Parameters(2097152,5,4),c.parameters());
    }
    @Test void combinedGoodputUsesCombinedTimeNotAverageOfRates() {
        var c=new FeedbackController(policy()); c.observe(normal(1000));
        c.observe(normal(2000));
        c.observe(new TransferMetrics.Round(8_000_000_000L,2000,16,100L,.05,.4,.8,.9,false,false,0,0,0));
        assertEquals(4,c.parameters().window()); // 400 B/s versus baseline 500 B/s
    }
    @Test void pressureFirstRevertsTrialThenHalvesAndNeverRestoresOldWindow() {
        var c=new FeedbackController(policy()); c.observe(normal(1000)); c.pressure();
        assertEquals(2,c.parameters().window()); assertFalse(c.trialActive());
        c.observe(normal(1000)); c.observe(normal(1000)); c.observe(normal(1000));
        assertEquals(2,c.parameters().window()); assertEquals(4,c.parameters().zstdLevel());
        c.pressure(); assertEquals(1,c.parameters().window()); assertEquals(3,c.parameters().zstdLevel());
        c.pressure(); assertEquals(1,c.parameters().window());
    }
    @Test void invalidOrShortRoundsHoldParametersAndTrialState() {
        var c=new FeedbackController(policy()); var initial=c.parameters();
        assertEquals(initial,c.observe(null));
        assertEquals(initial,c.observe(new TransferMetrics.Round(1,1000,16,100L,.05,.4,.8,.9,false,false,0,0,0)));
        assertEquals(initial,c.observe(new TransferMetrics.Round(2_000_000_000L,1000,15,100L,.05,.4,.8,.9,false,false,0,0,0)));
        assertEquals(initial,c.observe(new TransferMetrics.Round(2_000_000_000L,1000,16,null,null,.4,null,.9,false,false,0,0,0)));
        assertEquals(initial,c.observe(round(1000,100,Double.NaN,.4,.9,false,false,0,0,0)));
        c.observe(normal(1000)); c.observe(null); assertTrue(c.trialActive());
    }
    @Test void fixedNeverDecidesAndCollapsedBoundsNeverStartTrial() {
        for (int mode : new int[]{0,1}) {
            var c=new FeedbackController(new Policy(mode,1,262144,262144,262144,1,1,1,1,1,1));
            var initial=c.parameters();
            for (int i=0;i<10;i++) { c.observe(normal(1000)); c.pressure(); }
            assertEquals(initial,c.parameters()); assertFalse(c.trialActive());
        }
    }
    @Test void boundsClampChunkTrialAndWindowRecoversAfterPressure() {
        var c=new FeedbackController(new Policy(1,1,262144,1000000,600000,1,1,1,1,1,1));
        assertEquals(1000000,c.observe(normal(1000)).chunkBytes());
        var recovery=new FeedbackController(policy()); recovery.pressure();
        recovery.observe(normal(1000)); recovery.observe(normal(1000));
        assertEquals(3,recovery.observe(normal(1000)).window());
    }
}
