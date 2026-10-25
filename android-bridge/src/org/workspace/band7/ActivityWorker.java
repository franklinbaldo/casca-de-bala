package org.workspace.band7;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/** One bounded Java worker for a blocking JNI Activity8 session.
 * No BLE or Android lifecycle is implemented here. Results can commit even if a
 * consumer stops waiting; do not interpret a disposed UI as native cancellation.
 */
public final class ActivityWorker {
    public static final int MAX_PENDING = 2;
    public static final class Snapshot {
        public final boolean hasCursor;
        public final long cursor, records, missing;
        public Snapshot(boolean hasCursor, long cursor, long records, long missing) {
            this.hasCursor = hasCursor;
            this.cursor = cursor;
            this.records = records;
            this.missing = missing;
        }
    }
    interface Backend {
        long ingest(long first, Integer offset, byte[] payload) throws Exception;
        Snapshot coverage() throws Exception;
        void close() throws Exception;
    }
    interface BackendFactory { Backend open() throws Exception; }
    interface Task { void perform(Backend session); void fail(Throwable cause); }
    interface Operation<T> { T run(Backend session) throws Exception; }

    /** Disallow misleading Future.cancel(): JNI commits cannot be rolled back this way. */
    static final class Result<T> extends CompletableFuture<T> {
        @Override public boolean cancel(boolean interrupt) { return false; }
    }
    private static final Task STOP = new Task() {
        public void perform(Backend ignored) { }
        public void fail(Throwable ignored) { }
    };
    private final Object gate = new Object();
    private final ArrayBlockingQueue<Task> work = new ArrayBlockingQueue<>(MAX_PENDING + 1);
    private final Result<Void> ready = new Result<>();
    private final Result<Void> closed = new Result<>();
    private int pending;
    private boolean closing;

    ActivityWorker(BackendFactory factory) {
        if (factory == null) throw new IllegalArgumentException("factory required");
        Thread thread = new Thread(() -> runLoop(factory), "band7-activity-worker");
        thread.setDaemon(true);
        thread.start();
    }

    /** The directory must be app-private and owned by a trusted Android host. */
    public static ActivityWorker start(File privateDirectory, String deviceRef, String firmware,
                                       boolean synthetic, long start, long end) {
        if (privateDirectory == null) throw new IllegalArgumentException("directory required");
        return new ActivityWorker(() -> {
            final ActivitySession owner = ActivitySession.open(privateDirectory, deviceRef, firmware,
                                                               synthetic, start, end);
            return new Backend() {
                public long ingest(long first, Integer offset, byte[] bytes) {
                    return owner.ingest(first, offset, bytes);
                }
                public Snapshot coverage() {
                    ActivitySession.Coverage c = owner.coverage();
                    return new Snapshot(c.hasCursor, c.cursor, c.records, c.missing);
                }
                public void close() { owner.close(); }
            };
        });
    }
    public CompletableFuture<Void> ready() { return ready; }

    /** Admission copies bytes before returning. Max two queued operations in
     * addition to one running. Rejection never consumes or commits the payload. */
    public CompletableFuture<Long> ingest(long first, Integer offset, byte[] payload) {
        if (payload == null || payload.length > 32768 || payload.length % 8 != 0) {
            return failed(new IllegalArgumentException("Activity8 payload size"));
        }
        byte[] owned = payload.clone();
        return submit(backend -> backend.ingest(first, offset, owned));
    }
    public CompletableFuture<Snapshot> coverage() {
        return submit(Backend::coverage);
    }
    private <T> CompletableFuture<T> submit(Operation<T> operation) {
        Result<T> result = new Result<>();
        synchronized (gate) {
            if (closing) return failed(new IllegalStateException("worker closing"));
            if (pending == MAX_PENDING) {
                return failed(new RejectedExecutionException("band7 queue full"));
            }
            Task job = new Task() {
                public void perform(Backend backend) {
                    try { result.complete(operation.run(backend)); }
                    catch (Exception failure) { result.completeExceptionally(failure); }
                }
                public void fail(Throwable cause) { result.completeExceptionally(cause); }
            };
            pending++;
            if (!work.offer(job)) throw new IllegalStateException("queue invariant");
        }
        return result;
    }
    private static <T> CompletableFuture<T> failed(Throwable error) {
        Result<T> future = new Result<>();
        future.completeExceptionally(error);
        return future;
    }

    /** Enqueue graceful teardown behind admitted work; never blocks the UI. */
    public CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (!closing) {
                closing = true;
                if (!work.offer(STOP)) throw new IllegalStateException("stop slot invariant");
            }
        }
        return closed;
    }
    private void runLoop(BackendFactory factory) {
        Backend backend = null;
        Throwable terminal = null;
        try {
            backend = factory.open();
            ready.complete(null);
            for (;;) {
                Task task = work.take();
                if (task == STOP) break;
                synchronized (gate) { pending--; }
                task.perform(backend);
            }
        } catch (Throwable failure) {
            terminal = failure;
            ready.completeExceptionally(failure);
        } finally {
            // Close admission atomically before draining: otherwise submissions
            // after a failed open could wait forever with no worker alive.
            synchronized (gate) { closing = true; }
            if (backend != null) {
                try { backend.close(); }
                catch (Throwable failure) { if (terminal == null) terminal = failure; }
            }
            Throwable pendingFailure = terminal != null
                    ? terminal : new IllegalStateException("worker closed before completion");
            Task remaining;
            while ((remaining = work.poll()) != null) remaining.fail(pendingFailure);
            if (terminal == null) closed.complete(null);
            else closed.completeExceptionally(terminal);
        }
    }
}
