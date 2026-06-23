# Sui Wallet Report

Generated: 2026-06-24 Asia/Shanghai.

## Overall Conclusion

Sui native SUI and Sui Coin<T> token wallet flow is implemented and verified on
Sui testnet. The gate covered deterministic Ed25519 address derivation, SUI
deposit scan, SUI withdrawal, SUI collection, mock Coin<T> mint/deposit scan,
token withdrawal, token collection, idempotency, object/coin selection, gas
object handling, ledger reconciliation, PostgreSQL-backed state transitions,
and real testnet transaction confirmation.

Public Sui testnet does not provide canonical funded USDT/USDC test assets for
this environment. The token gate used a self-deployed six-decimal Move coin
(`MUSD`) on Sui testnet. This validates Sui Coin<T> mechanics and must be
replaced by production token configuration for mainnet USDT/USDC assets.

No source file contains private keys, master seed material, RPC keys, faucet
keys, or Sui CLI private keys. No push was performed.

## Key Architecture

- Chain model: Sui object/coin model, not UTXO and not a plain account model.
- Runtime currency id: `53`, loaded from `chain_profile`.
- BIP44 coin type: `784`, independent from runtime currency id.
- Derivation path: `m/44'/784'/{userIndex}'/0'/0'`.
- Key model: shared `ATOMEX_MASTER_SEED` through the unified SLIP-0010 Ed25519
  tree. No BTC/EVM/TRON secp256k1 private key is converted or hashed.
- Address model: `blake2b256(0x00 || ed25519_public_key)`.
- Signing model: Sui intent signing over `blake2b256(intent || txBytes)` with
  serialized signature `0x00 || signature || publicKey`.
- Transaction build/broadcast: Sui JSON-RPC `unsafe_paySui`, `unsafe_pay`,
  `sui_executeTransactionBlock`, `suix_getCoins`, `suix_getBalance`,
  `suix_queryTransactionBlocks`, and `sui_getTransactionBlock`.
- Source of truth: `chain_profile`, `chain_asset`, `token_config`,
  `chain_address`, `chain_scan_height`, `deposit_record`, `withdrawal_order`,
  `collection_record`, `ledger_balance`, and `sui_transaction`.
- `CurrencyEnum` and `CurrencyIds` are not used for Sui runtime routing.

## Addresses

### SUI Native Live Gate

- Owner/deposit:
  `0xe9ea852c9bcd9a48769da7608b26fdd1b4d7e28f57777cf74336b61546b440e0`
- External recipient:
  `0x8375c7ebc6ccbe821e70e0c160fa2a916bcec46693eac6181d50dd3fc41e56ca`
- Hot wallet:
  `0x0bda2e1c5cf7ff45cdd0d0abe80762847881d49a03fa750f13de43fd2cf7cc59`

### Mock MUSD Token Live Gate

- Publisher:
  `0xe2ff22635526e874d42c40c69440aa248e810e966c7d23b53fed337942ddeca9`
- Owner/deposit:
  `0xe9ea852c9bcd9a48769da7608b26fdd1b4d7e28f57777cf74336b61546b440e0`
- External recipient:
  `0x8375c7ebc6ccbe821e70e0c160fa2a916bcec46693eac6181d50dd3fc41e56ca`
- Hot wallet:
  `0x0bda2e1c5cf7ff45cdd0d0abe80762847881d49a03fa750f13de43fd2cf7cc59`
- Package:
  `0x516b04d9f19a4eee51fb9b2e3d80a4691ee65680cd488ddffd8b91c4d24762ce`
- TreasuryCap:
  `0x9d9851ec10d98e8d451bc264b7d06da525ce7cce99a8d71c64084a21a485e23b`
- Coin type:
  `0x516b04d9f19a4eee51fb9b2e3d80a4691ee65680cd488ddffd8b91c4d24762ce::mock_coin::MOCK_COIN`

## Live Transaction Results

Explorer form:
`https://suiexplorer.com/txblock/<digest>?network=testnet`.

### SUI Native

