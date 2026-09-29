#!/usr/bin/env bash
set -euo pipefail
# Installed as the forced command of the dedicated GitHub deployment key.
if [[ ${EUID} -ne 0 ]]; then exit 1; fi
if [[ ! ${SSH_ORIGINAL_COMMAND:-} =~ ^deploy\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  printf 'expected: deploy <commit-sha> <archive-sha256>\n' >&2
  exit 1
fi
RELEASE_SHA=${BASH_REMATCH[1]}
ARCHIVE_SHA=${BASH_REMATCH[2]}
exec 9>/run/lock/surprising-wallet-backend-deploy.lock
flock -w 900 9
umask 077
STAGING=$(mktemp -d /var/tmp/wallet-release.XXXXXX)
trap 'rm -rf -- "$STAGING"' EXIT
# The archive contains built artifacts only; the host never builds application code.
head -c 629145601 > "$STAGING/release.tar.gz"
[[ $(stat -c %s "$STAGING/release.tar.gz") -le 629145600 ]]
printf '%s  %s\n' "$ARCHIVE_SHA" "$STAGING/release.tar.gz" | sha256sum -c - >/dev/null
python3 - "$STAGING" <<'PY'
import pathlib, shutil, sys, tarfile
root = pathlib.Path(sys.argv[1])
expected = {'wallet-server.jar', 'surprising-wallet-all.service', 'backend-deploy.sh', 'backend-deploy-trigger.sh'}
with tarfile.open(root / 'release.tar.gz', 'r:gz') as archive:
    members = archive.getmembers()
    if len(members) != len(expected) or {m.name for m in members} != expected:
        raise SystemExit('unexpected release files')
    if any(not m.isfile() for m in members) or sum(m.size for m in members) > 629145600:
        raise SystemExit('invalid release archive')
    for member in members:
        with archive.extractfile(member) as source, (root / member.name).open('wb') as target:
            shutil.copyfileobj(source, target)
PY
bash "$STAGING/backend-deploy.sh" "$RELEASE_SHA" "$STAGING" 2>&1 | tee -a /var/log/surprising-wallet-deploy.log
install -o root -g root -m 0750 "$STAGING/backend-deploy-trigger.sh" /usr/local/sbin/surprising-wallet-backend-deploy.next
mv -f /usr/local/sbin/surprising-wallet-backend-deploy.next /usr/local/sbin/surprising-wallet-backend-deploy
printf 'backend deployment verified\n'
