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
                pending.append(("ALERT" if not item["active"] else "REMINDER") + ": " + finding.message)
                changes.append((key, True))
    for key, item in list(incidents.items()):
        if key in present:
            continue
        item.update(bad=0, good=item["good"] + 1)
        if item["active"] and item["good"] >= 2:
            pending.append("RECOVERED: " + key)
            changes.append((key, False))
        elif not item["active"] and item.get("pending") and item["good"] >= 2:
            pending.append("RESOLVED BEFORE DELIVERY: " + item["message"])
            changes.append((key, False))
        elif not item["active"] and not item.get("pending"):
            del incidents[key]
    if pending:
        message = f"[Wallet {label}]\n" + "\n".join(pending)
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


def collect(state, external=False):
    now = time.time()
    metrics, findings = {}, []

    def check(name, callback):
        try:
            callback()
        except Exception as error:
            findings.append(Finding(name, name + " check failed (" + type(error).__name__ + ")"))

    def http_check(name, url):
        start = time.monotonic()
        metrics[name] = health(url)
        metrics[name + "_seconds"] = round(time.monotonic() - start, 2)
        if not metrics[name]:
            findings.append(Finding(name, name + " is not UP"))

    public_url = os.environ["WALLET_MONITOR_PUBLIC_URL"]
    check("public_health", lambda: http_check("public_health", public_url))
    if external:
        return metrics, findings
    check("local_health", lambda: http_check("local_health", "http://127.0.0.1:8002/actuator/health"))
    check("readiness", lambda: http_check("readiness", "http://127.0.0.1:8002/actuator/health/readiness"))

    def resources():
        memory = {line.split()[0].rstrip(":"): int(line.split()[1])
                  for line in Path("/proc/meminfo").read_text().splitlines()}
        available = memory["MemAvailable"] / 1024
        swap = (memory["SwapTotal"] - memory["SwapFree"]) / 1024
        metrics.update(memory_available_mib=round(available), swap_used_mib=round(swap))
        if available < 200:
            findings.append(Finding("memory", f"Available RAM {available:.0f} MiB (<200 MiB)", 1 if available < 100 else 3))
        if swap > 512:
            findings.append(Finding("swap", f"Swap used {swap:.0f} MiB (>512 MiB)", 3))
        cpu = list(map(int, Path("/proc/stat").read_text().splitlines()[0].split()[1:9]))
        previous = state.get("cpu")
        state["cpu"] = cpu
        if previous:
            total = sum(cpu) - sum(previous)
            if total > 0:
                busy = 100 * (1 - ((cpu[3] + cpu[4]) - (previous[3] + previous[4])) / total)
                metrics["cpu_percent"] = round(busy, 1)
                if busy > 90:
                    findings.append(Finding("cpu", f"CPU busy {busy:.1f}% (>90%)", 3))
                io_wait = 100 * (cpu[4] - previous[4]) / total
                if io_wait > 20:
                    findings.append(Finding("io_wait", f"CPU I/O wait {io_wait:.1f}% (>20%)", 3))
        load = os.getloadavg()[0]
        metrics["load_1m"] = round(load, 2)
        if load > (os.cpu_count() or 1) * 1.5:
            findings.append(Finding("load", f"1-minute load {load:.2f} is high", 3))
        disk = shutil.disk_usage("/")
        used = disk.used / disk.total * 100
        metrics.update(disk_used_percent=round(used, 1), disk_free_gib=round(disk.free / 2**30, 1))
        if used > 80 or disk.free < 3 * 2**30:
            findings.append(Finding("disk", f"Disk used {used:.1f}%, free {disk.free / 2**30:.1f} GiB", 1 if used > 90 else 2))
        stat = os.statvfs("/")
        if stat.f_files and stat.f_favail / stat.f_files < .2:
            findings.append(Finding("inodes", "Less than 20% free filesystem inodes"))
        boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        if state.get("boot") and state["boot"] != boot:
            findings.append(Finding("reboot", "Host reboot detected", 1))
        state["boot"] = boot
        metrics["uptime_hours"] = round(float(Path("/proc/uptime").read_text().split()[0]) / 3600, 1)
    check("resources", resources)

    def services():
        units = ["surprising-wallet-all.service", "postgresql.service", "nginx.service",
                 "wallet-certificate.timer", "wallet-monitor.timer", "logrotate.timer"]
        output = command(["systemctl", "show", *units, "-p", "Id", "-p", "ActiveState", "-p", "NRestarts", "-p", "MemoryCurrent"])
        metrics["services"] = {}
        restarts = state.setdefault("restarts", {})
        for block in output.split("\n\n"):
            values = dict(line.split("=", 1) for line in block.splitlines() if "=" in line)
            unit = values["Id"]
            metrics["services"][unit] = values.get("ActiveState", "unknown")
            if values.get("ActiveState") != "active":
                findings.append(Finding("service:" + unit, unit + " is " + values.get("ActiveState", "unknown")))
            count = int(values.get("NRestarts", "0"))
            if count > restarts.get(unit, count):
                findings.append(Finding("restart:" + unit, unit + " automatically restarted", 1))
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
            findings.append(Finding("db_connections", f"PostgreSQL connections {data['connections']}/{data['max_connections']}"))
        for key in ("blocked_queries", "long_transactions", "dead_letters"):
            if data[key]:
                findings.append(Finding(key, f"PostgreSQL {key}: {data[key]}", 1 if key == "dead_letters" else 2))
        if data["pending"] > 1000 or data["oldest_seconds"] > 300:
            findings.append(Finding("queue_backlog", f"PGMQ pending={data['pending']}, oldest={data['oldest_seconds']:.0f}s"))
    check("database", postgres)

    def logs():
        output = command(["journalctl", "-u", "surprising-wallet-all", "-u", "postgresql",
                          "--since", "5 minutes ago", "-n", "2000", "-o", "json", "--no-pager"])
        errors = sum(bool(re.search(r"\bERROR\b|\bFATAL\b", str(json.loads(line).get("MESSAGE", ""))))
                     for line in output.splitlines() if line.startswith("{"))
        metrics["errors_5m"] = errors
        if errors >= 5:
            findings.append(Finding("error_logs", f"Application/database ERROR/FATAL events in 5m: {errors}", 1))
        kernel = command(["journalctl", "-k", "--since", "5 minutes ago", "-n", "1000", "--no-pager"])
        if re.search(r"out of memory|oom-kill|killed process", kernel, re.I):
            findings.append(Finding("oom", "Kernel OOM event in the last 5 minutes", 1))
        metrics["nginx_5m"] = nginx_counts(now)
        nginx = metrics["nginx_5m"]
        if nginx["errors"] >= 5 and nginx["errors"] / max(nginx["requests"], 1) >= .1:
            findings.append(Finding("http_5xx", f"Nginx 5xx in 5m: {nginx['errors']}/{nginx['requests']}", 1))
        size = sum(path.stat().st_size for path in Path("/opt/surprising-wallet/logs").rglob("*") if path.is_file())
        metrics["application_logs_mib"] = round(size / 2**20, 1)
        if size > 1024**3:
            findings.append(Finding("log_size", "Application logs exceed 1 GiB"))
    check("logs", logs)

    def certificate():
        output = command(["openssl", "x509", "-in", "/etc/nginx/wallet-tls/fullchain.pem", "-noout", "-enddate"])
        days = (ssl.cert_time_to_seconds(output.split("=", 1)[1]) - now) / 86400
        metrics["certificate_days"] = round(days, 1)
        if days < 14:
            findings.append(Finding("certificate", f"Origin TLS certificate expires in {days:.1f} days", 1))
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
        return 0 if deliver(f"[Wallet {label}] Monitor service failed or timed out; inspect its systemd journal.") else 1
    with (directory / "monitor.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.test_notification:
            ok = deliver(f"[Wallet {label}] TEST ALERT: monitoring delivery test; no real incident.")
            if ok:
                ok = deliver(f"[Wallet {label}] TEST RECOVERY: monitoring delivery test completed.")
            print("notification test delivered=" + str(ok))
            return 0 if ok else 1
        path = directory / "state.json"
        try:
            state = json.loads(path.read_text()) if path.exists() else {}
        except (ValueError, OSError):
            state = {}
            print("monitor state unreadable; rebuilding", flush=True)
        metrics, findings = collect(state, os.environ.get("WALLET_MONITOR_MODE") == "external")
        now = time.time()
        report = {"checked_at": now, "label": label, "metrics": metrics,
                  "findings": [finding.__dict__ for finding in findings]}
        if args.dry_run:
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0
        ok = transitions(state, findings, now, deliver, label)
        if args.summary:
            summary = f"[Wallet {label}] Daily status\n" + ("Healthy" if not findings else "ATTENTION")
            summary += "\n" + "\n".join(finding.message for finding in findings)
            summary += "\n" + json.dumps(metrics, ensure_ascii=False, indent=2)
            ok = deliver(summary) and ok
        state["last_check"] = now
        save(directory / "last-report.json", report)
        save(path, state)
        print(f"monitor completed; findings={len(findings)}; notification_ok={ok}", flush=True)
        return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
