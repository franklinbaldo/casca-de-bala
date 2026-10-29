package org.workspace.casca;

import static org.junit.Assert.*;
import org.junit.Test;

public class CascaJobDispatchTest {
    @Test public void blockingReadsAreOffTheMainLooper() {
        for (String kind : new String[] {"band.read", "band.capabilities",
                "gb.bluetooth", "gb.phone"})
            assertFalse(kind, CascaJobDispatch.mainThreadRequired(kind, "status"));
        assertFalse(CascaJobDispatch.mainThreadRequired("band.lists", "get"));
    }

    @Test public void GadgetbridgeWritesPreserveUiThreadAffinity() {
        for (String kind : new String[] {"band.devices", "gb.devices", "gb.discover",
                "gb.inspect", "band.notify", "band.find", "band.sync",
                "band.settings", "gb.invoke", "unknown.future"})
            assertTrue(kind, CascaJobDispatch.mainThreadRequired(kind, "set"));
        assertTrue(CascaJobDispatch.mainThreadRequired("band.lists", "set"));
    }
}
