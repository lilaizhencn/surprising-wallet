# Regression Report

Generated: 2026-06-20 14:51 Asia/Shanghai.

This file is cumulative. Later sections supersede earlier checkpoint notes when
they reference the same chain.

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
- At this early checkpoint, SOL/TON runtime connectors were still pending.
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

## TON Account/Jetton Integration Update

Generated: 2026-06-23 21:10 Asia/Shanghai.

Historical note: this section records the earlier pre-funding/pre-RPC state.
It is superseded by `TON Native / Jetton Live Gate Final Update` below.

### Overall

- At this earlier checkpoint, TON code integration was complete enough for
  offline/DB/regression validation, while live funding/RPC was still pending.
- `mvn -q clean install -DskipTests=false`: passed after the TON/FeeRateUpdater/report updates with
  85 tests, 0 failures, 0 errors, and 16 environment-conditioned skips.
- TON targeted default tests passed.
- TON PostgreSQL-backed idempotency/ledger/seqno test passed with
  `-Dton.db.enabled=true`.
- wallet-server test profile started and `/actuator/health` returned `UP`.
- wallet-sig1 and wallet-sig2 test profiles started with temporary external test
  xprv values; no xprv was written to source or YAML.
- Redis `PING`: `PONG`.

### TON Verified

- Runtime currency id `51` and BIP44 coin type `607` are database-driven and
  distinct.
- Shared Ed25519 SLIP-0010 derivation path:
  `m/44'/607'/{userIndex}'/0'`.
- WalletV4R2 uses explicit subwallet id `698983191` for offline address/signing
  consistency; ton4j provider lookup is not required for BOC construction.
- Native transfer BOC and Jetton transfer BOC build successfully without
  broadcasting.
- TEP-74 Jetton transfer notification body parsing is covered.
- `deposit_record` replay credits once.
- `ton_transaction` replay updates one row.
- `account_sequence` reserves monotonically.
- Ledger freeze/release guards prevent negative balances.

### Earlier TON Pending Items

- Live native deposit/withdraw/collection had not yet been executed at this
  checkpoint.
- Live Jetton USDT/USDC deposit/withdraw/collection had not yet been executed
  at this checkpoint.
- Initial environment had no `ATOMEX_MASTER_SEED`; a repository-external shared
  Ed25519 test seed was created at
  `/Users/atomex/.surprising-wallet-test-secrets/ed25519-master-seed` with mode
  `600`.
- Funding address for next TON testnet run:
  `0QAZqo0OKuwLmIsJT57odv_onrMViV4mnymFhoYst7EUiGWs`.
- TonCenter later failed with TLS handshake error and direct curl checks to both
  TonCenter and TonAPI testnet timed out after 20 seconds. No live txid is
  reported.

### Fixes

- Added TON WalletV4R2 key/address/RPC/API/transaction/scanner/adapter classes.
- Added `ton_transaction` and `account_sequence`.
- Added TON native profile/asset and disabled USDT/USDC Jetton placeholders.
- Added null guard to `FeeRateUpdater` so empty Redis does not throw NPE during
  startup.
- Deleted the fail-fast future TON adapter placeholder.

### Commit At This Earlier Checkpoint

- TON commit was not created at this checkpoint.
- Push: no.

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

## BTC-like No-Fallback Address and Signing Cutover

Generated: 2026-06-24 15:02 Asia/Shanghai.

- BTC/LTC/DOGE/BCH address runtime now reads `chain_address` only. The previous
  `AddressServiceImpl` legacy `*_address` fallback/backfill path was removed for
  BTC-like chains.
- `/wallet/v1/hot-info` and `/wallet/v1/addresses` now use `chain_address` for
  BTC/LTC/DOGE/BCH instead of the legacy address tables.
- BTC/LTC/DOGE/BCH signing runtime now uses `chain_signing_transaction` for
  build, recovery, broadcast idempotency, and confirmation lookup. The Redis
  message remains the existing `WithdrawTransaction` DTO to preserve sig1/sig2
  signing algorithms without using legacy transaction tables as runtime state.
