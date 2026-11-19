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
    private static final int PICK = 410;
    private SharedPreferences settings;
    private TextView folder, status;
    private LinearLayout panel;
    private Switch dates, auto;
    private Button preview, clean;
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
        preview = button("Preview scan", () -> run(false));
        clean = button("Clean now", () -> confirm("Clean the selected folder?",
            "Media Scrub will update supported files at their existing locations.", () -> run(true)));
        status = label("", 16);
        render();
    }

    @Override protected void onStart() { super.onStart(); settings.registerOnSharedPreferenceChangeListener(listener); render(); }
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
        auto.setEnabled(!busy && !selected.isEmpty());
        auto.setChecked(settings.getBoolean(Scanner.AUTO, false));
        status.setText(busy ? "Scanning…" : settings.getString(Scanner.RESULT, "Run a preview to see what would change."));
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", (dialog, which) -> action.run()).show();
    }

    private void changeFolder(String selected) {
        if (busy) return;
        setAuto(false);
        settings.edit().putString(Scanner.FOLDER, selected).remove(Scanner.RESULT).apply();
    }

    private void setAuto(boolean enabled) {
        boolean ready = ScanJob.schedule(this, enabled);
        settings.edit().putBoolean(Scanner.AUTO, enabled && ready).apply();
        if (!ready) settings.edit().putString(Scanner.RESULT, "Android could not schedule background cleaning.").apply();
        render();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
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

    private void run(boolean edit) {
        if (busy) return;
        busy = true;
        render();
        new Thread(() -> {
            try { Scanner.run(getApplicationContext(), edit, false); }
            catch (RuntimeException error) {
                settings.edit().putString(Scanner.RESULT, "Scan could not complete. " + error.getMessage()).apply();
            } finally {
                runOnUiThread(() -> { busy = false; if (!isDestroyed()) render(); });
            }
        }, "Manual scan").start();
    }
}
