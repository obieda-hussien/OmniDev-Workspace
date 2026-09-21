#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESTORE="$ROOT/.github/scripts/restore-release-keystore.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

store_pass='OmniCiStorePass123!'
alias_name='omni-ci-test'
source_keystore="$TMP/source.p12"

keytool -genkeypair   -alias "$alias_name"   -keyalg RSA   -keysize 2048   -validity 2   -dname 'CN=Omni CI Test,O=Omni,C=EG'   -keystore "$source_keystore"   -storetype PKCS12   -storepass "$store_pass"   -keypass "$store_pass"   >/dev/null 2>&1

plain="$(base64 < "$source_keystore" | tr -d '\n')"
wrapped="$(printf '%s' "$plain" | fold -w 48)"
data_uri="data:application/octet-stream;base64,$plain"
base64url="$(printf '%s' "$plain" | tr '+/' '-_' | tr -d '=')"

run_case() {
  local name="$1"
  local value="$2"
  local out="$TMP/$name.keystore"
  OMNI_SHARED_RELEASE_KEYSTORE_BASE64="$value"   OMNI_SHARED_RELEASE_STORE_PASSWORD="$store_pass"   OMNI_SHARED_RELEASE_KEY_ALIAS="$alias_name"   OMNI_SHARED_RELEASE_KEY_PASSWORD="$store_pass"     bash "$RESTORE" "$out" >/dev/null
  cmp -s "$source_keystore" "$out" || {
    echo "restore test '$name' decoded different bytes" >&2
    exit 1
  }
}

run_case plain "$plain"
run_case wrapped "$wrapped"
run_case data_uri "$data_uri"
run_case base64url "$base64url"

if OMNI_SHARED_RELEASE_KEYSTORE_BASE64='not-a-keystore: definitely invalid'    OMNI_SHARED_RELEASE_STORE_PASSWORD="$store_pass"    OMNI_SHARED_RELEASE_KEY_ALIAS="$alias_name"    OMNI_SHARED_RELEASE_KEY_PASSWORD="$store_pass"    bash "$RESTORE" "$TMP/invalid.keystore" >/dev/null 2>&1; then
  echo "invalid input unexpectedly succeeded" >&2
  exit 1
fi

if OMNI_SHARED_RELEASE_KEYSTORE_BASE64="$plain"    OMNI_SHARED_RELEASE_STORE_PASSWORD='wrong-password'    OMNI_SHARED_RELEASE_KEY_ALIAS="$alias_name"    OMNI_SHARED_RELEASE_KEY_PASSWORD="$store_pass"    bash "$RESTORE" "$TMP/wrong-password.keystore" >/dev/null 2>&1; then
  echo "wrong password unexpectedly succeeded" >&2
  exit 1
fi

if OMNI_SHARED_RELEASE_KEYSTORE_BASE64="$plain"    OMNI_SHARED_RELEASE_STORE_PASSWORD="$store_pass"    OMNI_SHARED_RELEASE_KEY_ALIAS="$alias_name"    OMNI_SHARED_RELEASE_KEY_PASSWORD='wrong-key-password'    bash "$RESTORE" "$TMP/wrong-key-password.keystore" >/dev/null 2>&1; then
  echo "wrong key password unexpectedly succeeded" >&2
  exit 1
fi

echo "release keystore restoration self-test passed"
