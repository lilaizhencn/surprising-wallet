# EVM Fork Testing

[中文版本](../zh/evm-fork-testing.md)

The `evm-fork/` directory contains the Hardhat fork environment used for ETH, BNB, Polygon, Arbitrum, Optimism, Base, Avalanche C-Chain, and HyperEVM regression tests.

The directory stays at the repository root because `scripts/regtest/all-chain-regtest.sh` and `evm-fork/scripts/run-fork-regression.sh` resolve it by path.

## Prerequisites

- Node.js 18+
- npm
- Maven/JDK 21
- PostgreSQL test database initialized from `docs/db/`
- Optional RPC environment variables for private or more reliable endpoints

## Install

```bash
cd evm-fork
npm install
cd ..
```

## Run All EVM Fork Regressions

```bash
scripts/regtest/all-chain-regtest.sh test-evm
```

The script starts a Hardhat fork on `127.0.0.1:8545`, deploys mock ERC20 contracts, runs Java integration tests, stops the fork, and moves to the next chain.

Run multi-user business-flow tests as well:

```bash
RUN_MULTIUSER=true scripts/regtest/all-chain-regtest.sh test-evm
```

## Run a Chain Subset

```bash
CHAIN_FILTER=ETH,BASE scripts/regtest/all-chain-regtest.sh test-evm
```

Supported filter names:

```text
ETH
BNB
POLYGON
ARBITRUM
OPTIMISM
BASE
AVAX_C
HYPEREVM
```

## RPC Environment Variables

```bash
export ETH_RPC_URL='https://...'
export BNB_RPC_URL='https://...'
export POLYGON_RPC_URL='https://...'
export HYPEREVM_RPC_URL='https://...'
export BNB_FORK_BLOCK='12345678'
export POLYGON_FORK_BLOCK='12345678'
```

Some public endpoints are rate-limited. Private RPC endpoints make the fork tests more stable.

RPC policy:

- The script only keeps public default endpoints that have been verified to complete stable fork regressions.
- BNB and Polygon no longer have built-in public default fork RPCs; inject verified private or archive-capable RPCs through `BNB_RPC_URL` and `POLYGON_RPC_URL`.
- When `BNB_FORK_BLOCK` or `POLYGON_FORK_BLOCK` is not set, the script reads the latest block from the current RPC and starts the fork at that height. This avoids stale fixed-block historical-state or missing-trie-node failures.
- Set `REQUIRE_ALL_EVM_FORKS=true` when every chain must pass. Otherwise chains without stable RPCs are reported in `BLOCKED_CHAINS` and the script continues with the rest.

June 25, 2026 test result:

- ETH, ARBITRUM, OPTIMISM, BASE, and AVAX_C completed fork regression with the current script defaults.
- BNB public RPCs failed during fork or Java regression with historical-state, missing-trie-node, or upstream 403 errors, so they are not default fork RPCs.
- Polygon Amoy public RPCs could complete part of the full-chain fork flow, but the multi-user flow timed out; `polygon-amoy.drpc.org` produced node task errors, so they are not default fork RPCs.
- Official RPC docs are useful network references, but Hardhat fork support is decided by this regression script: BNB Chain JSON-RPC docs `https://docs.bnbchain.org/bnb-smart-chain/developers/json_rpc/json-rpc-endpoint/`, Polygon RPC docs `https://docs.polygon.technology/pos/reference/rpc-endpoints/`.

## Outputs

| Path | Purpose |
|---|---|
| `evm-fork/logs/*.hardhat.log` | Hardhat fork logs |
| `evm-fork/deployments/*.json` | Mock token deployment metadata |
| `evm-fork/contracts/MockERC20.sol` | Test ERC20 contract |
| `evm-fork/scripts/deploy-mock-erc20.js` | Deployment script |

## Common Failures

RPC chain mismatch:

- The configured RPC endpoint is not the expected network.
- Use another endpoint or set the correct environment variable.

Fork startup timeout:

- Public endpoint is slow or rate-limited.
- Increase `FORK_START_TIMEOUT_SEC` or use a private RPC.

Mock deployment failed:

- Hardhat fork did not start correctly.
- Check `evm-fork/logs/<CHAIN>.hardhat.log`.
