# EVM Fork Testing

[中文版本](../zh/evm-fork-testing.md)

The `evm-fork/` directory contains the Hardhat fork environment used for ETH, BNB, Polygon, Arbitrum, Optimism, Base, and Avalanche C-Chain regression tests.

The directory stays at the repository root because `scripts/regtest/all-chain-regtest.sh` and `evm-fork/scripts/run-fork-regression.sh` resolve it by path.

## Prerequisites

- Node.js 18+
- npm
- Maven/JDK 17+
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
```

## RPC Environment Variables

```bash
export ETH_RPC_URL='https://...'
export BNB_RPC_URL='https://...'
export POLYGON_RPC_URL='https://...'
```

Some public endpoints are rate-limited. Private RPC endpoints make the fork tests more stable.

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

