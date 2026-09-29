import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import urllib.error

SPEC = importlib.util.spec_from_file_location("wallet_monitor", Path(__file__).parents[1] / "wallet_monitor.py")
monitor = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = monitor
SPEC.loader.exec_module(monitor)


class AlertStateTest(unittest.TestCase):
    def setUp(self):
        self.state = {}
        self.sent = []

    def sender(self, message):
        self.sent.append(message)
        return True

    def tick(self, findings, now, sender=None):
        return monitor.transitions(self.state, findings, now, sender or self.sender, "test")

    def test_fault_confirmation_dedup_reminder_and_recovery(self):
        fault = [monitor.Finding("health", "not UP")]
        self.tick(fault, 0)
        self.assertEqual([], self.sent)
        self.tick(fault, 60)
        self.tick(fault, 120)
        self.assertEqual(1, len(self.sent))
        self.tick(fault, 3660)
        self.assertIn("REMINDER", self.sent[-1])
        self.tick([], 3720)
        self.assertEqual(2, len(self.sent))
        self.tick([], 3780)
        self.assertIn("RECOVERED", self.sent[-1])
        self.assertEqual({}, self.state["incidents"])

    def test_transient_failure_is_silent(self):
        self.tick([monitor.Finding("health", "not UP")], 0)
        self.tick([], 60)
        self.assertEqual([], self.sent)

    def test_failed_delivery_is_retried_without_false_ack(self):
        fault = [monitor.Finding("oom", "OOM", 1)]
        self.assertFalse(self.tick(fault, 0, lambda _: False))
        self.assertFalse(self.state["incidents"]["oom"]["active"])
        self.tick(fault, 60)
        self.assertIn("ALERT", self.sent[-1])
        self.assertTrue(self.state["incidents"]["oom"]["active"])

    def test_transient_event_is_not_lost_when_telegram_is_down(self):
        self.tick([monitor.Finding("restart", "service restarted", 1)], 0, lambda _: False)
        self.tick([], 60)
        self.tick([], 120)
        self.assertIn("RESOLVED BEFORE DELIVERY: service restarted", self.sent[-1])

    def test_failed_recovery_notification_retries(self):
        self.tick([monitor.Finding("oom", "OOM", 1)], 0)
        self.tick([], 60)
        self.tick([], 120, lambda _: False)
        self.assertTrue(self.state["incidents"]["oom"]["active"])
        self.tick([], 180)
        self.assertIn("RECOVERED", self.sent[-1])

    def test_fault_flapping_resets_recovery_confirmation(self):
        fault = [monitor.Finding("disk", "disk full", 1)]
        self.tick(fault, 0)
        self.tick([], 60)
        self.tick(fault, 120)
        self.tick([], 180)
        self.assertEqual(1, len(self.sent))
        self.tick([], 240)
        self.assertEqual(2, len(self.sent))

    def test_telegram_exception_does_not_disclose_token(self):
        with patch.dict(monitor.os.environ, TELEGRAM_BOT_TOKEN="private-test-token", TELEGRAM_CHAT_ID="123"), \
             patch.object(monitor.urllib.request, "urlopen", side_effect=urllib.error.URLError("private-test-token")), \
             patch("builtins.print") as output:
            self.assertFalse(monitor.deliver("test"))
        self.assertNotIn("private-test-token", str(output.call_args_list))

    def test_external_probe_failure_becomes_finding(self):
        with patch.dict(monitor.os.environ, WALLET_MONITOR_PUBLIC_URL="https://example.invalid/health"), \
             patch.object(monitor, "health", side_effect=TimeoutError):
            _, findings = monitor.collect({}, external=True)
        self.assertEqual("public_health", findings[0].key)


if __name__ == "__main__":
    unittest.main()
