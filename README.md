<img src="artwork/media-scrub.svg" width="80" height="80" alt="Media Scrub icon">

# Media Scrub

**Automatic, offline metadata cleanup for the photos and videos you save on Android.**

Pick a folder, grant storage access, and turn on **Clean automatically** once. Then download images, save GIFs, and take photos as usual. Android starts the scans in the background without you having to reopen Media Scrub or approve each file.

As files arrive in the selected folder, Media Scrub removes supported identifiers such as GPS coordinates, camera details, and source URLs in metadata. Each cleaned file stays at its original name and location, with no extra copy in your gallery and no re-encoding. The app has no network permission.

[Download the signed APK](https://github.com/CallMeBakugo/media-scrub/releases/latest) · [Add to Obtainium](#install-with-obtainium) · [See supported formats](#format-coverage)

When you want to check what changed, **Inspect one file** shows the cleanup preview and **Recheck the inspected file** reads the saved result. Automatic cleanup and Storage Scopes have both been tested on GrapheneOS.

## Install with Obtainium

Add `https://github.com/CallMeBakugo/media-scrub` as an app source in Obtainium. It follows the signed APKs attached to [GitHub Releases](https://github.com/CallMeBakugo/media-scrub/releases). You can also install `media-scrub.apk` directly. Requires Android 11 or newer.

## Try it on GrapheneOS

1. **Start with a test folder.** Copy a few photos or videos into a folder such as `Pictures/MediaScrubTest`.
2. **Grant access.** In Media Scrub, tap **Android storage settings**. If GrapheneOS offers **Storage Scopes** for the app, grant access to that folder. Standard Android offers **All files access**. Then tap **Choose test folder** in Media Scrub and select the same folder. The system folder picker identifies the folder, and the storage setting grants access for same-path replacement.
3. **Preview and clean.** Tap **Preview scan** for a folder summary, or **Inspect one file** for a field-by-field comparison. **Clean now** updates the selected folder. A file counts as **Cleaned and verified** once the app reopens it and matches its SHA-256 hash to the cleaned output.
4. **Let it run.** Enable **Clean automatically**. Android schedules scans roughly every 15 minutes. Idle, battery, and storage conditions can delay cleanup, so a file shared right after saving may still have its original metadata. The cleaner waits for recently modified files so camera writes and downloads have time to finish.

**Use shared storage** scans accessible shared folders in the current profile. Common embedded capture dates are kept by default. Use the date switch to remove the capture date fields the app supports.

## Inspect one file

Choose a photo or video to compare its recognized metadata with a cleanup preview. For example, an image might show **GPS location → Will be removed** while keeping its orientation. Fields that would change are shown in bold. Some metadata blocks appear by name alone.

After cleanup, use **Recheck the inspected file** to compare the first inspection with a fresh read of the saved file. The comparison stays in memory on the current app screen. If Android revokes the picker link after replacement, select the file again to inspect its current metadata. The inspector accepts files up to 512 MiB. On a computer with ExifTool, you can also compare two copies with `exiftool -a -G1 -s before.jpg after.jpg`.

## Format coverage

| Format | What Media Scrub can remove |
| --- | --- |
| **JPEG** | Common EXIF identifiers, comments, supported standalone XMP, and recognized Photoshop IPTC/XMP resources |
| **PNG, APNG, WebP** | Common EXIF identifiers, PNG text fields, and supported WebP XMP |
| **GIF** | Comments and standard XMP application blocks, with frames, timing, transparency, and looping preserved |
| **Still HEIC, HEIF, AVIF** | Identifying EXIF fields from supported, unshared metadata items |
| **Boxed JP2, JPX, JPEG XL** | Recognized XMP in top-level XML boxes |
| **PSD, PSB** | Recognized IPTC and XMP resources, with layer and image data preserved |
| **MP4, MOV** | Common container tags such as location, camera make and model, author, and comments |

Coverage varies by file layout. **No supported changes** means the cleaner made no changes under the current settings. Motion photos, some Ultra HDR files, camera RAW, TIFF, SVG, ICO, WebM/MKV, and other unsupported layouts may be skipped. Identifiers can remain in unhandled metadata, filenames, sidecars, or the visible content. Media Scrub asks Android to rescan updated files so gallery apps can refresh their own records.

## Build and verify

The release build uses OpenJDK 21, Android SDK Platform 36, Android Build Tools 36.0.0, and Info-ZIP. Set `ANDROID_HOME` and run `./build.sh --unsigned media-scrub-unsigned.apk` to build without a signing key. For a signed release, set `MEDIA_SCRUB_KEYSTORE` to the absolute path of a keystore with the `media-scrub` alias and run `./build.sh`.

Two independent local builds of 0.2.0 matched. Copying the release signature onto a local rebuild reproduced the published APK byte for byte.

Run `python3 tests/smoke.py` with a JDK, Pillow, and FFmpeg. The HEIC and Photoshop cases also use libheif and ImageMagick when available. The fixtures check metadata removal and compare decoded pixels, animation properties, color profiles, or encoded streams as applicable.

The code and original artwork are available under the [MIT license](LICENSE).
