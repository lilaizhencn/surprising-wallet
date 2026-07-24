#!/usr/bin/env bash
set -euo pipefail

# surprising-wallet backend deploy script
# Invoked by GitHub Actions via SSH (command= restricted key).
# This script is self-contained: git pull → build → migrate → switch → verify.

if [[ ${EUID} -ne 0 ]]; then
  printf 'deploy must run as root\n' >&2
  exit 1
fi

REPO_DIR=/opt/surprising-wallet-backend/repo
RELEASE_DIR=/opt/surprising-wallet-backend/releases
CURRENT_DIR=/opt/surprising-wallet-backend/current
ENV_FILE=/etc/surprising-wallet/wallet.env
HEALTH_URL=http://127.0.0.1:8002/actuator/health
BRANCH=master

# ── 1. pull ──────────────────────────────────────────────────────────
if [[ ! -d $REPO_DIR ]]; then
  printf 'repo directory does not exist; clone it first:\n' >&2
  printf '  git clone %s %s\n' "${SW_REPO_URL:-<url>}" "$REPO_DIR" >&2
  exit 1
fi

cd "$REPO_DIR"
printf '=== git fetch ===\n'
git fetch origin "$BRANCH"

printf '=== git reset to origin/%s ===\n' "$BRANCH"
git reset --hard "origin/$BRANCH"

DEPLOY_SHA=$(git rev-parse HEAD)
printf 'deploy sha: %s\n' "$DEPLOY_SHA"

# ── 2. build ─────────────────────────────────────────────────────────
printf '=== mvn package ===\n'
mvn -pl wallet-api -am -DskipTests package -q

# ── 3. stage release ─────────────────────────────────────────────────
DEPLOY_RELEASE="$RELEASE_DIR/$DEPLOY_SHA"
JAR_SOURCE="$REPO_DIR/wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar"
SQL_SOURCE="$REPO_DIR/resources/docs/db"

if [[ ! -f $JAR_SOURCE ]]; then
  printf 'build did not produce wallet-api JAR\n' >&2
  exit 1
fi

install -d -m 0750 "$DEPLOY_RELEASE"
install -o wallet -g wallet -m 0640 "$JAR_SOURCE" "$DEPLOY_RELEASE/wallet-server.jar"

SQL_COUNT=0
if [[ -d $SQL_SOURCE ]]; then
  for f in "$SQL_SOURCE"/*.sql; do
    [[ -f $f ]] || continue
    install -o root -g wallet -m 0640 "$f" "$DEPLOY_RELEASE/"
    SQL_COUNT=$((SQL_COUNT + 1))
  done
fi
printf 'staged %d sql file(s)\n' "$SQL_COUNT"

chown root:wallet "$DEPLOY_RELEASE"
chmod 0750 "$DEPLOY_RELEASE"

# ── 4. migrate ───────────────────────────────────────────────────────
if [[ ! -f $ENV_FILE ]]; then
  printf 'env file %s is missing\n' "$ENV_FILE" >&2
  exit 1
fi
set -a
source "$ENV_FILE"
set +a

DB_URL=${SW_DB_URL#jdbc:}
if [[ $DB_URL != postgresql://127.0.0.1:* && $DB_URL != postgresql://localhost:* ]]; then
  printf 'automatic migration only permits loopback PostgreSQL\n' >&2
  exit 1
fi

for migration in "$DEPLOY_RELEASE"/*.sql; do
  [[ -f $migration ]] || continue
  printf 'running migration: %s\n' "$(basename "$migration")"
  PGUSER=${SW_DB_USERNAME:?SW_DB_USERNAME is required} \
  PGPASSWORD=${SW_DB_PASSWORD:?SW_DB_PASSWORD is required} \
    psql --set=ON_ERROR_STOP=1 "$DB_URL" --file="$migration"
done

# ── 5. switch ────────────────────────────────────────────────────────
PREVIOUS_TARGET=
if [[ -L $CURRENT_DIR ]]; then
  PREVIOUS_TARGET=$(readlink -f "$CURRENT_DIR")
fi

ln -sfn "$DEPLOY_RELEASE" "$CURRENT_DIR.next"
mv -Tf "$CURRENT_DIR.next" "$CURRENT_DIR"
systemctl restart surprising-wallet.service

# ── 6. verify ────────────────────────────────────────────────────────
healthy=false
for _ in $(seq 1 45); do
  if curl --fail --silent --max-time 2 "$HEALTH_URL" 2>/dev/null \
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

# ── 7. rollback ──────────────────────────────────────────────────────
printf 'backend release %s failed health check; rolling back\n' "$DEPLOY_SHA" >&2
if [[ -n $PREVIOUS_TARGET && -d $PREVIOUS_TARGET ]]; then
  ln -sfn "$PREVIOUS_TARGET" "$CURRENT_DIR.next"
  mv -Tf "$CURRENT_DIR.next" "$CURRENT_DIR"
  systemctl restart surprising-wallet.service
fi
exit 1