- `scripts/migrate-bitcoinlike-signing-transaction-cutover.sql` was executed on
  the local validation DB. It refused active legacy signing rows by design and
  migrated terminal legacy signing history for audit/live-test consistency.
- Local migrated terminal signing history:
  - BTC: 3
  - LTC: 5
  - DOGE: 12
  - BCH: 4
- Local BTC `chain_profile` was backfilled from checked-in schema because the
  validation DB had `chain_asset(BTC)` but no BTC profile row. This fixed the
  unified UTXO runtime lookup of `runtime_currency_id=1`.
- `scripts/drop-legacy-bitcoinlike-withdraw-transaction-tables.sql` was
  executed after the post-cutover gates. Local validation DB now has zero
  `btc/ltc/doge/bch_withdraw_transaction` tables.

### Validation

- `mvn -q -DskipTests compile`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -DskipTests=false -Dutxo.migration.db.enabled=true -Dtest=BitcoinLikeUnifiedUtxoRuntimeMigrationTest test`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -DskipTests=false -Dbitcoinlike.regtest.enabled=true -Dtest=BitcoinLikeRegtestFullFlowIntegrationTest test`: passed.
- DOGE regtest:
  - deposit `4a351af4b94b7398a675818fc3bfe234f6e5f95644959c33754fde75ee7b3e49`
  - withdraw `6ff8854531fc9a1b3b96e7011545ac6edc37e4a86e8385169f255c80a3306e4c`
  - collection `47e88653105ff8a21162cbc5a01556bd378ea541ebd8dfb58ab5b8e39a3d70b9`
- BCH regtest:
  - deposit `1b58a16b7482cb5b4c5d369012260b2702e36f6c5f8ae25efd6fb149230d0287`
  - withdraw `59c6c4deff09bfc5cb58bc309cc7f79f876215df779c00641ae1bd5ca8aff0ee`
  - collection `1b002c88cd57717c03f2f6fa154246feaed87d906987b5e88621b44728f794b5`
- LTC live gate passed with:
  - deposit `24aecf832537eb6b9e77722541ab812f3c6f887a75ff40aee83170bd35497f9f`
  - withdraw `ede1443842edaace31f1f7e4525f436b6bc69aad952bba2646b8c3be1678880c`
  - collection `34c2a03b9696b558c794350039d19ff38f76a44b1a3717f3531be73f31274949`
- `mvn -q clean install -DskipTests=false`: passed.
- wallet-server `--spring.profiles.active=test`: started after drop-table
  migration and health returned `UP`; BTC/LTC/DOGE/BCH withdraw jobs completed
  one scheduled pass. The final startup used scan disabled to avoid unrelated
  public BTC RPC TLS noise.
- wallet-sig1 and wallet-sig2 `--spring.profiles.active=test`: started with
  ephemeral environment-only keys.
- PostgreSQL `select 1`: passed; Redis `PING`: `PONG`.
- Secret scan: only documentation placeholders and an intentionally empty xprv
  response field matched.
- Push: no.

---

## Unified UTXO Legacy Cleanup Update

Generated: 2026-06-24 Asia/Shanghai.

- Removed legacy UTXO MBG runtime artifacts:
  `UtxoTransactionExample`, `UtxoTransactionRepository`,
  `UtxoTransactionService`, `UtxoTransactionServiceImpl`, and
  `UtxoTransactionMapper.xml`.
- Runtime BTC/LTC/DOGE/BCH UTXO paths now use `utxo_record` for scan
  persistence, wallet balance, selection, lock/release, spend settlement, and
  address transaction query.
- `ChainJdbcRepository` maps UTXO runtime currency id from
  `chain_profile.runtime_currency_id`; it no longer derives unified UTXO row
  currency id from `CurrencyEnum`.
- Added BTC `chain_profile` / `chain_asset` initialization for the DB asset
  model.
- Added separate physical-drop migration:
  `scripts/drop-legacy-bitcoinlike-utxo-tables.sql`. It was **not executed**.

Targeted verification:

