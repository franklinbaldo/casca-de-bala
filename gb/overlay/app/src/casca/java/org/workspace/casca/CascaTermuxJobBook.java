package org.workspace.casca;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Plain-Java, bounded volatile job ledger. All methods are synchronized.
 * No Android or JSON dependency; no process or command contents are persisted. */
final class CascaTermuxJobBook<T> {
    static final long TTL_MS = 60 * 60 * 1000L;
    static final int MAX_JOBS = 32;
    static final int MAX_IN_FLIGHT = 4;

    enum Admission { ACCEPTED, IN_FLIGHT_LIMIT, FULL }
    enum Status { MISSING, RUNNING, FINISHED }
    static final class Lookup<T> {
        final Status status;
        final T value;
        Lookup(Status status, T value) { this.status = status; this.value = value; }
    }
    private static final class Job<T> {
        final String client;
        final long started;
        T result;
        Job(String client, long started) { this.client = client; this.started = started; }
    }
    private final Map<String, Job<T>> jobs = new LinkedHashMap<>();

    private void expire(long now) {
        Iterator<Map.Entry<String, Job<T>>> entries = jobs.entrySet().iterator();
        while (entries.hasNext()) {
            Job<T> job = entries.next().getValue();
            // Also defend against clock rollback (e.g., simulated time in tests).
            if (now < job.started || now - job.started >= TTL_MS) entries.remove();
        }
    }

    synchronized Admission reserve(String id, String client, long now) {
        expire(now);
        if (id == null || id.isEmpty() || client == null || client.isEmpty() || jobs.containsKey(id))
            return Admission.FULL;
        int active = 0;
        for (Job<T> job : jobs.values()) if (job.result == null) active++;
        if (active >= MAX_IN_FLIGHT) return Admission.IN_FLIGHT_LIMIT;
        if (jobs.size() >= MAX_JOBS) {
            Iterator<Map.Entry<String, Job<T>>> entries = jobs.entrySet().iterator();
            while (entries.hasNext()) {
                if (entries.next().getValue().result != null) { entries.remove(); break; }
            }
            if (jobs.size() >= MAX_JOBS) return Admission.FULL;
        }
        jobs.put(id, new Job<>(client, now));
        return Admission.ACCEPTED;
    }

    synchronized void cancel(String id) { jobs.remove(id); }

    synchronized boolean finish(String id, T result, long now) {
        expire(now);
        Job<T> job = jobs.get(id);
        if (job == null || job.result != null || result == null) return false;
        job.result = result;
        return true;
    }

    synchronized boolean pending(String id, long now) {
        expire(now);
        Job<T> job = jobs.get(id);
        return job != null && job.result == null;
    }

    synchronized Lookup<T> lookup(String id, String client, long now) {
        expire(now);
        Job<T> job = jobs.get(id);
        if (job == null || !job.client.equals(client)) return new Lookup<>(Status.MISSING, null);
        return job.result == null ? new Lookup<>(Status.RUNNING, null)
            : new Lookup<>(Status.FINISHED, job.result);
    }
}
