# Regression Report

Generated: 2026-06-20 14:51 Asia/Shanghai.

## Overall Result

- `mvn clean install -DskipTests=false`: passed across 14 modules.
- `wallet-sig1`: started, first-sign job active, stopped cleanly.
- `wallet-sig2`: started, second-sign job active, stopped cleanly.
- `wallet-server`: started, PostgreSQL and Redis connected, BTC scan/collection jobs active, stopped cleanly.
- Commit: not created because several user-defined commit gates remain blocked.

## BTC Regression

- BTC address generation/witnessScript shape: covered by existing P2WSH tests.
- BTC SegWit multisig tests: 18 passed.
- BTC UTXO optimizer tests: 3 passed.
- BTC scan from DB height `5010273` advanced to `5010490`.
- New detected BTC deposit: `4fa59a278c4b94674a387a599627c2c1aa657c84c57f67f5f7289cfe931826b9-0`, amount `0.00200737 BTC`, userId `1`, credited once.
- User asset after credit: `0.00328103 BTC`.
- Duplicate check: one row for `(tx_id, seq)`.
- Collection created and broadcast: `5c5363bced47a9c0ee598357a90ce2a65dd39d2b8671987fd95794c397b05a5d`.
- Collection fee: `318 sats`, estimated `159 vB/634 wu`, actual `158 vB/630 wu`.
- Collection broadcast status: mempool accepted, not yet confirmed at report time.
- Redis signing/broadcast queues checked: length `0`.

## BTC 0.00001 Faucet Check

- All current `btc_address` rows were checked through testnet explorer API.
- No `1000 sats` output was found for the current DB address set.
- The specified `addressA` value is not present in code or database, so the exact `0.00001 BTC` assertion is blocked until the address or faucet txid is provided or the faucet transaction appears on-chain.

## Multi-Chain Review

- Production Tatum dependency: none found. Tatum helper is under `src/test`.
- EVM/TRON generic adapter scans were changed from silent empty returns to fail-fast `UnsupportedOperationException` until real RPC scanner runtimes are wired.
- Production token registry now has a `JdbcTokenRegistry` primary implementation and reads `token_config` first, then legacy `token_registry`.
- TRON legacy gRPC config was preserved for startup compatibility; PublicNode HTTP endpoint is kept in `atomex.chains.tron-mainnet`.

## Database

- `multi-chain-wallet-schema.sql` adds `chain_asset`, `token_config`, `chain_scan_height`, `hot_wallet_address`, `deposit_record`, `withdrawal_order`, `evm_nonce`, `evm_transaction`, `evm_token_transfer`, `tron_transaction`, `tron_token_transfer`, `sol_transaction`, `ton_transaction`, and compatibility `token_registry`, `evm_tx`, `tron_tx`, `ledger_balance`.
- Applying the SQL was blocked locally: `wallet` user has no `CREATE` privilege on schema `public`.
- Required DBA action: grant schema create or run migration with an owner role.

## Blocked Items

- EVM/TRON/SOL/TON live transfers were not executed.
- SOL/TON runtime connectors remain blocked/fail-fast.
- Multi-user BTC withdrawal scenario was not executed in this run.
- New multi-chain DB tables were not applied because of local DB permissions.
- No commit was created because not all required gates passed.

---

## EVM Fork Regression Update

Generated: 2026-06-20 19:08 Asia/Shanghai.

### Overall

- `mvn clean install -DskipTests=false`: passed across 14 modules at 2026-06-20 19:08:26 +08:00.
- `wallet-server`: started successfully on port `8002`; `/actuator/health` returned `UP`.
- `wallet-sig1`: started successfully on port `8004`; no actuator health endpoint is exposed.
- `wallet-sig2`: started successfully in non-web mode; second-sign job active.
- No commit was created because Polygon Amoy fork execution remains blocked by RPC archive/state capability.

### Fork Matrix

- ETH Sepolia: passed strict fork test with local Hardhat chainId `11155111`; PublicNode fork was rejected, fallback `https://sepolia.drpc.org` was used.
- Arbitrum Sepolia: passed strict fork test with chainId `421614`; PublicNode fork was rejected, fallback `https://sepolia-rollup.arbitrum.io/rpc` was used.
- Optimism Sepolia: passed strict fork test with chainId `11155420`; PublicNode fork was rejected, fallback `https://sepolia.optimism.io` was used.
- Base Sepolia: passed strict fork test with chainId `84532`; PublicNode fork was rejected, fallback `https://sepolia.base.org` was used.
- Avalanche Fuji C-Chain: passed strict fork test with chainId `43113`; PublicNode fork was rejected, fallback `https://api.avax-test.network/ext/bc/C/rpc` was used.
- BNB Chain Testnet: passed strict fork test with chainId `97`; PublicNode/official/dRPC endpoints failed, NodeReal BSC Testnet endpoint was used.
- Polygon Amoy: blocked. PublicNode rejected Hardhat fork; official/dRPC endpoints returned missing historical state.

