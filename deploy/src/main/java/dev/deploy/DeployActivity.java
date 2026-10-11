package dev.deploy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.deploy.core.DeployCore;
import dev.deploy.project.ProjectStore;

/**
 * The independent Deploy frontend.
 *
 * <p>The MCP server and AIDL service stay machine-facing interfaces. This
 * activity is now a small project manager on top of the same DeployCore: a
 * project owns private copies of imported ELF shared objects, and an open
 * project has the three views needed for the first analysis workflow.
 */
public final class DeployActivity extends AppCompatActivity {
    private static final int PICK_LIBRARY = 4101;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ProjectStore projects;
    private ProjectStore.Project currentProject;
    private Uri pendingUri;
    private MaterialToolbar toolbar;
    private LinearLayout content;
    private TabLayout projectTabs;
    private FrameLayout projectContentHost;
    private int projectTab;
    private String activeFilePath;
    private String activeSymbol;
    private boolean showC = true;
    private final Map<String, JSONArray> symbolCache = new HashMap<>();
    private LinearLayout codeHost;
    private LinearLayout symbolsHost;
    private TextView codeOutput;
    private int codeGeneration;
    private EditText assemblyAddress;
    private EditText assemblySource;
    private Spinner assemblyArch;
    private TextView assemblyOutput;

    @Override
    protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        projects = new ProjectStore(this);

        toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Deploy");
        toolbar.setFitsSystemWindows(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        renderHome();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentProject != null) {
            ProjectStore.Project fresh = projects.byId(currentProject.id);
            if (fresh != null) {
                currentProject = fresh;
                if (projectTab == 2) renderProjectTab();
            }
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())
                || intent.getData() == null) return;
        pendingUri = intent.getData();
        try {
            int flags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (flags != 0) getContentResolver().takePersistableUriPermission(pendingUri, flags);
        } catch (Throwable ignored) {
        }
        chooseProjectForImport(pendingUri);
    }

    // ---- project manager --------------------------------------------------

    private void renderHome() {
        currentProject = null;
        toolbar.setTitle("Deploy");
        toolbar.setNavigationIcon(null);
        content.removeAllViews();
        LinearLayout page = page();
        page.addView(headline("Projects"));
        page.addView(body("Create a project, then import the .so files you want to analyze."
                + " Deploy keeps a private copy so a removable source remains usable."));

        LinearLayout actions = row();
        actions.addView(filled("New project", v -> showNewProjectDialog(null)));
        actions.addView(tonal("Import .so", v -> {
            List<ProjectStore.Project> values = projects.all();
            if (values.isEmpty()) showNewProjectDialog(null);
            else chooseProjectForImport(null);
        }));
        actions.addView(outlined("Settings", v -> renderSettings()));
        page.addView(actions);

        List<ProjectStore.Project> values = projects.all();
        if (values.isEmpty()) {
            page.addView(section("NO PROJECTS"));
            page.addView(body("A project is the analysis workspace for one or more native libraries."));
        } else {
            page.addView(section(values.size() == 1 ? "1 PROJECT" : values.size() + " PROJECTS"));
            for (ProjectStore.Project value : values) page.addView(projectCard(value));
        }
        content.addView(scrolled(page));
    }

    private View projectCard(ProjectStore.Project project) {
        MaterialCardView card = card();
        LinearLayout inner = cardInner();
        inner.addView(title(project.name));
        inner.addView(caption(project.files.size() + " .so file(s) · created "
                + DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(project.createdAt))));
        if (!project.files.isEmpty()) inner.addView(mono(project.files.get(0).name));
        LinearLayout actions = row();
        actions.addView(filled("Open", v -> openProject(project)));
        actions.addView(tonal("Import", v -> {
            currentProject = project;
            launchPicker();
        }));
        inner.addView(actions);
        card.addView(inner);
        card.setOnClickListener(v -> openProject(project));
        return card;
    }

    private void showNewProjectDialog(Uri importAfterCreate) {
        EditText name = new EditText(this);
        name.setHint("Project name");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int pad = dp(24);
        name.setPadding(pad, dp(8), pad, 0);
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("New project")
                .setMessage("Give the native analysis workspace a name.")
                .setView(name)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null);
        androidx.appcompat.app.AlertDialog shown = dialog.create();
        shown.setOnShowListener(d -> shown.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    ProjectStore.Project project = projects.create(name.getText().toString());
                    if (importAfterCreate != null) {
                        currentProject = project;
                        pendingUri = importAfterCreate;
                        importUriInto(project, importAfterCreate);
                    } else {
                        openProject(project);
                    }
                    shown.dismiss();
                }));
        shown.show();
    }

    private void chooseProjectForImport(Uri direct) {
        List<ProjectStore.Project> values = projects.all();
        if (values.isEmpty()) {
            showNewProjectDialog(direct);
            return;
        }
        String[] names = new String[values.size()];
        for (int i = 0; i < values.size(); i++) names[i] = values.get(i).name;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Import .so into…")
                .setItems(names, (dialog, which) -> {
                    currentProject = values.get(which);
                    if (direct == null) launchPicker();
                    else importUriInto(currentProject, direct);
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("New project", (dialog, which) -> showNewProjectDialog(direct));
        builder.show();
    }

    private void launchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-sharedlib")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/x-sharedlib", "application/octet-stream"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_LIBRARY);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_LIBRARY || result != RESULT_OK || data == null || data.getData() == null) return;
        if (currentProject == null) {
            pendingUri = data.getData();
            showNewProjectDialog(pendingUri);
        } else {
            importUriInto(currentProject, data.getData());
        }
    }

    private void importUriInto(ProjectStore.Project project, Uri uri) {
        new Thread(() -> {
            try {
                ProjectStore.FileEntry file = projects.importUri(project, uri);
                runOnUiThread(() -> {
                    currentProject = projects.byId(project.id);
                    toast("Imported " + file.name);
                    if (currentProject != null) renderProject(); else renderHome();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> toast("Import failed: " + message(error)));
            }
        }, "deploy-import").start();
    }

    private void openProject(ProjectStore.Project project) {
        currentProject = projects.byId(project.id);
        activeFilePath = currentProject == null || currentProject.firstFile() == null
                ? null : currentProject.firstFile().path;
        activeSymbol = null;
        projectTab = 0;
        renderProject();
    }

    // ---- project tabs -----------------------------------------------------

    private void renderProject() {
        if (currentProject == null) {
            renderHome();
            return;
        }
        toolbar.setTitle(currentProject.name);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationContentDescription("Back to projects");
        toolbar.setNavigationOnClickListener(v -> renderHome());
        content.removeAllViews();
        projectTabs = new TabLayout(this);
        projectTabs.setTabMode(TabLayout.MODE_FIXED);
        projectTabs.setTabGravity(TabLayout.GRAVITY_FILL);
        projectTabs.addTab(projectTabs.newTab().setText("Decompile"));
        projectTabs.addTab(projectTabs.newTab().setText("Assemble"));
        projectTabs.addTab(projectTabs.newTab().setText("Info & files"));
        projectTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                projectTab = tab.getPosition();
                renderProjectTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        content.addView(projectTabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        projectContentHost = new FrameLayout(this);
        content.addView(projectContentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // TabLayout selects the first tab while it is being populated. That
        // selection happens before the content host exists, so explicitly
        // render the initial tab instead of relying on a second select event.
        projectTabs.getTabAt(projectTab).select();
        renderProjectTab();
    }

    private void renderProjectTab() {
        if (projectContentHost == null) return;
        projectContentHost.removeAllViews();
        if (projectTab == 0) renderDecompiler();
        else if (projectTab == 1) renderAssembler();
        else renderProjectInfo();
    }

    private void renderDecompiler() {
        LinearLayout page = page();
        page.addView(headline("Decompiler"));
        page.addView(body("Expand a library, choose a symbol, then switch between the C-like"
                + " pseudocode and Capstone assembly returned by Deploy."));
        symbolsHost = new LinearLayout(this);
        symbolsHost.setOrientation(LinearLayout.VERTICAL);
        page.addView(symbolsHost);
        codeHost = new LinearLayout(this);
        codeHost.setOrientation(LinearLayout.VERTICAL);
        page.addView(codeHost);
        if (currentProject.files.isEmpty()) {
            page.addView(section("NO LIBRARIES"));
            page.addView(body("Import a .so from Info & files to begin."));
        } else {
            page.addView(section("LIBRARIES → SYMBOLS"));
            for (ProjectStore.FileEntry file : currentProject.files) {
                MaterialButton fileButton = tonal(file.name, v -> selectFile(file));
                fileButton.setGravity(Gravity.START);
                symbolsHost.addView(fileButton);
                if (file.path.equals(activeFilePath)) renderSymbols(file);
            }
        }
        projectContentHost.addView(scrolled(page));
        if (activeSymbol != null && activeFilePath != null) {
            loadCode(activeFilePath, activeSymbol, showC);
        }
    }

    private void selectFile(ProjectStore.FileEntry file) {
        activeFilePath = file.path;
        activeSymbol = null;
        renderProjectTab();
    }

    private void renderSymbols(ProjectStore.FileEntry file) {
        JSONArray cached = symbolCache.get(file.path);
        if (cached == null) {
            symbolsHost.addView(caption("Loading symbols…"));
            int generation = ++codeGeneration;
            new Thread(() -> {
                JSONObject result = analyze(file.path, "symbols", jsonArgs("max", 512));
                runOnUiThread(() -> {
                    if (generation != codeGeneration || currentProject == null) return;
                    if (result.has("error")) {
                        symbolsHost.removeAllViews();
                        symbolsHost.addView(caption("Could not list symbols: " + result.optString("error")));
                        return;
                    }
                    JSONArray symbols = result.optJSONArray("symbols");
                    symbolCache.put(file.path, symbols == null ? new JSONArray() : symbols);
                    renderProjectTab();
                });
            }, "deploy-symbols").start();
            return;
        }
        if (cached.length() == 0) {
            symbolsHost.addView(caption("No symbols returned for this library."));
            return;
        }
        for (int i = 0; i < cached.length(); i++) {
            JSONObject symbol = cached.optJSONObject(i);
            if (symbol == null) continue;
            String name = symbol.optString("name", "(unnamed)");
            String value = symbol.optString("value", "");
            MaterialButton button = tonal("    ↳ " + name + (value.isEmpty() ? "" : "  " + value),
                    v -> selectSymbol(file, name));
            button.setGravity(Gravity.START);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(16);
            button.setLayoutParams(params);
            symbolsHost.addView(button);
        }
        if (activeSymbol != null && activeFilePath.equals(file.path)) renderCodeControls();
    }

    private void selectSymbol(ProjectStore.FileEntry file, String symbol) {
        activeFilePath = file.path;
        activeSymbol = symbol;
        showC = true;
        renderProjectTab();
    }

    private void renderCodeControls() {
        if (codeHost == null) return;
        codeHost.removeAllViews();
        codeHost.addView(section("SYMBOL CODE"));
        codeHost.addView(title(activeSymbol));
        LinearLayout modes = row();
        modes.addView(showC ? filled("C code", v -> { showC = true; loadCode(activeFilePath, activeSymbol, true); })
                : tonal("C code", v -> { showC = true; loadCode(activeFilePath, activeSymbol, true); }));
        modes.addView(!showC ? filled("ASM code", v -> { showC = false; loadCode(activeFilePath, activeSymbol, false); })
                : tonal("ASM code", v -> { showC = false; loadCode(activeFilePath, activeSymbol, false); }));
        codeHost.addView(modes);
        codeOutput = mono("Loading…");
        codeHost.addView(codeOutput);
    }

    private void loadCode(String path, String symbol, boolean c) {
        if (codeOutput == null) return;
        final int generation = ++codeGeneration;
        codeOutput.setText("Loading…");
        new Thread(() -> {
            JSONObject args = jsonArgs("name", symbol);
            try { args.put("max", c ? 4096 : 128); } catch (Throwable ignored) { }
            JSONObject result = analyze(path, c ? "decompile" : "disasm", args);
            runOnUiThread(() -> {
                if (generation != codeGeneration || codeOutput == null) return;
                codeOutput.setText(c ? result.optString("pseudocode", result.optString("error", result.toString()))
                        : formatInstructions(result));
            });
        }, "deploy-code").start();
    }

    // ---- assembler --------------------------------------------------------

    private void renderAssembler() {
        LinearLayout page = page();
        page.addView(headline("Assembler"));
        page.addView(body("Generate bytes at an ELF-relative address. This only assembles;"
                + " it never writes memory or executes the result."));
        page.addView(caption("Architecture"));
        assemblyArch = new Spinner(this);
        String[] arches = {"aarch64", "arm", "thumb", "x86", "x86_64"};
        assemblyArch.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, arches));
        page.addView(assemblyArch);
        page.addView(caption("Relative ELF address (for PC-relative instructions)"));
        assemblyAddress = new EditText(this);
        assemblyAddress.setSingleLine(true);
        assemblyAddress.setText("0x0");
        assemblyAddress.setInputType(InputType.TYPE_CLASS_TEXT);
        page.addView(assemblyAddress);
        page.addView(caption("Instructions"));
        assemblySource = new EditText(this);
        assemblySource.setGravity(Gravity.TOP | Gravity.START);
        assemblySource.setMinLines(6);
        assemblySource.setTypeface(Typeface.MONOSPACE);
        assemblySource.setHint("mov x0, #42; ret");
        assemblySource.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        page.addView(assemblySource);
        page.addView(filled("Assemble", v -> assemble()));
        assemblyOutput = mono("The generated bytes will appear here.");
        page.addView(assemblyOutput);
        projectContentHost.addView(scrolled(page));
    }

    private void assemble() {
        String arch = String.valueOf(assemblyArch.getSelectedItem());
        String source = assemblySource.getText().toString();
        String address = assemblyAddress.getText().toString().trim();
        assemblyOutput.setText("Assembling…");
        new Thread(() -> {
            JSONObject result;
            try { result = new DeployCore().assemble(arch, source, address); }
            catch (Throwable error) { result = jsonError(message(error)); }
            JSONObject finalResult = result;
            runOnUiThread(() -> assemblyOutput.setText(finalResult.toString()));
        }, "deploy-assemble").start();
    }

    // ---- project information ---------------------------------------------

    private void renderProjectInfo() {
        LinearLayout page = page();
        page.addView(headline("Project information"));
        page.addView(keyValue("Project", currentProject.name));
        page.addView(keyValue("Files", String.valueOf(currentProject.files.size())));
        page.addView(keyValue("Backend", "LIEF · Capstone · Ghidra · Keystone"));
        page.addView(tonal("Import another .so", v -> launchPicker()));
        page.addView(section("FILES"));
        if (currentProject.files.isEmpty()) {
            page.addView(body("No libraries have been imported."));
        } else {
            for (ProjectStore.FileEntry file : currentProject.files) {
                MaterialCardView card = card();
                LinearLayout inner = cardInner();
                inner.addView(title(file.name));
                inner.addView(caption(formatBytes(file.size) + " · " + file.path));
                LinearLayout actions = row();
                actions.addView(filled("Analyze", v -> {
                    activeFilePath = file.path;
                    activeSymbol = null;
                    projectTabs.getTabAt(0).select();
                }));
                actions.addView(outlined("Remove", v -> confirmRemove(file)));
                inner.addView(actions);
                card.addView(inner);
                page.addView(card);
            }
        }
        projectContentHost.addView(scrolled(page));
    }

    private void confirmRemove(ProjectStore.FileEntry file) {
        new MaterialAlertDialogBuilder(this).setTitle("Remove " + file.name + "?")
                .setMessage("The private project copy will be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    projects.removeFile(currentProject, file);
                    currentProject = projects.byId(currentProject.id);
                    activeFilePath = currentProject.firstFile() == null ? null : currentProject.firstFile().path;
                    activeSymbol = null;
                    renderProjectTab();
                }).show();
    }

    // ---- MCP settings -----------------------------------------------------

    private void renderSettings() {
        toolbar.setTitle("Deploy settings");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationContentDescription("Back to projects");
        toolbar.setNavigationOnClickListener(v -> renderHome());
        content.removeAllViews();
        LinearLayout page = page();
        page.addView(headline("MCP server"));
        page.addView(body("The server is a machine-facing interface. Project analysis stays in the tabs above."));
        DeployMcpService service = DeployMcpService.instance();
        boolean running = service != null && service.isRunning();
        page.addView(keyValue("Status", running ? "running" : "stopped"));
        page.addView(keyValue("Endpoint", service == null
                ? "http://127.0.0.1:" + DeployMcpService.DEFAULT_PORT + "/mcp" : service.endpoint()));
        page.addView(caption("Bearer token"));
        TextView token = mono(service == null ? getSharedPreferences("deploy", MODE_PRIVATE)
                .getString("token", "") : service.token());
        page.addView(token);
        LinearLayout actions = row();
        actions.addView(running ? filled("Stop service", v -> {
            DeployMcpService.stop(this); ui.postDelayed(this::renderSettings, 600);
        }) : filled("Start service", v -> {
            DeployMcpService.start(this); ui.postDelayed(this::renderSettings, 600);
        }));
        actions.addView(tonal("Copy token", v -> copy(token.getText().toString())));
        page.addView(actions);
        page.addView(section("BACKEND"));
        try {
            JSONObject status = new DeployCore().status();
            page.addView(mono(status.toString()));
        } catch (Throwable error) {
            page.addView(body("Backend unavailable: " + message(error)));
        }
        page.addView(section("FILES ACCESS"));
        page.addView(body("Deploy reads imported project copies and other readable ELF files."
                + " The MCP server does not grant root or process memory access."));
        page.addView(outlined("Open all-files access settings", v -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()))); }
            catch (Throwable error) { toast("Could not open storage settings"); }
        }));
        content.addView(scrolled(page));
    }

    // ---- backend formatting ----------------------------------------------

    private JSONObject analyze(String path, String op, JSONObject args) {
        try { return new DeployCore().analyze(path, op, args); }
        catch (Throwable error) { return jsonError(message(error)); }
    }

    private String formatInstructions(JSONObject result) {
        if (result.has("error")) return result.optString("error");
        JSONArray values = result.optJSONArray("instructions");
        if (values == null) return result.toString();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) continue;
            text.append(String.format(Locale.ROOT, "%s  %-8s %s   ; %s\n",
                    value.optString("address", ""), value.optString("mnemonic", ""),
                    value.optString("operands", ""), value.optString("bytes", "")));
        }
        if (text.length() == 0) text.append("No instructions returned.");
        return text.toString();
    }

    // ---- view helpers -----------------------------------------------------

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        page.setPadding(pad, pad, pad, dp(32));
        return page;
    }

    private ScrollView scrolled(View view) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        return scroll;
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout cardInner() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        inner.setPadding(pad, pad, pad, dp(12));
        return inner;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        row.setLayoutParams(params);
        return row;
    }

    private TextView headline(String value) {
        TextView view = text(value);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        return view;
    }

    private TextView title(String value) {
        TextView view = text(value);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        return view;
    }

    private TextView section(String value) {
        TextView view = text(value);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        view.setLetterSpacing(.08f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(26);
        params.bottomMargin = dp(4);
        view.setLayoutParams(params);
        return view;
    }

    private TextView body(String value) {
        TextView view = text(value);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        view.setTextColor(com.google.android.material.color.MaterialColors.getColor(view,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        view.setLayoutParams(params);
        return view;
    }

    private TextView caption(String value) {
        TextView view = text(value);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        view.setTextColor(com.google.android.material.color.MaterialColors.getColor(view,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
        view.setLayoutParams(params);
        return view;
    }

    private TextView mono(String value) {
        TextView view = caption(value);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(view,
                com.google.android.material.R.attr.colorSurfaceContainerHighest));
        return view;
    }

    private TextView keyValue(String key, String value) {
        return body(key + ":  " + value);
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        return view;
    }

    private MaterialButton filled(String label, View.OnClickListener listener) {
        return button(label, listener, com.google.android.material.R.attr.materialButtonStyle);
    }

    private MaterialButton tonal(String label, View.OnClickListener listener) {
        return button(label, listener, com.google.android.material.R.attr.materialButtonTonalStyle);
    }

    private MaterialButton outlined(String label, View.OnClickListener listener) {
        return button(label, listener, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    }

    private MaterialButton button(String label, View.OnClickListener listener, int style) {
        MaterialButton button = new MaterialButton(this, null, style);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private JSONObject jsonArgs(String key, Object value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (Throwable ignored) { }
        return result;
    }

    private JSONObject jsonError(String message) {
        JSONObject result = new JSONObject();
        try { result.put("error", message); } catch (Throwable ignored) { }
        return result;
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Deploy token", value));
            toast("Copied");
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", value / 1024d);
        return String.format(Locale.ROOT, "%.1f MiB", value / (1024d * 1024d));
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.toString() : value;
    }
}
