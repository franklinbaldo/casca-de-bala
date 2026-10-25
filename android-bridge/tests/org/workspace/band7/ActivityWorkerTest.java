package org.workspace.band7;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

/** Host-only worker state-machine tests; no JNI or Android device required. */
public class ActivityWorkerTest {
    static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    static class Fake implements ActivityWorker.Backend {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger closes = new AtomicInteger();
        CountDownLatch entered, release;
        byte[] lastPayload;
        public long ingest(long first, Integer offset, byte[] data) throws Exception {
            calls.add("ingest:" + first);
            lastPayload = data.clone();
            if (entered != null) { entered.countDown(); release.await(); }
            return data.length / 8;
        }
        public ActivityWorker.Snapshot coverage() {
            calls.add("coverage");
            return new ActivityWorker.Snapshot(true, 42, 3, 0);
        }
        public void close() { calls.add("close"); closes.incrementAndGet(); }
    }
    public static void main(String[] args) throws Exception {
        Fake fake = new Fake();
        ActivityWorker worker = new ActivityWorker(() -> fake);
        worker.ready().get(2, TimeUnit.SECONDS);
        byte[] bytes = new byte[8];
        CompletableFuture<Long> first = worker.ingest(120, null, bytes);
        Arrays.fill(bytes, (byte) 7);
        check(first.get(2, TimeUnit.SECONDS) == 1, "ingest");
        check(fake.lastPayload[0] == 0, "payload ownership copied on admission");
        check(worker.coverage().get(2, TimeUnit.SECONDS).cursor == 42, "coverage");
        check(!first.cancel(true), "cancellation must not hide commit");
        CompletableFuture<Void> close = worker.closeAsync();
        check(close == worker.closeAsync(), "close idempotent");
        close.get(2, TimeUnit.SECONDS);
        check(fake.closes.get() == 1, "closed once");
        check(fake.calls.equals(Arrays.asList("ingest:120", "coverage", "close")), "serial order");
        try { worker.ingest(130, null, new byte[8]).get(2, TimeUnit.SECONDS); throw new AssertionError("accepted after close"); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException, "closed error"); }

        Fake slow = new Fake();
        slow.entered = new CountDownLatch(1); slow.release = new CountDownLatch(1);
        ActivityWorker limited = new ActivityWorker(() -> slow);
        limited.ready().get(2, TimeUnit.SECONDS);
        CompletableFuture<Long> active = limited.ingest(10, null, new byte[8]);
        check(slow.entered.await(2, TimeUnit.SECONDS), "worker active");
        CompletableFuture<ActivityWorker.Snapshot> a = limited.coverage();
        CompletableFuture<ActivityWorker.Snapshot> b = limited.coverage();
        try { limited.coverage().get(2, TimeUnit.SECONDS); throw new AssertionError("queue overflow"); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof RejectedExecutionException, "backpressure"); }
        CompletableFuture<Void> shutdown = limited.closeAsync();
        slow.release.countDown();
        check(active.get(2, TimeUnit.SECONDS) == 1, "running task completed");
        a.get(2, TimeUnit.SECONDS); b.get(2, TimeUnit.SECONDS);
        shutdown.get(2, TimeUnit.SECONDS);
        check(slow.calls.equals(Arrays.asList("ingest:10", "coverage", "coverage", "close")), "queued tasks not dropped");

        CountDownLatch start = new CountDownLatch(1);
        ActivityWorker broken = new ActivityWorker(() -> { start.await(); throw new IllegalStateException("synthetic open failure"); });
        CompletableFuture<Long> pending = broken.ingest(20, null, new byte[8]);
        start.countDown();
        try { broken.ready().get(2, TimeUnit.SECONDS); throw new AssertionError("opened"); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException, "open failure"); }
        try { pending.get(2, TimeUnit.SECONDS); throw new AssertionError("accepted despite failed open"); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException, "propagate open failure"); }
        // Await terminal completion without calling closeAsync (which itself
        // closes admission and would mask the failed-open lifecycle regression).
        java.lang.reflect.Field terminalField=ActivityWorker.class.getDeclaredField("closed");
        terminalField.setAccessible(true);
        try { ((CompletableFuture<?>)terminalField.get(broken)).get(2,TimeUnit.SECONDS); throw new AssertionError("failure lost"); }
        catch(ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException,"terminal failure"); }
        try { broken.coverage().get(2, TimeUnit.SECONDS); throw new AssertionError("accepted after worker termination"); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException, "terminal admission closed"); }
        try { broken.closeAsync().get(2, TimeUnit.SECONDS); throw new AssertionError("closed successfully despite failure"); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof IllegalStateException, "close failure"); }
        System.out.println("PASS: serial, bounded, close, backpressure, open failure");
    }
}
