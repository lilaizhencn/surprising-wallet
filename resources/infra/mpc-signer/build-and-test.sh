#!/usr/bin/env bash
set -euo pipefail

MPC_SOURCE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MPC_BUILD_DIR=${MPC_BUILD_DIR:-/tmp/surprising-wallet-mpc-build}
MPC_CMAKE=${MPC_CMAKE:-cmake}
MPC_CTEST=${MPC_CTEST:-ctest}
MPC_BUILD_JOBS=${MPC_BUILD_JOBS:-4}
MPC_CB_REVISION=0b716706f998633912ee2be9b9bf8909aed66088
MPC_OPENSSL_VERSION=3.6.4
MPC_OPENSSL_SHA256=9bffaa1ad1e07b354c21bd3324ec02fa15579f45a7d0494b3e74bc449b7333ef

for command in "$MPC_CMAKE" "$MPC_CTEST" git curl python3 make perl; do
  command -v "$command" >/dev/null || {
    printf 'missing native build prerequisite: %s\n' "$command" >&2
    exit 1
  }
done
case "$MPC_BUILD_JOBS" in ''|*[!0-9]*) printf 'invalid MPC_BUILD_JOBS\n' >&2; exit 1 ;; esac
if [[ "$MPC_BUILD_JOBS" -lt 1 ]]; then exit 1; fi
mkdir -p "$MPC_BUILD_DIR/dependencies"
MPC_BUILD_DIR=$(cd "$MPC_BUILD_DIR" && pwd)

if [[ -z "${CBMPC_SOURCE_DIR:-}" ]]; then
  CBMPC_SOURCE_DIR="$MPC_BUILD_DIR/dependencies/cb-mpc"
  if [[ ! -d "$CBMPC_SOURCE_DIR/.git" ]]; then
    git init "$CBMPC_SOURCE_DIR"
    git -C "$CBMPC_SOURCE_DIR" remote add origin https://github.com/coinbase/cb-mpc.git
  fi
  git -C "$CBMPC_SOURCE_DIR" fetch --depth=1 origin "$MPC_CB_REVISION"
  GIT_LFS_SKIP_SMUDGE=1 git -C "$CBMPC_SOURCE_DIR" checkout --detach "$MPC_CB_REVISION"
fi
if [[ $(git -C "$CBMPC_SOURCE_DIR" rev-parse HEAD) != "$MPC_CB_REVISION" ]]; then
  printf 'cb-mpc checkout does not match pinned revision\n' >&2
  exit 1
fi
git -C "$CBMPC_SOURCE_DIR" submodule update --init --depth=1 vendors/secp256k1

if [[ -z "${CBMPC_OPENSSL_ROOT:-}" ]]; then
  CBMPC_OPENSSL_ROOT="$MPC_BUILD_DIR/dependencies/openssl-install"
  if [[ ! -f "$CBMPC_OPENSSL_ROOT/lib/libcrypto.a" || ! -f "$CBMPC_OPENSSL_ROOT/lib/libssl.a" ]]; then
    MPC_OPENSSL_ARCHIVE="$MPC_BUILD_DIR/dependencies/openssl-$MPC_OPENSSL_VERSION.tar.gz"
    if [[ ! -f "$MPC_OPENSSL_ARCHIVE" ]]; then
      curl --fail --location --retry 3 \
        "https://github.com/openssl/openssl/releases/download/openssl-$MPC_OPENSSL_VERSION/openssl-$MPC_OPENSSL_VERSION.tar.gz" \
        --output "$MPC_OPENSSL_ARCHIVE"
    fi
    python3 - "$MPC_OPENSSL_ARCHIVE" "$MPC_OPENSSL_SHA256" <<'VERIFY'
import hashlib, pathlib, sys
actual = hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
if actual != sys.argv[2]:
    raise SystemExit('OpenSSL source SHA256 mismatch')
VERIFY
    tar -xzf "$MPC_OPENSSL_ARCHIVE" -C "$MPC_BUILD_DIR/dependencies"
    MPC_OPENSSL_SOURCE="$MPC_BUILD_DIR/dependencies/openssl-$MPC_OPENSSL_VERSION"
    # Same curve25519 symbol visibility change required by the pinned cb-mpc.
    # TLS remains enabled for the standalone node's authenticated transport.
    python3 - "$MPC_OPENSSL_SOURCE/crypto/ec/curve25519.c" <<'PATCH'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
path.write_text(re.sub(r'^static', '', path.read_text(), flags=re.MULTILINE))
PATCH
    (
      cd "$MPC_OPENSSL_SOURCE"
      ./Configure no-shared no-tests no-apps no-comp no-legacy \
        --prefix="$CBMPC_OPENSSL_ROOT" --libdir=lib
      make -j"$MPC_BUILD_JOBS"
      make install_sw
    )
  fi
fi

"$MPC_CMAKE" -S "$MPC_SOURCE_ROOT" -B "$MPC_BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DCBMPC_SOURCE_DIR="$CBMPC_SOURCE_DIR" \
  -DCBMPC_OPENSSL_ROOT="$CBMPC_OPENSSL_ROOT" \
  -DWALLET_MPC_MONERO_BIN_DIR="${MPC_MONERO_BIN_DIR:-}"
"$MPC_CMAKE" --build "$MPC_BUILD_DIR" --parallel "$MPC_BUILD_JOBS"
"$MPC_CTEST" --test-dir "$MPC_BUILD_DIR" --output-on-failure
