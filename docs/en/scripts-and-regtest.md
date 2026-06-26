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
| `init` | Start BTC/LTC/DOGE/BCH local regtest nodes |
| `status` | Show node and EVM fork status |
| `stop` | Stop local UTXO regtest nodes |
| `reset` | Reset local UTXO regtest nodes and volumes |
| `test-utxo` | Run BTC/LTC/DOGE/BCH local full-flow tests |
| `test-evm` | Run EVM fork regression tests |
| `test-db` | Run DB-only account-chain tests |
| `test-live` | Run external testnet connectivity and optional spend tests |
| `test-local` | Run UTXO and EVM tests |
| `test-all` | Run local and DB tests, plus optional live tests |

## Local UTXO Nodes

The UTXO regtest scripts use Docker images under `infra/regtest/`.

```bash
scripts/regtest/all-chain-regtest.sh init
scripts/regtest/all-chain-regtest.sh status
scripts/regtest/all-chain-regtest.sh test-utxo
```

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

