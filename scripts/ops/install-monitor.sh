#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 && $# -eq 0 ]] || {
  printf 'usage: install-monitor.sh (as root, on the wallet host)\n' >&2; exit 1;
}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
[[ -s /etc/surprising-wallet-monitor/monitor.env ]]
BACKUP=/var/backups/wallet-monitor/$(date -u +%Y%m%dT%H%M%SZ)
install -d -m 0700 "$BACKUP"
backup_file() {
  if [[ -f $1 ]]; then cp -a --parents "$1" "$BACKUP/"; fi
}
backup_file /etc/surprising-wallet-monitor/monitor.env
backup_file /usr/local/libexec/wallet-monitor.py
install -d -m 0755 /usr/local/libexec
install -m 0755 "$ROOT/scripts/ops/wallet_monitor.py" /usr/local/libexec/wallet-monitor.py
chmod 0600 /etc/surprising-wallet-monitor/monitor.env
for file in "$ROOT"/resources/infra/monitor/*.service "$ROOT"/resources/infra/monitor/*.timer; do
  backup_file "/etc/systemd/system/$(basename "$file")"
  install -m 0644 "$file" /etc/systemd/system/
done
backup_file /etc/logrotate.d/nginx
backup_file /etc/logrotate.d/wallet-deploy
backup_file /etc/systemd/system/logrotate.timer.d/wallet.conf
backup_file /etc/systemd/journald.conf.d/99-wallet-logs.conf
install -m 0644 "$ROOT/resources/infra/monitor/nginx.logrotate" /etc/logrotate.d/nginx
install -m 0644 "$ROOT/resources/infra/monitor/wallet-deploy.logrotate" /etc/logrotate.d/wallet-deploy
install -d /etc/systemd/system/logrotate.timer.d /etc/systemd/journald.conf.d
install -m 0644 "$ROOT/resources/infra/monitor/logrotate-hourly.conf" /etc/systemd/system/logrotate.timer.d/wallet.conf
install -m 0644 "$ROOT/resources/infra/monitor/journald-wallet.conf" /etc/systemd/journald.conf.d/99-wallet-logs.conf
logrotate --debug /etc/logrotate.conf >/dev/null 2>&1
systemctl restart systemd-journald
systemctl daemon-reload
systemctl enable --now wallet-monitor.timer
systemctl restart logrotate.timer
systemctl enable --now wallet-monitor-summary.timer
printf 'Installed wallet-host monitoring; previous files backed up at %s\n' "$BACKUP"
