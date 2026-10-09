#!/usr/bin/env bash
set -euo pipefail
# Root owns deployment; the isolated build user only pulls and compiles public source.
[[ ${EUID} -eq 0 ]] || exit 1
exec 9>/run/lock/surprising-wallet-backend-deploy.lock
flock -n 9 || exit 0
SOURCE=/opt/surprising-wallet-source
CURRENT=/opt/surprising-wallet-backend/current
JAVA_HOME=$(dirname "$(dirname "$(readlink -f /usr/bin/java)")")
cd "$SOURCE"
as_builder() { runuser -u wallet-build -- "$@"; }
[[ $(as_builder git branch --show-current) == master ]]
[[ -z $(as_builder git status --porcelain --untracked-files=no) ]]
as_builder git pull --ff-only origin master
SHA=$(as_builder git rev-parse HEAD)
[[ $SHA =~ ^[0-9a-f]{40}$ ]]
if [[ $(readlink -f "$CURRENT" || true) == "/opt/surprising-wallet-backend/releases/$SHA" ]]; then
  printf 'already deployed %s; no update\n' "$SHA"
  exit 0
fi
# Compare with the deployed commit so failed builds are retried on the next tick.
printf 'building %s\n' "$SHA"
as_builder env JAVA_HOME="$JAVA_HOME" MAVEN_SKIP_RC=true LANG=C.UTF-8 MAVEN_OPTS='-Xms64m -Xmx256m' \
  mvn -B -ntp -pl wallet-api -am clean package \
  '-Dtest=!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.compiler.meminitial=64m -Dmaven.compiler.maxmem=512m \
  -DargLine=-Xmx256m "-Dproject.build.outputTimestamp=$(as_builder git show -s --format=%cI HEAD)"
STAGING=$(mktemp -d /var/tmp/wallet-source-release.XXXXXX)
trap 'rm -rf -- "$STAGING"' EXIT
install -m 0600 wallet-api/target/wallet-api-1.0.0-SNAPSHOT.jar "$STAGING/wallet-server.jar"
install -m 0600 resources/infra/systemd/surprising-wallet-all.service "$STAGING/"
# The build is complete before the existing service is restarted; activation rolls back on failure.
bash scripts/deploy/backend-deploy.sh "$SHA" "$STAGING"
install -o root -g root -m 0750 scripts/deploy/backend-auto-update.sh /usr/local/sbin/surprising-wallet-auto-update
install -o root -g root -m 0644 resources/infra/systemd/surprising-wallet-auto-update.{service,timer} /etc/systemd/system/
systemctl daemon-reload
printf 'source deployment verified: %s\n' "$SHA"
