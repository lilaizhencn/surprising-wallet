# Scripts and Regtest

[中文版本](../zh/scripts-and-regtest.md)

Runtime scripts remain in the repository root under `scripts/` because Java tests and shell scripts reference those paths directly.

## Main Entry Point

```bash
scripts/regtest/all-chain-regtest.sh <command>
```

Commands:

| Command | Purpose |
|---|---|
| `matrix` | Show supported local/fork/live coverage |
| `init` | Start BTC/LTC/DOGE/BCH local regtest nodes and XMR wallet-rpc regtest |
| `status` | Show node and EVM fork status |
| `stop` | Stop local UTXO/XMR regtest nodes |
| `reset` | Reset local UTXO/XMR regtest nodes and volumes |
| `test-utxo` | Run BTC/LTC/DOGE/BCH local full-flow tests |
| `test-xmr` | Run XMR local deposit/withdraw/collection regtest integration |
| `test-evm` | Run EVM fork regression tests |
| `test-db` | Run DB-only account-chain tests |
| `test-live` | Run external testnet connectivity and optional spend tests |
| `test-local` | Run UTXO and EVM tests |
| `test-all` | Run local and DB tests, plus optional live tests |

## Local UTXO And XMR Nodes

The UTXO regtest scripts use Docker images under `infra/regtest/`. XMR uses
`monerod --regtest` plus two `monero-wallet-rpc` containers: one application
wallet and one funder wallet.

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
scripts/regtest/all-chain-regtest.sh test-utxo
scripts/regtest/all-chain-regtest.sh test-xmr
```

`test-utxo` runs BTC, LTC, DOGE, and BCH sequentially. For each chain it resets
the explicitly named regtest test volume, starts from genesis, runs that chain's
full-flow, concurrency, and bulk-broadcast tests, stops the node, and only then
moves to the next chain. It uses a temporary database that is removed on exit.

`test-xmr` requires Docker, `curl`, `python3`, Maven, and a PostgreSQL database
initialized from `docs/db/surprising-wallet-init-pgsql.sql`. By default it uses
`jdbc:postgresql://127.0.0.1:5432/wallet`, user `wallet`, and an empty password;
override with `MONERO_REGTEST_DB_URL`, `MONERO_REGTEST_DB_USER`, and
`MONERO_REGTEST_DB_PASSWORD`. If the wallet-rpc containers are started with
authentication, set `MONERO_REGTEST_RPC_LOGIN=user:password`; the Java test will
upsert the regtest RPC node as `DIGEST`, otherwise it uses `NONE`.

Tune broadcast pressure:

```bash
BITCOINLIKE_BROADCAST_DEPOSITS=80 \
BITCOINLIKE_BROADCAST_WITHDRAWALS=40 \
scripts/regtest/all-chain-regtest.sh test-utxo
```

## EVM Fork

```bash
cd evm-fork
npm install
cd ..
scripts/regtest/all-chain-regtest.sh test-evm
```

Run a subset:

```bash
CHAIN_FILTER=ETH,BASE scripts/regtest/all-chain-regtest.sh test-evm
```

## External Testnets

```bash
scripts/regtest/all-chain-regtest.sh test-live
```

Spending tests are disabled by default:

```bash
RUN_LIVE_SPENDING=true scripts/regtest/all-chain-regtest.sh test-live
```

These flows depend on funded test accounts and external RPC/faucet availability.

## Database Initialization

The project keeps a single database initialization file:

```text
docs/db/surprising-wallet-init-pgsql.sql
```

The `scripts/` directory no longer stores standalone SQL upgrade or cutover scripts. Fresh local databases and test2/sandbox databases should start from this init SQL.
