# Telegram bot integration

The Bot Token supports outgoing publishing and an optional incoming listener. The outgoing **Chat ID / @channel** is a destination for publishing; it does not authorize incoming requests.

## Link a private owner chat

1. Create a bot with BotFather, copy its token into **Settings → Integrations → Telegram Bot**, and save.
2. Tap **Create pairing code**. In a private chat with that bot, send `/pair <code>` within ten minutes. The code works once and allows at most five incorrect attempts. Tap **Refresh link** to confirm the user and chat IDs.
3. Enable **Telegram Bot Listener**. Send `/help` to see the supported commands. Chat mode answers without tools. Before `/mode_agent` or `/mode_swarm`, set a specific **Target Context** in OmniDev settings; the listener refuses root `/` or an empty target.
4. **Revoke Telegram owner** stops the listener and removes the local link. Replacing the Bot Token also removes the link; generate a new code for the new bot.

Only the linked Telegram user in the matching private chat can use incoming messages and agent commands. Group messages, bot messages, edits and messages from other users are ignored. Pairing requests in private chat receive only success or failure. The listener does not download attachments: media messages currently pass a description and Telegram file ID to the model. Do not treat that description as actual image, audio or file contents.

The listener polls while the Android foreground service runs. It keeps a token-bound update cursor on the device and handles messages in order. Chat context and mode are in memory and reset when the service restarts; the in-app database separately stores processed user and assistant messages. Telegram delivery can still be uncertain if the app stops after processing an update but before persisting its cursor, or when a reply fails; do not use a Telegram command as a guarantee of exactly-once execution.

Bot tokens and pairing state stay on the device. The pairing state uses Android encrypted preferences, while the cursor stores a token hash and update number. The command menu is only a convenience; every incoming update is checked against the linked owner before it is mirrored, stored, or sent to a model. Agent tools still follow their own capability and confirmation policies.
