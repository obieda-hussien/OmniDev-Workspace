# Omni Ecosystem — Signing Policy

All applications in the Omni Ecosystem (including Workspace, Equalizer, Note, Memoria, PriceWatch, and Omni-launcher) must be signed with the same developer certificate to satisfy Android's `signature`-level permission constraints (specifically `com.omnilink.sdk.permission.BIND_EXTENSION` and `com.omnilink.sdk.permission.BIND_LAUNCHER`).

## Canonical Signing Certificate Fingerprint

- **Alias:** `omni_ecosystem_key`
- **SHA-256 Fingerprint:** `CA:78:51:3B:09:3E:65:D8:69:A3:FB:AF:8A:D4:EF:61:C3:E7:19:CC:E4:8C:83:CF:F0:D1:38:0B:4E:D3:A3:3E`

Every satellite app's `SignatureSecurityValidator` allowlist must verify incoming bindings against this canonical SHA-256 fingerprint.

## Secure Keystore Configuration

To keep the release keystore password and file paths out of every git repository, both paths and secrets must be configured exclusively via **Environment Variables** or locally inside a gitignored `local.properties` file on each developer machine or CI server.

### 1. Environment Variables Configuration

Set the following environment variables in your system profile or CI secrets (GHA Secrets):

```bash
# Absolute file path to the shared release keystore
export OMNI_RELEASE_STORE_FILE="/absolute/path/to/omni-release.keystore"

# Password for the release keystore and key alias
export OMNI_RELEASE_STORE_PASSWORD="<YOUR_RELEASE_KEYSTORE_PASSWORD>"

# Optional: Overrides for key password and alias if different from above
export OMNI_RELEASE_KEY_PASSWORD="<YOUR_RELEASE_KEY_PASSWORD>"
export OMNI_RELEASE_KEY_ALIAS="omni_ecosystem_key"

# Optional: Path to the shared debug keystore
export OMNI_DEBUG_STORE_FILE="/absolute/path/to/omni-debug.keystore"
```

### 2. local.properties Configuration (Alternative)

Add the following properties locally to your `local.properties` file (which is gitignored by default and must never be committed):

```properties
OMNI_RELEASE_STORE_FILE=/absolute/path/to/omni-release.keystore
OMNI_RELEASE_STORE_PASSWORD=<YOUR_RELEASE_KEYSTORE_PASSWORD>
OMNI_RELEASE_KEY_PASSWORD=<YOUR_RELEASE_KEY_PASSWORD>
OMNI_RELEASE_KEY_ALIAS=omni_ecosystem_key
OMNI_DEBUG_STORE_FILE=/absolute/path/to/omni-debug.keystore
```

## Security Compromise Notice

**IMPORTANT:** The generated plaintext release password `07a801078a361f5c163312ef473df70b` has been printed/committed to intermediate branch histories during early SDK tests. This password must be treated as **COMPROMISED** and must never be used in any production environment!

The shared release keystore password must be rotated immediately using `keytool` before release builds:

```bash
keytool -storepasswd -keystore "/path/to/omni-release.keystore" \
  -storepass "07a801078a361f5c163312ef473df70b" -new <YOUR_NEW_SECURE_PASSWORD>

keytool -keypasswd -keystore "/path/to/omni-release.keystore" -alias "omni_ecosystem_key" \
  -storepass <YOUR_NEW_SECURE_PASSWORD> -keypass "07a801078a361f5c163312ef473df70b" -new <YOUR_NEW_SECURE_PASSWORD>
```