- `mvn -q -DskipTests compile`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -DskipTests=false -Dutxo.migration.db.enabled=true -Dtest=BitcoinLikeUnifiedUtxoRuntimeMigrationTest test`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -DskipTests=false -Dbitcoinlike.regtest.enabled=true -Dtest=BitcoinLikeRegtestFullFlowIntegrationTest test`: passed.

DOGE local regtest:

- deposit:
  `34ac7f568667775f06a179c9e0b9f9ce6dd02bd0de3ac3c95ed9b0c3db543a68`
- withdraw:
  `e7723d5c79a8695727b1df1c52a2159ed5847278d0ff849cace8b79589ef976e`
- collection:
  `35d53c425773a97776f6c193b196c40604dd175106168d18314fd40b87180802`
- addresses:
  deposit `n3CGohzAycpjHaQqszBDamAzxH1r5KDSfi`,
  withdraw `n4hu8Ziwkkr1AeWXSgsEYGj2FJLTvyhUgc`,
  hot `mrWGppYoaG963tqtabihbYzxn6Q21S5vhD`.

BCH local regtest:

- deposit:
  `ce034235e4907182f1ccf3f20e4b4a69c3baf13a097b3b7d4f337705bb5f3c3a`
- withdraw:
  `07b2267f08727b0b6bbb61f14f7d2735529d1ff76c126e83dd4b98800d85e976`
- collection:
  `cc9a9b115a8873b728023e33a3a062a9e511349422e6f4600bf88e878f3892c0`
- addresses:
  deposit `bchreg:qzxtcvjt4nn6gmhme2emk7qtdxrwawrdxgfgeqr2nu`,
  withdraw `bchreg:qzklqdhmu0cqlwaws8mv76gw5c03sjf9fyckf7jzq6`,
  hot `bchreg:qz73fsusqk95lcjwtqxqmjhjs0kl2fzh9cg7yr78hj`.

Full regression:

- `mvn -q clean install -DskipTests=false`: passed.
- wallet-server `--spring.profiles.active=test`: health `UP`.
- wallet-sig1 `--spring.profiles.active=test`: started.
- wallet-sig2 `--spring.profiles.active=test`: started.
- PostgreSQL `select 1`: passed.
- Redis `PING`: `PONG`.
- Secret scan: no tracked private key/RPC key/API key assignment found; matches
  were documentation placeholders or intentional empty `xprv` response fields.
- Push: no.

Old UTXO table decision:

- Code is ready to drop only `btc_utxo_transaction`,
  `ltc_utxo_transaction`, `doge_utxo_transaction`, and
  `bch_utxo_transaction`.
- Do not drop address / withdraw_record / withdraw_transaction tables in this
  cleanup.
- Physical drop remains a separate operator action after backup:
  `psql "$DATABASE_URL" -f scripts/drop-legacy-bitcoinlike-utxo-tables.sql`.

---

## BTC-like Address Route Migration Update

Generated: 2026-06-24 Asia/Shanghai.

- Fresh local DB backup created:
  `db-backups/pre-drop-legacy-utxo-20260624140232.sql`.
- Guarded legacy UTXO drop was executed locally. Because local table ownership
  was split between `wallet` and `atomex`, the same guard was executed per owner.
- Confirmed remaining BTC-like UTXO runtime table:
  `utxo_record`.
- Confirmed dropped local tables:
  `btc_utxo_transaction`, `ltc_utxo_transaction`, `doge_utxo_transaction`,
  `bch_utxo_transaction`.
- `surprising-wallet-init-pgsql.sql` and `multi-chain-wallet-schema.sql` no
  longer create legacy `*_utxo_transaction` tables.
- Added `scripts/migrate-bitcoinlike-chain-address-backfill.sql`.
- Backfilled historical BTC-like addresses into `chain_address`:
  BTC `10`, LTC `7`, DOGE `7`, BCH `8`.
- BTC/LTC/DOGE/BCH new address generation now writes `chain_address` and does
  not write legacy `*_address` tables.
- `AddressService` resolves BTC-like addresses from `chain_address` first, then
  legacy `*_address` as a historical fallback/backfill source.
