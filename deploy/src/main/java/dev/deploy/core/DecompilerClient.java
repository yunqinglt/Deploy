package dev.deploy.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

final class DecompilerClient {
    static synchronized JSONObject analyze(String path, JSONObject args) throws org.json.JSONException {
        Context context = dev.deploy.DeployApplication.context();
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IDecompiler> worker = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                worker.set(IDecompiler.Stub.asInterface(binder)); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { worker.set(null); }
            @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
            @Override public void onBindingDied(ComponentName name) { worker.set(null); connected.countDown(); }
        };
        boolean bound = false;
        int pid = 0;
        IDecompiler target = null;
        boolean timedOut = false;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            bound = context.bindService(new Intent(context,DecompilerService.class),connection,Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(4,TimeUnit.SECONDS) || (target=worker.get())==null)
                throw new IllegalStateException("decompiler process did not connect");
            pid = target.processId();
            IDecompiler service = target;
            String json=args.toString();
            Future<String> result=executor.submit(() -> service.decompile(path,json));
            return new JSONObject(result.get(30,TimeUnit.SECONDS));
        } catch (Exception e) {
            timedOut = e instanceof TimeoutException;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new JSONObject().put("error", e instanceof TimeoutException
                    ? "decompiler exceeded 30 seconds; worker stopped" : "decompiler worker failed: " + e)
                    .put("code", e instanceof TimeoutException ? "DECOMPILER_TIMEOUT" : "DECOMPILE_FAILED");
        } finally {
            // Reuse the cached process after normal work. Killing a healthy bound
            // service races the next bind and triggers Android's restart backoff.
            if (timedOut && pid>0 && pid!=Process.myPid() && target!=null
                    && target.asBinder().isBinderAlive()) Process.killProcess(pid);
            executor.shutdownNow();
            if (bound) context.unbindService(connection);
        }
    }
}
