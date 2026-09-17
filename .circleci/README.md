# CircleCI fallback CI

OmniDev uses CircleCI Cloud as the automatic CI fallback when GitHub-hosted Actions minutes are unavailable.

## Required CircleCI setup

1. Connect `obieda-hussien/OmniDev-Workspace` to CircleCI using the GitHub integration.
2. Open **Project Settings → Environment Variables**.
3. Add:
   - `TELEGRAM_BOT_TOKEN` = the Telegram bot token.

The target Telegram chat ID is configured directly in `.circleci/config.yml`.

## Delivery behavior

- Lint HTML/XML reports are sent as individual documents.
- Unit-test HTML/XML reports are sent as individual documents.
- Every debug APK is sent as an individual complete document.
- Every release APK is sent as an individual complete document.
- Files are never split or chunked.
- If a file exceeds Telegram Bot API's direct `sendDocument` size limit, the file is not modified; the bot sends a warning message instead.

## GitHub Actions

`.github/workflows/android-ci.yml` is intentionally `workflow_dispatch` only. This prevents automatic GitHub-hosted jobs from producing quota-related failures on pushes and pull requests. It remains available as a manual backup when GitHub Actions minutes are available again.
