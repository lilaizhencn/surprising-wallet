#!/usr/bin/env python3
"""Small, read-only wallet host monitor. Runtime state contains no credentials."""

import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass
class Finding:
    key: str
    message: str
    confirmations: int = 2


SERVICE_NAMES = {
    "surprising-wallet-all.service": "钱包服务", "postgresql.service": "数据库服务",
    "nginx.service": "Nginx 网关", "wallet-certificate.timer": "证书续期定时器",
    "wallet-monitor.timer": "监控定时器", "wallet-monitor-summary.timer": "日报定时器",
    "logrotate.timer": "日志轮转定时器",
}
CHECK_NAMES = {
    "public_health": "公网接口", "local_health": "本机接口", "readiness": "钱包就绪状态",
    "resources": "服务器资源", "services": "系统服务", "database": "数据库检查",
    "logs": "日志检查", "certificate": "HTTPS 证书", "memory": "可用内存",
    "swap": "交换空间", "cpu": "CPU 使用率", "io_wait": "磁盘读写等待",
    "load": "系统负载", "disk": "磁盘空间", "inodes": "文件系统剩余索引",
    "reboot": "服务器重启", "db_connections": "数据库连接数", "blocked_queries": "数据库锁等待",
    "long_transactions": "数据库长事务", "dead_letters": "消息处理失败队列",
    "queue_backlog": "消息队列积压", "error_logs": "应用与数据库错误日志",
    "http_5xx": "接口服务端错误", "log_size": "应用日志占用", "oom": "系统内存溢出",
}


def check_name(key):
    if key.startswith(("service:", "restart:")):
        return SERVICE_NAMES.get(key.split(":", 1)[1], "系统服务")
    return CHECK_NAMES.get(key, "监控项目")


def notice(title, label, body, now=None):
    timestamp = dt.datetime.fromtimestamp(now if now is not None else time.time(),
                                         dt.timezone(dt.timedelta(hours=8))).strftime("%m-%d %H:%M:%S")
    return f"【钱包监控 · {title}】\n服务器：{label}\n时间：{timestamp}（北京时间）\n\n{body}"


def format_summary(label, metrics, findings, now):
    def value(key, suffix=""):
        return str(metrics[key]) + suffix if key in metrics else "未获取"

    def interface(key):
        return "正常" if metrics.get(key) is True else "异常或未获取"

    states = {"active": "正常", "inactive": "已停止", "failed": "失败",
              "activating": "启动中", "deactivating": "停止中"}
    lines = ["总体状态：" + ("正常" if not findings else "发现异常，请关注下方说明"), "",
             "CPU 使用率：" + value("cpu_percent", "%"),
             "可用内存：" + value("memory_available_mib", " MiB"),
             "钱包内存占用：" + value("wallet_memory_mib", " MiB"),
             "交换空间已用：" + value("swap_used_mib", " MiB"),
             "磁盘使用率：" + value("disk_used_percent", "%") + "；剩余：" + value("disk_free_gib", " GiB"),
             "系统负载（1 分钟）：" + value("load_1m"),
             "连续运行：" + value("uptime_hours", " 小时"), "",
             "公网接口：" + interface("public_health") + "；本机接口：" + interface("local_health")]
    for unit in ("surprising-wallet-all.service", "postgresql.service", "nginx.service"):
        lines.append(SERVICE_NAMES[unit] + "：" + states.get(metrics.get("services", {}).get(unit), "未获取"))
    database = metrics.get("database")
    if database:
        lines += [f"数据库连接：{database['connections']} / {database['max_connections']}",
                  f"等待锁的查询：{database['blocked_queries']}；长事务：{database['long_transactions']}",
                  f"待处理消息：{database['pending']}；最长等待：{database['oldest_seconds']:.0f} 秒",
                  f"处理失败的消息（死信）：{database['dead_letters']}"]
    else:
        lines.append("数据库与消息队列：未获取")
    lines += ["", "最近 5 分钟错误日志：" + value("errors_5m", " 条"),
              "应用日志占用：" + value("application_logs_mib", " MiB"),
              "HTTPS 证书剩余：" + value("certificate_days", " 天")]
    if "nginx_5m" in metrics:
        counts = metrics["nginx_5m"]
        lines.append(f"最近 5 分钟请求：{counts['requests']} 次，其中服务端错误 {counts['errors']} 次")
    if findings:
        lines += ["", "异常说明：", *["• " + finding.message for finding in findings]]
    return notice("每日状态摘要", label, "\n".join(lines), now)


