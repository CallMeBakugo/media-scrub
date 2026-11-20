package app.mediascrub;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

final class MediaCleaner {
    static class Unsupported extends IOException {
        Unsupported(String message) { super(message); }
    }

    private static final int MAX_METADATA = 8 * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("jpg", "jpeg", "jpe", "jfif", "png", "apng", "webp", "gif", "heic", "heif", "avif", "jp2", "jpx", "jxl", "psd", "psb", "mp4", "mov");

    static boolean supported(String name) { return FORMATS.contains(extension(name)); }

    private static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    static boolean clean(File source, File staged, boolean dates) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(source, "r");
             RandomAccessFile out = new RandomAccessFile(staged, "rw")) {
            out.setLength(0);
            switch (extension(source.getName())) {
                case "jpg": case "jpeg": case "jpe": case "jfif": return jpeg(in, out, dates);
                case "png": case "apng": return png(in, out, dates);
                case "webp": return webp(in, out, dates);
                case "psd": case "psb": return photoshopFile(in, out, null);
                case "gif": return gif(in, out, null);
                case "heic": case "heif": case "avif": return heif(in, out, dates, null);
                case "jp2": case "jpx": case "jxl": return boxedImage(in, out, null);
                case "mov": case "mp4": return video(in, out, dates);
                default: throw new Unsupported("Format");
            }
        }
    }

    static void inspectBlocks(File file, Map<String, String> values) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            switch (extension(file.getName())) {
                case "jpg": case "jpeg": case "jpe": case "jfif":
                    if (in.readUnsignedShort() != 0xffd8) return;
                    while (in.getFilePointer() < in.length() && in.readUnsignedByte() == 0xff) {
                        int marker = in.readUnsignedByte();
                        while (marker == 255) marker = in.readUnsignedByte();
                        if (marker == 0xda || marker == 0xd9) break;
                        byte[] data = bytes(in, in.readUnsignedShort() - 2);
                        if (marker == 0xe1 && prefix(data, "http://ns.adobe.com/xap/1.0/\0"))
                            values.put("XMP packet", "Present");
                        if (marker == 0xed) photoshop(data, values);
                        if (marker == 0xfe) values.put("JPEG comment", "Present");
                        if (marker == 0xe2 && prefix(data, "ICC_PROFILE\0"))
                            values.put("ICC color profile", "Present");
                    }
                    break;
                case "webp": case "png": case "apng":
                    boolean webp = extension(file.getName()).equals("webp");
                    in.seek(webp ? 12 : 8);
                    while (in.length() - in.getFilePointer() >= 8) {
                        long size = webp ? 0 : Integer.toUnsignedLong(in.readInt());
                        String type = four(in);
                        if (webp) size = uintLe(in);
                        long end = in.getFilePointer() + size + (webp ? size & 1 : 4);
                        if (end > in.length()) break;
                        if (type.equals("XMP ")) values.put("XMP packet", "Present");
                        if (type.equals("iCCP") || type.equals("ICCP")) values.put("ICC color profile", "Present");
                        if (type.equals("tIME")) values.put("PNG modified date", "Present");
                        if (Set.of("tEXt", "zTXt", "iTXt").contains(type)) {
                            byte[] keyword = bytes(in, Math.min(size, 80));
                            int n = 0;
                            while (n < keyword.length && keyword[n] != 0) n++;
                            String name = new String(keyword, 0, n, StandardCharsets.ISO_8859_1)
                                .replaceAll("[\\p{Cc}\\p{Cf}]", " ");
                            if (!name.isBlank()) values.put("PNG text · " + name, "Present");
                        }
                        in.seek(end);
                    }
                    break;
                case "psd": case "psb": photoshopFile(in, null, values); break;
                case "gif": gif(in, null, values); break;
                case "heic": case "heif": case "avif": heif(in, null, false, values); break;
                case "jp2": case "jpx": case "jxl": boxedImage(in, null, values); break;
                case "mp4": case "mov":
                    while (in.getFilePointer() < in.length()) {
                        Box atom = box(in, in.length());
                        if (atom.kind.equals("moov")) {
                            List<Box> items = new ArrayList<>();
                            videoChildren(in, atom, items, new ArrayList<>(), false, 0, values);
                            break;
                        }
                        in.seek(atom.end);
                    }
                    break;
                default: break;
            }
        }
    }

    private static byte[] bytes(RandomAccessFile file, long size) throws IOException {
        if (size < 0 || size > MAX_METADATA) throw new Unsupported("Metadata block too large");
        byte[] result = new byte[(int) size];
        file.readFully(result);
        return result;
    }

    private static void copy(RandomAccessFile in, RandomAccessFile out, long size) throws IOException {
        if (size < 0 || size > in.length() - in.getFilePointer()) throw new EOFException();
        byte[] buffer = new byte[(int) Math.min(size, 128 * 1024)];
        while (size > 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int count = (int) Math.min(size, buffer.length);
            in.readFully(buffer, 0, count);
            out.write(buffer, 0, count);
            size -= count;
        }
    }

    private static boolean prefix(byte[] data, String start) {
        byte[] expected = start.getBytes(StandardCharsets.ISO_8859_1);
        return data.length >= expected.length && Arrays.equals(data, 0, expected.length, expected, 0, expected.length);
    }

    private static String four(RandomAccessFile f) throws IOException {
        return new String(bytes(f, 4), StandardCharsets.ISO_8859_1);
    }

    private static void xmpGuard(byte[] data) throws Unsupported {
        String value = new String(data, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String marker : Set.of("gainmap", "gain-map", "hdrgm", "motionphoto", "microvideo",
                "gdepth", "dynamicdepth", "hasextendedxmp", "container:directory", "gpano",
                "apple-fi", "auxiliaryimage", "photo-sphere", "panorama", "c2pa")) {
            if (value.contains(marker)) throw new Unsupported("Media has auxiliary or display metadata");
        }
    }

    private static boolean safeXmp(byte[] contents) {
        try { xmpGuard(contents); return true; }
        catch (Unsupported retained) { return false; }
    }

    private static byte[] photoshop(byte[] payload, Map<String, String> values) {
        String prefix = "Photoshop 3.0\0";
        if (!prefix(payload, prefix)) return payload;
        return photoshopResources(payload, prefix.length(), values);
    }

    private static byte[] photoshopResources(byte[] payload, int offset, Map<String, String> values) {
        ByteBuffer data = ByteBuffer.wrap(payload);
        data.position(offset);
        ByteArrayOutputStream retained = new ByteArrayOutputStream(payload.length);
        retained.write(payload, 0, offset);
        while (data.hasRemaining()) {
            int start = data.position();
            if (data.remaining() < 12 || data.getInt() != 0x3842494d) return payload;
            int id = Short.toUnsignedInt(data.getShort());
            int nameBytes = 1 + Byte.toUnsignedInt(data.get());
            int pad = (nameBytes & 1);
            if (data.remaining() < nameBytes - 1 + pad + 4) return payload;
            data.position(data.position() + nameBytes - 1 + pad);
            int size = data.getInt();
            if (size < 0 || (long) size + (size & 1) > data.remaining()) return payload;
            int content = data.position();
            data.position(content + size + (size & 1));
            boolean iptc = id == 0x0404 || id == 0x0425;
            boolean xmp = id == 0x0424;
            if (values != null && (iptc || xmp))
                values.put(iptc ? "Photoshop IPTC" : "Photoshop XMP", "Present");
            if (iptc || (xmp && safeXmp(Arrays.copyOfRange(payload, content, content + size)))) continue;
            retained.write(payload, start, data.position() - start);
        }
        return retained.size() == offset ? null : retained.toByteArray();
    }

    private static boolean photoshopFile(RandomAccessFile in, RandomAccessFile out, Map<String, String> values)
        throws IOException {
        if (!four(in).equals("8BPS")) throw new Unsupported("Photoshop file header");
        int version = in.readUnsignedShort();
        if ((version != 1 && version != 2) || in.length() < 34) throw new Unsupported("Photoshop file version");
        in.seek(26);
        long colorData = Integer.toUnsignedLong(in.readInt());
        copyOrSkip(in, null, colorData);
        long offset = in.getFilePointer();
        byte[] original = bytes(in, Integer.toUnsignedLong(in.readInt()));
        long end = in.getFilePointer();
        byte[] cleaned = photoshopResources(original, 0, values);
        if (cleaned == null) cleaned = new byte[0];
        if (out == null || Arrays.equals(cleaned, original)) return false;
        in.seek(0);
        copy(in, out, offset);
        out.writeInt(cleaned.length);
        out.write(cleaned);
        in.seek(end);
        copy(in, out, in.length() - end);
        return true;
    }

    private static boolean gif(RandomAccessFile in, RandomAccessFile out, Map<String, String> values)
        throws IOException {
        byte[] header = bytes(in, 13);
        if (!prefix(header, "GIF87a") && !prefix(header, "GIF89a")) throw new Unsupported("GIF header");
        if (out != null) out.write(header);
        if ((header[10] & 128) != 0) copyOrSkip(in, out, 3L << ((header[10] & 7) + 1));
        boolean changed = false;
        boolean image = false;
        while (in.getFilePointer() < in.length()) {
            long start = in.getFilePointer();
            int marker = in.readUnsignedByte();
            if (marker == 0x3b) {
                if (!image || in.getFilePointer() != in.length()) throw new Unsupported("GIF trailer");
                if (out != null) out.write(marker);
                return changed;
            }
            String field = null;
            if (marker == 0x2c) {
                byte[] descriptor = bytes(in, 9);
                if ((descriptor[8] & 128) != 0) copyOrSkip(in, null, 3L << ((descriptor[8] & 7) + 1));
                in.readUnsignedByte();
                image = true;
            } else if (marker == 0x21) {
                int tag = in.readUnsignedByte();
                if (tag == 0xfe) field = "GIF comment";
                if (tag == 0xff) {
                    int size = in.readUnsignedByte();
                    if (size != 11) throw new Unsupported("GIF application header");
                    byte[] application = bytes(in, size);
                    if (prefix(application, "XMP DataXMP")) field = "GIF XMP packet";
                }
            } else throw new Unsupported("Unexpected GIF block");
            for (int size; (size = in.readUnsignedByte()) != 0;) copyOrSkip(in, null, size);
            long end = in.getFilePointer();
            if (field != null) {
                if (values != null) values.put(field, "Present");
                changed = true;
            } else if (out != null) {
                in.seek(start);
                copy(in, out, end - start);
            }
        }
        throw new EOFException();
    }

    private static void copyOrSkip(RandomAccessFile in, RandomAccessFile out, long size) throws IOException {
        if (size > in.length() - in.getFilePointer()) throw new EOFException();
        if (out == null) in.seek(in.getFilePointer() + size);
        else copy(in, out, size);
    }

    private static boolean jpeg(RandomAccessFile in, RandomAccessFile out, boolean dates) throws IOException {
        if (in.readUnsignedShort() != 0xffd8) throw new Unsupported("JPEG header");
        out.writeShort(0xffd8);
        boolean changed = false;
        boolean scan = false;
        while (in.getFilePointer() < in.length()) {
            if (in.readUnsignedByte() != 0xff) throw new Unsupported("JPEG marker");
            int marker;
            int fill = 1;
            while ((marker = in.readUnsignedByte()) == 0xff) fill++;
            if (marker == 0xd9) {
                if (!scan || in.getFilePointer() != in.length()) throw new Unsupported("JPEG contains trailing or auxiliary data");
                while (fill-- > 0) out.write(0xff);
                out.write(marker);
                return changed;
            }
            if (marker < 0xc0 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) throw new Unsupported("JPEG marker");
            byte[] payload = bytes(in, in.readUnsignedShort() - 2);
            byte[] next = payload;
            if (marker == 0xe1) {
                if (prefix(payload, "Exif\0\0")) {
                    byte[] tiff = exif(Arrays.copyOfRange(payload, 6, payload.length), dates);
                    next = tiff == null ? null : new byte[tiff.length + 6];
                    if (next != null) {
                        System.arraycopy(payload, 0, next, 0, 6);
                        System.arraycopy(tiff, 0, next, 6, tiff.length);
                    }
                } else if (prefix(payload, "http://ns.adobe.com/xap/1.0/\0")) {
                    if (safeXmp(payload)) next = null;
                }
            }
            if (marker == 0xe2 && !prefix(payload, "ICC_PROFILE\0"))
                throw new Unsupported("JPEG contains multiple images or auxiliary data");
            if (marker >= 0xe3 && marker <= 0xef && marker != 0xed && marker != 0xee)
                throw new Unsupported("JPEG contains unrecognized application metadata");
            if (marker == 0xed) next = photoshop(payload, null);
            if (marker == 0xfe) next = null;
            if (next != null) {
                while (fill-- > 0) out.write(0xff);
                out.write(marker);
                out.writeShort(next.length + 2);
                out.write(next);
            }
            changed |= !Arrays.equals(payload, next);
            if (marker == 0xda) {
                scan = true;
                copyScan(in, out);
            }
        }
        throw new EOFException();
    }

    private static void copyScan(RandomAccessFile in, RandomAccessFile out) throws IOException {
        byte[] block = new byte[65536];
        blocks: while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int n = in.read(block);
            if (n == -1) throw new EOFException();
            for (int i = 0; i < n; i++) {
                if ((block[i] & 255) != 255) continue;
                long start = in.getFilePointer() - n + i;
                int j = i + 1;
                while (j < n && (block[j] & 255) == 255) j++;
                if (j == n) {
                    out.write(block, 0, i);
                    in.seek(start);
                    int marker;
                    int count = 0;
                    do { marker = in.readUnsignedByte(); count++; } while (marker == 255);
                    if (marker == 0 || (marker >= 0xd0 && marker <= 0xd7)) {
                        while (--count > 0) out.write(255);
                        out.write(marker);
                        continue blocks;
                    }
                    in.seek(start);
                    return;
                }
                int marker = block[j] & 255;
                if (marker != 0 && (marker < 0xd0 || marker > 0xd7)) {
                    out.write(block, 0, i);
                    in.seek(start);
                    return;
                }
                i = j;
            }
            out.write(block, 0, n);
        }
    }

    private static boolean png(RandomAccessFile in, RandomAccessFile out, boolean dates) throws IOException {
        long signature = in.readLong();
        if (signature != 0x89504e470d0a1a0aL) throw new Unsupported("PNG header");
        out.writeLong(signature);
        boolean changed = false;
        boolean image = false;
        while (in.getFilePointer() < in.length()) {
            long size = Integer.toUnsignedLong(in.readInt());
            String type = four(in);
            if (size > in.length() - in.getFilePointer() - 4) throw new EOFException();
            if (type.equals("caBX")) throw new Unsupported("PNG provenance");
            if (Set.of("tEXt", "zTXt", "iTXt", "eXIf").contains(type) || (dates && type.equals("tIME"))) {
                byte[] before = bytes(in, size);
                validatePng(type, before, in.readInt());
                byte[] after = type.equals("eXIf") ? exif(before, dates) : null;
                // Text may include a description or identifying editing history.
                if (after != null) pngChunk(out, type, after);
                changed |= !Arrays.equals(before, after);
            } else {
                out.writeInt((int) size);
                out.writeBytes(type);
                copy(in, out, size + 4);
            }
            image |= type.equals("IDAT");
            if (type.equals("IEND")) {
                if (!image || size != 0 || in.getFilePointer() != in.length()) throw new Unsupported("PNG structure");
                return changed;
            }
        }
        throw new EOFException();
    }

    private static int pngCrc(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        return (int) crc.getValue();
    }

    private static void validatePng(String type, byte[] data, int expected) throws IOException {
        if (pngCrc(type, data) != expected) throw new IOException("PNG metadata CRC mismatch");
    }

    private static void pngChunk(RandomAccessFile out, String type, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.writeBytes(type);
        out.write(data);
        out.writeInt(pngCrc(type, data));
    }

    private static boolean webp(RandomAccessFile in, RandomAccessFile out, boolean dates) throws IOException {
        if (!four(in).equals("RIFF") || uintLe(in) + 8 != in.length() || !four(in).equals("WEBP"))
            throw new Unsupported("WebP header");
        out.writeBytes("RIFF");
        out.writeInt(0);
        out.writeBytes("WEBP");
        boolean changed = false;
        boolean hasImage = false;
        boolean hasExif = false;
        boolean hasXmp = false;
        long flags = -1;
        while (in.getFilePointer() < in.length()) {
            String type = four(in);
            long size = uintLe(in);
            long padded = size + (size & 1);
            if (padded > in.length() - in.getFilePointer()) throw new EOFException();
            if (type.equals("XMP ") || type.equals("EXIF")) {
                byte[] before = bytes(in, size);
                if ((size & 1) != 0) in.readByte();
                byte[] after = type.equals("EXIF")
                    ? exif(prefix(before, "Exif\0\0") ? Arrays.copyOfRange(before, 6, before.length) : before, dates)
                    : safeXmp(before) ? null : before;
                if (after != null) {
                    out.writeBytes(type);
                    writeLe(out, after.length);
                    out.write(after);
                    if ((after.length & 1) != 0) out.write(0);
                    hasExif |= type.equals("EXIF");
                    hasXmp |= type.equals("XMP ");
                }
                changed |= !Arrays.equals(before, after);
            } else {
                out.writeBytes(type);
                writeLe(out, size);
                if (type.equals("VP8X")) {
                    if (size != 10 || flags >= 0) throw new Unsupported("WebP extended header");
                    flags = out.getFilePointer();
                }
                copy(in, out, padded);
            }
            hasImage |= Set.of("VP8 ", "VP8L", "ANMF").contains(type);
        }
        if (!hasImage) throw new Unsupported("WebP has no image");
        if (changed) {
            long size = out.length();
            if (flags >= 0) {
                out.seek(flags);
                int old = out.readUnsignedByte();
                out.seek(flags);
                out.write((old & ~12) | (hasExif ? 8 : 0) | (hasXmp ? 4 : 0));
            }
            out.seek(4);
            writeLe(out, size - 8);
        } else {
            out.seek(4);
            writeLe(out, out.length() - 8);
        }
        return changed;
    }

    private static long uintLe(RandomAccessFile in) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
    }

    private static void writeLe(RandomAccessFile out, long value) throws IOException {
        out.writeInt(Integer.reverseBytes((int) value));
    }

    private static final class Tags {
        final List<Integer> ids = new ArrayList<>();
        final List<Integer> types = new ArrayList<>();
        final List<byte[]> values = new ArrayList<>();
        void add(int id, int type, byte[] value) { ids.add(id); types.add(type); values.add(value); }
        int tableSize() { return 6 + 12 * ids.size(); }
    }

    private static byte[] exif(byte[] data, boolean stripDates) throws IOException {
        if (data.length < 8 || (data[0] != 'I' && data[0] != 'M') || data[0] != data[1])
            throw new Unsupported("EXIF header");
        try {
            ByteBuffer in = ByteBuffer.wrap(data).order(data[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            if (in.getShort(2) != 42) throw new Unsupported("EXIF TIFF version");
            Tags root = new Tags();
            Tags sub = new Tags();
            int exifOffset = collect(in, in.getInt(4), root, true, stripDates);
            if (exifOffset != 0) collect(in, exifOffset, sub, false, stripDates);
            if (root.ids.isEmpty() && sub.ids.isEmpty()) return null;
            if (!sub.ids.isEmpty()) {
                byte[] pointer = ByteBuffer.allocate(4).order(in.order()).putInt(8 + root.tableSize() + 12).array();
                root.add(0x8769, 4, pointer);
            }
            int extra = 8 + root.tableSize() + (sub.ids.isEmpty() ? 0 : sub.tableSize());
            ByteBuffer out = ByteBuffer.allocate(extra + 256).order(in.order());
            out.put(data[0]).put(data[1]).putShort((short) 42).putInt(8);
            int end = putTags(out, root, extra);
            if (!sub.ids.isEmpty()) end = putTags(out, sub, end);
            return Arrays.copyOf(out.array(), end);
        } catch (IndexOutOfBoundsException | IllegalArgumentException invalid) {
            throw new Unsupported("Invalid EXIF offsets");
        }
    }

    private static int collect(ByteBuffer in, int start, Tags tags, boolean main, boolean stripDates) throws IOException {
        if (start < 8) throw new Unsupported("Invalid EXIF directory");
        int count = Short.toUnsignedInt(in.getShort(start));
        if (start + 6L + count * 12L > in.capacity()) throw new Unsupported("Truncated EXIF directory");
        int pointer = 0;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int position = start + 2 + i * 12;
            int id = Short.toUnsignedInt(in.getShort(position));
            int type = Short.toUnsignedInt(in.getShort(position + 2));
            int length = in.getInt(position + 4);
            if (!seen.add(id)) throw new Unsupported("Duplicate EXIF tag");
            if (main && id == 0x8769 && type == 4 && length == 1) pointer = in.getInt(position + 8);
            if (((main && id == 0x112) || (!main && id == 0xa001)) && type == 3 && length == 1) {
                tags.add(id, type, new byte[]{in.get(position + 8), in.get(position + 9)});
            }
            boolean isDate = main ? id == 0x132 : id == 0x9003 || id == 0x9004;
            if (!stripDates && isDate && type == 2 && length == 20) {
                int offset = in.getInt(position + 8);
                byte[] value = new byte[20];
                in.duplicate().position(offset).get(value);
                String date = new String(value, StandardCharsets.US_ASCII);
                if (date.matches("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\x00"))
                    tags.add(id, type, value);
            }
        }
        return pointer;
    }

    private static int putTags(ByteBuffer out, Tags tags, int offset) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < tags.ids.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt(tags.ids::get));
        out.putShort((short) order.size());
        for (int i : order) {
            byte[] value = tags.values.get(i);
            int type = tags.types.get(i);
            out.putShort(tags.ids.get(i).shortValue()).putShort((short) type)
                .putInt(type == 2 ? value.length : 1);
            if (value.length > 4) {
                out.putInt(offset);
                ByteBuffer view = out.duplicate();
                view.position(offset).put(value);
                offset += value.length;
            } else {
                out.put(value);
                for (int n = value.length; n < 4; n++) out.put((byte) 0);
            }
        }
        out.putInt(0);
        return offset;
    }

    private static final Set<String> VIDEO_TAGS = Set.of("©xyz", "©mak", "©mod", "©ART", "aART",
        "©aut", "©cmt", "©cpy", "cprt", "loci", "auth", "©wrt", "©too", "©nam", "desc", "ldes");

    private record Box(long start, long data, long end, String kind) {}
    private record Span(long start, int size) {}

    private static Box box(RandomAccessFile in, long parentEnd) throws IOException {
        long start = in.getFilePointer();
        if (parentEnd - start < 8) throw new Unsupported("Incomplete video box");
        long size = Integer.toUnsignedLong(in.readInt());
        String kind = four(in);
        if (size == 1) size = in.readLong();
        if (size == 0) size = parentEnd - start;
        long data = in.getFilePointer();
        if (size < data - start || size > parentEnd - start) throw new Unsupported("Invalid video box");
        return new Box(start, data, start + size, kind);
    }

    private record Extent(long start, long size) {}
    private record Rewrite(long offset, byte[] bytes) {}

    private static boolean heif(RandomAccessFile in, RandomAccessFile out, boolean dates,
        Map<String, String> values) throws IOException {
        Box header = box(in, in.length());
        if (!header.kind.equals("ftyp")) throw new Unsupported("HEIF file type header");
        in.seek(header.end);
        Box meta = null;
        List<Box> payloads = new ArrayList<>();
        while (in.getFilePointer() < in.length()) {
            Box item = box(in, in.length());
            if (item.kind.equals("moov") || item.kind.equals("moof"))
                throw new Unsupported("Timed HEIF or AVIF images");
            if (item.kind.equals("meta")) {
                if (meta != null) throw new Unsupported("Multiple HEIF metadata boxes");
                meta = item;
            }
            if (item.kind.equals("mdat")) payloads.add(item);
            in.seek(item.end);
        }
        if (meta == null || meta.end - meta.data < 4) throw new Unsupported("HEIF metadata header");
        in.seek(meta.data);
        if (in.readInt() != 0) throw new Unsupported("HEIF metadata version");
        Map<Long, String> types = new HashMap<>();
        Box locations = null;
        Box inline = null;
        while (in.getFilePointer() < meta.end) {
            Box item = box(in, meta.end);
            if (item.kind.equals("iinf")) heifInfo(in, item, types);
            if (item.kind.equals("iloc")) {
                if (locations != null) throw new Unsupported("Duplicate HEIF locations");
                locations = item;
            }
            if (item.kind.equals("idat")) {
                if (inline != null) throw new Unsupported("Duplicate HEIF inline data");
                inline = item;
            }
            in.seek(item.end);
        }
        if (!types.containsValue("Exif")) return false;
        if (values != null) {
            values.put("HEIF EXIF item", "Present");
            return false;
        }
        if (locations == null) throw new Unsupported("No HEIF item locations");
        in.seek(locations.data);
        Map<Long, List<Extent>> extents = heifLocations(ByteBuffer.wrap(bytes(in, locations.end - locations.data)), inline, payloads);
        List<Rewrite> patches = new ArrayList<>();
        boolean checked = false;
        long bytesToRewrite = 0;
        for (var type : types.entrySet()) {
            if (!type.getValue().equals("Exif")) continue;
            List<Extent> item = extents.getOrDefault(type.getKey(), List.of());
            if (item.size() != 1) continue;
            Extent target = item.get(0);
            boolean shared = extents.entrySet().stream().filter(e -> !e.getKey().equals(type.getKey()))
                .flatMap(e -> e.getValue().stream()).anyMatch(e ->
                    e.start < target.start + target.size && target.start < e.start + e.size);
            if (shared || target.size < 18 || target.size > MAX_METADATA) continue;
            in.seek(target.start);
            byte[] original = bytes(in, target.size);
            int offset = ByteBuffer.wrap(original).getInt();
            if (offset < 0 || offset > original.length - 12) continue;
            byte[] tiff = Arrays.copyOfRange(original, offset + 4, original.length);
            byte[] cleaned;
            try { cleaned = exif(tiff, dates); }
            catch (Unsupported invalid) { continue; }
            if (cleaned == null) {
                cleaned = ByteBuffer.allocate(14).order(tiff[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN)
                    .put(tiff[0]).put(tiff[1]).putShort((short)42).putInt(8).putShort((short)0).putInt(0).array();
            }
            if (cleaned.length + 4 > original.length) continue;
            byte[] replacement = new byte[original.length];
            System.arraycopy(cleaned, 0, replacement, 4, cleaned.length);
            checked = true;
            if (!Arrays.equals(original, replacement)) {
                bytesToRewrite += replacement.length;
                if (bytesToRewrite > 2L * MAX_METADATA) throw new Unsupported("HEIF EXIF data too large");
                patches.add(new Rewrite(target.start, replacement));
            }
        }
        if (!checked) throw new Unsupported("No safely writable HEIF EXIF item");
        if (patches.isEmpty()) return false;
        in.seek(0);
        copy(in, out, in.length());
        for (Rewrite patch : patches) { out.seek(patch.offset); out.write(patch.bytes); }
        return true;
    }

    private static void heifInfo(RandomAccessFile in, Box info, Map<Long, String> types) throws IOException {
        if (info.end - info.data < 6) throw new Unsupported("HEIF item information");
        int version = in.readInt() >>> 24;
        long count = version == 0 ? in.readUnsignedShort() : version == 1 ? Integer.toUnsignedLong(in.readInt()) : -1;
        if (count < 0 || count > 4096) throw new Unsupported("HEIF item count");
        for (long n = 0; n < count; n++) {
            Box entry = box(in, info.end);
            if (entry.kind.equals("infe") && entry.end - entry.data >= 12) {
                int entryVersion = in.readInt() >>> 24;
                if (entryVersion == 2 || (entryVersion == 3 && entry.end - entry.data >= 14)) {
                    long id = entryVersion == 2 ? in.readUnsignedShort() : Integer.toUnsignedLong(in.readInt());
                    int protection = in.readUnsignedShort();
                    String type = four(in);
                    if (types.putIfAbsent(id, protection == 0 ? type : "") != null)
                        throw new Unsupported("Duplicate HEIF item");
                }
            }
            in.seek(entry.end);
        }
    }

    private static Map<Long, List<Extent>> heifLocations(ByteBuffer data, Box inline, List<Box> payloads)
        throws IOException {
        Map<Long, List<Extent>> map = new HashMap<>();
        try {
            int version = data.getInt() >>> 24;
            if (version > 2) throw new Unsupported("HEIF location version");
            int widths = Short.toUnsignedInt(data.getShort());
            int offset = widths >>> 12, length = (widths >>> 8) & 15, base = (widths >>> 4) & 15;
            int index = version == 0 ? 0 : widths & 15;
            long count = number(data, version == 2 ? 4 : 2);
            if (count > 4096 || length == 0) throw new Unsupported("HEIF location layout");
            for (long n = 0; n < count; n++) {
                long id = number(data, version == 2 ? 4 : 2);
                int method = version == 0 ? 0 : data.getShort() & 15;
                if (data.getShort() != 0 || method > 1 || (method == 1 && inline == null))
                    throw new Unsupported("Indirect HEIF data");
                long origin = number(data, base);
                if (method == 1) origin = Math.addExact(origin, inline.data);
                int entries = Short.toUnsignedInt(data.getShort());
                List<Extent> spans = new ArrayList<>();
                for (int nExtent = 0; nExtent < entries; nExtent++) {
                    number(data, index);
                    long start = Math.addExact(origin, number(data, offset));
                    long size = number(data, length);
                    boolean inside = method == 1 ? contains(inline, start, size) :
                        payloads.stream().anyMatch(box -> contains(box, start, size));
                    if (!inside) throw new Unsupported("HEIF extent outside media data");
                    if (size > 0) spans.add(new Extent(start, size));
                }
                if (map.putIfAbsent(id, spans) != null) throw new Unsupported("Duplicate HEIF location");
            }
        } catch (BufferUnderflowException | ArithmeticException invalid) {
            throw new Unsupported("Invalid HEIF item offsets");
        }
        return map;
    }

    private static boolean contains(Box box, long offset, long size) {
        return offset >= box.data && offset <= box.end && size <= box.end - offset;
    }

    private static long number(ByteBuffer data, int width) throws IOException {
        if (width > 8) throw new Unsupported("HEIF offset width");
        long value = 0;
        for (int i = 0; i < width; i++) value = (value << 8) | Byte.toUnsignedInt(data.get());
        if (value < 0) throw new Unsupported("HEIF offset overflow");
        return value;
    }

    private static boolean boxedImage(RandomAccessFile in, RandomAccessFile out, Map<String, String> values)
        throws IOException {
        Box header = box(in, in.length());
        if (!(header.kind.equals("jP  ") || header.kind.equals("JXL ")) ||
            header.end - header.data != 4 || in.readInt() != 0x0d0a870a)
            throw new Unsupported("Unsupported image container");
        List<Box> remove = new ArrayList<>();
        while (in.getFilePointer() < in.length()) {
            Box item = box(in, in.length());
            if (item.kind.equals("xml ") && item.end - item.data <= MAX_METADATA) {
                byte[] xml = bytes(in, item.end - item.data);
                if (values != null) values.put("XML metadata box", "Present");
                String text = new String(xml, StandardCharsets.UTF_8);
                if ((text.contains("adobe:ns:meta/") || text.contains("adobe.com/xap/")) && safeXmp(xml))
                    remove.add(item);
            }
            in.seek(item.end);
        }
        if (out == null || remove.isEmpty()) return false;
        in.seek(0);
        copy(in, out, in.length());
        byte[] zero = new byte[16384];
        for (Box item : remove) {
            out.seek(item.start + 4);
            out.writeBytes("free");
            out.seek(item.data);
            for (long left = item.end - item.data; left > 0;) {
                int size = (int) Math.min(left, zero.length);
                out.write(zero, 0, size);
                left -= size;
            }
        }
        return true;
    }

    private static boolean video(RandomAccessFile in, RandomAccessFile out, boolean dates) throws IOException {
        List<Box> redact = new ArrayList<>();
        List<Span> times = new ArrayList<>();
        boolean movie = false;
        boolean media = false;
        while (in.getFilePointer() < in.length()) {
            Box atom = box(in, in.length());
            if (atom.kind.equals("moov")) {
                if (movie) throw new Unsupported("Multiple video movie headers");
                movie = true;
                videoChildren(in, atom, redact, times, dates, 0, null);
            }
            if (Set.of("moof", "uuid", "mfra", "meta", "XMP_", "xml ").contains(atom.kind))
                throw new Unsupported("Fragmented or auxiliary video metadata");
            if (atom.kind.equals("mdat")) media = true;
            in.seek(atom.end);
        }
        if (!movie || !media) throw new Unsupported("Video structure");
        if (redact.isEmpty() && times.isEmpty()) return false;
        in.seek(0);
        copy(in, out, in.length());
        byte[] zeros = new byte[16384];
        for (Box atom : redact) {
            if (atom.end - atom.data > MAX_METADATA) throw new Unsupported("Video metadata block too large");
            out.seek(atom.start + 4);
            out.writeBytes("free");
            out.seek(atom.data);
            long remaining = atom.end - atom.data;
            while (remaining > 0) {
                int n = (int) Math.min(remaining, zeros.length);
                out.write(zeros, 0, n);
                remaining -= n;
            }
        }
        for (Span time : times) {
            out.seek(time.start);
            out.write(zeros, 0, time.size);
        }
        return true;
    }

    private static void videoChildren(RandomAccessFile in, Box parent, List<Box> redact, List<Span> times,
        boolean dates, int depth, Map<String, String> inspection)
        throws IOException {
        if (depth > 8) throw new Unsupported("Video nesting too deep");
        Map<Integer, String> keys = new HashMap<>();
        List<Box> lists = new ArrayList<>();
        if (parent.kind.equals("meta")) {
            if (in.readInt() != 0) throw new Unsupported("QuickTime metadata layout");
            if (in.getFilePointer() > parent.end) throw new Unsupported("Incomplete metadata box");
        }
        while (in.getFilePointer() < parent.end) {
            Box atom = box(in, parent.end);
            if (inspection != null && (VIDEO_TAGS.contains(atom.kind) || atom.kind.equals("©day")))
                inspection.put("Video tag · " + atom.kind, "Present");
            if (VIDEO_TAGS.contains(atom.kind) || (dates && atom.kind.equals("©day"))) redact.add(atom);
            else if (Set.of("udta", "trak", "meta", "mdia").contains(atom.kind))
                videoChildren(in, atom, redact, times, dates, depth + 1, inspection);
            else if (dates && Set.of("mvhd", "tkhd", "mdhd").contains(atom.kind)) {
                if (atom.end - atom.data < 4) throw new Unsupported("Video time fields");
                int version = in.readInt() >>> 24;
                int length = version == 0 ? 8 : version == 1 ? 16 : 0;
                if (length == 0 || atom.end - in.getFilePointer() < length) throw new Unsupported("Video time fields");
                long offset = in.getFilePointer();
                byte[] value = bytes(in, length);
                if (!Arrays.equals(value, new byte[length])) times.add(new Span(offset, length));
            } else if (parent.kind.equals("mdia") && atom.kind.equals("hdlr")) {
                if (atom.end - atom.data < 12) throw new Unsupported("Incomplete video track");
                in.seek(atom.data + 8);
                if (!Set.of("vide", "soun", "text", "sbtl", "subt", "clcp").contains(four(in)))
                    throw new Unsupported("Video contains an auxiliary or timed data track");
            } else if (atom.kind.equals("keys")) readKeys(in, atom, keys);
            else if (atom.kind.equals("ilst")) lists.add(atom);
            else if (Set.of("uuid", "XMP_", "xml ", "----").contains(atom.kind))
                throw new Unsupported("Unrecognized video metadata");
            in.seek(atom.end);
        }
        for (Box list : lists) {
            in.seek(list.data);
            while (in.getFilePointer() < list.end) {
                Box atom = box(in, list.end);
                int key = ByteBuffer.wrap(atom.kind.getBytes(StandardCharsets.ISO_8859_1)).getInt();
                String name = keys.getOrDefault(key, "").toLowerCase(Locale.ROOT);
                boolean identifying = name.contains("location") || name.contains("serial")
                    || name.contains("camera.identifier") || name.matches("(?:.*\\.)?(?:make|model|artist|author|comment|description|software|title|owner|encoder|copyright)");
                if (inspection != null && (!name.isEmpty() || VIDEO_TAGS.contains(atom.kind) || atom.kind.equals("©day")))
                    inspection.put("Video tag · " + (name.isEmpty() ? atom.kind : name), "Present");
                if (VIDEO_TAGS.contains(atom.kind) || identifying || (dates && (atom.kind.equals("©day") || (name.contains("creationdate") || name.equals("creation_time")))))
                    redact.add(atom);
                else if (atom.kind.equals("----")) throw new Unsupported("Custom video metadata");
                in.seek(atom.end);
            }
        }
    }

    private static void readKeys(RandomAccessFile in, Box atom, Map<Integer, String> keys) throws IOException {
        if (atom.end - atom.data < 8 || in.readInt() != 0) throw new Unsupported("Video key list");
        int count = in.readInt();
        if (count < 0 || count > 4096) throw new Unsupported("Video key count");
        for (int i = 1; i <= count; i++) {
            Box key = box(in, atom.end);
            keys.put(i, new String(bytes(in, key.end - key.data), StandardCharsets.UTF_8));
        }
    }
}
