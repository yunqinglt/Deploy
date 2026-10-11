package dev.deploy.core;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import org.json.JSONObject;

/** Private process: a native failure or timeout cannot take down either MCP frontend. */
public final class DecompilerService extends Service {
    private final IDecompiler.Stub binder = new IDecompiler.Stub() {
        @Override public int processId() { return Process.myPid(); }
        @Override public String decompile(String path, String argsJson) {
            try { return DeployCore.decompileLocal(path, new JSONObject(argsJson)).toString(); }
            catch (Exception e) {
                try { return new JSONObject().put("error",String.valueOf(e.getMessage())).put("code","DECOMPILE_FAILED").toString(); }
                catch (Exception ignored) { return "{\"error\":\"decompilation failed\"}"; }
            }
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