- BCH first signer now validates stored redeemScript only when a legacy row
  provides one; new `chain_address` rows can be signed by deriving scripts from
  path metadata.

Validation:

- `mvn -q -DskipTests compile`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -DskipTests=false -Dutxo.migration.db.enabled=true -Dtest=BitcoinLikeUnifiedUtxoRuntimeMigrationTest test`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -DskipTests=false -Dbitcoinlike.regtest.enabled=true -Dtest=BitcoinLikeRegtestFullFlowIntegrationTest test`: passed.
- `mvn -q clean install -DskipTests=false`: passed.
- wallet-server address API generated DOGE address
  `2N4VqvAwgzRYrrFKUoDkjXxLE8YB99TrFKi` for user `99042401`.
- DB assertion for that DOGE address:
  `chain_address` row count `1`, `doge_address` row count `0`.
- Push: no.

---

## Sui Testnet Gate Final Update

Generated: 2026-06-24 Asia/Shanghai.

- Shared-master-seed SLIP-0010 Ed25519 derivation passed stability, chain
  separation, user-index separation, signature verification, and service-restart
  determinism tests.
- Runtime id `53` and BIP44 coin type `784` are separate database fields.
- Sui native addresses:
  - Owner/deposit: `0xe9ea852c9bcd9a48769da7608b26fdd1b4d7e28f57777cf74336b61546b440e0`
  - External: `0x8375c7ebc6ccbe821e70e0c160fa2a916bcec46693eac6181d50dd3fc41e56ca`
  - Hot: `0x0bda2e1c5cf7ff45cdd0d0abe80762847881d49a03fa750f13de43fd2cf7cc59`
- SUI deposit:
  `79yfEshLC7FeZbHzcFsBATxiHqt9yzW6jiNWNJqn8PtP`.
- SUI withdrawal:
  `5y5UcARz2xJxtFNhV248SDWLFm965aLDcbAeE6i6LLWc`.
- SUI collection:
  `AbYDXSYHRjJXg7s64ik9swzz2TuSdcfxnYQwG9WK1fPG`.
- Mock Sui Coin<T> package:
  `0x516b04d9f19a4eee51fb9b2e3d80a4691ee65680cd488ddffd8b91c4d24762ce`.
- Mock MUSD CoinType:
  `0x516b04d9f19a4eee51fb9b2e3d80a4691ee65680cd488ddffd8b91c4d24762ce::mock_coin::MOCK_COIN`.
- Final MUSD mint/deposit:
  `3YWdJtK7Ag9biLJVEdsVXKujtykvHfYJzkhFwT23PEe4`.
- Final MUSD withdrawal:
  `CgyMdZYiBAn8UncrbBBxGi4NvvY3tKYEt5w3vTxf3xkN`.
- Final MUSD collection:
  `GozpwydgFLaj1jnBoYUQMHTjfGjzp8vYvxumdv5VtJod`.
- Scanner checkpoint reached checkpoint `351930951`; duplicate scans did not
  duplicate credit.
- Successful withdrawal/collection business-id replay returned the original
  digest and did not broadcast again.
- Sui coin/object selection validated `unsafe_paySui`, `unsafe_pay`, separate
  SUI gas object selection for Coin<T>, and RPC confirmation through
  `sui_getTransactionBlock`.
- `deposit_record`: MUSD 2 `CREDITED` real testnet rows; SUI real testnet
  deposit plus repeatable DB-flow synthetic rows.
- `withdrawal_order`: SUI 1 `CONFIRMED`; MUSD 2 `CONFIRMED`.
- `collection_record`: SUI 1 `CONFIRMED`; MUSD 2 `CONFIRMED`.
- Final ledger: SUI owner available/total `890000000`, MUSD owner
  available/total `16000000`, locked `0`, negative Sui ledger rows `0`.
- Public faucet rate limiting was reproduced (`429`). Publisher funding used a
  controlled Sui testnet owner address; no faucet/key material was committed.
- Sui Java `HttpClient` TLS/EOF instability was handled by an OkHttp fallback;
  production code no longer shells out to `curl`.