### Mock ERC20 Deployments

- ETH USDT: `0xc56d9D5Fb9202C6c91BeD66E27FB816fa2E54b89`.
- ETH USDC: `0xAB9F64Fff7CC373D77C1E52C4BC75190DDbcBfB9`.
- Arbitrum USDT: `0x3A14799B74b73a63b5ca29023946E0EB3A7E6085`.
- Arbitrum USDC: `0x729788b708E76b54C3AE6F230Df7FDB8D8a16394`.
- Optimism USDT: `0xcb4CB0127B079d42b81F53e747b763d206D050f9`.
- Optimism USDC: `0x66C9A0b2971316079d31dda472f4eB5bF900C53b`.
- Base USDT: `0x210BBd033630e5e611B7922D70b0Caabe64636d9`.
- Base USDC: `0xeba5CEc9257045Df0B44eA784F9a7Fa07DeeF6d4`.
- Avalanche Fuji USDT: `0x1B43cbC6879C8237469794F9B8Ed290810e502d9`.
- Avalanche Fuji USDC: `0xe757C06f170C8EE956E7d80793087c971Ab5D7b5`.
- BNB USDT: `0x8cE7De403F365090C68922610DC6B032F0c95485`.
- BNB USDC: `0x7b331c59e5f9139923a06EA0B06CEa36cE9CF5d7`.

### EVM Flow Verified Per Passed Chain

- Native transfer from unlocked fork account to BTC-root-derived wallet address.
- Native deposit scan with idempotent rescan.
- ERC20 USDT/USDC mint and `Transfer` log decode.
- ERC20 deposit scan and idempotent rescan.
- Native withdraw and native collection with strict pending nonce progression.
- ERC20 withdraw and ERC20 hot-wallet collection.
- Ledger consistency: DB ledger native balance equals chain native balance after actual gas cost; USDT/USDC final wallet balances are zero in ledger and chain state.
- Double-spend guard: extra USDT debit rejected.

### DB State

- `token_config` enabled: ETH, BNB, Arbitrum, Optimism, Base, Avalanche USDT/USDC.
- `token_config` disabled: Polygon mock USDT/USDC, because strict fork execution is blocked.
- `hot_wallet_address` FORK_TEST rows remain only for verified chains:
  - ETH `0x403726e337548f3c537d89cc73495e2f1a34f9ad`, path `m/44/2/1/92002/0`.
  - BNB `0x439e0a440aa58bfe6a72f7bb18b898aeacc19843`, path `m/44/2/1/92003/0`.
  - Arbitrum `0x4cc5cfe0ac9614e93eb97998f945620e228e640b`, path `m/44/2/1/92005/0`.
  - Optimism `0xdbed45badbec9a77e914867d97b0e554f429ce49`, path `m/44/2/1/92006/0`.
  - Base `0xd8257572fcecc40b6a4a31cacfeb2320abfc1b60`, path `m/44/2/1/92007/0`.
  - Avalanche `0x7da34c2bd2478ba6eb1326ede7756ae74e9113b2`, path `m/44/2/1/92008/0`.
- Passed-chain `deposit_record`: 18 CREDITED rows, exactly one native, one USDT, and one USDC deposit per verified chain.

### Fixes

- Added Hardhat/ethers fork test environment under `evm-fork/`.
- Added real Solidity mock ERC20 with mint/transfer/Transfer event.
- Added fork regression runner with upstream RPC chainId preflight and local fork chainId enforcement.
- Fixed EVM chain profile IDs to match the current testnet runtime config.
- Fixed fork test gas accounting to use actual chain balance deltas instead of post-send gas estimates.
- Fixed fork test wallet derivation to use one BTC root key with per-chain high-index paths.
- Fixed `EvmDepositScanner` Spring constructor injection so `wallet-server` starts.
- Cleaned stale BNB/Polygon fork artifacts and disabled their mock `token_config` rows.
- Re-enabled BNB mock `token_config` after NodeReal fork validation passed.

