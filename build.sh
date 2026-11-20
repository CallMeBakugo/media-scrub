#!/usr/bin/env bash
set -euo pipefail
umask 077
export TZ=UTC
export LC_ALL=C
unsigned=false
if [[ ${1:-} == --unsigned ]]; then
    unsigned=true
    shift
fi
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK.}"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
tools="${MEDIA_SCRUB_BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
output="${1:-media-scrub.apk}"
temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
mkdir "$temporary/classes" "$temporary/dex"
javac --release 17 -cp "$platform" -d "$temporary/classes" src/app/mediascrub/*.java
jar --create --date=2000-01-01T00:00:00Z --file "$temporary/classes.jar" -C "$temporary/classes" .
"$tools/d8" --release --lib "$platform" --min-api 30 --output "$temporary/dex" "$temporary/classes.jar"
"$tools/aapt2" compile --dir res -o "$temporary/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest AndroidManifest.xml --min-sdk-version 30 --target-sdk-version 36 -o "$temporary/raw.apk" "$temporary/resources.zip"
touch -t 200001010000 "$temporary/dex/classes.dex"
(cd "$temporary/dex" && zip -q -X "$temporary/raw.apk" classes.dex)
"$tools/zipalign" -f 4 "$temporary/raw.apk" "$temporary/aligned.apk"
if $unsigned; then
    install -m644 "$temporary/aligned.apk" "$output"
    exit
fi
: "${MEDIA_SCRUB_KEYSTORE:?Set MEDIA_SCRUB_KEYSTORE to an absolute keystore path.}"
password=()
if [[ -v MEDIA_SCRUB_STORE_PASSWORD ]]; then
    password=(--ks-pass env:MEDIA_SCRUB_STORE_PASSWORD --key-pass env:MEDIA_SCRUB_STORE_PASSWORD)
fi
"$tools/apksigner" sign --alignment-preserved true --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --ks "$MEDIA_SCRUB_KEYSTORE" --ks-key-alias media-scrub "${password[@]}" --out "$temporary/signed.apk" "$temporary/aligned.apk"
"$tools/apksigner" verify "$temporary/signed.apk"
install -m644 "$temporary/signed.apk" "$output"
