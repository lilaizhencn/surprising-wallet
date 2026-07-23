#!/usr/bin/env sh
set -eu

: "${BCH_REGTEST_RPC_USER:=wallet}"
: "${BCH_REGTEST_RPC_PASSWORD:=wallet123}"

# BCHN RPC remains bound to loopback. The relay is exposed only through the
# host launcher's 127.0.0.1 port mapping.
socat TCP-LISTEN:18445,fork,reuseaddr TCP:127.0.0.1:18443 &

exec bitcoind \
  -regtest=1 \
  -server=1 \
  -txindex=1 \
  -printtoconsole=1 \
  -datadir=/var/lib/bitcoincash \
  -rpcuser="${BCH_REGTEST_RPC_USER}" \
  -rpcpassword="${BCH_REGTEST_RPC_PASSWORD}" \
  -rpcbind=127.0.0.1 \
  -rpcallowip=127.0.0.1 \
  -rpcport=18443 \
  -rpcthreads="${BCH_REGTEST_RPC_THREADS:-32}" \
  -rpcworkqueue="${BCH_REGTEST_RPC_WORKQUEUE:-256}" \
  -fallbackfee=0.00001