---

## EVM Multi-User Stability Update

Generated: 2026-06-20 21:08 Asia/Shanghai.

### Overall

- `RUN_MULTIUSER=true bash evm-fork/scripts/run-fork-regression.sh`: completed.
- Passed chains: ETH Sepolia, BNB Testnet, Arbitrum Sepolia, Optimism Sepolia, Base Sepolia, Avalanche Fuji C-Chain.
- Blocked chain: Polygon Amoy. All configured fork RPC endpoints failed to provide a stable fork execution environment; one observed failure mode was missing historical state during mock ERC20 deployment.
- `mvn clean install -DskipTests=false`: passed across 14 modules at 2026-06-20 21:06:09 +08:00.
- Service startup validation passed: `wallet-server` health `UP`, `wallet-sig1` first-sign job started, `wallet-sig2` second-sign job started, PostgreSQL `select 1` passed, Redis `PING` returned `PONG`.
- Ports were cleaned after validation: no listener remained on `8002`, `8004`, `8081`, or `8545`.
- Commit: not created, because Polygon fork execution remains blocked and the worktree contains pre-existing unrelated changes that were not isolated into a safe commit set.

### Fork RPC Result

- ETH Sepolia: PublicNode rejected Hardhat fork; fallback `https://sepolia.drpc.org` passed.
- BNB Testnet: NodeReal BSC Testnet endpoint passed. The user-provided endpoint supports the required calls used here: `eth_chainId`, `eth_getBalance`, `eth_getLogs`, `eth_estimateGas`, `eth_getTransactionCount`, `eth_sendRawTransaction`, and receipt queries.
- Arbitrum Sepolia: PublicNode rejected Hardhat fork; fallback `https://sepolia-rollup.arbitrum.io/rpc` passed.
- Optimism Sepolia: PublicNode rejected Hardhat fork; fallback `https://sepolia.optimism.io` passed.
- Base Sepolia: PublicNode rejected Hardhat fork; fallback `https://sepolia.base.org` passed.
- Avalanche Fuji C-Chain: PublicNode rejected Hardhat fork; fallback `https://api.avax-test.network/ext/bc/C/rpc` passed.
- Polygon Amoy: blocked by fork RPC capability, not by wallet business logic.

### Mock ERC20 Deployments

- ETH USDT: `0xc56d9D5Fb9202C6c91BeD66E27FB816fa2E54b89`; USDC: `0xAB9F64Fff7CC373D77C1E52C4BC75190DDbcBfB9`.
- BNB USDT: `0x8cE7De403F365090C68922610DC6B032F0c95485`; USDC: `0x7b331c59e5f9139923a06EA0B06CEa36cE9CF5d7`.
- Arbitrum USDT: `0x3A14799B74b73a63b5ca29023946E0EB3A7E6085`; USDC: `0x729788b708E76b54C3AE6F230Df7FDB8D8a16394`.
- Optimism USDT: `0xcb4CB0127B079d42b81F53e747b763d206D050f9`; USDC: `0x66C9A0b2971316079d31dda472f4eB5bF900C53b`.
- Base USDT: `0x210BBd033630e5e611B7922D70b0Caabe64636d9`; USDC: `0xeba5CEc9257045Df0B44eA784F9a7Fa07DeeF6d4`.
- Avalanche Fuji USDT: `0x1B43cbC6879C8237469794F9B8Ed290810e502d9`; USDC: `0xe757C06f170C8EE956E7d80793087c971Ab5D7b5`.

### Multi-User Address Creation

