#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  printf 'backend activation must run as root\n' >&2
  exit 1
fi

if [[ $# -ne 1 || ! $1 =~ ^[0-9a-f]{40}$ ]]; then
  printf 'usage: %s <40-character-git-sha>\n' "$0" >&2
  exit 1
fi

DEPLOY_SHA=$1
DEPLOY_ROOT=/opt/surprising-wallet-backend
DEPLOY_RELEASE="$DEPLOY_ROOT/releases/$DEPLOY_SHA"
DEPLOY_CURRENT="$DEPLOY_ROOT/current"
DEPLOY_JAR="$DEPLOY_RELEASE/wallet-server.jar"
DEPLOY_MIGRATION="$DEPLOY_RELEASE/20260723_eip7702_native_collection_and_batch_withdrawal.sql"
DEPLOY_ENV=/etc/surprising-wallet/wallet.env
DEPLOY_HEALTH_URL=http://127.0.0.1:8002/actuator/health

if [[ ! -f $DEPLOY_JAR || ! -s $DEPLOY_JAR || ! -f $DEPLOY_MIGRATION ]]; then
  printf 'release %s is incomplete\n' "$DEPLOY_SHA" >&2
  exit 1
fi
if [[ ! -f $DEPLOY_ENV ]]; then
  printf 'missing backend environment file %s\n' "$DEPLOY_ENV" >&2
  exit 1
fi

chown root:wallet "$DEPLOY_RELEASE"
chmod 0750 "$DEPLOY_RELEASE"
chown wallet:wallet "$DEPLOY_JAR"
chmod 0640 "$DEPLOY_JAR"
chown root:wallet "$DEPLOY_MIGRATION"
chmod 0640 "$DEPLOY_MIGRATION"

set -a
# shellcheck disable=SC1090
source "$DEPLOY_ENV"
set +a

DB_URL=${SW_DB_URL#jdbc:}
if [[ $DB_URL != postgresql://127.0.0.1:* && $DB_URL != postgresql://localhost:* ]]; then
  printf 'automatic migration only permits the loopback PostgreSQL configured on this host\n' >&2
  exit 1
fi

PGUSER=${SW_DB_USERNAME:?SW_DB_USERNAME is required} \
PGPASSWORD=${SW_DB_PASSWORD:?SW_DB_PASSWORD is required} \
  psql --set=ON_ERROR_STOP=1 "$DB_URL" --file="$DEPLOY_MIGRATION"

PREVIOUS_TARGET=
if [[ -L $DEPLOY_CURRENT ]]; then
  PREVIOUS_TARGET=$(readlink -f "$DEPLOY_CURRENT")
fi

ln -sfn "$DEPLOY_RELEASE" "$DEPLOY_ROOT/current.next"
mv -Tf "$DEPLOY_ROOT/current.next" "$DEPLOY_CURRENT"
systemctl restart surprising-wallet.service

healthy=false
for _ in $(seq 1 45); do
  if curl --fail --silent --show-error --max-time 2 "$DEPLOY_HEALTH_URL" \
      | grep -q '"status":"UP"'; then
    healthy=true
    break
  fi
  sleep 2
done

if [[ $healthy == true ]]; then
  printf 'backend release %s is healthy\n' "$DEPLOY_SHA"
  exit 0
fi

printf 'backend release %s failed health check; rolling back\n' "$DEPLOY_SHA" >&2
if [[ -n $PREVIOUS_TARGET && -d $PREVIOUS_TARGET ]]; then
  ln -sfn "$PREVIOUS_TARGET" "$DEPLOY_ROOT/current.next"
  mv -Tf "$DEPLOY_ROOT/current.next" "$DEPLOY_CURRENT"
  systemctl restart surprising-wallet.service
fi
exit 1
