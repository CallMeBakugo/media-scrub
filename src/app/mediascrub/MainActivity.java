package app.mediascrub;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int PICK = 410, INSPECT = 411;
    private SharedPreferences settings;
    private TextView folder, status, automaticStatus;
    private Button interval;
    private LinearLayout panel;
    private Switch dates, auto;
    private Button preview, clean, inspect, recheck, skipped;
    private Uri inspectedUri;
    private MetadataInspector.Report firstInspection;
    private boolean busy;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> runOnUiThread(this::render);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = Scanner.prefs(this);
        ScrollView scroll = new ScrollView(this);
        panel = new LinearLayout(this);
        panel.setPadding(dp(24), dp(22), dp(24), dp(24));
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        scroll.setOnApplyWindowInsetsListener((view, inset) -> {
            android.graphics.Insets bars = inset.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
        label("Media Scrub", 32);
        folder = label("", 17);
        button("Choose test folder", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK));
        button("Use shared storage", () -> confirm("Scan shared storage?",
            "Limit access with GrapheneOS Storage Scopes if you only want certain folders included.",
            () -> changeFolder(".")));
        button("Android storage settings", () -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException missing) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        });
        dates = new Switch(this);
        dates.setText("Remove supported capture date tags");
        dates.setChecked(settings.getBoolean(Scanner.DATES, false));
        dates.setMinHeight(dp(48));
        panel.addView(dates);
        dates.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean(Scanner.DATES, enabled).apply());
        auto = new Switch(this);
        auto.setText("Clean automatically");
        auto.setChecked(settings.getBoolean(Scanner.AUTO, false));
        auto.setMinHeight(dp(48));
        panel.addView(auto);
        auto.setOnCheckedChangeListener((view, enabled) -> {
            if (enabled == settings.getBoolean(Scanner.AUTO, false)) return;
            if (enabled) {
                auto.setChecked(false);
                confirm("Enable background cleaning?",
                    "Supported metadata will be removed from files in the selected folder as Android schedules scans.",
                    () -> setAuto(true));
            } else setAuto(false);
        });
        interval = button("", this::chooseInterval);
        automaticStatus = label("", 14);
        preview = button("Preview scan", () -> run(false));
        clean = button("Clean now", () -> confirm("Clean the selected folder?",
            "Media Scrub will update supported files at their existing locations.", () -> run(true)));
        inspect = button("Inspect one file", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            startActivityForResult(pick, INSPECT);
        });
        recheck = button("Recheck the inspected file", () -> inspect(inspectedUri, true));
        status = label("", 16);
        skipped = button("View skipped files", this::showSkippedFiles);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        settings.registerOnSharedPreferenceChangeListener(listener);
        if (settings.getBoolean(Scanner.AUTO, false)) ScanJob.schedule(this);
        render();
    }
    @Override protected void onStop() { settings.unregisterOnSharedPreferenceChangeListener(listener); super.onStop(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView label(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setPadding(0, dp(6), 0, dp(12));
        panel.addView(text);
        return text;
    }

    private Button button(String value, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        panel.addView(button);
        return button;
    }

    private void render() {
        String selected = settings.getString(Scanner.FOLDER, "");
        folder.setText(selected.isEmpty() ? "Choose a folder to get started." :
            selected.equals(".") ? "Shared internal storage" : selected);
        preview.setEnabled(!busy && !selected.isEmpty());
        clean.setEnabled(!busy && !selected.isEmpty());
        dates.setEnabled(!busy);
        inspect.setEnabled(!busy);
        recheck.setVisibility(firstInspection == null ? View.GONE : View.VISIBLE);
        recheck.setEnabled(!busy);
        auto.setEnabled(!busy && !selected.isEmpty());
        boolean automatic = settings.getBoolean(Scanner.AUTO, false);
        auto.setChecked(automatic);
        interval.setText("Scan interval: " + ScanJob.interval(settings) + " minutes");
        interval.setEnabled(!busy);
        long lastRun = settings.getLong(ScanJob.LAST_SCAN, 0);
        automaticStatus.setVisibility(automatic || lastRun != 0 ? View.VISIBLE : View.GONE);
        String lastScan = lastRun == 0 ? "Waiting for the first automatic scan." :
            "Last automatic scan: " + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(lastRun)) +
                " · " + settings.getString(ScanJob.LAST_TRIGGER, "Scheduled");
        if (automatic && !settings.getBoolean(ScanJob.MEDIA_WATCH, true))
            lastScan += "\nMedia changes unavailable. Scheduled scans are still enabled.";
        automaticStatus.setText(lastScan);
        status.setText(busy ? "Scanning…" : settings.getString(Scanner.RESULT, "Run a preview to see what would change."));
        int count = settings.getInt(SkippedFiles.COUNT, 0);
        skipped.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        skipped.setEnabled(!busy);
        skipped.setText("View skipped files (" + count + ")");
    }

    private void showSkippedFiles() {
        String reportId = settings.getString(SkippedFiles.REPORT_ID, "");
        if (reportId.isEmpty()) return;
        new Thread(() -> {
            try {
                var entries = SkippedFiles.read(getApplicationContext(), reportId);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (entries.isEmpty()) {
                        Toast.makeText(this, "The scan report changed. Tap again to view the current report.",
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ListView list = new ListView(this);
                    list.setAdapter(new ArrayAdapter<SkippedFiles.Entry>(this, android.R.layout.simple_list_item_2,
                            android.R.id.text1, entries) {
                        @Override public View getView(int position, View convertView, ViewGroup parent) {
                            View row = super.getView(position, convertView, parent);
                            SkippedFiles.Entry entry = getItem(position);
                            TextView path = row.findViewById(android.R.id.text1);
                            TextView reason = row.findViewById(android.R.id.text2);
                            path.setSingleLine(false);
                            reason.setSingleLine(false);
                            path.setText(entry.path().replaceAll("[\\p{Cc}\\p{Cf}]", "�"));
                            reason.setText(entry.reason());
                            return row;
                        }
                    });
                    new AlertDialog.Builder(this).setTitle("Skipped files (" + entries.size() + ")")
                        .setView(list).setPositiveButton("Done", null).show();
                });
            } catch (android.database.SQLException unavailable) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing())
                        Toast.makeText(this, "Could not open the skipped-files report.", Toast.LENGTH_SHORT).show();
                });
            }
        }, "Read skipped files").start();
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", (dialog, which) -> action.run()).show();
    }

    private void changeFolder(String selected) {
        if (busy) return;
        setAuto(false);
        settings.edit().putString(Scanner.FOLDER, selected).remove(Scanner.RESULT)
            .remove(ScanJob.LAST_SCAN).remove(ScanJob.LAST_TRIGGER)
            .remove(SkippedFiles.REPORT_ID).remove(SkippedFiles.COUNT).apply();
    }

    private void setAuto(boolean enabled) {
        settings.edit().putBoolean(Scanner.AUTO, enabled).apply();
        if (!ScanJob.schedule(this)) {
            settings.edit().putBoolean(Scanner.AUTO, false)
                .putString(Scanner.RESULT, "Android could not schedule background cleaning.").apply();
            ScanJob.schedule(this);
        }
        render();
    }

    private void chooseInterval() {
        int[] minutes = {15, 30, 60};
        int previous = ScanJob.interval(settings);
        int checked = previous == 60 ? 2 : previous == 30 ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("Automatic scan interval")
            .setSingleChoiceItems(new String[]{"15 minutes", "30 minutes", "60 minutes"}, checked,
                (dialog, index) -> {
                    dialog.dismiss();
                    if (minutes[index] == previous) return;
                    settings.edit().putInt(ScanJob.INTERVAL, minutes[index]).apply();
                    if (!ScanJob.schedule(this)) {
                        settings.edit().putInt(ScanJob.INTERVAL, previous)
                            .putString(Scanner.RESULT, "Android could not change the scan interval.").apply();
                        ScanJob.schedule(this);
                    }
                })
            .setNegativeButton("Cancel", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == INSPECT) {
            inspect(data.getData(), false);
            return;
        }
        if (request != PICK) return;
        Uri uri = data.getData();
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority()) || !id.startsWith("primary:"))
                throw new IllegalArgumentException();
            String selected = id.substring("primary:".length());
            changeFolder(selected.isEmpty() ? "." : selected);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, "Choose a folder in this profile’s internal shared storage.", Toast.LENGTH_LONG).show();
        }
    }

    private void inspect(Uri uri, boolean compare) {
        if (busy || uri == null) return;
        busy = true;
        render();
        boolean stripDates = settings.getBoolean(Scanner.DATES, false);
        new Thread(() -> {
            try {
                MetadataInspector.Report report = MetadataInspector.inspect(getApplicationContext(), uri, stripDates);
                runOnUiThread(() -> {
                    busy = false;
                    if (isDestroyed()) return;
                    if (!compare) {
                        inspectedUri = uri;
                        firstInspection = report;
                    }
                    render();
                    showInspection(report, compare ? firstInspection : null);
                });
            } catch (java.io.IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    busy = false;
                    if (isDestroyed()) return;
                    render();
                    new AlertDialog.Builder(this).setTitle("Could not inspect the file")
                        .setMessage("Choose it again if it was moved or replaced. " + error.getMessage())
                        .setPositiveButton("OK", null).show();
                });
            }
        }, "Metadata inspection").start();
    }

    private void showInspection(MetadataInspector.Report report, MetadataInspector.Report previous) {
        java.util.Map<String, String> before = previous == null ? report.current() : previous.current();
        java.util.Map<String, String> after = previous == null ? report.preview() : report.current();
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setPadding(dp(22), dp(12), dp(22), dp(14));
        scroll.addView(text);
        android.text.SpannableStringBuilder body = new android.text.SpannableStringBuilder();
        body.append(previous != null ? "Original inspection → Saved file now" :
            after == null ? "Metadata on the selected file" : "Current file → Cleanup preview");
        body.append("\n").append(previous == null ? report.detail() : "This comparison uses a fresh read of the selected file.");
        java.util.Set<String> names = new java.util.LinkedHashSet<>(before.keySet());
        if (after != null) names.addAll(after.keySet());
        if (names.isEmpty()) body.append("\n\nNo recognized metadata fields found.");
        for (String name : names) {
            String initial = before.get(name);
            String updated = after == null ? null : after.get(name);
            body.append("\n\n").append(name).append("\n");
            if (after == null || java.util.Objects.equals(initial, updated)) {
                body.append(initial);
                continue;
            }
            int start = body.length();
            if (initial != null) body.append(initial).append("\n");
            if (updated == null) body.append(previous == null ? "Will be removed" : "No longer detected");
            else body.append(previous == null ? "Preview: " : "Saved: ").append(updated);
            body.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, body.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        body.append("\n\nThis view lists recognized fields and metadata blocks.");
        text.setText(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(report.filename())
            .setView(scroll).setPositiveButton("Done", null).create();
        dialog.show();
    }

    private void run(boolean edit) {
        if (busy) return;
        busy = true;
        render();
        new Thread(() -> {
            try { Scanner.run(getApplicationContext(), edit, false); }
            catch (RuntimeException error) {
                settings.edit().putString(Scanner.RESULT, "Scan could not complete. " + error.getMessage())
                    .remove(SkippedFiles.REPORT_ID).remove(SkippedFiles.COUNT).apply();
            } finally {
                runOnUiThread(() -> { busy = false; if (!isDestroyed()) render(); });
            }
        }, "Manual scan").start();
    }
}