- `mvn -q clean install -DskipTests=false`: passed.
- PostgreSQL `select 1`: passed; Redis: `PONG`.
- wallet-server health: `UP`; wallet-sig1 and wallet-sig2 started from final
  clean artifacts.
- Secret scan: no plaintext private key, Sui CLI private key, RPC key, or API key
  assignment in tracked Sui/prod config files.
- Push: no.

Detailed evidence: `SUI_WALLET_REPORT.md`.

---

## Aptos Devnet Gate Update

Generated: 2026-06-23 Asia/Shanghai.

- Aptos APT native and Aptos Coin<T> token flow are implemented with the shared
  SLIP-0010 Ed25519 master-seed tree.
- Runtime id `52` and BIP44 coin type `637` are database-driven and separate.
- Native owner: `0xd0456d74d63c33ab208843ae764c1acb006ac6f8557b217201c893b4996eae24`.
- Mock token owner: `0x8589db260fb347dd4b5391ff3fb85d314b4adcea9bbaf9fc7774eca40fd0d16e`.
- Mock MUSD CoinType:
  `0x0efda149ef9237e8a6cb23228ec986bec0898f320f0d03e8f8b744208244759e::mock_coin::MockCoin`.
- Native deposit:
  `0xdd7cf27d412dcfe112556f0674bf7bb0ab4fe7c8964b101608bc08e4ac7cb57d`.
- Native withdrawal:
  `0xae6c1cdb173f58e632a92fc7df0109e7021ff6e0446f29d52bf5f4903d1931ea`.
- Native collection:
  `0x67349361098b848ce26e813ccd6c40251750d26c799781bf3ad2d735dc3baeb1`.
- Mock package publish:
  `0x0d9db37ba5bcf9a968713be3602df5e80306588b90359d2fb98890584521b483`.
- Mock token deposit/mint:
  `0xae178ede89e8c7e903b010af60904cd88a9c7708fbb28380b1c6845a63e17e69`.
- Mock token withdrawal:
  `0xcf108fd2a631fa4486169c47d3c7bc80abfe313cdf8605c717568aed6936344f`.
- Mock token collection:
  `0xe6b8131fa318aaeb9b96acf53511f606ada9bf45de226b74cdccae177f5f2e5d`.
- `deposit_record`: APT 1 CREDITED row; MUSD 2 CREDITED rows. The latest
  MUSD row is the final post-fix token live run.
- `withdrawal_order`: APT 1 CONFIRMED; MUSD 2 CONFIRMED.
- `collection_record`: APT 1 CONFIRMED; MUSD 2 CONFIRMED.
- Final ledger: APT owner available/total `85000000`, MUSD owner
  available/total `18000000`, locked `0`.
- Negative Aptos ledger rows: `0`.
- Scanner checkpoint: `aptos-coin-event-scanner`, best/safe version
  `112983882`.
- Aptos public devnet produced an intermediate `429` rate-limit. The token live
  gate then scanned from the mint transaction version and passed.
- Aptos unit tests, PostgreSQL integration test, native live test, and token live
  test passed.
- `mvn -q clean install -DskipTests=false`: passed.
- Full Surefire: 95 tests, 0 failures, 0 errors, 20 skipped.
- PostgreSQL `select 1`: passed; Redis: `PONG`.
- wallet-server test profile started and `/actuator/health` returned `UP`.
- wallet-sig1 test profile started; `FirstSignJob` active.
- wallet-sig2 test profile started; `SecondSignJob` active.
- Startup caveat: wallet-server logged transient BTC public-RPC
  `SSLHandshakeException/connection reset` during scheduled BTC scan, but the
  process stayed healthy. This is an external BTC RPC read failure, not an Aptos
  regression.
- Secret scan: no plaintext private key, RPC key, API key, or master-seed value
  in tracked source/config or new Aptos files.
- Push: no.

Detailed evidence: `APTOS_WALLET_REPORT.md`.

---

## TON Native / Jetton Live Gate Final Update

