#!/bin/bash
#
# Build and sign a Freer release APK — the only way to make one.
#
# Releases are signed with the key derived from the main FID (Freer for
# Mac → Tools → APK key) and carry a v3 rotation proof (the "lineage")
# from the key that signed 3.3.0 and earlier. Without that proof every
# existing install refuses the update with INSTALL_FAILED_UPDATE_INCOMPATIBLE
# and the user has to uninstall — erasing the wallet's data. Gradle cannot
# attach a lineage, so the APK it writes to app/build/outputs is NOT
# shippable; this script re-signs it.
#
# Usage:   ./sign-release.sh            build, sign, verify
#          ./sign-release.sh --no-build sign the existing Gradle output
#
# Reads from ~/.gradle/gradle.properties (the same entries Gradle uses):
#   FREER_RELEASE_STORE_FILE      the .p12 exported by Freer for Mac
#   FREER_RELEASE_KEY_ALIAS
#   FREER_RELEASE_STORE_PASSWORD
#   FREER_RELEASE_KEY_PASSWORD    optional; a PKCS#12 has one password
#   FREER_RELEASE_LINEAGE_FILE    optional; default: freer.lineage next to
#                                 the store file
#
# Writes build/release/Freer-<versionName>.apk.

set -euo pipefail
cd "$(dirname "$0")"

# The signer every release must have, and the one the lineage must start
# from. Pinned so a wrong keystore or lineage fails here, not on phones.
EXPECTED_SIGNER=2d820c25546094569544361410e30444b470b389023bb1adba801f97cfe7f02d
ORIGINAL_SIGNER=5a1f9d7bf908adb6b1f599c783edf4efacff50f8d8d144af19440c7a0c7d48ff

# minSdk is 28, where every device verifies v3 — so rotate for all of
# them, and skip v1/v2, which cannot carry a rotation proof.
ROTATION_MIN_SDK=28

die() { echo "sign-release: $*" >&2; exit 1; }

BUILD=1
case "${1:-}" in
    --no-build) BUILD=0 ;;
    "") ;;
    *) die "unknown argument: $1 (try --no-build)" ;;
esac

# --- Tools ------------------------------------------------------------------

# The inherited JAVA_HOME may be an x86 JDK that cannot run here; prefer
# Android Studio's bundled one.
STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -x "$STUDIO_JBR/bin/java" ]; then export JAVA_HOME="$STUDIO_JBR"; fi

SDK_DIR="${ANDROID_HOME:-}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
    SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties)
fi
[ -d "$SDK_DIR/build-tools" ] || die "no Android SDK found (set ANDROID_HOME or sdk.dir in local.properties)"
BUILD_TOOLS=$(ls -d "$SDK_DIR"/build-tools/*/ | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"

# --- Signing config ---------------------------------------------------------

PROPS="$HOME/.gradle/gradle.properties"
[ -f "$PROPS" ] || die "$PROPS not found"
prop() { sed -n "s/^$1=//p" "$PROPS" | tail -1; }

STORE_FILE=$(prop FREER_RELEASE_STORE_FILE)
KEY_ALIAS=$(prop FREER_RELEASE_KEY_ALIAS)
[ -n "$STORE_FILE" ] && [ -f "$STORE_FILE" ] || die "FREER_RELEASE_STORE_FILE missing or not a file: '$STORE_FILE'"
[ -n "$KEY_ALIAS" ] || die "FREER_RELEASE_KEY_ALIAS is not set"
LINEAGE=$(prop FREER_RELEASE_LINEAGE_FILE)
LINEAGE="${LINEAGE:-$(dirname "$STORE_FILE")/freer.lineage}"
[ -f "$LINEAGE" ] || die "lineage not found: $LINEAGE (create it once with apksigner rotate)"

# Passed to apksigner through the environment, never on a command line.
export FREER_KS_PASS FREER_KEY_PASS
FREER_KS_PASS=$(prop FREER_RELEASE_STORE_PASSWORD)
FREER_KEY_PASS=$(prop FREER_RELEASE_KEY_PASSWORD)
FREER_KEY_PASS="${FREER_KEY_PASS:-$FREER_KS_PASS}"
[ -n "$FREER_KS_PASS" ] || die "FREER_RELEASE_STORE_PASSWORD is not set"

# --- Build ------------------------------------------------------------------

UNSIGNED=app/build/outputs/apk/release/app-release.apk
if [ "$BUILD" = 1 ]; then
    ./gradlew assembleRelease
fi
[ -f "$UNSIGNED" ] || die "$UNSIGNED not found — run without --no-build"

VERSION=$("$AAPT2" dump badging "$UNSIGNED" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
[ -n "$VERSION" ] || die "could not read versionName from $UNSIGNED"
mkdir -p build/release
OUT="build/release/Freer-$VERSION.apk"
# Signed under a temporary name and moved into place only once every
# check below has passed, so a bad signature never sits at the real name.
TMP="build/release/.Freer-$VERSION.apk.unverified"
trap 'rm -f "$TMP" "$TMP.idsig"' EXIT

# --- Sign -------------------------------------------------------------------

"$APKSIGNER" sign \
    --ks "$STORE_FILE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:FREER_KS_PASS --key-pass env:FREER_KEY_PASS \
    --lineage "$LINEAGE" --rotation-min-sdk-version "$ROTATION_MIN_SDK" \
    --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
    --out "$TMP" "$UNSIGNED" 2> >(grep -v '^WARNING' >&2)

# --- Verify -----------------------------------------------------------------

VERIFY=$("$APKSIGNER" verify -v --print-certs "$TMP" 2>/dev/null) || die "apksigner verify failed"
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" <<<"$VERIFY" \
    || die "no valid v3 signature"
SIGNER=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$VERIFY")
[ "$SIGNER" = "$EXPECTED_SIGNER" ] \
    || die "signed by $SIGNER, expected $EXPECTED_SIGNER — wrong keystore?"

LINEAGE_CERTS=$("$APKSIGNER" lineage --in "$TMP" --print-certs 2>/dev/null \
    | sed -n 's/^Signer #[0-9]* in lineage certificate SHA-256 digest: //p')
[ "$(head -1 <<<"$LINEAGE_CERTS")" = "$ORIGINAL_SIGNER" ] \
    || die "the lineage does not start at the original signer — existing installs could not update"
[ "$(tail -1 <<<"$LINEAGE_CERTS")" = "$EXPECTED_SIGNER" ] \
    || die "the lineage does not end at the release signer"

mv -f "$TMP" "$OUT"

echo
echo "Signed $OUT"
echo "  version  $VERSION"
echo "  signer   $SIGNER"
echo "  lineage  $(tr '\n' ' ' <<<"$LINEAGE_CERTS" | sed 's/ $//; s/ / → /g')"
