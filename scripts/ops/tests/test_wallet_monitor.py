import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
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
        self.assertIn("持续告警", self.sent[-1])
        self.tick([], 3720)
        self.assertEqual(2, len(self.sent))
        self.tick([], 3780)
        self.assertIn("已恢复", self.sent[-1])
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
        self.assertIn("告警", self.sent[-1])
        self.assertTrue(self.state["incidents"]["oom"]["active"])

    def test_transient_event_is_not_lost_when_telegram_is_down(self):
        self.tick([monitor.Finding("restart:surprising-wallet-all.service", "钱包服务发生自动重启", 1)], 0, lambda _: False)
        self.tick([], 60)
        self.tick([], 120)
        self.assertIn("通知补发：钱包服务 曾出现异常，现已恢复", self.sent[-1])

    def test_failed_recovery_notification_retries(self):
        self.tick([monitor.Finding("oom", "OOM", 1)], 0)
        self.tick([], 60)
        self.tick([], 120, lambda _: False)
        self.assertTrue(self.state["incidents"]["oom"]["active"])
        self.tick([], 180)
        self.assertIn("已恢复", self.sent[-1])

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

    def test_summary_is_readable_chinese_with_values(self):
        message = monitor.format_summary("阿里云钱包", {
            "cpu_percent": 4.2, "memory_available_mib": 650,
            "disk_used_percent": 19.0, "disk_free_gib": 30.5,
            "public_health": True, "local_health": True,
            "services": {"surprising-wallet-all.service": "active"},
            "database": {"connections": 7, "max_connections": 30,
                         "blocked_queries": 0, "long_transactions": 0,
                         "pending": 3, "oldest_seconds": 12.5, "dead_letters": 0},
        }, [], 0)
        self.assertIn("CPU 使用率：4.2%", message)
        self.assertIn("可用内存：650 MiB", message)
        self.assertIn("数据库连接：7 / 30", message)
        self.assertIn("钱包服务：正常", message)
        self.assertIn("北京时间", message)
        self.assertNotIn("cpu_percent", message)
        self.assertNotIn("{", message)

    def test_summary_reports_missing_data_and_faults(self):
        message = monitor.format_summary("测试", {}, [
            monitor.Finding("memory", "内存不足：可用 90 MiB，告警阈值 200 MiB")], 0)
        self.assertIn("可用内存：未获取", message)
        self.assertIn("数据库与消息队列：未获取", message)
        self.assertIn("发现异常", message)
        self.assertIn("可用 90 MiB", message)

    def test_recovery_uses_chinese_service_name(self):
        self.tick([monitor.Finding("service:postgresql.service", "数据库服务未正常运行", 1)], 0)
        self.tick([], 60)
        self.tick([], 120)
        self.assertIn("已恢复：数据库服务", self.sent[-1])
        self.assertNotIn("postgresql.service", self.sent[-1])

    def test_http_200_with_down_status_is_not_healthy(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = b'{"status":"DOWN"}'
        with patch.object(monitor.urllib.request, "urlopen", return_value=response):
            self.assertFalse(monitor.health("https://example.invalid/health"))


if __name__ == "__main__":
    unittest.main()
