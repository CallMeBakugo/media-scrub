package app.mediascrub;

import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

final class MetadataInspector {
    private static final long LIMIT = 512L * 1024 * 1024;
    record Report(String filename, Map<String, String> current, Map<String, String> preview, String detail) {}
    private record Field(String label, String tag) {}
    private static final List<Field> IMAGE = List.of(
        new Field("Camera make", "Make"), new Field("Camera model", "Model"),
        new Field("Camera owner", "CameraOwnerName"), new Field("Camera serial", "BodySerialNumber"),
        new Field("Lens make", "LensMake"), new Field("Lens model", "LensModel"),
        new Field("Lens serial", "LensSerialNumber"), new Field("Artist", "Artist"),
        new Field("Copyright", "Copyright"), new Field("Description", "ImageDescription"),
        new Field("Comment", "UserComment"), new Field("Software", "Software"),
        new Field("Image ID", "ImageUniqueID"), new Field("Capture date", "DateTimeOriginal"),
        new Field("Digitized date", "DateTimeDigitized"), new Field("Modified date tag", "DateTime"),
        new Field("Capture time offset", "OffsetTimeOriginal"),
        new Field("Orientation", "Orientation"), new Field("EXIF color space", "ColorSpace")
    );

    static Report inspect(Context context, Uri uri, boolean dates) throws IOException {
        String filename = name(context, uri);
        String extension = extension(context, uri, filename);
        String mime = context.getContentResolver().getType(uri);
        boolean video = (mime != null && mime.startsWith("video/")) || extension.equals("mp4") || extension.equals("mov");
        File folder = new File(context.getCacheDir(), "inspection");
        if (!folder.exists() && !folder.mkdir()) throw new IOException("Could not open inspection storage.");
        File[] previous = folder.listFiles();
        if (previous != null) for (File old : previous) {
            if (System.currentTimeMillis() - old.lastModified() > 3600000L) old.delete();
        }
        File source = File.createTempFile("media-", "." + extension, folder);
        File staged = null;
        try {
            staged = File.createTempFile("preview-", "." + extension, folder);
            copy(context, uri, source);
            Map<String, String> before = read(source, video);
            try {
                boolean changed = MediaCleaner.clean(source, staged, dates);
                Map<String, String> after = changed ? read(staged, video) : before;
                return new Report(filename, before, after, changed
                    ? "Preview uses your current cleanup settings." : "The cleaner found no changes for this file.");
            } catch (IOException error) {
                return new Report(filename, before, null, "No cleanup preview. " + error.getMessage());
            }
        } finally {
            if (staged != null) staged.delete();
            source.delete();
        }
    }

    private static String name(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        }
        return "Selected media";
    }

    private static String extension(Context context, Uri uri, String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (extension.matches("[a-z0-9]{1,10}")) return extension;
        }
        String type = context.getContentResolver().getType(uri);
        if (type == null) return "media";
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            default -> "media";
        };
    }

    private static void copy(Context context, Uri uri, File target) throws IOException {
        try (InputStream in = open(context, uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Could not open the selected file.");
            byte[] buffer = new byte[128 * 1024];
            long size = 0;
            for (int n; (n = in.read(buffer)) != -1;) {
                size += n;
                if (size > LIMIT) throw new IOException("This file exceeds the 512 MiB inspection limit.");
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                out.write(buffer, 0, n);
            }
        }
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())
                && DocumentsContract.isDocumentUri(context, uri)) {
            String id = DocumentsContract.getDocumentId(uri);
            if (id.startsWith("primary:")) {
                File root = Environment.getExternalStorageDirectory().getCanonicalFile();
                File file = new File(root, id.substring("primary:".length())).getCanonicalFile();
                if (file.toPath().startsWith(root.toPath()) && !file.equals(root)) {
                    try { return new FileInputStream(file); }
                    catch (FileNotFoundException | SecurityException unavailable) {
                        // The document provider may still grant access via the picker.
                    }
                }
            }
        }
        return context.getContentResolver().openInputStream(uri);
    }

    private static Map<String, String> read(File file, boolean video) {
        Map<String, String> values = new LinkedHashMap<>();
        if (video) {
            try (MediaMetadataRetriever reader = new MediaMetadataRetriever()) {
                reader.setDataSource(file.getPath());
                put(values, "GPS location", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION));
                put(values, "Capture date", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
                put(values, "Artist", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                put(values, "Author", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR));
                put(values, "Title", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
                put(values, "Writer", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER));
                put(values, "Rotation", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            } catch (IOException | RuntimeException ignored) {}
        } else {
            try {
                ExifInterface reader = new ExifInterface(file.getPath());
                float[] coordinates = new float[2];
                if (reader.getLatLong(coordinates)) {
                    put(values, "GPS location", String.format(Locale.ROOT, "%.6f, %.6f", coordinates[0], coordinates[1]));
                }
                for (Field field : IMAGE) put(values, field.label, reader.getAttribute(field.tag));
            } catch (IOException | RuntimeException ignored) {}
        }
        try { MediaCleaner.inspectBlocks(file, values); }
        catch (IOException | RuntimeException ignored) {}
        return values;
    }

    static void put(Map<String, String> values, String key, String value) {
        if (value == null || value.isBlank()) return;
        String text = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
        if (!text.isEmpty()) values.put(key, text.length() > 240 ? text.substring(0, 240) + "…" : text);
    }
}
