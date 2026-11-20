package app.mediascrub;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class SkippedFiles {
    static final String REPORT_ID = "skipped_report_id";
    static final String COUNT = "skipped_report_count";

    record Entry(String path, String reason) {}

    static void begin(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS latest_skipped(report_id TEXT, path TEXT, reason TEXT)");
        database.execSQL("CREATE TEMP TABLE pending_skips(path TEXT, reason TEXT)");
    }

    static void add(SQLiteDatabase database, File root, File file, String reason) {
        String path = root.toPath().relativize(file.toPath()).toString();
        database.execSQL("INSERT INTO pending_skips(path, reason) VALUES(?, ?)", new Object[]{
            path, reason == null || reason.isBlank() ? "This file layout could not be processed." : reason
        });
    }

    static void publish(SQLiteDatabase database, String reportId) {
        database.beginTransaction();
        try {
            database.execSQL("DELETE FROM latest_skipped");
            database.execSQL(
                "INSERT INTO latest_skipped(report_id, path, reason) SELECT ?, path, reason FROM pending_skips ORDER BY rowid",
                new Object[]{reportId});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    static List<Entry> read(Context context, String reportId) {
        List<Entry> result = new ArrayList<>();
        File databaseFile = context.getDatabasePath("scans.db");
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = database.rawQuery("SELECT path, reason FROM latest_skipped WHERE report_id=? ORDER BY rowid",
                 new String[]{reportId})) {
            while (cursor.moveToNext()) result.add(new Entry(cursor.getString(0), cursor.getString(1)));
        }
        return result;
    }
}
