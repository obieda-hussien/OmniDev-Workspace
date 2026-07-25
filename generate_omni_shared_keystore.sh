#!/bin/bash
# ==============================================================================
# Omni Ecosystem — Shared Signing Keystore Generator
# ------------------------------------------------------------------------------
# Generates the ONE release keystore that must sign all six apps:
#   OmniDev-Workspace, OmniEqualizer, OmniNote, OmniMemoria, OmniPriceWatch,
#   Omni-launcher
# Also generates ONE shared debug keystore, so debug builds across Termux and
# AndroidIDE don't accidentally end up signed with different per-tool debug
# keys (which would break installs the same way a release mismatch would).
#
# Run in Termux:
#   pkg install openjdk-17          # keytool ships with the JDK, not standalone
#   bash generate_omni_shared_keystore.sh
# ==============================================================================

set -euo pipefail

KEYSTORE_DIR="$HOME/omni-ecosystem-keys"
RELEASE_KEYSTORE="$KEYSTORE_DIR/omni-release.keystore"
DEBUG_KEYSTORE="$KEYSTORE_DIR/omni-debug.keystore"
RELEASE_ALIAS="omni_ecosystem_key"
VALIDITY_DAYS=10000   # ~27 years — matches Android Studio/Play defaults; a short
                      # validity here would eventually lock every app out of
                      # installing once the cert expires.

echo "=============================================================="
echo " Omni Ecosystem — Shared Keystore Generator"
echo "=============================================================="

if ! command -v keytool &> /dev/null; then
  echo "ERROR: keytool not found."
  echo "In Termux, install a JDK first:  pkg install openjdk-17"
  exit 1
fi

if [ -f "$RELEASE_KEYSTORE" ]; then
  echo "ERROR: $RELEASE_KEYSTORE already exists — refusing to overwrite it."
  echo "If you really mean to regenerate it, move or delete that file first."
  exit 1
fi

mkdir -p "$KEYSTORE_DIR"
chmod 700 "$KEYSTORE_DIR"

# --- Generate a strong random password (hex-only: no shell-quoting surprises) ---
if command -v openssl &> /dev/null; then
  RELEASE_PASSWORD=$(openssl rand -hex 16)
elif [ -r /dev/urandom ]; then
  RELEASE_PASSWORD=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
else
  echo "ERROR: no secure random source found. Install openssl: pkg install openssl-tool"
  exit 1
fi

echo
echo "--- Generating the RELEASE keystore (signs all six apps) ---"
keytool -genkeypair -v \
  -keystore "$RELEASE_KEYSTORE" \
  -alias "$RELEASE_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$RELEASE_PASSWORD" \
  -keypass "$RELEASE_PASSWORD" \
  -dname "CN=Omni Ecosystem, OU=obieda-hussien, O=obieda-hussien, L=Alexandria, ST=Alexandria, C=EG"

echo
echo "--- Generating the shared DEBUG keystore (standard Android debug conventions) ---"
if [ -f "$DEBUG_KEYSTORE" ]; then
  echo "Debug keystore already exists at $DEBUG_KEYSTORE — leaving it as is."
else
  keytool -genkeypair -v \
    -keystore "$DEBUG_KEYSTORE" \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -storepass android \
    -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo
echo "=============================================================="
echo " RELEASE certificate SHA-256 (paste this into every satellite"
echo " app's SignatureSecurityValidator allowlist)"
echo "=============================================================="
keytool -list -v -keystore "$RELEASE_KEYSTORE" -alias "$RELEASE_ALIAS" -storepass "$RELEASE_PASSWORD" \
  | grep -A1 "SHA256:" | head -1

cat << EOF

==============================================================
 SAVE THIS NOW — you asked to change the password right after:
   Generated release keystore password: $RELEASE_PASSWORD
==============================================================

Files created:
  Release: $RELEASE_KEYSTORE
  Debug:   $DEBUG_KEYSTORE

NEXT STEPS
----------
1) Change the release password immediately (this does NOT change the key
   itself or its SHA-256 fingerprint — the value above stays valid):
     keytool -storepasswd -keystore "$RELEASE_KEYSTORE" \\
       -storepass "$RELEASE_PASSWORD" -new <YOUR_NEW_PASSWORD>
     keytool -keypasswd -keystore "$RELEASE_KEYSTORE" -alias "$RELEASE_ALIAS" \\
       -storepass <YOUR_NEW_PASSWORD> -keypass "$RELEASE_PASSWORD" -new <YOUR_NEW_PASSWORD>

2) Copy both .keystore files to a path every one of the six projects can
   reach, then add this to EACH project's app/build.gradle.kts:

     android {
         signingConfigs {
             create("release") {
                 storeFile = file("/absolute/path/to/omni-release.keystore")
                 storePassword = System.getenv("OMNI_RELEASE_STORE_PASSWORD")
                 keyAlias = "$RELEASE_ALIAS"
                 keyPassword = System.getenv("OMNI_RELEASE_STORE_PASSWORD")
             }
             getByName("debug") {
                 storeFile = file("/absolute/path/to/omni-debug.keystore")
                 storePassword = "android"
                 keyAlias = "androiddebugkey"
                 keyPassword = "android"
             }
         }
         buildTypes {
             release { signingConfig = signingConfigs.getByName("release") }
             debug   { signingConfig = signingConfigs.getByName("debug") }
         }
     }

   Set the password via an environment variable (OMNI_RELEASE_STORE_PASSWORD),
   never hardcoded in a committed Gradle file.

3) NEVER commit either .keystore file, or the password, to any git repo —
   not even a private one. Add both filenames to every project's .gitignore.

4) Back up both files AND the final password somewhere outside this device
   (password manager + encrypted cloud backup, or similar). Losing this
   keystore means none of the six apps can ever be updated again without
   users uninstalling and reinstalling everything from scratch.
EOF