- ETH: hot `0x674ac7df25a3126cbd76f2b39af5cbecd9ec28b5`; users A/B/C/D `0x9d21a9b96045e9f8f81bef9fe872cd904a741f02`, `0x398bb530491817507718c371dddc2fc3a0d2511b`, `0x82795e5947f1e371cbf1dd97a0c99ca924c85975`, `0x6f50a4cbba1c478773c4c2e4730be549464b2e8b`.
- BNB: hot `0xbf6b7c8e0e349649ef226d1928de026c46f15af5`; users A/B/C/D `0x82999051878bc8a071290dded5d72bd9564a943a`, `0x5f7babca7e35c7447915178161ba4726ce93a104`, `0x574e442129a69ed5c580aa3eec8aa7172c9b2a9e`, `0x522a58da8cfcd201e04f14597640332cb3c4e410`.
- Arbitrum: hot `0xcfb88cbaa4b956288e2b57aae42119cdd71695b5`; users A/B/C/D `0x9f8870bd2226c48acbc079f35308f0811ce58996`, `0x5b435e7c66a9cf487d81bf644ecdd96d946ec807`, `0x394d4c781b8deb63bca6ee57fd7f49e77376e279`, `0xbf68116681e6b8c357c2677a63131165212abe7d`.
- Optimism: hot `0x3f9fd18bd78f2a67caa8c4a599ce95b25586b536`; users A/B/C/D `0xe4b36bcc8187975abf5cbde227f2ee1a9edca7f0`, `0x45b208f6bce97577b66334793f5f9ac06fce2bed`, `0x7d7111ec8edb8b16415d9176fc1ae2513b5c2017`, `0x366ee4c5a6b7bb32440d919c23d93a53a37b69a5`.
- Base: hot `0xdf724863640bb1f06c273141ed2363e4d1282871`; users A/B/C/D `0xd5f664f8c1cf601e9e55d3d8f53a31e43514617b`, `0x08b35ddf9c0a62b40f62a2d6ff6a50ed1f604bfc`, `0x46cbafe9ace7f83e65e6dac0f28e70716bc3d9e0`, `0xdf6e43809425b6fed943eefb89aa5c22e40feb85`.
- Avalanche Fuji: hot `0x19e91caa695e667c4ba971ca124d037f183442be`; users A/B/C/D `0xf9262c5a13fb2b965c8a2873546d6b45839267ac`, `0x6f55c2bba5ec6d20f4e76b04e681e7bc03660017`, `0x0f1539957371097a2b46ccfbbe5326756bcb5c13`, `0x8d3136683fcf501271289c81298a219b23a38e80`.

### Business Flows Verified

- User lifecycle: BTC-root-derived EVM address creation, user/hot wallet DB registration, and per-chain token_config load.
- Deposits: native transfer, USDT transfer, USDC transfer, scanner capture, `deposit_record` insert, `ledger_balance` credit.
- Idempotency: native and ERC20 deposits were rescanned; duplicate credit did not occur.
- Withdrawals: balance freeze, order creation, nonce reservation, raw transaction signing, broadcast, status transition to `CONFIRMED`, and locked balance settlement.
- Collections: native user-to-hot transfer and ERC20 user-to-hot transfer; internal collection ledger was updated without duplicate user debit.
- Multi-user correctness: user A native deposit/collection, user B USDT deposit/collection, user C native withdraw with failure recovery, user D USDC withdraw.
- Recovery: scanner-stop simulation recovered through block rescan; rejected raw transaction retried with same nonce; interrupted token collection recovered once and second recovery was a no-op.

### DB Consistency

- Stability withdrawal orders: ETH/BNB/Arbitrum/Optimism/Base/Avalanche each have 4 `CONFIRMED` orders and 0 non-terminal orders.
- Ledger locked balances: all checked EVM rows for passed chains have `locked_balance = 0`.
- Negative ledger rows: 0 for passed-chain EVM rows.
- `token_config`: passed chains have USDT/USDC enabled with decimals `6`; Polygon mock USDT/USDC remain disabled because fork validation is blocked.
- `evm_tx`: unique tx hash count equals row count for passed chains, indicating no duplicated transaction record during retries/recovery.

### Fixes In This Update

- Added multi-user fork stability test `EvmForkMultiUserBusinessFlowIntegrationTest`.
- Added guarded ledger methods for freeze, release, and locked debit settlement.
- Extended fork regression runner to optionally run multi-user stability tests via `RUN_MULTIUSER=true`.
- Fixed fork runner so a chain that starts fork but cannot deploy mock ERC20 is marked blocked instead of aborting the whole matrix.
- Hardened test frozen-balance settlement for actual gas cost exceeding the pre-frozen gas buffer.

### BTC Non-Impact Verification

- No BTC witness, multisig, Redis signing flow, UTXO scan core, or fee logic was changed in this update.
- Full Maven regression re-ran BTC SDK tests successfully: 21 tests passed, 0 failures.
- wallet-server startup triggered BTC scanner and processed heights around `5010494` to `5010502` without startup failure.

### Remaining Blocked Items

- Polygon Amoy was later validated with the operator-provided Alchemy fork-capable RPC; see `Polygon And TRON Follow-Up`.
- No commit was created and nothing was pushed.

Suggested commit message when all commit gates are satisfied:

`feat: evm multi-user full business flow stability validation with fork environment`

---

## Polygon And TRON Follow-Up

Generated: 2026-06-20 23:35 Asia/Shanghai.

### Polygon

- User-provided Alchemy Polygon Amoy RPC was tested.
- Hardhat fork without a fixed block can hang during initialization.
- Hardhat fork with block `40491200` passed in TTY mode.
- Mock USDT: `0xb5F6211f94FCC162D5c8cebba4f656c965577392`.
- Mock USDC: `0x729B992ba1ccea88BE66985DCa5Ff28Ebba12046`.
- Polygon full-chain integration test passed.
- Polygon multi-user business-flow stability test passed.
- DB summary: 5 MATIC credited deposits, 2 USDT credited deposits, 2 USDC credited deposits, 4 stability orders all `CONFIRMED`.

### EVM Fork Runner

- Added `CHAIN_FILTER` so one chain can be tested without rerunning verified chains.
- Added `POLYGON_RPC_URL`, `POLYGON_FORK_BLOCK`, and `FORK_START_TIMEOUT_SEC` support.
- Removed known failed fork endpoints from the runner and kept only endpoints that previously passed fork tests or private env-var endpoints.
- Added `EVM_FORK_TESTING.md` in the repository root.

### Environment Profiles

- Added `application-test.yaml` and `application-prod.yaml` for:
  - `wallet-server`
  - `wallet-sig1`
  - `wallet-sig2`
- Test profile uses testnet RPC and current test key material.
- Prod profile uses mainnet RPC and empty env placeholders for production key material; production keys were not generated.

### TRON Trident

- Trident SDK: `io.github.tronprotocol:trident:0.11.0`.
- Added Trident dependency to `wallet-service`.
- Fixed protobuf runtime conflict by pinning `protobuf-java` and `protobuf-java-util` to `3.25.8` before legacy transitive dependencies.
- Added TRON key/address/TRC20/gas/scanner/client/service wrappers.
- TRON unit tests passed.
- Nile gRPC connectivity test passed with `-Dtron.live.enabled=true`.
- Multi-chain SQL migration was applied with the local DB role `atomex` after the lower-privilege `wallet` role was rejected by schema/table ownership.
- Nile TRX/TRC20 USDT live fund flows passed after the faucet funding arrived.
- TRC20 scanner bug fixed: Nile returns event contract address as a 20-byte value, so scanner now normalizes it to `41` + 20-byte address before token_config matching.
- USDC TRC20 live flow remains blocked until a Nile/Shasta USDC test token or mock contract is provided.

### TRON Nile Live Flow

- Source/faucet address: `TB1x9vmH5SbBd1EUaUePGbZzqmXGosFtxK`.
- USDT contract: `TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf`.
- TRX deposit txid: `3ada2e6ebc97d206fcf08e332d4c30699d9ea9035d91cb852bf1e5a49b7ad80c`.
- TRX withdraw txids: `6767933aac18c26d14a1e0f07047897bf30dd0e1ac0cd38bef8de0142cad27b4`, `6cf387ca07f3fb05a5ba570d92ec1f066de80ac796f43536795ed0b318cbf985`.
- USDT deposit txids: `e2b7583e00d7f097e6d5c98c4bf22850b6760b8bdc36aeee716c413a9f4a33ee`, `9895fcd268408d94f28ab23b0f86f7c1b5cfb8efeba44ab37a3f7ac3a3685ede`.
- USDT collection txid: `8b7d473180bb4584ccc59cf2244bd9c35a4a4f9c4b93ca5588a237e65007a43d`.
- USDT withdraw txid: `c5b2a2ad1d4cfed63c884320c89a34e6dbd69cd24d176828e5a6195408c3f271`.
- Gas top-up txids: `d6189681b50f23199df6845f592bfc01303a58b099d2631166a53816f9bf93f0`, `610111a7398087fa3be86a93ebd2f06bd398149daf0eb6f19053a83d37266ca2`.
- Detailed report: `TRON_LIVE_FLOW_REPORT.md`.

### Final Build And Startup Verification

