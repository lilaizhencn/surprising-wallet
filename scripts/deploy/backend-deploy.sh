#!/usr/bin/env bash
set -euo pipefail
# Activate a CI-built, checksum-verified release; never initialize or reset a database.
[[ ${EUID} -eq 0 && $# -eq 2 && $1 =~ ^[0-9a-f]{40}$ ]] || exit 1
DEPLOY_SHA=$1
STAGING=$2
DEPLOY_ROOT=/opt/surprising-wallet-backend
RELEASE_DIR="$DEPLOY_ROOT/releases/$DEPLOY_SHA"
CURRENT_DIR="$DEPLOY_ROOT/current"
ENV_FILE=/etc/surprising-wallet/wallet.env
UNIT=surprising-wallet-all.service
UNIT_FILE="/etc/systemd/system/$UNIT"
HEALTH_URL=http://127.0.0.1:8002/actuator/health
[[ -s "$STAGING/wallet-server.jar" && -f "$ENV_FILE" ]]
/usr/bin/java -version 2>&1 | grep -Eq 'version "27([.\"]|$)'
systemctl is-active --quiet postgresql.service
# Only database credentials are passed to psql; never print environment contents.
set -a
source "$ENV_FILE"
set +a
[[ ${SW_WALLET_MODE:-all} == all ]]
PGUSER=${SW_DB_USERNAME:?} PGPASSWORD=${SW_DB_PASSWORD:?} \
  psql "${SW_DB_URL#jdbc:}" --set=ON_ERROR_STOP=1 --no-psqlrc >/dev/null <<'SQL'
DO $$ BEGIN
  IF to_regclass('public.wallet_task_lease') IS NULL OR to_regclass('public.wallet_outbox') IS NULL
     OR to_regclass('public.chain_fee_rate') IS NULL
     OR NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname='pgmq' AND extversion='1.11.1') THEN
    RAISE EXCEPTION 'wallet database prerequisites are missing';
  END IF;
END $$;
SQL
install -d -o root -g wallet -m 0750 "$DEPLOY_ROOT/releases"
if [[ -d $RELEASE_DIR ]]; then
  cmp -s "$STAGING/wallet-server.jar" "$RELEASE_DIR/wallet-server.jar" || {
    printf 'refusing to replace an existing commit with different bytes\n' >&2; exit 1;
  }
else
  install -d -o root -g wallet -m 0750 "$RELEASE_DIR"
  install -o root -g wallet -m 0640 "$STAGING/wallet-server.jar" "$RELEASE_DIR/wallet-server.jar"
fi
PREVIOUS_TARGET=$(readlink -f "$CURRENT_DIR" || true)
PREVIOUS_UNIT=$(mktemp)
HAD_UNIT=false
if [[ -f $UNIT_FILE ]]; then cp -a "$UNIT_FILE" "$PREVIOUS_UNIT"; HAD_UNIT=true; fi
rollback() {
  local status=$?
  trap - ERR
  systemctl stop "$UNIT" || true
  if [[ $HAD_UNIT == true ]]; then
    install -m 0644 "$PREVIOUS_UNIT" "$UNIT_FILE"
  else
    systemctl disable "$UNIT" || true
    rm -f "$UNIT_FILE"
  fi
  systemctl daemon-reload
  if [[ -n $PREVIOUS_TARGET && -d $PREVIOUS_TARGET ]]; then
    ln -sfn "$PREVIOUS_TARGET" "$CURRENT_DIR.next"
    mv -Tf "$CURRENT_DIR.next" "$CURRENT_DIR"
    systemctl start "$UNIT" || true
  else
    rm -f "$CURRENT_DIR"
  fi
  rm -f "$PREVIOUS_UNIT"
  printf 'deployment %s failed; previous release restored when available\n' "$DEPLOY_SHA" >&2
  exit "$status"
}
trap rollback ERR
install -o root -g root -m 0644 "$STAGING/surprising-wallet-all.service" "$UNIT_FILE"
systemctl daemon-reload
ln -sfn "$RELEASE_DIR" "$CURRENT_DIR.next"
mv -Tf "$CURRENT_DIR.next" "$CURRENT_DIR"
systemctl enable "$UNIT"
systemctl restart "$UNIT"
healthy=false
for _ in $(seq 1 60); do
  if systemctl is-active --quiet "$UNIT" && curl --fail --silent --max-time 2 "$HEALTH_URL" | grep -q '"status":"UP"'; then
    healthy=true
    break
  fi
  sleep 2
done
[[ $healthy == true ]]
trap - ERR
rm -f "$PREVIOUS_UNIT"
printf 'release %s healthy; all mode active\n' "$DEPLOY_SHA"