Generated: 2026-06-23 22:10 Asia/Shanghai. This section supersedes the earlier
TON blocked notes.

- TON native + Jetton code completed and verified on TON testnet.
- Public testnet USDT/USDC could not be obtained, so USDT and USDC were
  validated with self-deployed TON testnet mock Jetton masters.
- Runtime id `51` and BIP44 coin type `607` remain database-driven and separate.
- Owner address:
  `0QAZqo0OKuwLmIsJT57odv_onrMViV4mnymFhoYst7EUiGWs`.
- External recipient:
  `0QC1F5dA8vlIyvUGIMiHTZUreQVNt69dXZvsNjuxFLUGOcCG`.
- Hot wallet:
  `0QCOrx-PYGs7ab6r81lFm6B7t4XbmNkdeqc6Ns_bIpmnHrmE`.
- Mock USDT master:
  `kQCZ5SAA78W_0vA5eSoU23YomxnUwah3KYagqeesNQI5jOXT`.
- Mock USDC master:
  `kQCzPT6908-8TR862TQo1S43-2kEme8UKRCRSWkaxNLD7H_2`.
- Native withdrawal:
  `6/v48yQabtNIfMH5VImFxW9xK6dO9FS+sLiqdXvRs/g=`.
- Native collection:
  `mU39Pf7F9Q68rYc2/5OTbNCRpqmREMxb7dy1U+kzlM8=`.
- USDT withdrawal:
  `WCLKsqWSVTCd7oeVKWP4W5Ft57XQdusB/uGNuzBpxKw=`.
- USDT collection:
  `aVTlXPR5Cw4HIaxAu+3zy9/I3b6fsQfQvdnG2EgrgRE=`.
- USDC withdrawal:
  `Us7AaK2DCJI+ISjcMoT4iUhXKFDyfzRiDYDyhGeNjgU=`.
- USDC collection:
  `284BbYeWklER9X5xFbAY57wsyHaykd3Z3aDpCWCHYQg=`.
- `deposit_record`: TON 3 CREDITED rows totaling `4001000000`; USDT 4
  CREDITED rows totaling `4000000000`; USDC 4 CREDITED rows totaling
  `4000000000`.
- `withdrawal_order`: TON/USDT/USDC each 1 CONFIRMED.
- `collection_record`: TON/USDT/USDC each 1 CONFIRMED.
- `ton_transaction`: TON 5 CONFIRMED rows, USDT 6 CONFIRMED rows, USDC 6
  CONFIRMED rows.
- Duplicate deposit and duplicate transaction checks: 0 rows.
- Final ledger: TON `3946000000`, USDT `3900000000`, USDC `3900000000`;
  locked balances `0`; negative ledger rows `0`.
- Scanner checkpoint: `ton-account-message-scanner`,
  `best_height=67008877`, `safe_height=67008877`, `status=ACTIVE`.
- Native scanner was hardened to ignore platform-originated Jetton protocol
  messages so Jetton excess/refund messages cannot be credited as native TON
  deposits.
- TonCenter client now includes HTTP response body diagnostics and retries
  transient 429/5xx responses.
- `mvn -q clean install -DskipTests=false`: passed.
- PostgreSQL `select 1`: passed; Redis: `PONG`.
- wallet-server test profile health: `UP`.
- wallet-sig1 test profile: started and listened on `8004`.
- wallet-sig2 test profile: started in non-web mode and second-sign job was
  active.
- Current owner balance after live gate is about `0.826` testnet TON; apply more
  testnet TON to the owner address before additional extended live tests.
- Push: no.

Detailed evidence: `TON_WALLET_REPORT.md`.

---

## Solana Devnet Gate Final Update

Generated: 2026-06-23 Asia/Shanghai.

- Shared-master-seed SLIP-0010 Ed25519 derivation passed stability, chain separation,
  user-index separation, signature verification, and service-restart determinism tests.
