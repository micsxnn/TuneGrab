#!/usr/bin/env bash
# Private keys are supplied only to this trusted CI step; never to PR builds or artifacts.
# v3.1 rotates on Android 13+; Android 7-12 retain the legacy signer for compatibility.
set -euo pipefail
umask 077
DIR="${1:-app/build/outputs/apk/release}"
ALIAS="tunegrab"
VERSION="$(sed -nE 's/^[[:space:]]*versionName = "([0-9]+(\.[0-9]+){1,3})"/\1/p' app/build.gradle.kts)"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]] || { echo 'Invalid release version'; exit 1; }
if [[ "${GITHUB_REF:-}" == refs/tags/* && "${GITHUB_REF_NAME:-}" != "v$VERSION" ]]; then
  echo 'Tag and APK version do not match'; exit 1
fi
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
BT="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
[[ -x "$BT/apksigner" && -x "$BT/zipalign" ]] || { echo 'Android signing tools unavailable'; exit 1; }
for variable in OLD_KEYSTORE_B64 OLD_KEYSTORE_PASS NEW_KEYSTORE_B64 NEW_KEYSTORE_PASS; do
  [[ -n "${!variable:-}" ]] || { echo "Missing signing secret: $variable"; exit 1; }
done
shopt -s nullglob
APKS=("$DIR"/*-unsigned.apk)
[[ ${#APKS[@]} -eq 1 ]] || { echo 'Expected exactly one unsigned APK'; exit 1; }
UNSIGNED="${APKS[0]}"
WORK="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/tunegrab-sign.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
printf '%s' "$OLD_KEYSTORE_B64" | base64 --decode > "$WORK/old.p12"
printf '%s' "$NEW_KEYSTORE_B64" | base64 --decode > "$WORK/new.p12"
export OLD_KEYSTORE_PASS NEW_KEYSTORE_PASS
fingerprint() {
  keytool -exportcert -keystore "$1" -storepass:env "$2" -alias "$ALIAS" 2>/dev/null | sha256sum | cut -d' ' -f1
}
OLD_FP="$(fingerprint "$WORK/old.p12" OLD_KEYSTORE_PASS)"
NEW_FP="$(fingerprint "$WORK/new.p12" NEW_KEYSTORE_PASS)"
[[ "$OLD_FP" =~ ^[0-9a-f]{64}$ && "$NEW_FP" =~ ^[0-9a-f]{64}$ && "$OLD_FP" != "$NEW_FP" ]] || {
  echo 'Invalid or identical signing certificates'; exit 1;
}
"$BT/apksigner" rotate --out "$WORK/lineage" \
  --old-signer --ks "$WORK/old.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:OLD_KEYSTORE_PASS --key-pass env:OLD_KEYSTORE_PASS --set-rollback false \
  --new-signer --ks "$WORK/new.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:NEW_KEYSTORE_PASS --key-pass env:NEW_KEYSTORE_PASS
"$BT/zipalign" -P 16 -f 4 "$UNSIGNED" "$WORK/aligned.apk"
SIGNED="$DIR/TuneGrab-v$VERSION.apk"
"$BT/apksigner" sign \
  --ks "$WORK/old.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:OLD_KEYSTORE_PASS --key-pass env:OLD_KEYSTORE_PASS \
  --next-signer --ks "$WORK/new.p12" --ks-key-alias "$ALIAS" \
    --ks-pass env:NEW_KEYSTORE_PASS --key-pass env:NEW_KEYSTORE_PASS \
  --lineage "$WORK/lineage" --rotation-min-sdk-version 33 \
  --out "$SIGNED" "$WORK/aligned.apk"
"$BT/apksigner" verify --verbose "$SIGNED"
cert() {
  "$BT/apksigner" verify --print-certs --min-sdk-version "$1" --max-sdk-version "$2" "$SIGNED" \
    | awk '/certificate SHA-256 digest/ {print tolower($NF)}'
}
check() {
  local actual; actual="$(cert "$1" "$2")"
  [[ "$actual" == "$3" ]] || { echo "Incorrect certificate for API $1-$2"; exit 1; }
  echo "Verified certificate for API $1-$2: $actual"
}
check 24 27 "$OLD_FP"
check 28 32 "$OLD_FP"
check 33 99 "$NEW_FP"
cp "$WORK/lineage" "$DIR/signing.lineage"
{
  printf 'Legacy certificate SHA-256 (Android 7-12): %s\n' "$OLD_FP"
  printf 'Rotated certificate SHA-256 (Android 13+): %s\n' "$NEW_FP"
  printf 'Rotation minimum API: 33\nLegacy rollback capability: disabled\n'
} > "$DIR/SIGNING-CERTIFICATES.txt"
(cd "$DIR" && sha256sum "TuneGrab-v$VERSION.apk" > SHA256SUMS)
rm -f "$UNSIGNED" "$SIGNED.idsig"
echo "Verified release APK: $SIGNED"
