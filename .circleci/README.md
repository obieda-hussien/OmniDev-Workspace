# CircleCI emergency fallback

GitHub Actions is the primary CI provider for OmniDev Workspace.

## Current state

CircleCI is intentionally **disabled by default**. The VCS integration may still create a lightweight pipeline record, but the `omnidev-ci` workflow is guarded by the `circleci_enabled` pipeline parameter and no CircleCI build jobs run unless that parameter is explicitly set to `true`.

This avoids duplicate Android compilation, duplicate status gates, and CircleCI credit usage while GitHub Android CI is healthy.

## Primary Android CI

`.github/workflows/android-ci.yml` runs automatically for:

- pull requests targeting `main`
- pushes to `main`
- manual `workflow_dispatch` runs

Pull requests run the full debug verification matrix: lint, unit tests, and debug APK builds for Lite, Norm, Pro, OEM, and Admin. Pushes to `main` additionally build release APKs after the verification job succeeds. Manual runs can choose whether release APKs are also built.

## Re-enabling CircleCI as fallback

The existing CircleCI jobs remain in `.circleci/config.yml`. To use them as an emergency fallback, trigger a CircleCI pipeline with pipeline parameter:

```text
circleci_enabled=true
```

Do not enable CircleCI permanently while GitHub Android CI is healthy; otherwise the same Android matrix is built twice.

## Optional Telegram delivery

When CircleCI is explicitly enabled, `TELEGRAM_BOT_TOKEN` may be configured in CircleCI Project Settings → Environment Variables for the existing artifact/report delivery steps.
