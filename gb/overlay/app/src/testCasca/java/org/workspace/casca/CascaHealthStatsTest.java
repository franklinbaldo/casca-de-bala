package org.workspace.casca;

import static org.junit.Assert.*;
import org.junit.Test;

public class CascaHealthStatsTest {
    @Test public void emptyInputDoesNotInventAnAggregate() {
        assertNull(new CascaHealthStats().snapshot());
    }

    @Test public void minMaxRoundedAverageAndLatestAreDistinct() {
        CascaHealthStats stats=new CascaHealthStats();
        stats.add(72,200);stats.add(85,100);stats.add(80,300);
        CascaHealthStats.Snapshot s=stats.snapshot();
        assertNotNull(s);
        assertEquals(72,s.min);assertEquals(85,s.max);assertEquals(3,s.count);
        assertEquals(79,s.average);assertEquals(80,s.latest);assertEquals(300L,s.latestAt);
    }

    @Test public void equalTimestampKeepsLastEncountered() {
        CascaHealthStats stats=new CascaHealthStats();
        stats.add(9,42);stats.add(10,42);
        assertEquals(10,stats.snapshot().latest);
    }
}
