# Omni Ecosystem — Signing Policy

All applications in the Omni Ecosystem (including Workspace, Equalizer, Note, Memoria, PriceWatch, and Omni-launcher) must be signed with the same developer certificate to satisfy Android's `signature`-level permission constraints (specifically `com.omnilink.sdk.permission.BIND_EXTENSION` and `com.omnilink.sdk.permission.BIND_LAUNCHER`).

## Canonical Signing Certificate Fingerprint

- **Alias:** `omni_ecosystem_key`
- **SHA-256 Fingerprint:** `CA:78:51:3B:09:3E:65:D8:69:A3:FB:AF:8A:D4:EF:61:C3:E7:19:CC:E4:8C:83:CF:F0:D1:38:0B:4E:D3:A3:3E`

Every satellite app's `SignatureSecurityValidator` allowlist must verify incoming bindings against this canonical SHA-256 fingerprint.
