#!/usr/bin/env sh
set -eu

: "${DOGE_REGTEST_RPC_USER:=wallet}"
: "${DOGE_REGTEST_RPC_PASSWORD:=wallet123}"

# Dogecoin Core is intentionally bound to loopback. socat exposes it only to
# Docker's port mapping, which the host launcher binds to 127.0.0.1.
socat TCP-LISTEN:22556,fork,reuseaddr TCP:127.0.0.1:22555 &

exec dogecoind \
  -regtest=1 \
  -server=1 \
  -txindex=1 \
  -printtoconsole=1 \
  -datadir=/var/lib/dogecoin \
  -wallet=regtest-funder \
  -rpcuser="${DOGE_REGTEST_RPC_USER}" \
  -rpcpassword="${DOGE_REGTEST_RPC_PASSWORD}" \
  -rpcbind=127.0.0.1 \
  -rpcallowip=127.0.0.1 \
  -rpcport=22555