- Deposit/faucet tx:
  `79yfEshLC7FeZbHzcFsBATxiHqt9yzW6jiNWNJqn8PtP`
- Withdrawal tx:
  `5y5UcARz2xJxtFNhV248SDWLFm965aLDcbAeE6i6LLWc`
- Collection tx:
  `AbYDXSYHRjJXg7s64ik9swzz2TuSdcfxnYQwG9WK1fPG`
- Deposit amount: `1000000000` MIST.
- Withdrawal amount: `100000000` MIST, fee reserve `10000000` MIST.
- Collection amount: `100000000` MIST, fee reserve `10000000` MIST.
- Actual gas recorded by RPC for these three confirmed txs: `1997880` MIST
  each.

### Mock MUSD Token

- Publisher funding tx:
  `BQCqm1BokLqFMQyeRJTjtM6M7i2g1tHgDrvtBe3utF1t`
- Mock package publish tx:
  `AWv7n1nMy5DEyLZUCR4aMQEUmfAF33Ycdg7Lapnb5pcc`
- Initial mint/deposit tx:
  `2ki6oa675g83egoDsLfs3BujtpeWEw6HNi9gbbmf17pN`
- Final mint/deposit tx after the OkHttp fallback fix:
  `3YWdJtK7Ag9biLJVEdsVXKujtykvHfYJzkhFwT23PEe4`
- Initial withdrawal tx:
  `8uzYzkFxn3nMgaDGPxXM9FjtVEGxqFHjxU92s98fVPi4`
- Final withdrawal tx:
  `CgyMdZYiBAn8UncrbBBxGi4NvvY3tKYEt5w3vTxf3xkN`
- Initial collection tx:
  `6bXPA1HoTeFzWe3R7VjASj8jnC7FaT1xWUH2R1opUmGT`
- Final collection tx:
  `GozpwydgFLaj1jnBoYUQMHTjfGjzp8vYvxumdv5VtJod`
- Each deposit/mint amount: `10000000` atomic units.
- Each withdrawal amount: `2000000` atomic units.
- Each collection amount: `3000000` atomic units.
- Actual gas recorded by RPC:
  - Mint: `2426064` MIST.
  - Token withdrawal: `2422264` MIST.
  - Token collection: `2422264` MIST.

## Scanner, Idempotency, And Recovery

- Scanner uses `suix_queryTransactionBlocks` with `ToAddress` and parses
  positive `balanceChanges` for tracked deposit addresses.
- Native SUI uses coin type `0x2::sui::SUI`; token deposits use the database
  `token_config.contract_address` CoinType.
- Deposit uniqueness remains `(chain, tx_hash, log_index)`.
- Immediate scanner replay preserved the same credited ledger balance.
- `chain_scan_height`: `sui-balance-change-scanner`, final observed
  `best_height=351930951`, `safe_height=351930951`.
- Successful withdrawal/collection business-id replay returned the existing tx
  digest and did not broadcast again.
- Sui coin object selection uses `suix_getCoins`; native transfer uses
  `unsafe_paySui`, token transfer uses `unsafe_pay` with a separate gas object.
- Failed precondition reproduced: a repeated token live run without fresh mint
  correctly failed before broadcast because on-chain token balance was depleted.
  A new mint was executed, then the final token live gate passed.

## Ledger Results

Final DB evidence:

- SUI deposit rows: 1 real testnet `CREDITED` row plus 3 DB-flow synthetic
  rows from repeatable PostgreSQL tests.
- MUSD deposit rows: 2 real testnet `CREDITED` rows.
- SUI withdrawal rows: 1 `CONFIRMED`.
- MUSD withdrawal rows: 2 `CONFIRMED`.
- SUI collection rows: 1 `CONFIRMED`.
- MUSD collection rows: 2 `CONFIRMED`.
- SUI owner ledger: available/total `890000000`, locked `0`.
- MUSD owner ledger: available/total `16000000`, locked `0`.
- Negative Sui ledger rows: `0`.

Collection moves funds from deposit address to hot wallet and does not debit the
customer platform ledger balance, matching the existing exchange ledger model.

