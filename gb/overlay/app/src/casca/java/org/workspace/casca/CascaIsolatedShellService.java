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

/**
 * LOCAL-ONLY feasibility probe for a true isolated Android shell.
 *
 * No MCP tools are registered here and NO caller-supplied command is executed.
 * Shell execution from an isolated_app SELinux domain is not guaranteed on
 * stock Android. A physical-device test must prove the sandbox before a
 * separately reviewed approval-and-job protocol can be enabled.
 */
public final class CascaIsolatedShellService extends Service {
    static final int MSG_PROBE = 1;
    static final int MSG_RESULT = 2;
    static final long TIMEOUT_MS = 5_000;
    static final int MAX_OUTPUT_BYTES = 4_096;
    static final String SENTINEL = "casca-shell-probe-sentinel";
    private final AtomicBoolean busy = new AtomicBoolean();

    private final Messenger inbound = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.what != MSG_PROBE || message.replyTo == null) return;
            final Messenger reply = message.replyTo;
            final int requestId = message.arg1;
            if (!busy.compareAndSet(false, true)) {
                sendResult(reply, requestId, failure("probe_already_running"));
                return;
            }
            new Thread(() -> {
                try { sendResult(reply, requestId, runLocalProbe()); }
                finally { busy.set(false); }
            }, "casca-isolated-shell-probe").start();
        }
    });

    @Override public IBinder onBind(Intent intent) { return inbound.getBinder(); }

    private static Bundle failure(String code) {
        Bundle result = new Bundle();
        result.putString("failure", code);
        return result;
    }

    private static void sendResult(Messenger reply, int requestId, Bundle result) {
        Message response = Message.obtain(null, MSG_RESULT);
        response.arg1 = requestId;
        response.setData(result);
        try { reply.send(response); }
        catch (RemoteException ignored) { /* Activity no longer present. */ }
    }

    private Bundle runLocalProbe() {
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        result.putBoolean("isolated", Process.isIsolated());
        // This marker contains no secrets. The main app created it in its own
        // private storage before binding; the isolated UID must fail to open.
        boolean privateFileReadable = false;
        try (FileInputStream ignored = new FileInputStream(new File(getFilesDir(), SENTINEL))) {
            privateFileReadable = true;
        } catch (IOException | SecurityException denied) {
            // Expected: the isolated process cannot read Casca's private files.
        }
        result.putBoolean("privateFileReadable", privateFileReadable);

        java.lang.Process child = null;
        try {
            // Deliberately fixed benign commands, NOT an arbitrary command API.
            child = new ProcessBuilder("/system/bin/sh", "-c", "id; pwd")
                    .redirectErrorStream(true).start();
            if (!child.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                result.putBoolean("timedOut", true);
                child.destroyForcibly();
                return result;
            }
            result.putInt("exitCode", child.exitValue());
            byte[] output = capped(child.getInputStream(), MAX_OUTPUT_BYTES);
            result.putString("output", new String(output, StandardCharsets.UTF_8));
            result.putBoolean("truncated", output.length == MAX_OUTPUT_BYTES);
        } catch (IOException | InterruptedException | SecurityException failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            result.putString("failure", failed.getClass().getSimpleName());
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
        return result;
    }

    private static byte[] capped(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        while (output.size() < maximum) {
            int read = input.read(buffer, 0, Math.min(buffer.length, maximum - output.size()));
            if (read < 0) break;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
