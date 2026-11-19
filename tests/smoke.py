"""Exercise the format engine with synthetic media. Requires Pillow, FFmpeg, and a JDK."""
import json
from pathlib import Path
import subprocess
import tempfile
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
        assert "2024-01-12" in str(probe), ext
        assert clean(after, work / f"second.{ext}") == "clean"
        no_dates = work / f"no-dates.{ext}"
        assert clean(before, no_dates, True) == "changed"
        probe = run("ffprobe", "-v", "error", "-show_format", "-show_streams", str(no_dates))
        assert "2024-01-12" not in probe, ext
        for key in ("v", "a"):
            assert packet_hash(before) == packet_hash(no_dates), ext
        print(f"PASS {ext}: GPS/artist removed with identical encoded video and audio")