- Runtime id `50` and BIP44 coin type `501` are separate database fields.
- SOL deposits:
  - `4F4cZdy39pEDqQA6TteEYvXmxJkW2tstpMAY3aU34m4eDWFrSYfnT666vXu1td384xBnxQU9JXcvR1DusiqvD9sZ`
  - `2bRy3NN6pm3yoMQLEQSTKXaRZrpK6KbqwTLXovU1y2XZr759rvn1fu9LVjcf92pN5BnMch6yPVjMdUUB9pVsjEKv`
- SOL withdrawal:
  `54WzTvv62Vt4NARYJvzrb9KUdwtpCLpkBPUqjQNK5cKspn9yj3MkWfRTD3Go8CCN1U3Q6RqLM1NW5Komt9cprNEr`.
- SOL collection:
  `5PvuJukmM1wdPm9LqVgnLSsMh4SLpHjCdXwkSvjgVcWZrDt6c6c6NUp14WmSV5rQMAtG8JeBgYhGtYPp1G59nFdZ`.
- Mock SPL USDT and USDC each passed mint/ATA creation, deposit, scanner credit,
  withdrawal, collection, confirmation, and replay idempotency.
- Scanner checkpoint reached slot `471360225`; duplicate scans did not duplicate credit.
- Successful withdrawal/collection business-id replay returned the original signature.
- Oversized withdrawal failed before broadcast; locked balances remained zero.
- Negative Solana ledger rows: `0`.
- Official RPC faucet rate limiting and transient EOF were reproduced. The official
  PoW faucet and bounded RPC retry completed the gate without mocked transactions.
- `mvn -q clean install -DskipTests=false`: passed.
- Full Surefire: 79 tests, 0 failures, 0 errors, 14 environment-conditioned skips.
- PostgreSQL `select 1`: passed; Redis: `PONG`.
- wallet-server health: `UP`; wallet-sig1 and wallet-sig2 started.
- Production YAML contains only an environment placeholder for the master seed.
- Push: no.

Detailed evidence: `SOLANA_WALLET_REPORT.md`.

---

## Bitcoin Cash Regtest Gate Final Update

Generated: 2026-06-23 Asia/Shanghai. This section supersedes the earlier BCH funded-live deferral.

- Official Bitcoin Cash Node `29.0.0` installed through the checked Docker workflow; node result `chain=regtest`, `txindex=1`, RPC `127.0.0.1:18443`, final height `126`.
- Regtest addresses:
  - UserA: `bchreg:pzeausgnzhry45zss97uu2lrlrnhy4k2xvff4pdrhr`
  - UserB: `bchreg:pqlvl75tpu99kv2yl5jshw98ktthu0d7ggg8a76hul`
  - UserC: `bchreg:ppyxcegm2k6yx7m0u9r7xqtk0pntacm9sgg7k64w5d`
  - Hot wallet: `bchreg:prp53syhkfe3w87zv2s6vlxnlf4v7uuywsrslzjnzy`
- Deposits:
  - `7388b67eef77f204242ab1af9887bad4d071a7e93b125d706fbc803f8b7c0c27` — 10 BCH, block 102.
  - `2625dab2fc411c40967b8ee01083367360d6ec100a0b82ef44be519d14d5ff94` — 5 BCH, block 102.
  - `60ccb42812b6900b345a781fb62abab8ab473f06def89e21edf48aac051ebe31` — 2 BCH, block 120, sent while wallet-server was stopped.
- Withdrawal `2807d1ec012c244aef51e3342ec6fa9bc733c4f7d1c3a1f2d3f260a30b22acd0`: two-stage signing, BCHN broadcast, six-confirmation settlement, 1 BCH output, 8.99999623 BCH change, 377 sat fee.
- Collection `a7c53c36e0c2dcf5caba1a1e010defbda0b9c0fc1e0c4f447fbd25ab65447a3a`: recovered after a reproduced one-output fee-plan bug, sent 4.99999657 BCH to the hot wallet, 343 sat fee, final `CONFIRMED`.
- BCHN decoded both production transaction signatures as `[ALL|FORKID]`.
- Independent ForkId node test:
  - correct transaction `7784e5d3fe294952d4f1089cd4beac012d48c8a670c96bbd0728c3bb94f17269`: `testmempoolaccept allowed=true`;
  - signatures changed from `0x41` to `0x01`: rejected with `mandatory-script-verify-flag-failed (Signature must use SIGHASH_FORKID)`.
