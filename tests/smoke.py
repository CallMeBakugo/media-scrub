"""Exercise the format engine with synthetic media. Requires Pillow, FFmpeg, and a JDK."""
import json
from pathlib import Path
import subprocess
import tempfile
import shutil
import struct
from PIL import ImageSequence, features
from PIL import Image, ImageCms, PngImagePlugin

ROOT = Path(__file__).resolve().parents[1]


def run(*command):
    return subprocess.check_output(command, text=True, stderr=subprocess.PIPE).strip()


with tempfile.TemporaryDirectory() as directory:
    work = Path(directory)
    run("javac", "--release", "17", "-d", str(work),
        str(ROOT / "src/app/mediascrub/MediaCleaner.java"), str(ROOT / "tests/RunCleaner.java"))

    def clean(before, after, strip_dates=False):
        return run("java", "-cp", str(work), "app.mediascrub.RunCleaner",
                   str(before), str(after), str(strip_dates).lower())

    def fields(file):
        return run("java", "-cp", str(work), "app.mediascrub.RunCleaner", str(file)).splitlines()

    image = Image.effect_noise((600, 400), 100).convert("RGB")
    exif = Image.Exif()
    exif[0x0112] = 6
    exif[0x010F] = "Private camera maker"
    exif[0x013B] = "Private owner"
    exif[0x0132] = "2024:01:12 13:45:55"
    exif[0x8769] = {0x9003: "2024:01:12 13:45:55", 0xA001: 1, 0xA431: "Private serial"}
    exif[0x8825] = {1: "N", 2: (40, 10, 20), 3: "W", 4: (71, 40, 21)}
    color = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    for ext in ("jpg", "png", "webp"):
        before = work / f"before.{ext}"
        after = work / f"after.{ext}"
        options = dict(exif=exif, icc_profile=color)
        if ext == "png":
            info = PngImagePlugin.PngInfo()
            info.add_text("Author", "Private author")
            options["pnginfo"] = info
        if ext == "jpg":
            options.update(progressive=True, quality=95)
        image.save(before, **options)
        assert clean(before, after) == "changed"
        first = Image.open(before)
        result = Image.open(after)
        assert first.tobytes() == result.tobytes(), ext
        assert first.info["icc_profile"] == result.info["icc_profile"], ext
        tags = result.getexif()
        assert tags[0x112] == 6 and tags[0x132] == "2024:01:12 13:45:55", ext
        assert 0x10f not in tags and 0x8825 not in tags and 0x13b not in tags, ext
        sub = tags.get_ifd(0x8769)
        assert sub[0x9003] == "2024:01:12 13:45:55" and sub[0xa001] == 1 and 0xa431 not in sub, ext
        assert "Author" not in result.info, ext
        assert "ICC color profile" in fields(before) and "ICC color profile" in fields(after), ext
        if ext == "png":
            assert "PNG text · Author" in fields(before) and "PNG text · Author" not in fields(after)
        second = work / f"second.{ext}"
        assert clean(after, second) == "clean"
        assert clean(before, second, True) == "changed"
        stripped = Image.open(second).getexif()
        assert 0x132 not in stripped and 0x9003 not in stripped.get_ifd(0x8769), ext
        print(f"PASS {ext}: GPS/owner/serial removed, identical pixels and ICC, orientation and date policy")

    jpeg = work / "before.jpg"
    motion = work / "motion.jpg"
    motion.write_bytes(jpeg.read_bytes() + b"extra motion payload")
    assert clean(motion, work / "ignored.jpg").startswith("skipped"), "Motion JPEG was not skipped"
    print("PASS JPEG with trailing media skipped")

    # The two image frames differ in color, transparency and timing.
    before, after = work / "before.gif", work / "after.gif"
    gif_frames = [Image.new("RGBA", (32, 32), (0, 200, 100, 255)), Image.new("RGBA", (32, 32), (70, 0, 200, 255))]
    gif_frames[1].putpixel((0, 0), (0, 0, 0, 0))
    gif_frames[0].save(before, save_all=True, append_images=gif_frames[1:], duration=[40, 120],
                       disposal=[1, 2], loop=3, comment=b"saved from example.test/forum")
    raw = before.read_bytes()
    before.write_bytes(raw[:-1] + b"\x21\xff\x0bXMP DataXMP\x03abc\x00" + raw[-1:])
    assert clean(before, after) == "changed"
    assert {"GIF comment", "GIF XMP packet"} <= set(fields(before))
    assert not fields(after)
    def animation(path):
        with Image.open(path) as f:
            return f.info.get("loop"), [(frame.convert("RGBA").tobytes(), frame.info["duration"], frame.disposal_method)
                                        for frame in ImageSequence.Iterator(f)]
    assert animation(before) == animation(after)
    assert clean(after, work / "again.gif") == "clean"
    print("PASS animated GIF: comments and XMP removed, loop, timing, transparency and frames retained")

    before, after = work / "before.apng", work / "after.apng"
    info = PngImagePlugin.PngInfo()
    info.add_text("Source", "example.test")
    gif_frames[0].save(before, format="PNG", save_all=True, append_images=gif_frames[1:],
                       duration=[40, 120], loop=3, pnginfo=info)
    assert clean(before, after) == "changed"
    assert "PNG text · Source" not in fields(after)
    with Image.open(before) as original, Image.open(after) as cleaned:
        assert original.info["loop"] == cleaned.info["loop"]
        assert [(f.tobytes(), f.info["duration"]) for f in ImageSequence.Iterator(original)] == [
                (f.tobytes(), f.info["duration"]) for f in ImageSequence.Iterator(cleaned)]
    print("PASS APNG: source removed, frame pixels, timing and loop retained")

    before, after = work / "iptc.jpg", work / "clean-iptc.jpg"
    def resource(resource_id, data):
        return b"8BIM" + struct.pack(">H2xI", resource_id, len(data)) + data + b"\x00" * (len(data) % 2)
    resources = b"Photoshop 3.0\x00" + resource(0x0404, b"https://example.test/thread") + resource(0x040A, b"retained")
    jpeg = (work / "before.jpg").read_bytes()
    before.write_bytes(jpeg[:2] + b"\xff\xed" + struct.pack(">H", len(resources) + 2) + resources + jpeg[2:])
    assert clean(before, after) == "changed"
    assert "Photoshop IPTC" in fields(before) and "Photoshop IPTC" not in fields(after)
    assert b"retained" in after.read_bytes() and b"example.test" not in after.read_bytes()
    assert Image.open(before).tobytes() == Image.open(after).tobytes()
    print("PASS JPEG IPTC: source field removed, other Photoshop resource and pixels retained")

    if shutil.which("magick"):
        for ext in ("psd", "psb"):
            before, after = work / f"before.{ext}", work / f"after.{ext}"
            run("magick", str(work / "before.png"), f"{ext.upper()}:{before}")
            payload = before.read_bytes()
            offset = 30 + struct.unpack_from(">I", payload, 26)[0]
            size = struct.unpack_from(">I", payload, offset)[0]
            data = resource(0x0404, b"https://example.test/photoshop")
            before.write_bytes(payload[:offset] + struct.pack(">I", size + len(data)) +
                               data + payload[offset + 4:])
            assert clean(before, after) == "changed", ext
            assert "Photoshop IPTC" in fields(before) and "Photoshop IPTC" not in fields(after), ext
            assert after.read_bytes()[offset + 4 + size:] == payload[offset + 4 + size:], ext
            def layer_pixels(path):
                return run("magick", str(path), "-format", "%[signature]\n", "info:")
            assert layer_pixels(before) == layer_pixels(after), ext
            print(f"PASS {ext}: IPTC removed with layer and image data preserved")

    for ext in ("avif", "heic"):
        before, after = work / f"before.{ext}", work / f"after.{ext}"
        if ext == "avif":
            if not features.check("avif"):
                continue
            image.thumbnail((80, 80))
            image.save(before, exif=exif)
            def pixels(path):
                return Image.open(path).tobytes()
        else:
            if not shutil.which("heif-enc") or not shutil.which("heif-convert"):
                continue
            run("heif-enc", "-o", str(before), str(work / "before.jpg"))
            def pixels(path):
                rendered = path.with_name(path.stem + "-rendered.png")
                run("heif-convert", str(path), str(rendered))
                return Image.open(rendered).tobytes()
        assert b"Private camera maker" in before.read_bytes(), ext
        assert clean(before, after) == "changed", ext
        assert b"Private camera maker" not in after.read_bytes(), ext
        assert b"2024:01:12 13:45:55" in after.read_bytes(), ext
        assert len(before.read_bytes()) == len(after.read_bytes()) and pixels(before) == pixels(after), ext
        assert clean(after, work / f"again.{ext}") == "clean", ext
        print(f"PASS {ext}: EXIF identifiers removed, date and decoded pixels retained")

    xmp = b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><source>https://example.test/thread</source></x:xmpmeta>'
    for ext in ("jp2", "jpx", "jxl"):
        before, after = work / f"before.{ext}", work / f"after.{ext}"
        if ext == "jxl":
            encoders = run("ffmpeg", "-encoders")
            if "libjxl " not in encoders:
                continue
            run("ffmpeg", "-v", "error", "-i", str(work / "before.png"), "-c:v", "libjxl", str(before))
            data = before.read_bytes()
            if data.startswith(b"\xff\x0a"):
                data = b"\x00\x00\x00\x0cJXL \x0d\x0a\x87\x0a" + b"\x00\x00\x00\x14ftypjxl \x00\x00\x00\x00jxl " + struct.pack(">I4s", len(data)+8, b"jxlc") + data
        else:
            if not features.check("jpg_2000"):
                continue
            image.save(before, format="JPEG2000")
            data = before.read_bytes()
        before.write_bytes(data + struct.pack(">I4s", len(xmp) + 8, b"xml ") + xmp)
        assert clean(before, after) == "changed", ext
        assert b"example.test" not in after.read_bytes(), ext
        assert after.read_bytes()[:len(data)] == data, ext
        if ext == "jxl":
            assert run("ffmpeg", "-v", "error", "-i", str(before), "-f", "hash", "-") == run(
                "ffmpeg", "-v", "error", "-i", str(after), "-f", "hash", "-")
        else:
            assert Image.open(before).tobytes() == Image.open(after).tobytes(), ext
        print(f"PASS {ext}: boxed XMP removed, encoded image preserved")

    for ext, flags in (("mp4", ["-movflags", "use_metadata_tags"]), ("mov", [])):
        before = work / f"before.{ext}"
        after = work / f"after.{ext}"
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=128x96:r=12:d=1",
            "-f", "lavfi", "-i", "sine=d=1", "-c:v", "libx264", "-c:a", "aac",
            "-metadata", "location=+40.1234-071.5678/", "-metadata", "artist=PrivateArtist",
            "-metadata", "creation_time=2024-01-12T13:45:55Z", *flags, str(before))
        assert clean(before, after) == "changed", ext
        for key in ("v", "a"):
            def packet_hash(path):
                return run("ffmpeg", "-v", "error", "-i", str(path), "-map", "0:" + key,
                           "-c", "copy", "-f", "hash", "-")
            assert packet_hash(before) == packet_hash(after), ext
        probe = json.loads(run("ffprobe", "-v", "error", "-show_format", "-print_format", "json", str(after)))
        assert "40.1234" not in str(probe) and "PrivateArtist" not in str(probe), ext
        assert len(before.read_bytes()) == len(after.read_bytes()), ext
        old_fields = fields(before)
        assert any("artist" in field.lower() or "©ART" in field for field in old_fields), ext
        assert not any("artist" in field.lower() or "©ART" in field for field in fields(after)), ext
        assert "2024-01-12" in str(probe), ext
        assert clean(after, work / f"second.{ext}") == "clean"
        no_dates = work / f"no-dates.{ext}"
        assert clean(before, no_dates, True) == "changed"
        probe = run("ffprobe", "-v", "error", "-show_format", "-show_streams", str(no_dates))
        assert "2024-01-12" not in probe, ext
        for key in ("v", "a"):
            assert packet_hash(before) == packet_hash(no_dates), ext
        print(f"PASS {ext}: GPS/artist removed with identical encoded video and audio")
