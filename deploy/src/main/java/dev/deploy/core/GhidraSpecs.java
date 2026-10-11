package dev.deploy.core;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class GhidraSpecs {
    private static String ready;
    static synchronized String directory() throws Exception {
        if (ready != null) return ready;
        Context context = dev.deploy.DeployApplication.context();
        File dir = new File(context.getFilesDir(), "ghidra-11.3.2");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create specification directory");
        for (String name : new String[]{"AARCH64.sla", "AARCH64.ldefs", "AARCH64.pspec", "AARCH64.cspec"}) {
            File target = new File(dir, name);
            File temp = File.createTempFile("spec-", ".tmp", dir);
            try {
                try (InputStream in = context.getAssets().open("ghidra/" + name);
                        FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                if (!temp.renameTo(target)) throw new IllegalStateException("cannot install specification " + name);
            } finally { temp.delete(); }
        }
        ready = dir.getAbsolutePath();
        return ready;
    }
}
