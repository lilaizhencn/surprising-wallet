#!/usr/bin/env bash
set -euo pipefail

# surprising-wallet backend deploy script
# Server-side: git pull → build all wallet jars → migrate → switch → verify.
# Invoked by GitHub Actions via SSH (command= restricted key).
# Migrations are idempotent: each SQL file runs at most once (tracked in _deploy_migrations).

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
TRIGGER_SOURCE="$REPO_DIR/scripts/deploy/backend-deploy-trigger.sh"
TRIGGER_TARGET=/usr/local/sbin/surprising-wallet-backend-deploy
TRIGGER_BACKUP=/usr/local/sbin/surprising-wallet-backend-deploy.before-durable-20260805
SYSTEMD_DIR=/etc/systemd/system
SYSTEMD_UNITS=(surprising-wallet.service surprising-wallet-sig1.service surprising-wallet-sig2.service)

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

# The restricted SSH key invokes a root-owned wrapper outside the repository.
# Refresh that wrapper from the checked-out source so the next invocation waits
# for the real build, migration and health result instead of returning early.
if [[ -f $TRIGGER_SOURCE ]]; then
  if [[ -f $TRIGGER_TARGET && ! -f $TRIGGER_BACKUP ]]; then
    install -o root -g root -m 0750 "$TRIGGER_TARGET" "$TRIGGER_BACKUP"
  fi
  install -o root -g root -m 0750 "$TRIGGER_SOURCE" "$TRIGGER_TARGET"
  printf 'updated deployment trigger: %s\n' "$TRIGGER_TARGET"
fi

# ── 2. build ─────────────────────────────────────────────────────────
printf '=== mvn package (wallet-api + wallet-sig1 + wallet-sig2) ===\n'
mvn -DskipTests package -q

# ── 3. stage release ─────────────────────────────────────────────────
DEPLOY_RELEASE="$RELEASE_DIR/$DEPLOY_SHA"
JAR_SOURCE="$REPO_DIR/wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar"
SIG1_JAR_SOURCE="$REPO_DIR/wallet-sig1/target/wallet-sig1-1.0.0-SNAPSHOT.jar"
SIG2_JAR_SOURCE="$REPO_DIR/wallet-sig2/target/wallet-sig2-1.0.0-SNAPSHOT.jar"
SQL_SOURCE="$REPO_DIR/resources/docs/db"

if [[ ! -f $JAR_SOURCE || ! -f $SIG1_JAR_SOURCE || ! -f $SIG2_JAR_SOURCE ]]; then
  printf 'build did not produce all wallet JARs\n' >&2
  exit 1
fi

install -d -m 0750 "$DEPLOY_RELEASE"
install -o wallet -g wallet -m 0640 "$JAR_SOURCE" "$DEPLOY_RELEASE/wallet-server.jar"
install -o wallet -g wallet -m 0640 "$SIG1_JAR_SOURCE" "$DEPLOY_RELEASE/wallet-sig1.jar"
install -o wallet -g wallet -m 0640 "$SIG2_JAR_SOURCE" "$DEPLOY_RELEASE/wallet-sig2.jar"
install -d -o root -g wallet -m 0750 "$DEPLOY_RELEASE/.previous-systemd"

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

# ── 4. migrate (idempotent: each file runs at most once) ─────────────
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

PGUSER=${SW_DB_USERNAME:?SW_DB_USERNAME is required}
PGPASSWORD=${SW_DB_PASSWORD:?SW_DB_PASSWORD is required}
export PGUSER PGPASSWORD

# ensure migration tracking table (idempotent)
psql --set=ON_ERROR_STOP=1 "$DB_URL" -c "
  CREATE TABLE IF NOT EXISTS _deploy_migrations (
    filename text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
  )"

for migration in "$DEPLOY_RELEASE"/*.sql; do
  [[ -f $migration ]] || continue
  name=$(basename "$migration")
  already=$(psql -tA "$DB_URL" -c "COPY (SELECT 1 FROM _deploy_migrations WHERE filename='$name') TO STDOUT" 2>/dev/null)
  if [[ -n $already ]]; then
    printf 'skipped (already applied): %s\n' "$name"
    continue
  fi
  printf 'running migration: %s\n' "$name"
  psql --set=ON_ERROR_STOP=1 "$DB_URL" --file="$migration"
  psql "$DB_URL" -c "INSERT INTO _deploy_migrations(filename) VALUES('$name') ON CONFLICT DO NOTHING"
done

printf '=== verify durable processing schema ===\n'
psql --set=ON_ERROR_STOP=1 "$DB_URL" <<'SQL'
DO $$
BEGIN
  IF to_regclass('public.wallet_task_lease') IS NULL
     OR to_regclass('public.wallet_outbox') IS NULL THEN
    RAISE EXCEPTION 'durable processing tables are missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'withdrawal_order'
       AND column_name = 'next_attempt_at'
  ) THEN
    RAISE EXCEPTION 'withdrawal_order lease columns are missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'chain_signing_transaction'
       AND column_name = 'broadcast_lease_until'
  ) THEN
    RAISE EXCEPTION 'chain_signing_transaction broadcast lease columns are missing';
  END IF;
END
$$;
SQL

# ── 5. switch ────────────────────────────────────────────────────────
PREVIOUS_TARGET=
if [[ -L $CURRENT_DIR ]]; then
  PREVIOUS_TARGET=$(readlink -f "$CURRENT_DIR")
fi

for unit in "${SYSTEMD_UNITS[@]}"; do
  if [[ -f "$SYSTEMD_DIR/$unit" ]]; then
    install -o root -g root -m 0644 "$SYSTEMD_DIR/$unit" "$DEPLOY_RELEASE/.previous-systemd/$unit"
  fi
done

install -o root -g root -m 0644 "$REPO_DIR/resources/infra/systemd/surprising-wallet.service" \
  /etc/systemd/system/surprising-wallet.service
install -o root -g root -m 0644 "$REPO_DIR/resources/infra/systemd/surprising-wallet-sig1.service" \
  /etc/systemd/system/surprising-wallet-sig1.service
install -o root -g root -m 0644 "$REPO_DIR/resources/infra/systemd/surprising-wallet-sig2.service" \
  /etc/systemd/system/surprising-wallet-sig2.service
systemctl daemon-reload

ln -sfn "$DEPLOY_RELEASE" "$CURRENT_DIR.next"
mv -Tf "$CURRENT_DIR.next" "$CURRENT_DIR"
systemctl restart surprising-wallet.service surprising-wallet-sig1.service surprising-wallet-sig2.service

# ── 6. verify ────────────────────────────────────────────────────────
healthy=false
for _ in $(seq 1 45); do
  if curl --fail --silent --max-time 2 "$HEALTH_URL" 2>/dev/null \
      | grep -q '"status":"UP"' \
      && systemctl is-active --quiet surprising-wallet.service \
      && systemctl is-active --quiet surprising-wallet-sig1.service \
      && systemctl is-active --quiet surprising-wallet-sig2.service; then
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
  for unit in "${SYSTEMD_UNITS[@]}"; do
    if [[ -f "$DEPLOY_RELEASE/.previous-systemd/$unit" ]]; then
      install -o root -g root -m 0644 "$DEPLOY_RELEASE/.previous-systemd/$unit" "$SYSTEMD_DIR/$unit"
    fi
  done
  systemctl daemon-reload
  systemctl restart "${SYSTEMD_UNITS[@]}"
fi
exit 1