- `mvn -q clean install -DskipTests=false` passed across the full reactor at `2026-06-21 00:42:00 +08:00`.
- Surefire summary after the final build: 57 tests, 0 failures, 0 errors, 11 skipped.
- BTC SDK regression in the full build passed: 21 tests, 0 failures.
- Redis check passed: `PONG`.
- PostgreSQL check passed: `select 1`.
- Multi-chain SQL verification passed: all 9 new `token_config` TRC20 columns exist, `collection_record`/`gas_topup_task` exist, and `wallet` can insert into both new tables in rollback transactions.
- `wallet-server` started on `8002`, `/actuator/health` returned `UP`, PostgreSQL Hikari pool initialized, and BTC scan job started.
- `wallet-sig1` started on `8004`, and the first-sign job started.
- `wallet-sig2` started as a non-web signing process, and the second-sign job started.
- `application-test.yaml` loading was verified for all three runnable projects with `--spring.profiles.active=test`.
- Test profile startup results: `wallet-server` health `UP`, `wallet-sig1` listening on `8004`, and `wallet-sig2` non-web signing process started.
- Startup risk note: `wallet-sig2` printed `第二次签名服务校验钱包环境 没有初始化`; process startup and second-sign job still entered running state.
- Cleanup completed after validation.

### Commit Gate

- TRX and TRC20 USDT Nile live flow passed.
- USDC TRC20 live flow remains blocked because the Nile faucet supplied USDT only and no controlled mock USDC deployment has been verified through the wallet path.
- TRON commit was created after the operator requested committing the current completed stage: `4654a45 feat: implement tron trident trc20 wallet full flow`.
- Push: no.

Detailed TRON report: `TRON_TRIDENT_REPORT.md`.

---

## Litecoin Wallet Update

Generated: 2026-06-21 07:50 Asia/Shanghai.

### Overall

- Litecoin implementation is code-complete for the current BTC-like P2WSH UTXO architecture.
- `CurrencyEnum` is treated as historical runtime id infrastructure: LTC uses runtime id `24`, while HD derivation uses BIP44 coin type `2`.
- BTC/EVM/TRON core logic was not rewritten.
- Live Litecoin testnet deposit/withdraw/collection remains blocked by missing funded Litecoin Core-compatible testnet RPC.
- Commit was not created because the Litecoin live chain gates are not satisfied.
- Push: no.

### Code And Config

- Added `ChainType.LTC`.
- Added `CurrencyEnum.LTC` with independent `bip44CoinType`.
- Added Litecoin network parameters with mainnet/testnet HRP and legacy address headers.
- Added Litecoin fee/dust policy.
- Added `LtcCommand`, `LtcWallet`, `LitecoinChainAdapter`.
- Added `ScanLtcBlockJob`, `BatchLtcWithdrawJob`, `LtcCollectionJob`.
- Added `LtcFirstSignService` and `LtcSecondSignService`.
- Added LTC RPC config in `application.yaml`, `application-test.yaml`, and `application-prod.yaml`.
- LTC scanner/collection are not enabled by default; existing BTC enabled-currency behavior is preserved.

### Database

- `multi-chain-wallet-schema.sql` was applied with the local admin role `atomex`.
- The low-privilege `wallet` role was rejected for DDL with `permission denied for schema public`; this is expected with current local DB permissions.
- Added/verified:
  - `ltc_address`
  - `ltc_utxo_transaction`
  - `ltc_withdraw_record`
  - `ltc_withdraw_transaction`
  - `best_block_height(currency=24)`
  - `currency_balance(currency_index=24)`
  - `chain_asset(chain='LTC', symbol='LTC')`
- LTC tables have independent primary and unique constraints; UTXO idempotency key is `(tx_id, seq)`.
- No MBG generation was run.

### Address Generation

- Test derivation path shape: `m/44/2/1/9001/{index}`.
- Generated Litecoin testnet P2WSH address index 0: `tltc1qeh6wxfsj4cfwh5dmp0nnpqj52s9u5gkc59gyj94qllg7wnjxx6qsnda7vj`.
- Generated Litecoin testnet P2WSH address index 1: `tltc1qydpzhcujqtca9uuepts0k996jfv483xlnkf8majw0f0umaht9j6q2aktvc`.
- BTC TestNet3 parser rejects these `tltc1` addresses.

### Tests

