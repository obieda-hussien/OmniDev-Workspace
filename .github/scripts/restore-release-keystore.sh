#!/usr/bin/env bash
set -euo pipefail

die() {
  echo "::error::$*" >&2
  exit 1
}

for required in   OMNI_SHARED_RELEASE_KEYSTORE_BASE64   OMNI_SHARED_RELEASE_STORE_PASSWORD   OMNI_SHARED_RELEASE_KEY_ALIAS   OMNI_SHARED_RELEASE_KEY_PASSWORD; do
  [[ -n "${!required:-}" ]] || die "Missing $required in the protected environment."
done

dest="${1:-${RUNNER_TEMP:-/tmp}/omni-admin-release.keystore}"
mkdir -p "$(dirname "$dest")"
umask 077

# GitHub secrets are text fields. Normalize common safe copy/paste variants without
# ever printing the secret: wrapped base64, CRLF, a data:*;base64, prefix, or
# URL-safe base64. Arbitrary non-base64 text is rejected.
encoded="$(printf '%s' "$OMNI_SHARED_RELEASE_KEYSTORE_BASE64" | tr -d '[:space:]')"

if [[ "$encoded" == data:*";base64,"* ]]; then
  encoded="${encoded#*,}"
fi

if (( ${#encoded} >= 2 )); then
  first="${encoded:0:1}"
  last="${encoded: -1}"
  if [[ ( "$first" == '"' && "$last" == '"' ) || ( "$first" == "'" && "$last" == "'" ) ]]; then
    encoded="${encoded:1:${#encoded}-2}"
  fi
fi

# Accept base64url too, then restore standard alphabet/padding.
encoded="${encoded//-/+}"
encoded="${encoded//_/\/}"

if [[ ! "$encoded" =~ ^[A-Za-z0-9+/]*={0,2}$ ]]; then
  die "OMNI_SHARED_RELEASE_KEYSTORE_BASE64 contains non-base64 characters. Re-create the secret from the keystore file only; do not include shell prompts, labels, or filenames."
fi

case $(( ${#encoded} % 4 )) in
  0) ;;
  2) encoded+="==" ;;
  3) encoded+="=" ;;
  1) die "OMNI_SHARED_RELEASE_KEYSTORE_BASE64 has an impossible base64 length. Re-create the secret from the original keystore file." ;;
esac

tmp="$dest.tmp"
trap 'rm -f "$tmp"' EXIT

if ! printf '%s' "$encoded" | base64 --decode > "$tmp" 2>/dev/null; then
  die "Could not decode OMNI_SHARED_RELEASE_KEYSTORE_BASE64. Re-create it from the original keystore file using: base64 < release.keystore | tr -d '\\n'"
fi

[[ -s "$tmp" ]] || die "Decoded release keystore is empty."
chmod 600 "$tmp"

# Fail here with a precise message instead of letting Gradle fail later. keytool
# auto-detects JKS/PKCS12 on current JDKs, so the file extension is irrelevant.
if ! keytool -list   -keystore "$tmp"   -storepass "$OMNI_SHARED_RELEASE_STORE_PASSWORD"   -alias "$OMNI_SHARED_RELEASE_KEY_ALIAS"   >/dev/null 2>&1; then
  die "Decoded data is not a readable keystore with the configured store password and alias. Verify OMNI_SHARED_RELEASE_KEYSTORE_BASE64, OMNI_SHARED_RELEASE_STORE_PASSWORD, and OMNI_SHARED_RELEASE_KEY_ALIAS."
fi

# Verify the private-key password too. A keystore can be readable while the
# private key is still inaccessible, which would otherwise fail late in Gradle.
probe_dir="$(mktemp -d)"
probe_jar="$probe_dir/probe.jar"
mkdir -p "$probe_dir/empty"
jar --create --file "$probe_jar" -C "$probe_dir/empty" . >/dev/null 2>&1
if ! jarsigner   -keystore "$tmp"   -storepass "$OMNI_SHARED_RELEASE_STORE_PASSWORD"   -keypass "$OMNI_SHARED_RELEASE_KEY_PASSWORD"   "$probe_jar"   "$OMNI_SHARED_RELEASE_KEY_ALIAS"   >/dev/null 2>&1; then
  rm -rf "$probe_dir"
  die "The release keystore is readable, but the configured key password cannot sign with the requested alias. Verify OMNI_SHARED_RELEASE_KEY_PASSWORD."
fi
rm -rf "$probe_dir"

mv "$tmp" "$dest"
trap - EXIT

if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "OMNI_SHARED_RELEASE_STORE_FILE=$dest" >> "$GITHUB_ENV"
fi

echo "Release keystore restored and validated without exposing secret contents."
