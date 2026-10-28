package org.workspace.casca;

import static org.junit.Assert.*;
import org.junit.Test;

public class CascaTermuxJobBookTest {
    @Test public void clientsAreIsolatedAndResultCanOnlyArriveOnce() {
        CascaTermuxJobBook<String> book = new CascaTermuxJobBook<>();
        assertEquals(CascaTermuxJobBook.Admission.ACCEPTED, book.reserve("id", "alice", 1));
        assertEquals(CascaTermuxJobBook.Status.MISSING, book.lookup("id", "bob", 2).status);
        assertEquals(CascaTermuxJobBook.Status.RUNNING, book.lookup("id", "alice", 2).status);
        assertTrue(book.finish("id", "ok", 3));
        assertFalse(book.finish("id", "overwritten", 4));
        assertEquals("ok", book.lookup("id", "alice", 4).value);
    }

    @Test public void fourInFlightJobsAndRollback() {
        CascaTermuxJobBook<String> book = new CascaTermuxJobBook<>();
        for (int i = 0; i < 4; i++)
            assertEquals(CascaTermuxJobBook.Admission.ACCEPTED, book.reserve("i" + i, "client", 0));
        assertEquals(CascaTermuxJobBook.Admission.IN_FLIGHT_LIMIT, book.reserve("no", "client", 0));
        book.cancel("i0");
        assertEquals(CascaTermuxJobBook.Admission.ACCEPTED, book.reserve("yes", "client", 0));
    }

    @Test public void evictsOldestCompletedResultWhenFull() {
        CascaTermuxJobBook<String> book = new CascaTermuxJobBook<>();
        for (int i = 0; i < 32; i++) {
            assertEquals(CascaTermuxJobBook.Admission.ACCEPTED, book.reserve("i" + i, "client", 0));
            assertTrue(book.finish("i" + i, "ok", 0));
        }
        assertEquals(CascaTermuxJobBook.Admission.ACCEPTED, book.reserve("new", "client", 0));
        assertEquals(CascaTermuxJobBook.Status.MISSING, book.lookup("i0", "client", 0).status);
        assertEquals(CascaTermuxJobBook.Status.FINISHED, book.lookup("i31", "client", 0).status);
    }

    @Test public void expiresResultsAndRejectsLateCallbacks() {
        CascaTermuxJobBook<String> book = new CascaTermuxJobBook<>();
        assertEquals(CascaTermuxJobBook.Admission.ACCEPTED, book.reserve("old", "client", 0));
        assertEquals(CascaTermuxJobBook.Status.RUNNING,
                book.lookup("old", "client", CascaTermuxJobBook.TTL_MS - 1).status);
        assertFalse(book.finish("old", "late", CascaTermuxJobBook.TTL_MS));
        assertEquals(CascaTermuxJobBook.Status.MISSING,
                book.lookup("old", "client", CascaTermuxJobBook.TTL_MS).status);
    }
}
