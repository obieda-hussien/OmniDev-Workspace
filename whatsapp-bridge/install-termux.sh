#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$(dirname "$0")"
if ! command -v pkg >/dev/null 2>&1; then
  echo 'Run this script inside Termux.' >&2
  exit 1
fi
pkg update
pkg install -y nodejs-lts git
node -e 'if (+process.versions.node.split(".")[0] < 20) process.exit(1)' || {
  echo 'Node.js 20 or newer is required. Update your Termux package source.' >&2
  exit 1
}
npm install --omit=dev
if [ ! -f config.json ]; then
  read -r -p 'Your WhatsApp number with country code (digits only, e.g. 201012345678): ' omni_owner_phone
  OMNI_OWNER_PHONE="$omni_owner_phone" node --input-type=module -e '
    import { randomBytes } from "node:crypto";
    import { writeFileSync } from "node:fs";
    const ownerPhone = (process.env.OMNI_OWNER_PHONE ?? "").replace(/[^0-9]/g, "");
    if (ownerPhone.length < 8) { console.error("Invalid phone number"); process.exit(1); }
    writeFileSync("config.json", JSON.stringify({ ownerPhone, apiKey: randomBytes(32).toString("hex"), port: 3000 }, null, 2) + "\n", { mode: 0o600, flag: "wx" });
  '
fi
chmod 600 config.json
echo 'Installed. Copy the API key below into OmniDev → Integrations → WhatsApp Bridge:'
node -e 'console.log(JSON.parse(require("node:fs").readFileSync("config.json", "utf8")).apiKey)'
echo 'Bridge URL in OmniDev: http://127.0.0.1:3000'
echo 'Start with: npm start'
