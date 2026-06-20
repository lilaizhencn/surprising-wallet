# EVM Fork Testing Guide

Generated: 2026-06-20 Asia/Shanghai.

## Scope

This document records the fork RPC endpoints and commands that were verified for the wallet EVM fork tests.
Endpoints that failed Hardhat fork execution were removed from the regression runner.

## Verified Chains

| Chain | Chain ID | Native Symbol | Fork RPC Strategy | Status |
| --- | ---: | --- | --- | --- |
| Ethereum Sepolia | 11155111 | ETH | `https://sepolia.drpc.org` | passed |
| BNB Testnet | 97 | BNB | `BNB_RPC_URL` env var, verified with NodeReal | passed |
| Polygon Amoy | 80002 | MATIC | `POLYGON_RPC_URL` env var, fixed fork block `40491200` | passed manually with Hardhat TTY |
| Arbitrum Sepolia | 421614 | ETH_ARB | `https://sepolia-rollup.arbitrum.io/rpc` | passed |
| Optimism Sepolia | 11155420 | ETH_OP | `https://sepolia.optimism.io` | passed |
| Base Sepolia | 84532 | ETH_BASE | `https://sepolia.base.org` | passed |
| Avalanche Fuji C-Chain | 43113 | AVAX_C | `https://api.avax-test.network/ext/bc/C/rpc` | passed |

## Removed Endpoints

The following endpoint classes were removed from the automated fork runner because they either rejected Hardhat fork requests,
could not provide historical state, or were unstable for contract deployment:

- EVM PublicNode testnet fork endpoints for Sepolia/L2/Fuji.
- Polygon Amoy public and dRPC endpoints that returned missing historical state.
- BNB public/official endpoints that could not satisfy fork execution reliably.

Production `application-*.yaml` RPC configuration can still use PublicNode for normal runtime reads/writes. Fork testing has stricter historical-state requirements.

## Commands

Install fork dependencies once:

```bash
cd evm-fork
npm install
```

Run all verified public chains except private-RPC chains:

```bash
RUN_MULTIUSER=true bash evm-fork/scripts/run-fork-regression.sh
```

Run BNB with NodeReal:

```bash
BNB_RPC_URL='<redacted-node-real-url>' \
CHAIN_FILTER=BNB \
RUN_MULTIUSER=true \
bash evm-fork/scripts/run-fork-regression.sh
```

Run Polygon Amoy with Alchemy. Keep the API key outside git:

```bash
POLYGON_RPC_URL='<redacted-alchemy-amoy-url>' \
POLYGON_FORK_BLOCK=40491200 \
CHAIN_FILTER=POLYGON \
RUN_MULTIUSER=true \
bash evm-fork/scripts/run-fork-regression.sh
```

If the Polygon background runner hangs while Hardhat initializes, the manual TTY-equivalent flow is:

```bash
cd evm-fork
HARDHAT_CHAIN_ID=80002 HARDHAT_DISABLE_TELEMETRY_PROMPT=true \
npx hardhat node \
  --fork "$POLYGON_RPC_URL" \
  --fork-block-number 40491200 \
  --hostname 127.0.0.1 \
  --port 8545
```

In a second terminal:

```bash
cd evm-fork
EVM_CHAIN=POLYGON npm run deploy:mock
```

Then run the service tests from the repository root:

```bash
mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=com.surprising.wallet.service.chain.evm.EvmForkFullChainIntegrationTest \
  -Devm.fork.enabled=true \
  -Devm.fork.chain=POLYGON \
  -Devm.native.symbol=MATIC \
  -Devm.expected.chainId=80002 \
  -Devm.confirmations=1 \
  test

mvn -q -pl backendservices/wallet-parent/wallet-service \
  -Dtest=com.surprising.wallet.service.chain.evm.EvmForkMultiUserBusinessFlowIntegrationTest \
  -Devm.multiuser.enabled=true \
  -Devm.fork.chain=POLYGON \
  -Devm.native.symbol=MATIC \
  -Devm.expected.chainId=80002 \
  -Devm.confirmations=1 \
  test
```

## Polygon Result

- Fork block: `40491200`.
- Mock USDT: `0xb5F6211f94FCC162D5c8cebba4f656c965577392`.
- Mock USDC: `0x729B992ba1ccea88BE66985DCa5Ff28Ebba12046`.
- Full-chain integration test: passed.
- Multi-user business-flow stability test: passed.
- DB result: 5 MATIC credited deposits, 2 USDT credited deposits, 2 USDC credited deposits, and 4 stability withdrawal orders in `CONFIRMED`.

## What The Fork Tests Validate

- BTC-root-derived EVM address generation.
- Native transfer, deposit scan, withdraw, and collection.
- ERC20 deploy, mint, transfer, event log scan, withdraw, and collection.
- Nonce reservation and pending nonce correctness.
- Gas estimation and actual gas settlement.
- Ledger idempotency and ledger-vs-chain reconciliation.
- Multi-user isolation and retry/recovery behavior.