- `mvn -q -pl currency-sdks/bitcoin-sdk -Dtest=LitecoinNetworkParamsTest test`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am -Dtest=LitecoinAddressGenerationTest,LitecoinFeeEstimatorTest,BlockchainAdapterRegistryTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-server -am -DskipTests compile`: passed.
- `mvn -q -pl backendservices/wallet-sig1 -am -DskipTests compile`: passed.
- `mvn -q -pl backendservices/wallet-sig2 -am -DskipTests compile`: passed.
- `mvn -q clean install -DskipTests=false`: passed.
- Final Surefire summary: 65 tests, 0 failures, 0 errors, 12 skipped.
- `LitecoinLiveFlowIntegrationTest`: skipped with explicit blocked reason because `ltc.live.enabled=true` and funded Core RPC credentials are not available.

### Startup

- Redis: `PONG`.
- PostgreSQL: `select 1` passed.
- `wallet-server --spring.profiles.active=test`: started on `8002`; `/actuator/health` returned `UP`.
- `wallet-sig1 --spring.profiles.active=test`: started on `8004`; first-sign job active.
- `wallet-sig2 --spring.profiles.active=test`: started as non-web signing process; second-sign job active.
- All three processes were stopped after validation.

### Blocked

- Live LTC deposit txid: blocked.
- Live LTC withdraw txid: blocked.
- Live LTC collection txid: blocked.
- Live UTXO/ledger reconciliation: blocked.
- Required to unblock: a running Litecoin testnet JSON-RPC endpoint compatible with Litecoin Core and funded testnet LTC for a generated `tltc1...` platform address.

Detailed Litecoin report: `LITECOIN_WALLET_REPORT.md`.

---

## Live Deposit Verification

Deposit scan was executed against the Alchemy Litecoin testnet RPC endpoint (`https://litecoin-testnet.g.alchemy.com/v2/...`).

### Results

| Item | Value |
|------|-------|
| Deposit txid | `24aecf832537eb6b9e77722541ab812f3c6f887a75ff40aee83170bd35497f9f` |
| Block | 4773130 |
| Amount | 0.01 tLTC |
| Address | `tltc1qeh6wxfsj4cfwh5dmp0nnpqj52s9u5gkc59gyj94qllg7wnjxx6qsnda7vj` |
| Scan detected | yes |
| UTXO inserted | yes |
| user_asset credited | yes, 0.01 LTC |
| Confirmations at scan | 23 |
| Best block at scan | 4773150+ |
| RPC provider | Alchemy Litecoin testnet |
| Dust threshold | 1000 litoshi |
| Default fee rate | 2 litoshi/vbyte |

### Idempotency

- UTXO insert uses `batchAddOnDuplicateKey` (ON CONFLICT DO NOTHING for `(tx_id, seq)`).
- `creditDepositIfNeeded` checks the `credited` flag before applying credit.
- Restarting the scanner did NOT produce a duplicate credit.
- Rescanning the same block after already processing it does NOT produce duplicate UTXO records.

### Remaining Blocked Items

- **LTC live withdrawal**: blocked. Withdrawal requires the multisig infrastructure (wallet_multisig_config, hot_wallet_address, sig1/sig2 services running concurrently).
- **LTC live collection**: blocked for the same reason as withdrawal.
- **Scanner recovery scoped test**: skipped due to limited live node session time.

### Node Connectivity

- `getblockcount`: 4773150+
- `getblockhash`: returns valid hash
- `getblock`: returns full block with txids
- `getrawtransaction` (verbose): returns decoded tx with vout, scriptPubKey, addresses, confirmations
- `sendrawtransaction`: not tested (no withdrawal was triggered)
- Basic Auth: not used (Alchemy uses URL-based API key)

### LTC RPC Config Notes

Alchemy Litecoin testnet uses URL-based API key authentication. The `RpcCommandProcessor.buildRpcClient` method was modified to skip the `Authorization: Basic` header when both `username` and `password` are empty. This change is in `currency-sdks/wallet-client/src/main/java/com/surprising/wallet/client/RpcCommandProcessor.java`.

---

## Litecoin Live Gate Final Update

Generated: 2026-06-21 18:50 Asia/Shanghai.

This section supersedes the earlier Litecoin blocked status.

### Live Chain Results

- Deposit `24aecf832537eb6b9e77722541ab812f3c6f887a75ff40aee83170bd35497f9f`, block `4773130`, `0.01000000 tLTC`: scanned and credited exactly once.
- Withdrawal `ede1443842edaace31f1f7e4525f436b6bc69aad952bba2646b8c3be1678880c`, block `4773436`: confirmed; destination received `0.00500000 tLTC`.
- Collection `34c2a03b9696b558c794350039d19ff38f76a44b1a3717f3531be73f31274949`, block `4773439`: confirmed; hot wallet received `0.00499682 tLTC`.
- Final evidence at tip `4773453`: deposit `324` confirmations, withdrawal `18`, collection `15`.

