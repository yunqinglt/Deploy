package dev.deploy.project;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Small, private project database for the first independent Deploy frontend. */
public final class ProjectStore {
    private static final String PREFS = "projects";
    private static final String KEY = "projects";
    private static final long MAX_IMPORT_BYTES = 128L * 1024L * 1024L;

    private final Context context;
    private final SharedPreferences prefs;
    private final File root;

    public static final class FileEntry {
        public final String name;
        public final String path;
        public final long size;
        public final long addedAt;

        FileEntry(String name, String path, long size, long addedAt) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.addedAt = addedAt;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("name", name).put("path", path)
                    .put("size", size).put("addedAt", addedAt);
        }

        static FileEntry from(JSONObject value) {
            return new FileEntry(value.optString("name", "library.so"),
                    value.optString("path", ""), value.optLong("size", 0L),
                    value.optLong("addedAt", 0L));
        }
    }

    public static final class Project {
        public final String id;
        public final String name;
        public final long createdAt;
        public final List<FileEntry> files;

        Project(String id, String name, long createdAt, List<FileEntry> files) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.files = Collections.unmodifiableList(files);
        }

        public FileEntry firstFile() {
            return files.isEmpty() ? null : files.get(0);
        }

        JSONObject json() throws JSONException {
            JSONArray values = new JSONArray();
            for (FileEntry file : files) values.put(file.json());
            return new JSONObject().put("id", id).put("name", name)
                    .put("createdAt", createdAt).put("files", values);
        }
    }

    public ProjectStore(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.root = new File(this.context.getFilesDir(), "projects");
        root.mkdirs();
    }

    public synchronized List<Project> all() {
        List<Project> values = read();
        Collections.sort(values, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        return values;
    }

    public synchronized Project byId(String id) {
        for (Project project : read()) if (project.id.equals(id)) return project;
        return null;
    }

    public synchronized Project create(String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty()) name = "Untitled project";
        String id = UUID.randomUUID().toString();
        File directory = new File(root, id);
        directory.mkdirs();
        List<Project> values = read();
        values.add(new Project(id, name, System.currentTimeMillis(), new ArrayList<>()));
        write(values);
        return byId(id);
    }

    /** Imports a content URI into the private project directory. */
    public synchronized FileEntry importUri(Project project, Uri uri) throws Exception {
        if (project == null || uri == null) throw new IllegalArgumentException("project and URI are required");
        String display = displayName(uri);
        if (!display.toLowerCase(java.util.Locale.ROOT).endsWith(".so")) {
            throw new IllegalArgumentException("Choose an ELF shared object ending in .so");
        }
        String safe = display.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "library.so";
        File directory = new File(root, project.id);
        directory.mkdirs();
        File destination = new File(directory, uniqueName(directory, safe));
        long size = 0L;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
                FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IllegalArgumentException("The selected file cannot be opened");
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_IMPORT_BYTES) throw new IllegalArgumentException("The .so is larger than 128 MiB");
                output.write(buffer, 0, count);
            }
        } catch (Throwable t) {
            destination.delete();
            throw t;
        }
        FileEntry entry = new FileEntry(destination.getName(), destination.getAbsolutePath(),
                size, System.currentTimeMillis());
        List<Project> values = read();
        for (int i = 0; i < values.size(); i++) {
            Project value = values.get(i);
            if (value.id.equals(project.id)) {
                List<FileEntry> files = new ArrayList<>(value.files);
                files.add(entry);
                values.set(i, new Project(value.id, value.name, value.createdAt, files));
                write(values);
                return entry;
            }
        }
        throw new IllegalArgumentException("Project was removed while importing");
    }

    public synchronized void removeFile(Project project, FileEntry entry) {
        if (project == null || entry == null) return;
        new File(entry.path).delete();
        List<Project> values = read();
        for (int i = 0; i < values.size(); i++) {
            Project value = values.get(i);
            if (value.id.equals(project.id)) {
                List<FileEntry> files = new ArrayList<>();
                for (FileEntry file : value.files) if (!file.path.equals(entry.path)) files.add(file);
                values.set(i, new Project(value.id, value.name, value.createdAt, files));
                write(values);
                return;
            }
        }
    }

    private String displayName(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Throwable ignored) {
        }
        String value = uri.getLastPathSegment();
        return value == null ? "library.so" : value;
    }

    private static String uniqueName(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; n < 10000; n++) {
            candidate = new File(directory, stem + "-" + n + ext);
            if (!candidate.exists()) return candidate.getName();
        }
        return UUID.randomUUID() + ext;
    }

    private List<Project> read() {
        List<Project> values = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                JSONArray files = value.optJSONArray("files");
                List<FileEntry> entries = new ArrayList<>();
                if (files != null) for (int j = 0; j < files.length(); j++) {
                    JSONObject file = files.optJSONObject(j);
                    if (file != null) entries.add(FileEntry.from(file));
                }
                values.add(new Project(value.optString("id", ""),
                        value.optString("name", "Untitled project"),
                        value.optLong("createdAt", 0L), entries));
            }
        } catch (Throwable ignored) {
        }
        return values;
    }

    private void write(List<Project> values) {
        JSONArray array = new JSONArray();
        for (Project value : values) try { array.put(value.json()); } catch (JSONException ignored) { }
        prefs.edit().putString(KEY, array.toString()).commit();
    }
}
