# Media Scrub

Media Scrub removes common identifying metadata from shared photos and videos in an Android profile. It runs locally and has no network permission. It processes common JPEG, PNG/APNG, WebP, GIF, HEIF/HEIC, AVIF, JPEG 2000, JPEG XL, Photoshop, MP4, and MOV layouts. The cleaner copies encoded media directly and keeps the same filename and folder. During a write it stages a hidden temporary file beside the original and replaces the file only if that operation is supported.

## Try it on GrapheneOS

1. Install `media-scrub.apk` from the GitHub release. In Obtainium, add the repository URL to receive updates.
2. Create a folder such as `Pictures/MediaScrubTest`. Put disposable copies of several images and videos in it. Keep your comparison originals outside the folder.
3. Open Media Scrub and tap **Android storage settings**. If your GrapheneOS build offers **Storage Scopes** for the app, add only that test folder. Standard Android offers **All files access**. The app uses direct file access for safe replacement, so selecting a folder in the file picker alone does not give it that access.
4. In the app, tap **Choose test folder** and select the same directory. Run **Preview scan** to see what would change. Then tap **Clean now** and compare the files with your originals. Check playback, orientation, and file count. **Cleaned and verified** means the app reopened the saved file and matched its SHA-256 hash against the cleaned output. For an independent metadata check on your computer, compare the before and after files with `exiftool -a -G1 -s before.jpg after.jpg` (use the actual filenames, including for video).
5. After the manual test, turn on **Clean automatically** and add another tagged file. The background scan is scheduled about every 15 minutes and can run later under Android battery or storage restrictions. Tap **Clean now** for an immediate pass. Files recently modified are deferred so that camera writes and downloads have time to finish.

The default date setting keeps common embedded capture dates. The date switch removes the date fields this release supports. Filesystem timestamps and names can still expose dates. Shared internal storage is scanned within the selected profile and permission scope. **Use shared storage** scans all visible shared folders in that profile.

## Inspect one file

Tap **Inspect one file** and select a photo or video. The report compares recognized fields on the current file with a cleanup preview, using your current date setting. Bold rows show the differences. Some blocks are listed by name without displaying their contents. The preview leaves the selected file untouched.

After cleaning the folder, tap **Recheck the inspected file** to compare the first read with a fresh read of the saved file. The comparison is kept for the current app screen. If Android revokes the picker link after a replacement, select the file again to inspect its current metadata. The optional inspector accepts files up to 512 MiB.

## Format coverage

- **JPEG, PNG/APNG, WebP.** Removes common EXIF identifiers, standalone XMP where safe, JPEG comments, PNG text, and recognized JPEG Photoshop IPTC resources. Preserves encoded pixels, orientation, ICC profiles, and APNG frame and timing data. JPEGs with motion, gain maps, or other auxiliary layouts may be skipped.
- **GIF.** Removes comments and standard XMP application blocks. Copies frames, palettes, timing, disposal, transparency, and loop extensions.
- **HEIF/HEIC and AVIF.** Removes identifying EXIF fields from supported still-image layouts. Rewrites only unshared, contiguous EXIF data. XMP, auxiliary image data, and other container fields are outside this cleanup.
- **JP2/JPX and JPEG XL.** Removes recognized XMP from top-level XML boxes in supported containers. Other boxes and raw JPEG XL codestreams are outside this cleanup.
- **PSD and PSB.** Removes recognized IPTC and XMP resources. Layer data and image data are copied unchanged. Alternate resource layouts may be skipped.
- **MP4 and MOV.** Clears common identifying container fields such as location, make, model, author, and comments. Encoded audio and video, rotation, and timing are preserved. Some fragmented and proprietary layouts and auxiliary tracks are skipped.

Each format supports a different subset of metadata. **No supported changes** means the cleaner made no changes under the current settings. URLs and other identifiers can remain in unhandled metadata, filenames, sidecar files, or visible content. TIFF, camera RAW, SVG, ICO, and WebM/MKV are outside this release's cleanup coverage.

Android may maintain its own gallery database and thumbnails. Media Scrub requests a rescan after replacement. Test how your gallery and backup apps handle the update on your handset before broadening the folder scope.

## Build and check

The app requires Android 11 or newer. The release build uses OpenJDK 21, Android SDK Platform 36, Android Build Tools 36.0.0, and Info-ZIP. Set `ANDROID_HOME` and run `./build.sh --unsigned media-scrub-unsigned.apk` to rebuild without a signing key. For a signed release, set `MEDIA_SCRUB_KEYSTORE` to an absolute path to a keystore with the `media-scrub` alias and run `./build.sh`. Keep the release key for in-place updates.

Run `python3 tests/smoke.py` with a JDK, Pillow, and FFmpeg. The HEIC and Photoshop cases also use libheif and ImageMagick when available. Fixtures verify removal and preservation using decoded pixels, animation properties, color profiles, or encoded streams, depending on format.

The code and original project artwork are available under the [MIT license](LICENSE).
