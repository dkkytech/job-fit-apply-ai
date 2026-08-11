"""Credential precedence for run-analyzer notifications."""

import unittest

from analyzer import notify


class NotifyCredentialConfigTest(unittest.TestCase):
    def test_explicit_environment_lookup_does_not_mutate_module_credentials(self):
        before = (notify.TELEGRAM_TOKEN, notify.TELEGRAM_CHAT)
        values = {
            "RUN_ANALYZER_TELEGRAM_BOT_TOKEN": "analyzer-token",
            "TELEGRAM_BOT_TOKEN": "legacy-token",
        }

        self.assertEqual(
            "analyzer-token",
            notify._credential(
                "RUN_ANALYZER_TELEGRAM_BOT_TOKEN",
                "TELEGRAM_BOT_TOKEN",
                env=values,
            ),
        )
        self.assertEqual(before, (notify.TELEGRAM_TOKEN, notify.TELEGRAM_CHAT))

    def test_namespaced_analyzer_credentials_win(self):
        values = {
            "RUN_ANALYZER_TELEGRAM_BOT_TOKEN": "analyzer-token",
            "RUN_ANALYZER_TELEGRAM_CHAT_ID": "analyzer-chat",
            "TELEGRAM_BOT_TOKEN": "legacy-token",
            "TELEGRAM_CHAT_ID": "legacy-chat",
        }
        self.assertEqual(
            "analyzer-token",
            notify._credential(
                "RUN_ANALYZER_TELEGRAM_BOT_TOKEN",
                "TELEGRAM_BOT_TOKEN",
                env=values,
            ),
        )
        self.assertEqual(
            "analyzer-chat",
            notify._credential(
                "RUN_ANALYZER_TELEGRAM_CHAT_ID",
                "TELEGRAM_CHAT_ID",
                env=values,
            ),
        )

    def test_blank_namespaced_credentials_fall_back_to_legacy(self):
        values = {
            "RUN_ANALYZER_TELEGRAM_BOT_TOKEN": "",
            "RUN_ANALYZER_TELEGRAM_CHAT_ID": "   ",
            "TELEGRAM_BOT_TOKEN": "legacy-token",
            "TELEGRAM_CHAT_ID": "legacy-chat",
        }
        self.assertEqual(
            "legacy-token",
            notify._credential(
                "RUN_ANALYZER_TELEGRAM_BOT_TOKEN",
                "TELEGRAM_BOT_TOKEN",
                env=values,
            ),
        )
        self.assertEqual(
            "legacy-chat",
            notify._credential(
                "RUN_ANALYZER_TELEGRAM_CHAT_ID",
                "TELEGRAM_CHAT_ID",
                env=values,
            ),
        )


if __name__ == "__main__":
    unittest.main()
