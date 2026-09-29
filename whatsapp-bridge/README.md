# OmniDev WhatsApp Bridge for Termux

This is the companion Node.js server for **Settings → Integrations → WhatsApp Bridge (Baileys)**. It runs on the same Android phone as OmniDev, binds only to `127.0.0.1:3000`, and requires a generated 256-bit bearer key. It accepts text from the configured account's **own private chat** and sends replies only there. Groups, other contacts, and media are ignored. The WhatsApp Business Cloud API is a separate integration.

## Install on Termux

Download `omnidev-whatsapp-termux.zip` from the PR or the provided download link. In Termux:

```sh
termux-setup-storage
pkg update
pkg install -y unzip
mkdir -p ~/omnidev-whatsapp
cd ~/omnidev-whatsapp
unzip ~/storage/downloads/omnidev-whatsapp-termux.zip
cd whatsapp-bridge
bash install-termux.sh
npm start
```

The installer downloads Node.js LTS, Git and the exact npm dependencies in `package.json`, checks Node >=20, prompts for your own number in international format (digits only), then creates `config.json` and prints the API key. Keep the Termux session running. To start again later: `cd ~/omnidev-whatsapp/whatsapp-bridge && npm start`. If Termux is frequently suspended, use `termux-wake-lock` while running and exclude Termux from your phone's battery optimization; release with `termux-wake-unlock` when finished. The app's foreground listener must also be enabled.

In OmniDev enter `http://127.0.0.1:3000`, the **same number**, and the printed API key. Tap **Request Pairing Code** while the server reports `connecting`, then enter the code under **WhatsApp → Linked Devices → Link with phone number**. Check the status icon until it says `open`, enable the listener, and send `!help` to your own chat. Agent and Swarm need a specific Target Context selected in OmniDev. If status says `logged_out`, stop the server and explicitly remove `auth/` to link anew; this disconnects the old session. Never delete `data/inbox.jsonl` unless you intentionally discard queued messages.

## Diagnostics

- `Error: Unauthorized`: copy the API key from `config.json` again; restart the app listener after changing it.
- `Pairing already requested`: wait for the existing code or restart Termux to request a new one.
- `WhatsApp is not connected`: check `npm start` output, network, and Linked Devices; the app retries on its next poll.
- `HTTP 410 / cursor expired`: the retained queue has moved past the app cursor; the app resumes from the oldest retained message. Default queue capacity is 5,000 entries.
- `Cannot connect`: verify `npm start` is running on the **same phone**, the URL is loopback, and the API key matches. The app deliberately rejects LAN addresses.
- `npm install` fails on native compilation: run `pkg install -y python make clang` and retry `npm install --omit=dev`.

`npm test` runs the queue and owner-filter checks without connecting to WhatsApp. `config.json`, `auth/`, and `data/` are private files and are excluded from Git and the download ZIP. Keep backups of `auth/` private. This is a personal local bridge built with the unofficial WhatsApp Web library; protocol changes can require a dependency update. The provided Baileys 6.7.22 includes the project's published fix for message spoofing. The library's multi-file auth helper is for local use here; its own documentation cautions against treating it as production credential storage.

The queue survives server restarts and the app saves a cursor after processing. Delivery is at least once across failures; a crash at the send/cursor boundary may repeat a reply. Do not use remote Agent commands for tasks requiring guaranteed exactly-once side effects. The bridge sends plain text only and refuses requests to other recipients.
