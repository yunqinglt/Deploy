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
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
    private int viewGeneration;
    private final List<LibraryNode> libraries = new ArrayList<>();
    private final ExecutorService analysisWorkers = Executors.newFixedThreadPool(2);
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
        invalidateAnalysis();
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
        page.addView(actions);
        page.addView(outlined("Settings", v -> renderSettings()));

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
        LinearLayout inputContainer = new LinearLayout(this);
        inputContainer.setOrientation(LinearLayout.VERTICAL);
        inputContainer.setPadding(dp(24), dp(16), dp(24), dp(8));
        TextInputLayout nameField = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        nameField.setHint("Project name");
        TextInputEditText name = new TextInputEditText(nameField.getContext());
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inputContainer.addView(nameField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("New project")
                .setMessage("Give the native analysis workspace a name.")
                .setView(inputContainer)
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
        if (projectTabs.getSelectedTabPosition() == projectTab) renderProjectTab();
        else projectTabs.getTabAt(projectTab).select();
    }

    private void renderProjectTab() {
        if (projectContentHost == null) return;
        invalidateAnalysis();
        projectContentHost.removeAllViews();
        if (projectTab == 0) renderDecompiler();
        else if (projectTab == 1) renderAssembler();
        else renderProjectInfo();
    }

    private void renderDecompiler() {
        LinearLayout page = page();
        page.addView(headline("Decompiler"));
        page.addView(body("Tap a library or symbol to expand it. Tap again to collapse."));
        if (currentProject.files.isEmpty()) {
            page.addView(body("Import a .so from Info & files to begin."));
        } else {
            page.addView(section("LIBRARIES → SYMBOLS → CODE"));
            for (ProjectStore.FileEntry file : currentProject.files) {
                LibraryNode node = new LibraryNode(file);
                libraries.add(node);
                page.addView(node.root);
                if (file.path.equals(activeFilePath)) node.toggle();
            }
        }
        projectContentHost.addView(scrolled(page));
    }

    /** Nodes own their child views; expanding never rebuilds the entire tree. */
    private final class LibraryNode {
        final ProjectStore.FileEntry file;
        final int generation = viewGeneration;
        final LinearLayout root = column();
        final LinearLayout children = column();
        final MaterialButton toggle;
        int request;
        boolean expanded;
        String query = "";
        String table = "all";
        final List<SymbolNode> nodes = new ArrayList<>();

        LibraryNode(ProjectStore.FileEntry file) {
            this.file = file;
            toggle = treeButton("▸ " + file.name, v -> toggle());
            root.addView(toggle);
            indent(children, 12);
            children.setVisibility(View.GONE);
            root.addView(children);
        }

        void toggle() {
            expanded = !expanded;
            toggle.setText((expanded ? "▾ " : "▸ ") + file.name);
            toggle.setContentDescription((expanded ? "Collapse " : "Expand ") + file.name);
            children.setVisibility(expanded ? View.VISIBLE : View.GONE);
            request++;
            cancelCode();
            children.removeAllViews();
            if (expanded) {
                activeFilePath = file.path;
                loadPage(0);
            } else if (file.path.equals(activeFilePath)) activeFilePath = null;
        }

        void cancelCode() {
            for (SymbolNode node : nodes) node.cancel();
            nodes.clear();
        }

        void loadPage(int offset) {
            cancelCode();
            final int ticket = ++request;
            children.removeAllViews();
            children.addView(caption("Loading symbols…"));
            JSONObject args = jsonArgs("max", 100);
            try { args.put("offset", offset).put("query", query).put("table", table); }
            catch (Exception ignored) { }
            analysisWorkers.execute(() -> {
                JSONObject result = analyze(file.path, "symbols", args);
                runOnUiThread(() -> {
                    if (generation != viewGeneration || ticket != request || !expanded) return;
                    children.removeAllViews();
                    EditText search = new EditText(DeployActivity.this);
                    search.setSingleLine(true);
                    search.setHint("Filter symbol names");
                    search.setText(query);
                    children.addView(search);
                    Spinner tables = new Spinner(DeployActivity.this);
                    tables.setAdapter(new ArrayAdapter<>(DeployActivity.this,
                            android.R.layout.simple_spinner_dropdown_item,
                            new String[]{"all", "dynamic", "static"}));
                    tables.setSelection(table.equals("static") ? 2 : table.equals("dynamic") ? 1 : 0);
                    children.addView(tables);
                    children.addView(tonal("Apply filter", v -> {
                        query = search.getText().toString().trim();
                        table = String.valueOf(tables.getSelectedItem());
                        loadPage(0);
                    }));
                    if (result.has("error")) {
                        children.addView(body(result.optString("error")));
                        children.addView(tonal("Retry", v -> loadPage(offset)));
                        return;
                    }
                    int total = result.optInt("count");
                    JSONArray values = result.optJSONArray("symbols");
                    int returned = values == null ? 0 : values.length();
                    children.addView(caption("Dynamic: " + result.optInt("dynamicSymbols")
                            + " · Static: " + result.optInt("staticSymbols") + " · Showing "
                            + (returned == 0 ? 0 : offset + 1) + "–" + (offset + returned) + " of " + total));
                    if (result.has("note")) children.addView(body(result.optString("note")));
                    if (values != null) for (int i = 0; i < values.length(); i++) {
                        JSONObject symbol = values.optJSONObject(i);
                        if (symbol != null) {
                            SymbolNode node = new SymbolNode(this, symbol);
                            nodes.add(node);
                            children.addView(node.root);
                        }
                    }
                    if (returned == 0) children.addView(caption("No matching symbols in this table."));
                    LinearLayout pages = row();
                    if (offset > 0) pages.addView(tonal("Previous 100", v -> loadPage(Math.max(0, offset - 100))));
                    if (!result.isNull("nextOffset")) pages.addView(tonal("Next 100", v -> loadPage(result.optInt("nextOffset"))));
                    children.addView(pages);
                });
            });
        }
    }

    private final class SymbolNode {
        final LibraryNode library;
        final JSONObject symbol;
        final LinearLayout root = column();
        final LinearLayout code = column();
        final MaterialButton toggle;
        final String label;
        final boolean function;
        final int libraryRequest;
        boolean expanded;
        int request;
        int timeoutSeconds = 30;
        Future<?> job;

        SymbolNode(LibraryNode library, JSONObject symbol) {
            this.library = library;
            this.symbol = symbol;
            libraryRequest = library.request;
            String name = symbol.optString("name");
            String type = symbol.optString("type");
            boolean defined = symbol.optBoolean("defined");
            function = defined && (type.equals("FUNC") || type.equals("GNU_IFUNC"));
            label = (name.isEmpty() ? "(unnamed)" : name) + "  " + symbol.optString("value")
                    + "\n" + symbol.optString("table") + " · " + (defined ? type : "import");
            toggle = treeButton("▸ " + label, v -> toggle());
            root.addView(toggle);
            indent(code, 12);
            code.setVisibility(View.GONE);
            root.addView(code);
        }

        void cancel() {
            request++;
            if (job != null) job.cancel(true);
        }

        void toggle() {
            expanded = !expanded;
            cancel();
            toggle.setText((expanded ? "▾ " : "▸ ") + label);
            code.setVisibility(expanded ? View.VISIBLE : View.GONE);
            code.removeAllViews();
            if (expanded) {
                if (function) showCode(true, 20000, 0);
                else {
                    code.addView(body(symbol.optBoolean("defined") ? "This symbol is data or metadata, not a function."
                            : "Imported symbol: its implementation is in another library."));
                    code.addView(mono(symbol.toString()));
                }
            }
        }

        void showCode(boolean c, int budget, long offset) {
            cancel();
            final int ticket = ++request;
            code.removeAllViews();
            LinearLayout modes = row();
            modes.addView(c ? filled("C code", v -> showCode(true, budget, 0))
                    : tonal("C code", v -> showCode(true, budget, 0)));
            modes.addView(c ? tonal("ASM code", v -> showCode(false, budget, 0))
                    : filled("ASM code", v -> showCode(false, budget, 0)));
            code.addView(modes);
            TextView output = mono(c ? "Analyzing… (up to " + timeoutSeconds + " seconds)" : "Loading…");
            code.addView(output);
            long start;
            try { start = Long.parseUnsignedLong(symbol.optString("value").substring(2), 16); }
            catch (Exception e) { output.setText("Invalid symbol address"); return; }
            long size = symbol.optLong("size");
            JSONObject args = jsonArgs("address", "0x" + Long.toUnsignedString(start + offset, 16));
            try {
                args.put("max", c ? budget : 512);
                if (c) args.put("timeoutSeconds", timeoutSeconds);
                if (!c) {
                    args.put("bytes", size > 0 ? (int)Math.min(4096, size - offset) : 4096);
                }
            } catch (Exception ignored) { }
            job = analysisWorkers.submit(() -> {
                JSONObject result = analyze(library.file.path, c ? "decompile" : "disasm", args);
                runOnUiThread(() -> {
                    if (library.generation != viewGeneration || libraryRequest != library.request
                            || !library.expanded || !expanded || ticket != request) return;
                    output.setText(c ? result.optString("pseudocode", result.optString("error", result.toString()))
                            : formatInstructions(result));
                    if (c) {
                        String warnings = result.optString("warnings");
                        if (!warnings.isEmpty()) code.addView(body(warnings));
                        code.addView(caption("C pseudocode uses inferred types and control flow."));
                        if ("DECOMPILER_TIMEOUT".equals(result.optString("code")) && timeoutSeconds < 120)
                            code.addView(tonal("Retry with 120 seconds", v -> {
                                timeoutSeconds = 120;
                                showCode(true, budget, 0);
                            }));
                        if ("DECOMPILE_INSTRUCTION_LIMIT".equals(result.optString("code"))) {
                            code.addView(body("Instruction limit: " + budget + ". ASM remains available."));
                            if (budget < 100000) code.addView(tonal("Retry with 100,000 instructions",
                                    v -> showCode(true, 100000, 0)));
                        }
                    } else if (!result.has("error")) {
                        long consumed = result.optLong("decodedBytes");
                        long next = offset + consumed;
                        code.addView(caption("Bytes " + offset + "–" + next
                                + (size > 0 ? " / " + size : " · function size unknown")
                                + " · " + result.optString("stopReason")));
                        LinearLayout pages = row();
                        if (offset > 0) pages.addView(tonal("From start", v -> showCode(false, budget, 0)));
                        if (consumed > 0 && (size == 0 || next < size)
                                && !"undecodable_bytes".equals(result.optString("stopReason")))
                            pages.addView(tonal("Next ASM page", v -> showCode(false, budget, next)));
                        code.addView(pages, 1);
                    }
                });
            });
        }
    }

    private void invalidateAnalysis() {
        viewGeneration++;
        for (LibraryNode library : libraries) library.cancelCode();
        libraries.clear();
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    private void indent(View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(margin);
        view.setLayoutParams(params);
    }

    private MaterialButton treeButton(String label, View.OnClickListener listener) {
        MaterialButton button = tonal(label, listener);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    @Override protected void onDestroy() {
        invalidateAnalysis();
        analysisWorkers.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
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
                    renderProjectTab();
                }).show();
    }

    // ---- MCP settings -----------------------------------------------------

    private void renderSettings() {
        invalidateAnalysis();
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
