# Local Sui Coin fixtures

The package defines independent `usdt::USDT` and `usdc::USDC` Coin types with six decimals. It is only for an isolated validator/fullnode ledger.

Publish and mint with the local test administrator outside the checkout. Never import its private keystore into the Java wallet or MPC nodes. The wallet receives public Coin objects and uses its independently generated MPC keys for all withdrawals and collections.

`resources/scripts/regtest/run-sui-flow.sh` uses existing loopback fullnode/faucet endpoints and a protected local fixture descriptor through `MPC_SUI_TEST_RPC_URL`, `MPC_SUI_TEST_FAUCET_URL` and `MPC_SUI_TEST_COIN_FIXTURE`. It reuses PostgreSQL 18 on `127.0.0.1:5432` with an isolated temporary wallet database. No auxiliary Sui indexer, GraphQL database or private root seed is used.
