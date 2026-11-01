#!/usr/bin/env bash
set -euo pipefail
umask 077
MPC_PACKAGE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
: "${MPC_BUILD_DIR:?set MPC_BUILD_DIR to the configured native build directory}"
MPC_CMAKE=${MPC_CMAKE:-cmake}
[[ $# -eq 1 && $1 = /* && $1 == *.tar.gz && ! -e $1 ]] || {
  printf 'usage: package-release.sh /absolute/path/new-release.tar.gz\n' >&2
  exit 1
}
MPC_PACKAGE_OUTPUT=$1
[[ -f "$MPC_BUILD_DIR/CMakeCache.txt" ]] || { printf 'configured native build required\n' >&2; exit 1; }
MPC_PACKAGE_STAGE=$(mktemp -d "${TMPDIR:-/tmp}/wallet-mpc-release.XXXXXX")
trap 'rm -rf -- "$MPC_PACKAGE_STAGE"' EXIT
# Do not package a stale executable. Run this only when no acceptance fixture is using this build.
"$MPC_CMAKE" --build "$MPC_BUILD_DIR" --target wallet-mpc-signer --parallel "${MPC_BUILD_JOBS:-4}"
"$MPC_CMAKE" --install "$MPC_BUILD_DIR" --component Runtime --prefix "$MPC_PACKAGE_STAGE/wallet-mpc-signer"
python3 - "$MPC_PACKAGE_ROOT" "$MPC_BUILD_DIR" "$MPC_PACKAGE_STAGE/wallet-mpc-signer" <<'MANIFEST'
import hashlib,json,pathlib,platform,re,subprocess,sys
source,build,stage=map(pathlib.Path,sys.argv[1:])
cache={}
for line in (build/'CMakeCache.txt').read_text().splitlines():
    match=re.match(r'([^#/:][^:]*):[^=]+=(.*)',line)
    if match: cache[match[1]]=match[2]
def git(*args):
    return subprocess.check_output(['git','-C',str(source),*args],text=True).strip()
pinned='0b716706f998633912ee2be9b9bf8909aed66088'
actual=subprocess.check_output(['git','-C',cache['CBMPC_SOURCE_DIR'],'rev-parse','HEAD'],text=True).strip()
if actual!=pinned: raise SystemExit('cb-mpc build dependency revision differs')
version=(pathlib.Path(cache['CBMPC_OPENSSL_ROOT'])/'include/openssl/opensslv.h').read_text()
if not re.search(r'#\s*define\s+OPENSSL_VERSION_STR\s+"3\.6\.4"',version):
    raise SystemExit('patched OpenSSL3.6.4 is required')
files={str(p.relative_to(stage)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(stage.rglob('*')) if p.is_file()}
manifest={'formatVersion':1,'sourceGitRevision':git('rev-parse','HEAD'),'sourceWorktreeDirty':bool(git('status','--porcelain')),
          'platform':platform.system(),'architecture':platform.machine(),'cbMpcRevision':pinned,'opensslVersion':'3.6.4',
          'opensslCryptoArchiveSha256':hashlib.sha256((pathlib.Path(cache['CBMPC_OPENSSL_ROOT'])/'lib/libcrypto.a').read_bytes()).hexdigest(),
          'files':files,'containsPrivateMaterial':False,
          'scope':'Build artifact manifest; acceptance and production activation are separate gates.'}
(stage/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
MANIFEST
tar -czf "$MPC_PACKAGE_OUTPUT" -C "$MPC_PACKAGE_STAGE" wallet-mpc-signer
python3 - "$MPC_PACKAGE_OUTPUT" <<'CHECKSUM'
import hashlib,pathlib,sys
p=pathlib.Path(sys.argv[1]); value=hashlib.sha256(p.read_bytes()).hexdigest()
p.with_name(p.name+'.sha256').write_text(value+'  '+p.name+'\n')
print('Native release archive:',p)
print('SHA256:',value)
CHECKSUM