- Deposit checkpoint rewind and replay produced no duplicate deposit, UTXO, or ledger credit.
- Successful withdrawal order replay was skipped before a second freeze/sign/broadcast.
- Failed withdrawal signing released the `0.501 BCH` frozen balance and unified/legacy UTXO locks.
- Collection retry required an explicit `FAILED -> RETRYING` transition and produced exactly one successful recovery transaction.
- Final customer ledger: `8.999 + 5 + 2 = 15.999 BCH`, locked `0`, negative rows `0`.
- Controlled available UTXOs: `15.99999280 BCH`; difference `0.00099280 BCH` equals charged withdrawal fee minus actual withdrawal and collection network fees.
- Scanner checkpoint: best height `126`, safe height `120`, using BCH regtest profile confirmations `6`.
- PostgreSQL-backed DOGE/BCH flow test with forced database execution: passed.
- `mvn -q clean install -DskipTests=false`: passed.
- Full Surefire: 74 tests, 0 failures, 0 errors, 12 skipped.
- wallet-server health: `UP`; wallet-sig1 and wallet-sig2 started from final clean artifacts.
- PostgreSQL `select 1`: passed; Redis: `PONG`.
- Secret scan: no tracked extended private key or plaintext API/RPC key assignment.
- Push: no.

Detailed evidence: `BITCOIN_CASH_WALLET_REPORT.md`.

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

---

## Dogecoin Regtest Gate Final Update

Generated: 2026-06-23 Asia/Shanghai. This section supersedes the earlier DOGE funded-live deferral.

- Official Dogecoin Core `1.14.9` Regtest node installed through the checked Docker workflow; release SHA-256 verified.
- Node result: `chain=regtest`, RPC host binding `127.0.0.1:22555`, height `131`.
- Multi-user deposits:
  - `1fb68eb8a9bca674dfd4f90d7757359b69aa4a107fab35d507bee4f65f47f10a` — 1000 DOGE.
  - `54d7c0d5eff21a67487bebe3ed4105915a16f980e5830b49f7d0e0d598339b0a` — 500 DOGE.
  - `55563f61db1d6a1c9b080442f336f666df5fd2516295ff0f8e48002248370b87` — 200 DOGE after scanner interruption.
- Withdrawal `c1cee4ab5933bdf6457a19739d88e74e87272294c8ec2c23207eb327af9d93e7`: real two-stage signing, broadcast, change, six-confirmation settlement, and UTXO spend passed.
- Collections:
  - `8e5b7d8a4d2a72bafe659ff0769c75b4b262ec2c5d5dcd80a731fa602ed8816d`.
  - Recovered interruption transaction `def4eba2a79114376a8bb0704b80fe9854a013e2e35232c8aeb2fc7c87974765`.
- Rewound scanner replay produced no duplicate deposit, UTXO, or ledger credit.
- Replayed signed transaction produced an idempotent completed-state skip and no second broadcast.
- Failed collection retry bug was reproduced and fixed with atomic collection claim and terminal failed transaction state.
- Sig1 interruption across another collection interval retained exactly one database transaction and one Redis message; restart completed the transaction.
- Final ledger: users `899 + 500 + 200 = 1599 DOGE`, locked `0`, negative rows `0`.
- Controlled UTXOs `1599.98937000 DOGE`; difference matches collected fee minus actual network fees.
- Forced PostgreSQL DOGE flow test: passed.
- `mvn -q clean install -DskipTests=false`: passed.
- Full Surefire: 72 tests, 0 failures, 0 errors, 12 skipped.
- wallet-server health: `UP`; wallet-sig1 and wallet-sig2 started.
- PostgreSQL `select 1`: passed; Redis: `PONG`.
- Secret scan: no extended private key, RPC key, or API key in tracked source/config.
- Push: no.

Detailed evidence: `DOGECOIN_WALLET_REPORT.md`.