## Fee / Dust / Object Management

- Sui is not UTXO; UTXO dust and lock/spent states are not applicable.
- `default_fee_rate` for testnet is `10000000` MIST and is used as fee reserve.
- Actual Sui gas is read from `effects.gasUsed` and stored on
  `sui_transaction`.
- Token transfers require an owned Coin<T> object and a separate SUI gas object.
- Coin split/merge is delegated to Sui `unsafe_pay` / `unsafe_paySui` transaction
  builders, which create the required output coin objects.

## Database Schema And MBG

Added:

- `sui_transaction`, unique `(chain, tx_digest)`.
- Sui testnet/mainnet `chain_profile` rows.
- SUI `chain_asset` row.
- Disabled placeholder `TESTCOIN` token config row for DB-driven activation.
- Runtime `MUSD` token config row for the deployed testnet mock coin.

Reused:

- `chain_address`
- `deposit_record`
- `withdrawal_order`
- `collection_record`
- `ledger_balance`
- `chain_scan_height`

No MBG generation was required. No service, scanner, signer, RPC, fee, or
business logic was generated by MBG.

## Files

Added production files:

- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/chain/SuiTransactionRecord.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiHex.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiKeyService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiAddressService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiTransactionSigner.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiRpcClient.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiTransactionService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiDepositScanner.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/sui/SuiChainAdapter.java`
- `infra/sui/mock-coin/Move.toml`
- `infra/sui/mock-coin/Move.lock`
- `infra/sui/mock-coin/sources/mock_coin.move`

Added tests:

- `SuiAddressGenerationTest`
- `SuiSigningTest`
- `SuiDatabaseFlowIntegrationTest`
- `SuiLiveNativeFlowIntegrationTest`
- `SuiLiveTokenFlowIntegrationTest`

Modified:

- `backendservices/wallet-parent/wallet-service/pom.xml`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/dao/ChainJdbcRepository.java`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml`
- `multi-chain-wallet-schema.sql`
- `CURRENCY_MODEL_COMPATIBILITY_REPORT.md`
- `regression-report.md`

Deleted:

- None.

## Test Commands And Results

- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.sui.*Test' -Dsurefire.failIfNoSpecifiedTests=false`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.sui.SuiDatabaseFlowIntegrationTest' -Dsui.db.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.sui.SuiLiveNativeFlowIntegrationTest' -Dsui.live.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`: passed with real testnet txs above.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.sui.SuiLiveTokenFlowIntegrationTest' -Dsui.token.live.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`: passed with real testnet txs above.
- `mvn -q clean install -DskipTests=false`: passed.
- PostgreSQL `select 1`: passed.
- Redis `PING`: `PONG`.
- wallet-server test profile: started; `/actuator/health` returned `UP`.
- wallet-sig1 test profile: started; `FirstSignJob` active.
- wallet-sig2 test profile: started; `SecondSignJob` active.
- Production YAML / Sui source secret scan: no plaintext private key, RPC key,
  API key, or master seed value. Only environment-variable placeholders and code
  variable names were found.
- Startup note: wallet-server emitted transient BTC public-RPC
  `SSLHandshakeException` logs during scheduled BTC scan, but service startup
  and health stayed `UP`.

## Blocked And Risk Items

- Public Sui faucet returned HTTP `429` during publisher funding. The publisher
  was funded by a controlled Sui testnet owner address derived from the same
  MasterSeed.
- Public Sui RPC can show TLS/EOF instability with Java `HttpClient` on this
  local environment. The Sui RPC client now uses Java `HttpClient` first and an
  OkHttp fallback; it no longer shells out to `curl`.
- Mock MUSD validates Sui Coin<T> mechanics, not issuer controls for canonical
  USDT/USDC.
- Canonical Sui USDT/USDC test assets were not available through a reliable
  public faucet; production token CoinTypes must remain database-driven.

## Commit / Push

- Commit message: `feat: add sui wallet flow`.
- Commit hash: reported after commit because a commit cannot contain its own hash.
- Push: no.
