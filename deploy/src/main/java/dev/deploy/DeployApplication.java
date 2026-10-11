package dev.deploy;

import android.app.Application;
import android.content.Context;

public final class DeployApplication extends Application {
    private static Context context;
    @Override public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
    }
    public static Context context() {
        if (context == null) throw new IllegalStateException("Deploy is not initialized");
        return context;
    }
}
