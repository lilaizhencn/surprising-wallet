#!/usr/bin/env sh
set -eu

: "${LTC_REGTEST_RPC_USER:=wallet}"
: "${LTC_REGTEST_RPC_PASSWORD:=wallet123}"

# Litecoin Core RPC remains bound to loopback. The relay is exposed only through
# the host launcher's 127.0.0.1 port mapping.
socat TCP-LISTEN:19445,fork,reuseaddr TCP:127.0.0.1:19443 &

exec litecoind \
  -regtest=1 \
  -server=1 \
  -txindex=1 \
  -printtoconsole=1 \
  -datadir=/var/lib/litecoin \
  -rpcuser="${LTC_REGTEST_RPC_USER}" \
  -rpcpassword="${LTC_REGTEST_RPC_PASSWORD}" \
  -rpcbind=127.0.0.1 \
  -rpcallowip=127.0.0.1 \
  -rpcport=19443 \
  -rpcthreads="${LTC_REGTEST_RPC_THREADS:-32}" \
  -rpcworkqueue="${LTC_REGTEST_RPC_WORKQUEUE:-256}" \
  -fallbackfee=0.0001
