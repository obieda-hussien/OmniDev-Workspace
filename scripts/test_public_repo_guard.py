import unittest
from public_repo_guard import current_tree_secret_labels


class TelegramTokenGuardTest(unittest.TestCase):
    def token(self, suffix="x"):
        # Construct a synthetic fixture; never commit a complete credential.
        return "123456789" + ":" + "x" * 34 + suffix

    def test_catches_tokens_ending_in_each_valid_character_class(self):
        for last in ["x", "7", "_", "-"]:
            self.assertIn("Telegram bot token", current_tree_secret_labels('token="' + self.token(last) + '"'))

    def test_catches_bot_api_urls_and_whitespace_delimiters(self):
        self.assertIn("Telegram bot token", current_tree_secret_labels("https://api.telegram.org/bot" + self.token("-") + "/sendDocument"))
        self.assertIn("Telegram bot token", current_tree_secret_labels(" " + self.token("-") + " "))

    def test_does_not_accept_wrong_lengths_or_secret_expressions(self):
        self.assertEqual([], current_tree_secret_labels(self.token() + "x"))
        self.assertEqual([], current_tree_secret_labels(self.token()[:-1]))
        self.assertEqual([], current_tree_secret_labels("${{ secrets.TELEGRAM_BOT_TOKEN }}"))


if __name__ == "__main__":
    unittest.main()