def command(args, timeout=5, env=None):
    return subprocess.run(args, check=True, capture_output=True, text=True,
                          timeout=timeout, env=env).stdout.strip()


def health(url):
    request = urllib.request.Request(url, headers={"User-Agent": "wallet-monitor/1.0"})
    with urllib.request.urlopen(request, timeout=8) as response:
        return response.status == 200 and json.loads(response.read(8192))["status"] == "UP"


def deliver(message):
    """Never log exceptions containing the Bot API URL/token or response content."""
    token, chat = os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        print("telegram delivery failed: missing credentials", flush=True)
        return False
    payload = {"chat_id": chat, "text": message[:3900], "disable_web_page_preview": True}
    if os.environ.get("TELEGRAM_MESSAGE_THREAD_ID"):
        payload["message_thread_id"] = int(os.environ["TELEGRAM_MESSAGE_THREAD_ID"])
    request = urllib.request.Request(
        "https://api.telegram.org/bot" + token + "/sendMessage",
        data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            result = json.loads(response.read(65536))
        if result.get("ok"):
            return True
        print("telegram delivery failed: API rejected request", flush=True)
    except urllib.error.HTTPError as error:
        print(f"telegram delivery failed: HTTP {error.code}", flush=True)
    except Exception as error:
        print("telegram delivery failed: " + type(error).__name__, flush=True)
    return False


def transitions(state, findings, now, sender, label):
    """Confirm faults/recoveries, coalesce notifications, retry failed delivery."""
    incidents = state.setdefault("incidents", {})
    present = {finding.key: finding for finding in findings}
    pending = []
    changes = []
    for key, finding in present.items():
        item = incidents.setdefault(key, {"bad": 0, "good": 0, "active": False, "sent": 0})
        item.update(bad=item["bad"] + 1, good=0, message=finding.message)
        if item["bad"] >= finding.confirmations:
            if not item["active"] or now - item["sent"] >= 3600:
                item["pending"] = True
                pending.append(("告警" if not item["active"] else "持续告警") + "：" + finding.message)
                changes.append((key, True))
    for key, item in list(incidents.items()):
        if key in present:
            continue
        item.update(bad=0, good=item["good"] + 1)
        if item["active"] and item["good"] >= 2:
            pending.append("已恢复：" + check_name(key) + "，连续两次检查正常")
            changes.append((key, False))
        elif not item["active"] and item.get("pending") and item["good"] >= 2:
            pending.append("通知补发：" + check_name(key) + " 曾出现异常，现已恢复")
            changes.append((key, False))
        elif not item["active"] and not item.get("pending"):
            del incidents[key]
    if pending:
        message = notice("状态通知", label, "\n".join("• " + line for line in pending), now)
        if sender(message):
            for key, active in changes:
                if active:
                    incidents[key].update(active=True, sent=now, pending=False)
                else:
                    del incidents[key]
            state["last_delivery"] = now
        else:
            state["delivery_failed_at"] = now
            return False
    return True


def database_metrics():
    queues = ["wallet_sign_first", "wallet_sign_second", "wallet_sign_done",
              "wallet_withdraw", "wallet_deposit_event", "wallet_withdraw_event", "wallet_rbf"]
    live = " UNION ALL ".join(
        f"SELECT count(*) AS n, min(enqueued_at) AS oldest FROM pgmq.q_{q}" for q in queues)
    dead = " UNION ALL ".join(f"SELECT count(*) AS n FROM pgmq.q_{q}_dead" for q in queues)
    sql = """SELECT json_build_object(
        'connections', (SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend'),
        'max_connections', current_setting('max_connections')::int,
        'long_transactions', (SELECT count(*) FROM pg_stat_activity
          WHERE pid<>pg_backend_pid() AND xact_start<now()-interval '5 minutes'),
        'blocked_queries', (SELECT count(*) FROM pg_stat_activity
          WHERE wait_event_type='Lock' AND query_start<now()-interval '30 seconds'),
        'pending', (SELECT sum(n) FROM (LIVE) q),
        'oldest_seconds', (SELECT coalesce(extract(epoch FROM now()-min(oldest)),0) FROM (LIVE) q),
        'dead_letters', (SELECT sum(n) FROM (DEAD) q));""".replace("LIVE", live).replace("DEAD", dead)
    environment = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8",
                   "PGOPTIONS": "-c statement_timeout=3000 -c default_transaction_read_only=on"}
    # The monitor never loads wallet.env or accesses wallet secrets/business rows.
    return json.loads(command([
        "runuser", "-u", "postgres", "--", os.environ.get("WALLET_MONITOR_PSQL", "/opt/postgresql/18/bin/psql"),
        "-h", "/run/postgresql", "-X", "-At", "-v", "ON_ERROR_STOP=1",
        "-d", os.environ.get("WALLET_MONITOR_DB", "surprising_wallet"), "-c", sql], timeout=6, env=environment))


