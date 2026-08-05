#!/usr/bin/env bash
set -euo pipefail

DEPLOY_SCRIPT=/opt/surprising-wallet-backend/repo/scripts/deploy/backend-deploy.sh
DEPLOY_LOG=/var/log/surprising-wallet-deploy.log
DEPLOY_LOCK=/run/lock/surprising-wallet-backend-deploy.lock

if [[ ! -f $DEPLOY_SCRIPT ]]; then
  printf 'backend deploy script does not exist: %s\n' "$DEPLOY_SCRIPT" >&2
  exit 1
fi

# Wait for an in-progress deployment instead of returning before the server has
# built, migrated, restarted and health-checked the release. Each queued run
# fetches origin/master, so it always deploys the newest commit.
flock "$DEPLOY_LOCK" bash "$DEPLOY_SCRIPT" \
  >>"$DEPLOY_LOG" 2>&1

printf 'backend deployment verified\n'
