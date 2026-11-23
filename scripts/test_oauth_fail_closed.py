"""Regression guard for the intentionally disabled legacy OAuth implementation."""
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/com/omnidev/workspace/data/auth/OAuthManager.kt"


class LegacyOAuthSafetyTest(unittest.TestCase):
    def test_no_secret_exchange_or_token_persistence(self):
        source = SOURCE.read_text(encoding="utf-8")
        for forbidden in (
            "HttpsURLConnection", "client_secret=", "setGitHubOAuthToken(",
            "setGitHubPat(", "openConnection(", "CustomTabsIntent.Builder(",
        ):
            self.assertNotIn(forbidden, source)

    def test_callback_and_exchange_fail_closed(self):
        source = SOURCE.read_text(encoding="utf-8")
        callback = source.split("fun extractCodeFromCallback(", 1)[1].split("suspend fun handleGitHubCallback(", 1)[0]
        exchange = source.split("suspend fun handleGitHubCallback(", 1)[1]
        self.assertIn("return null", callback)
        self.assertIn("return Result.failure(", exchange)
        self.assertNotIn("Result.success(", exchange)


if __name__ == "__main__":
    unittest.main()
