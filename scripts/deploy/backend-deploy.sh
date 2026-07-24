#!/usr/bin/env bash
set -euo pipefail

# surprising-wallet backend deploy script
# Triggered by GitHub Actions via SSH.
# Must be installed on the server as /usr/local/sbin/surprising-wallet-backend-deploy

REPO_DIR="${REPO_DIR:-/opt/surprising-wallet-backend/repo}"
BRANCH="${BRANCH:-master}"

if [ ! -d "$REPO_DIR" ]; then
  echo "ERROR: repo directory $REPO_DIR does not exist"
  exit 1
fi

cd "$REPO_DIR"

echo "=== git fetch ==="
git fetch origin "$BRANCH"

echo "=== git reset to origin/$BRANCH ==="
git reset --hard "origin/$BRANCH"

echo "=== mvn package (skip tests) ==="
mvn -pl wallet-api -am -DskipTests package -q

echo "=== restart surprising-wallet ==="
sudo systemctl restart surprising-wallet

echo "=== deploy done ==="
