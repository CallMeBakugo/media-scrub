# Media Scrub

Media Scrub removes common identifying metadata from shared photos and videos in an Android profile. It runs locally and has no network permission. It processes JPEG, PNG, WebP, and common MP4 and MOV layouts. The cleaner copies encoded media directly and keeps the same filename and folder. During a write it stages a hidden temporary file beside the original and replaces the file only if that operation is supported.

## Try it on GrapheneOS

1. Install `media-scrub.apk` from the GitHub release. In Obtainium, add the repository URL to receive updates.
2. Create a folder such as `Pictures/MediaScrubTest`. Put disposable copies of several images and videos in it. Keep your comparison originals outside the folder.
3. Open Media Scrub and tap **Android storage settings**. If your GrapheneOS build offers **Storage Scopes** for the app, add only that test folder. Standard Android offers **All files access**. The app uses direct file access for safe replacement, so selecting a folder in the file picker alone does not give it that access.
4. In the app, tap **Choose test folder** and select the same directory. Run **Preview scan** to see what would change. Then tap **Clean now** and compare the files with your originals. Check playback, orientation, and file count. **Cleaned and verified** means the app reopened the saved file and matched its SHA-256 hash against the cleaned output. For an independent metadata check on your computer, compare the before and after files with `exiftool -a -G1 -s before.jpg after.jpg` (use the actual filenames, including for video).
5. After the manual test, turn on **Clean automatically** and add another tagged file. The background scan is scheduled about every 15 minutes and can run later under Android battery or storage restrictions. Tap **Clean now** for an immediate pass. Files recently modified are deferred so that camera writes and downloads have time to finish.

The default date setting keeps common embedded capture dates. The date switch removes the date fields this release supports. Filesystem timestamps and names can still expose dates. Shared internal storage is scanned within the selected profile and permission scope. **Use shared storage** scans all visible shared folders in that profile.

## Format coverage

- JPEG, PNG, WebP. The app removes identifying EXIF fields, common standalone XMP, PNG text comments, and JPEG comments. It keeps orientation, embedded ICC profiles, and encoded pixels. It skips recognized motion photos, multi-image JPEGs, auxiliary display metadata such as Ultra HDR gain maps, and JPEG Photoshop resources where safe IPTC removal is pending.
- MP4 and MOV. The app clears common identifying container fields such as location, make, model, author, and comments. It leaves encoded video, audio, rotation, and their existing timing untouched. Fragmented video, recognized timed metadata tracks, and several unsupported proprietary metadata layouts are skipped.
- HEIC, AVIF, GIF, RAW, WebM, and other formats listed as skipped are candidates for later releases. Identifiers in unknown fields, filenames, visible content, audio, or previously uploaded copies can remain. A report of **No targeted tags** applies only to fields this release understands.

Android may maintain its own gallery database and thumbnails. Media Scrub requests a rescan after replacement. Test how your gallery and backup apps handle the update on your handset before broadening the folder scope.

## Build and check

Requires Android 11 or later on the phone. To build, install JDK 17+, the Android 36 platform, Build Tools 36.0.0, and `zip`. Set `ANDROID_HOME` and `MEDIA_SCRUB_KEYSTORE`, which must point to a keystore containing the `media-scrub` alias. Run `./build.sh` to produce the signed APK. Keep the same signing key for future updates.

Run `python3 tests/smoke.py` with a JDK, Pillow and FFmpeg installed. The tests generate their own media and verify metadata removal, orientation, identical decoded images, intact encoded audio and video, date policy, and skipping an image with appended media.