def nginx_counts(now):
    counts = {"requests": 0, "errors": 0}
    for path in (Path("/var/log/nginx/access.log.1"), Path("/var/log/nginx/access.log")):
        if not path.exists():
            continue
        with path.open("rb") as stream:
            size = stream.seek(0, 2)
            stream.seek(max(0, size - 2 * 1024 * 1024))
            lines = stream.read().decode(errors="replace").splitlines()
        for line in lines:
            match = re.search(r'\[([^\]]+)\] "[^"]*" (\d{3}) ', line)
            if not match:
                continue
            timestamp = dt.datetime.strptime(match[1], "%d/%b/%Y:%H:%M:%S %z").timestamp()
            if now - 300 <= timestamp <= now:
                counts["requests"] += 1
                counts["errors"] += int(int(match[2]) >= 500)
    return counts


def collect(state):
    now = time.time()
    metrics, findings = {}, []

    def check(name, callback):
        try:
            callback()
        except Exception as error:
            reason = "检查未完成"
            if isinstance(error, urllib.error.HTTPError):
                reason = f"接口返回 HTTP {error.code}"
            elif isinstance(error, (TimeoutError, subprocess.TimeoutExpired)):
                reason = "检查超时"
            elif isinstance(error, urllib.error.URLError):
                reason = "连接失败或证书校验失败"
            elif isinstance(error, FileNotFoundError):
                reason = "检查程序或文件缺失"
            elif isinstance(error, subprocess.CalledProcessError):
                reason = "检查命令执行失败"
            elif isinstance(error, (ValueError, KeyError)):
                reason = "返回数据格式异常"
            findings.append(Finding(name, check_name(name) + "：" + reason))

    def http_check(name, url):
        start = time.monotonic()
        metrics[name] = health(url)
        metrics[name + "_seconds"] = round(time.monotonic() - start, 2)
        if not metrics[name]:
            findings.append(Finding(name, check_name(name) + "：未就绪，健康状态异常"))

    public_url = os.environ["WALLET_MONITOR_PUBLIC_URL"]
    check("public_health", lambda: http_check("public_health", public_url))
    check("local_health", lambda: http_check("local_health", "http://127.0.0.1:8002/actuator/health"))
    check("readiness", lambda: http_check("readiness", "http://127.0.0.1:8002/actuator/health/readiness"))

    def resources():
        memory = {line.split()[0].rstrip(":"): int(line.split()[1])
                  for line in Path("/proc/meminfo").read_text().splitlines()}
        available = memory["MemAvailable"] / 1024
        swap = (memory["SwapTotal"] - memory["SwapFree"]) / 1024
        metrics.update(memory_available_mib=round(available), swap_used_mib=round(swap))
        if available < 200:
            findings.append(Finding("memory", f"内存不足：可用 {available:.0f} MiB，告警阈值 200 MiB", 1 if available < 100 else 3))
        if swap > 512:
            findings.append(Finding("swap", f"交换空间使用较多：已用 {swap:.0f} MiB，阈值 512 MiB", 3))
        cpu = list(map(int, Path("/proc/stat").read_text().splitlines()[0].split()[1:9]))
        previous = state.get("cpu")
        state["cpu"] = cpu
        if previous:
            total = sum(cpu) - sum(previous)
            if total > 0:
                busy = 100 * (1 - ((cpu[3] + cpu[4]) - (previous[3] + previous[4])) / total)
                metrics["cpu_percent"] = round(busy, 1)
                if busy > 90:
                    findings.append(Finding("cpu", f"CPU 使用率过高：{busy:.1f}%，阈值 90%", 3))
                io_wait = 100 * (cpu[4] - previous[4]) / total
                if io_wait > 20:
                    findings.append(Finding("io_wait", f"磁盘读写等待过高：{io_wait:.1f}%，阈值 20%", 3))
        load = os.getloadavg()[0]
        metrics["load_1m"] = round(load, 2)
        if load > (os.cpu_count() or 1) * 1.5:
            findings.append(Finding("load", f"系统负载过高：1 分钟负载 {load:.2f}，超过核数的 1.5 倍", 3))
        disk = shutil.disk_usage("/")
        used = disk.used / disk.total * 100
        metrics.update(disk_used_percent=round(used, 1), disk_free_gib=round(disk.free / 2**30, 1))
        if used > 80 or disk.free < 3 * 2**30:
            findings.append(Finding("disk", f"磁盘空间不足：已用 {used:.1f}%，剩余 {disk.free / 2**30:.1f} GiB；阈值为使用率 80% 或剩余 3 GiB", 1 if used > 90 else 2))
        stat = os.statvfs("/")
        if stat.f_files and stat.f_favail / stat.f_files < .2:
            findings.append(Finding("inodes", "文件系统剩余索引不足 20%，可能无法创建新文件"))
        boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        if state.get("boot") and state["boot"] != boot:
            findings.append(Finding("reboot", "检测到服务器重启", 1))
        state["boot"] = boot
        metrics["uptime_hours"] = round(float(Path("/proc/uptime").read_text().split()[0]) / 3600, 1)
    check("resources", resources)

    def services():
        units = ["surprising-wallet-all.service", "postgresql.service", "nginx.service",
                 "wallet-certificate.timer", "wallet-monitor.timer", "wallet-monitor-summary.timer", "logrotate.timer"]
        output = command(["systemctl", "show", *units, "-p", "Id", "-p", "ActiveState", "-p", "NRestarts", "-p", "MemoryCurrent"])
        metrics["services"] = {}
        restarts = state.setdefault("restarts", {})
        for block in output.split("\n\n"):
            values = dict(line.split("=", 1) for line in block.splitlines() if "=" in line)
            unit = values["Id"]
            metrics["services"][unit] = values.get("ActiveState", "unknown")
            if values.get("ActiveState") != "active":
                findings.append(Finding("service:" + unit, SERVICE_NAMES[unit] + "未正常运行"))
            count = int(values.get("NRestarts", "0"))
            if count > restarts.get(unit, count):
                findings.append(Finding("restart:" + unit, SERVICE_NAMES[unit] + "发生自动重启，请检查异常原因", 1))
            restarts[unit] = count
            if unit == "surprising-wallet-all.service":
                value = values.get("MemoryCurrent", "0")
                if value.isdigit():
                    metrics["wallet_memory_mib"] = round(int(value) / 2**20)
    check("services", services)

    def postgres():
        data = database_metrics()
        metrics["database"] = data
        if data["connections"] >= data["max_connections"] * .8:
            findings.append(Finding("db_connections", f"数据库连接数偏高：{data['connections']} / {data['max_connections']}，达到上限的 80%"))
        for key in ("blocked_queries", "long_transactions", "dead_letters"):
            if data[key]:
                findings.append(Finding(key, f"{check_name(key)}：{data[key]} 项需要处理", 1 if key == "dead_letters" else 2))
        if data["pending"] > 1000 or data["oldest_seconds"] > 300:
            findings.append(Finding("queue_backlog", f"消息队列积压：待处理 {data['pending']} 条，最久等待 {data['oldest_seconds']:.0f} 秒；阈值为 1000 条或 300 秒"))
    check("database", postgres)

    def logs():
        output = command(["journalctl", "-u", "surprising-wallet-all", "-u", "postgresql",
                          "--since", "5 minutes ago", "-n", "2000", "-o", "json", "--no-pager"])
        errors = sum(bool(re.search(r"\bERROR\b|\bFATAL\b", str(json.loads(line).get("MESSAGE", ""))))
                     for line in output.splitlines() if line.startswith("{"))
        metrics["errors_5m"] = errors
        if errors >= 5:
            findings.append(Finding("error_logs", f"错误日志增多：应用或数据库最近 5 分钟出现 {errors} 条错误，阈值 5 条", 1))
        kernel = command(["journalctl", "-k", "--since", "5 minutes ago", "-n", "1000", "--no-pager"])
        if re.search(r"out of memory|oom-kill|killed process", kernel, re.I):
            findings.append(Finding("oom", "最近 5 分钟发生系统内存溢出，进程可能被系统终止", 1))
        metrics["nginx_5m"] = nginx_counts(now)
        nginx = metrics["nginx_5m"]
        if nginx["errors"] >= 5 and nginx["errors"] / max(nginx["requests"], 1) >= .1:
            findings.append(Finding("http_5xx", f"接口错误率偏高：最近 5 分钟 {nginx['requests']} 次请求中有 {nginx['errors']} 次服务端错误，达到 5 次且占比超过 10%", 1))
        size = sum(path.stat().st_size for path in Path("/opt/surprising-wallet/logs").rglob("*") if path.is_file())
        metrics["application_logs_mib"] = round(size / 2**20, 1)
        if size > 1024**3:
            findings.append(Finding("log_size", "应用日志占用超过 1 GiB，请检查日志轮转"))
    check("logs", logs)

    def certificate():
        output = command(["openssl", "x509", "-in", "/etc/nginx/wallet-tls/fullchain.pem", "-noout", "-enddate"])
        days = (ssl.cert_time_to_seconds(output.split("=", 1)[1]) - now) / 86400
        metrics["certificate_days"] = round(days, 1)
        if days < 14:
            findings.append(Finding("certificate", f"源站 HTTPS 证书将在 {days:.1f} 天后到期，提醒阈值为 14 天", 1))
    check("certificate", certificate)
    return metrics, findings