### State And Reconciliation

- `deposit_record`: one `CREDITED` row for the real deposit; no internal withdrawal/collection deposits.
- `withdrawal_order`: live order `CONFIRMED`.
- `collection_record`: one live record, `CONFIRMED`.
- Initial deposit UTXO and controlled withdrawal UTXO: `SPENT`.
- Withdrawal change and collection hot-wallet outputs: `AVAILABLE`.
- Account `9001` ledger: available/total `0.00499680 LTC`, locked `0`.
- Legacy `user_asset`: balance `0.00499680`, frozen `0`.
- Negative LTC ledger rows: `0`.
- Repeated deposit scan, withdrawal done-queue replay, and collection done-queue replay produced no duplicate credit or broadcast.
- Scanner stop/restart resumed from the persisted chain height.

### Fees

- Withdrawal: `404` litoshi, weight `803`, vsize `201`, approximately `2.01` litoshi/vbyte.
- Collection: `318` litoshi, weight `632`, vsize `158`, approximately `2.01` litoshi/vbyte.
- Dust threshold: `1000` litoshi; all created outputs were above dust.

### Regression

- `mvn -q clean install -DskipTests=false`: passed.
- Full Surefire summary: 64 tests, 0 failures, 0 errors, 11 skipped.
- Strict `LitecoinLiveFlowIntegrationTest`: 4 tests, 0 failures, 0 errors, 0 skipped.
- BTC, EVM, and TRON tests passed within the full reactor build.
- PostgreSQL `select 1`: passed.
- Incremental migration rerun: passed idempotently.
- Full `surprising-wallet-init-pgsql.sql`: passed against a disposable empty PostgreSQL database after correcting identity seed collisions.
- Redis `PING`: `PONG`.
- wallet-server test profile: started; `/actuator/health` returned `UP`.
- wallet-sig1 test profile: started.
- wallet-sig2 test profile: started.
- All three validation processes were stopped cleanly.
- Precise private-key scan found no extended private key or WIF key after README/config remediation.
- Production YAML uses environment placeholders and does not generate keys.

### Commit / Push

- Commit message: `feat: add litecoin wallet flow`.
- Push: no.

Detailed evidence: `LITECOIN_WALLET_REPORT.md`.

---

## Dogecoin Integration Update

Generated: 2026-06-21 23:07 Asia/Shanghai.

- Dogecoin was implemented with bitcoinj `0.17.1` using independent legacy P2SH 2-of-3 signing.
- Runtime id `41` and BIP44 coin type `3` are separate and database-validated.
- Full Maven passed: 69 tests, 0 failures, 0 errors, 12 skipped.
- Real Dogecoin testnet RPC block/transaction reads passed.
- Scanner processed block `65162280`, advanced its checkpoint, and ignored the non-platform output.
- Deterministic multi-user addresses, two-stage P2SH signatures, fee/dust policy, PostgreSQL deposit idempotency, UTXO lock/release, ledger non-negative guard, and recovery jobs passed.
- wallet-server health `UP`; wallet-sig1 and wallet-sig2 started.
- Funded live deposit/withdraw/collection is deferred until testnet DOGE is supplied.
- Funding address: `2MtNHNEL8YcV3EWM3DZhFcZhxCwNCKSsrtk`.
- Push: no.

Detailed evidence: `DOGECOIN_WALLET_REPORT.md`.

---

## Bitcoin Cash Integration Update

Generated: 2026-06-21 23:23 Asia/Shanghai.

- BCH was implemented with bitcoinj `0.17.1`, CashAddr/legacy compatibility, and BCH `SIGHASH_FORKID`.
- Runtime id `42` and BIP44 coin type `145` are database-validated and separate.
- Full Maven: 71 tests, 0 failures, 0 errors, 11 skipped.
- Real testnet RPC read block `1715780`; scanner advanced the BCH checkpoint and ignored non-platform outputs.
- CashAddr, P2SH 2-of-3, `0x41` signatures, PostgreSQL idempotency, UTXO lock/release, ledger non-negative, and recovery tests passed.
- wallet-server health `UP`; both signer services started.
- Funded live deposit/withdraw/collection is deferred until testnet BCH is supplied.
- Funding address: `bchtest:pzleucus9lj0zns4j52mkecpams5hftrzqfaauzp8t`.
- Push: no.

Detailed evidence: `BITCOIN_CASH_WALLET_REPORT.md`.
