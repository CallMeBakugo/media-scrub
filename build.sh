#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK.}"
: "${MEDIA_SCRUB_KEYSTORE:?Set MEDIA_SCRUB_KEYSTORE to an absolute keystore path.}"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
tools="${MEDIA_SCRUB_BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
output="${1:-media-scrub.apk}"
temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
mkdir "$temporary/classes" "$temporary/dex"
javac --release 17 -cp "$platform" -d "$temporary/classes" src/app/mediascrub/*.java
jar --create --file "$temporary/classes.jar" -C "$temporary/classes" .
"$tools/d8" --release --lib "$platform" --min-api 30 --output "$temporary/dex" "$temporary/classes.jar"
"$tools/aapt2" link -I "$platform" --manifest AndroidManifest.xml --min-sdk-version 30 --target-sdk-version 36 -o "$temporary/raw.apk"
(cd "$temporary/dex" && zip -q -X "$temporary/raw.apk" classes.dex)
"$tools/zipalign" -f 4 "$temporary/raw.apk" "$temporary/aligned.apk"
password=()
if [[ -v MEDIA_SCRUB_STORE_PASSWORD ]]; then
    password=(--ks-pass env:MEDIA_SCRUB_STORE_PASSWORD --key-pass env:MEDIA_SCRUB_STORE_PASSWORD)
fi
"$tools/apksigner" sign --ks "$MEDIA_SCRUB_KEYSTORE" --ks-key-alias media-scrub "${password[@]}" --out "$temporary/signed.apk" "$temporary/aligned.apk"
"$tools/apksigner" verify "$temporary/signed.apk"
install -m644 "$temporary/signed.apk" "$output"
