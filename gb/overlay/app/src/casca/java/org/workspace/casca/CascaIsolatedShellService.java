package org.workspace.casca;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android's isolated UID is a mandatory boundary. No app tokens, Bluetooth,
 * network sockets, or storage permissions are available to this service.
 * Caller is another component of this same APK (exported=false). */
public final class CascaIsolatedShellService extends Service {
    static final int MSG_PROBE = 1, MSG_RESULT = 2, MSG_EXEC = 3, MSG_EXEC_RESULT = 4;
    static final long TIMEOUT_MS = 5_000;
    static final int MAX_OUTPUT_BYTES = 4_096;
    static final String SENTINEL = "casca-shell-probe-sentinel";
    private final AtomicBoolean busy = new AtomicBoolean();

    private final Messenger inbound = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if ((message.what != MSG_PROBE && message.what != MSG_EXEC) || message.replyTo == null) return;
            final Messenger reply = message.replyTo;
            final int requestId = message.arg1;
            final int type = message.what;
            final Bundle args = new Bundle(message.getData());
            if (!busy.compareAndSet(false, true)) {
                sendResult(reply, requestId, type, failure("busy"));
                return;
            }
            new Thread(() -> {
                try {
                    Bundle result = type == MSG_PROBE ? runLocalProbe() : runApprovedCommand(args);
                    sendResult(reply, requestId, type, result);
                } finally { busy.set(false); }
            }, "casca-isolated-shell-worker").start();
        }
    });
    @Override public IBinder onBind(Intent intent) { return inbound.getBinder(); }

    private static Bundle failure(String code) {
        Bundle result = new Bundle(); result.putString("failure", code); return result;
    }
    private static void sendResult(Messenger reply, int requestId, int type, Bundle result) {
        Message response = Message.obtain(null, type == MSG_PROBE ? MSG_RESULT : MSG_EXEC_RESULT);
        response.arg1 = requestId; response.setData(result);
        try { reply.send(response); } catch (RemoteException ignored) { /* caller went away */ }
    }
    private Bundle evidence(String privatePath) {
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        result.putBoolean("isolated", Process.isIsolated());
        boolean privateFileReadable = false;
        try (FileInputStream ignored = new FileInputStream(new File(privatePath))) {
            privateFileReadable = true;
        } catch (IOException | SecurityException denied) { /* expected */ }
        result.putBoolean("privateFileReadable", privateFileReadable);
        return result;
    }
    private Bundle runLocalProbe() {
        return execute("id; pwd", TIMEOUT_MS, new File(getFilesDir(), SENTINEL).getAbsolutePath());
    }
    private Bundle runApprovedCommand(Bundle args) {
        String command = args.getString("command", "");
        String path = args.getString("private_path", "");
        long timeout = args.getLong("timeout_ms", 0);
        if (command.isEmpty() || command.getBytes(StandardCharsets.UTF_8).length > 1024
                || command.indexOf('\u0000') >= 0 || path.isEmpty()
                || timeout < 100 || timeout > TIMEOUT_MS) return failure("invalid_request");
        return execute(command, timeout, path);
    }
    private Bundle execute(String command, long timeout, String privatePath) {
        Bundle result = evidence(privatePath);
        if (!result.getBoolean("isolated") || result.getBoolean("privateFileReadable", true)) {
            result.putString("failure", "isolation_not_proven"); return result;
        }
        java.lang.Process child = null;
        try {
            child = new ProcessBuilder("/system/bin/sh", "-c", command).start();
            result.putBoolean("executed", true);
            CappedStream out = new CappedStream(child.getInputStream(), MAX_OUTPUT_BYTES);
            CappedStream err = new CappedStream(child.getErrorStream(), MAX_OUTPUT_BYTES);
            out.start(); err.start();
            boolean ended = child.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!ended) {
                result.putBoolean("timedOut", true);
                child.destroyForcibly();
                child.waitFor(250, TimeUnit.MILLISECONDS);
            } else {
                result.putInt("exitCode", child.exitValue());
            }
            out.join(300); err.join(300);
            result.putString("stdout", out.text());
            result.putString("stderr", err.text());
            result.putBoolean("truncated", out.truncated || err.truncated);
        } catch (IOException | InterruptedException | SecurityException failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            result.putString("failure", failed.getClass().getSimpleName());
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
        return result;
    }
    /** Consume past the cap to avoid blocking the child on full pipes, but
     * never store more than the configured limit. */
    private static final class CappedStream extends Thread {
        private final InputStream input;
        private final int cap;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        volatile boolean truncated;
        CappedStream(InputStream input, int cap) {
            super("casca-shell-stream"); setDaemon(true); this.input = input; this.cap = cap;
        }
        @Override public void run() {
            byte[] chunk = new byte[1024];
            try {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    int keep = Math.min(read, Math.max(0, cap - buffer.size()));
                    synchronized (buffer) {
                        if (keep > 0) buffer.write(chunk, 0, keep);
                        if (keep < read) truncated = true;
                    }
                }
            } catch (IOException ignored) { /* process killed / pipe closed */ }
        }
        String text() {
            synchronized (buffer) {
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
