#!/usr/bin/env sh
set -eu

: "${BTC_REGTEST_RPC_USER:=wallet}"
: "${BTC_REGTEST_RPC_PASSWORD:=wallet123}"

# Bitcoin Core RPC remains bound to loopback. The relay is exposed only through
# the host launcher's 127.0.0.1 port mapping.
socat TCP-LISTEN:18446,fork,reuseaddr TCP:127.0.0.1:18443 &

exec bitcoind \
  -regtest=1 \
  -server=1 \
  -txindex=1 \
  -printtoconsole=1 \
  -datadir=/var/lib/bitcoin \
  -rpcuser="${BTC_REGTEST_RPC_USER}" \
  -rpcpassword="${BTC_REGTEST_RPC_PASSWORD}" \
  -rpcbind=127.0.0.1 \
  -rpcallowip=127.0.0.1 \
  -rpcport=18443 \
  -rpcthreads="${BTC_REGTEST_RPC_THREADS:-32}" \
  -rpcworkqueue="${BTC_REGTEST_RPC_WORKQUEUE:-256}" \
  -fallbackfee=0.00001