def save(path, value):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.flush()
        os.fsync(output.fileno())
    temporary.chmod(0o600)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--summary", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--test-notification", action="store_true")
    parser.add_argument("--failure-notification", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    directory = Path(os.environ.get("WALLET_MONITOR_STATE_DIR", "/var/lib/surprising-wallet-monitor"))
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    label = os.environ.get("WALLET_MONITOR_LABEL", "wallet")
    if args.failure_notification:
        return 0 if deliver(notice("监控程序异常", label, "巡检程序执行失败或超时，请检查监控服务日志；服务器状态暂时无法可靠确认。")) else 1
    with (directory / "monitor.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.test_notification:
            ok = deliver(notice("告警测试", label, "这是一条通知链路测试，服务器未发生真实故障。\n正式告警将用中文说明异常、当前数值和阈值。"))
            if ok:
                ok = deliver(notice("恢复测试", label, "通知链路测试完成，告警与恢复消息均已成功投递。"))
            print("notification test delivered=" + str(ok))
            return 0 if ok else 1
        path = directory / "state.json"
        try:
            state = json.loads(path.read_text()) if path.exists() else {}
        except (ValueError, OSError):
            state = {}
            print("monitor state unreadable; rebuilding", flush=True)
        metrics, findings = collect(state)
        now = time.time()
        report = {"checked_at": now, "label": label, "metrics": metrics,
                  "findings": [finding.__dict__ for finding in findings]}
        if args.dry_run:
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0
        ok = transitions(state, findings, now, deliver, label)
        if args.summary:
            ok = deliver(format_summary(label, metrics, findings, now)) and ok
        state["last_check"] = now
        save(directory / "last-report.json", report)
        save(path, state)
        print(f"monitor completed; findings={len(findings)}; notification_ok={ok}", flush=True)
        return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
