package io.github.aullchen.lcp.core.stream;

import io.github.aullchen.lcp.api.Metadata.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompressionPlanTest {
    private static final long BUDGET = 64L*1024*1024;
    @ParameterizedTest @CsvSource({
        "262144,4,262144,3", "524288,2,524288,2", "262144,1,262144,1", "524288,4,524288,3"
    })
    void feedbackNarrowsRangesAndClampsInitial(long plain, long slots, long chunk, long window) {
        var policy=new Policy(1,1,262144,524288,524288,1,4,3,1,5,3);
        var limits=new Limits(600000,plain,128,16777216,slots);
        var accepted=CompressionPlan.negotiate(policy,limits,ChunkCompression.NONE,BUDGET);
        assertEquals(new Policy(1,1,262144,chunk,chunk,1,slots,window,1,1,1),accepted);
    }
    @ParameterizedTest @CsvSource({
        "1,262143,4,600000,67108864", "1,524288,1,600000,67108864",
        "0,262144,4,600000,67108864", "0,524288,1,600000,67108864",
        "1,524288,4,524288,67108864", "1,524288,4,600000,1"
    })
    void rejectsEmptyIntersectionUnsatisfiedFixedFrameAndBudget(int mode,long plain,long slots,long frame,long budget) {
        var policy=new Policy(mode,1,mode==0?524288:262144,524288,524288,2,mode==0?2:4,2,1,1,1);
        var limits=new Limits(frame,plain,128,16777216,slots);
        assertThrows(IllegalArgumentException.class,() -> CompressionPlan.negotiate(policy,limits,ChunkCompression.NONE,budget));
    }
    @Test void satisfiableFixedPolicyIsUnchanged() {
        var policy=new Policy(0,1,262144,262144,262144,2,2,2,1,1,1);
        assertEquals(policy,CompressionPlan.negotiate(policy,new Limits(600000,524288,128,16777216,4),ChunkCompression.NONE,BUDGET));
    }
}
