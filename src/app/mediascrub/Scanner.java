package app.mediascrub;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.system.Os;
import android.system.StructStat;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;

final class Scanner {
    static final String FOLDER = "folder", AUTO = "auto", DATES = "dates", RESULT = "result";
    private static final Set<String> OTHER = Set.of("tiff", "tif", "dng", "bmp", "svg", "ico", "j2k",
        "raf", "cr2", "nef", "arw", "raw", "webm", "mkv", "avi", "3gp", "m4v", "mts", "m2ts");
    private static final String TEMP = ".media-scrub-";

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("media-scrub", Context.MODE_PRIVATE);
    }

    static String run(Context context, boolean edit, boolean background) {
        synchronized (Scanner.class) {
            SharedPreferences settings = prefs(context);
            if (background && !settings.getBoolean(AUTO, false)) return "";
            String folder = settings.getString(FOLDER, "");
            if (folder.isEmpty()) return "Choose a folder first.";
            boolean dates = settings.getBoolean(DATES, false);
            String result;
            try {
                File storage = Environment.getExternalStorageDirectory().getCanonicalFile();
                File root = new File(storage, folder).getCanonicalFile();
                if (!root.toPath().startsWith(storage.toPath()) ||
                    (root.equals(storage) && !folder.equals(".")) || forbidden(storage, root))
                    throw new IOException("Choose a folder in shared internal storage.");
                result = scan(context, root, settings, folder, edit, dates, background);
            } catch (IOException | SecurityException error) {
                result = "Scan could not start. Check folder access in Android settings. " + error.getMessage();
            }
            if (!Thread.currentThread().isInterrupted()) settings.edit().putString(RESULT, result).apply();
            return result;
        }
    }

    private static boolean forbidden(File storage, File file) {
        String relative = storage.toPath().relativize(file.toPath()).toString();
        return relative.equals("Android/data") || relative.startsWith("Android/data/")
            || relative.equals("Android/obb") || relative.startsWith("Android/obb/");
    }

    private static String scan(Context context, File root, SharedPreferences settings, String folder,
        boolean edit, boolean dates, boolean background) throws IOException {
        ArrayDeque<File> queue = new ArrayDeque<>();
        queue.add(root);
        int changed = 0, clean = 0, skipped = 0, errors = 0, waiting = 0;
        String issue = "";
        long deadline = System.nanoTime() + 6L * 60 * 1_000_000_000;
        File storage = Environment.getExternalStorageDirectory().getCanonicalFile();
        File preview = null;
        try (SQLiteDatabase db = context.openOrCreateDatabase("scans.db", Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE IF NOT EXISTS seen(path TEXT PRIMARY KEY, signature TEXT NOT NULL)");
            if (!edit) preview = File.createTempFile("preview-", ".tmp", context.getCacheDir());
            while (!queue.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) break;
                if (background && (!settings.getBoolean(AUTO, false) ||
                    !folder.equals(settings.getString(FOLDER, "")) || dates != settings.getBoolean(DATES, false))) break;
                if (background && System.nanoTime() >= deadline) break;
                File file = queue.removeFirst();
                if (!file.equals(file.getCanonicalFile()) || forbidden(storage, file)) continue;
                if (file.isDirectory()) {
                    File[] children = file.listFiles();
                    if (children == null) { errors++; issue = "Folder could not be listed"; }
                    else {
                        java.util.Arrays.sort(children);
                        for (File child : children) queue.add(child);
                    }
                    continue;
                }
                String name = file.getName();
                if (name.startsWith(TEMP)) {
                    // Files left by an interrupted write are never gallery items.
                    if (edit && System.currentTimeMillis() - file.lastModified() > 86_400_000L
                        && name.matches("\\.media-scrub-[0-9a-f-]{36}\\.tmp")) file.delete();
                    continue;
                }
                if (!MediaCleaner.supported(name)) {
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0 && OTHER.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) skipped++;
                    continue;
                }
                if (!file.isFile()) continue;
                if (edit && System.currentTimeMillis() - file.lastModified() < 30_000L) { waiting++; continue; }
                File stage = preview;
                try {
                    String signature = signature(file, dates);
                    if (background && seen(db, file, signature)) { clean++; continue; }
                    if (edit) {
                        File candidate = new File(file.getParentFile(), TEMP + UUID.randomUUID() + ".tmp");
                        if (!candidate.createNewFile()) throw new IOException("Could not reserve working file");
                        stage = candidate;
                    }
                    boolean needsChange = MediaCleaner.clean(file, stage, dates);
                    if (!signature.equals(signature(file, dates))) { waiting++; continue; }
                    if (needsChange && edit) {
                        if (Thread.currentThread().isInterrupted() ||
                            (background && (!settings.getBoolean(AUTO, false) ||
                            !folder.equals(settings.getString(FOLDER, "")) || dates != settings.getBoolean(DATES, false))))
                            break;
                        if (!stage.setLastModified(file.lastModified())) throw new IOException("Could not preserve modification time");
                        try (RandomAccessFile staged = new RandomAccessFile(stage, "rw")) { staged.getFD().sync(); }
                        byte[] expected = digest(stage);
                        if (!signature.equals(signature(file, dates))) { waiting++; continue; }
                        Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        MediaScannerConnection.scanFile(context, new String[]{file.getPath()}, null, null);
                        String writtenSignature = signature(file, dates);
                        if (!Arrays.equals(expected, digest(file)) || !writtenSignature.equals(signature(file, dates)))
                            throw new IOException("Saved file did not match the cleaned output.");
                        signature = writtenSignature;
                    }
                    if (edit) db.execSQL("INSERT OR REPLACE INTO seen VALUES(?, ?)", new Object[]{file.getPath(), signature});
                    if (needsChange) changed++; else clean++;
                } catch (MediaCleaner.Unsupported unsupported) {
                    skipped++;
                    issue = unsupported.getMessage();
                } catch (IOException | SecurityException failure) {
                    errors++;
                    issue = failure.getMessage();
                } finally {
                    if (edit && stage != null) stage.delete();
                }
            }
        } finally {
            if (preview != null) preview.delete();
        }
        return (edit ? "Cleaned and verified " : "Would update ") + changed + " · No supported changes " + clean +
            "\nSkipped " + skipped + " · Errors " + errors + " · Waiting " + waiting +
            (queue.isEmpty() ? "" : "\nScan stopped before reaching every file.") +
            (issue == null || issue.isEmpty() ? "" : "\nLast issue: " + issue);
    }

    private static byte[] digest(File file) throws IOException {
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            for (int count; (count = in.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                hash.update(buffer, 0, count);
            }
        }
        return hash.digest();
    }

    private static String signature(File file, boolean dates) throws IOException {
        try {
            StructStat stat = Os.stat(file.getPath());
            return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_size + ":" + stat.st_ctime +
                ":" + file.lastModified() + ":" + dates;
        } catch (android.system.ErrnoException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private static boolean seen(SQLiteDatabase db, File file, String signature) {
        try (android.database.Cursor cursor = db.rawQuery("SELECT 1 FROM seen WHERE path=? AND signature=?",
            new String[]{file.getPath(), signature})) { return cursor.moveToFirst(); }
    }
}
