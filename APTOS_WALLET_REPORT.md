# Aptos Wallet Report

Generated: 2026-06-23 Asia/Shanghai.

## Overall Conclusion

Aptos APT native and Aptos Coin<T> token wallet flow is implemented and verified
on Aptos devnet. The gate covered deterministic Ed25519 address derivation,
native APT deposit scan, native withdrawal, native collection, mock Coin<T>
deposit scan, token withdrawal, token collection, idempotency, sequence
reservation, ledger reconciliation, PostgreSQL-backed state transitions, and
real devnet transaction confirmation.

Public Aptos devnet does not provide canonical funded USDT/USDC test assets. The
token gate used a self-deployed six-decimal mock Move coin (`MUSD`) on devnet.
This validates Aptos Coin<T>/FA mechanics and must be replaced by production
token configuration for mainnet assets.

No source file contains private keys, master seed material, RPC keys, or faucet
keys. No push was performed.

## Key Architecture

- Chain model: Account model with sequence numbers, not UTXO and not EVM logs.
- Runtime currency id: `52`, loaded from `chain_profile`.
- BIP44 coin type: `637`, independent from runtime currency id.
- Derivation path: `m/44'/637'/{userIndex}'/0'/0'`.
- Key model: shared `ATOMEX_MASTER_SEED` through the unified SLIP-0010 Ed25519
  tree. No BTC/EVM/TRON secp256k1 private key is converted or hashed.
- Address model: Aptos auth-key address = `SHA3-256(pubkey || 0x00)`.
- Sequence recovery: `account_sequence` reserves sender sequence before signing.
- Source of truth: `chain_profile`, `chain_asset`, `token_config`,
  `chain_address`, `chain_scan_height`, `deposit_record`, `withdrawal_order`,
  `collection_record`, `ledger_balance`, and `aptos_transaction`.
- `CurrencyEnum` and `CurrencyIds` are not used for Aptos runtime routing.

## Addresses

### APT Native Live Gate

- Owner/deposit:
  `0xd0456d74d63c33ab208843ae764c1acb006ac6f8557b217201c893b4996eae24`
- External recipient:
  `0x1bde14f8ad736d0d74be4b3cd9dadaedbd8fef13faad5ee33a57e2402e784ac1`
- Hot wallet:
  `0x6e3ae50e96330c88563aefa2e288984fe9064674d2b4438a5bb599b68e3455fd`

### Mock Token Live Gate

- Publisher:
  `0x0efda149ef9237e8a6cb23228ec986bec0898f320f0d03e8f8b744208244759e`
- Owner/deposit:
  `0x8589db260fb347dd4b5391ff3fb85d314b4adcea9bbaf9fc7774eca40fd0d16e`
- External recipient:
  `0x9cf77412fc5b2001c54966b4886c5ac278f608e3a28fe9f00373c1dec7065af8`
- Hot wallet:
  `0x09609cbcbd34d74f498efc80d7b128d8694917dc935cbd6e000580b78eb2f243`
- Coin type:
  `0x0efda149ef9237e8a6cb23228ec986bec0898f320f0d03e8f8b744208244759e::mock_coin::MockCoin`
- FA metadata object:
  `0x225acb326248bb0879a27f521be85f0c596e67966f830fc228241f389f6045fe`

## Live Transaction Results

Explorer form:
`https://explorer.aptoslabs.com/txn/<tx_hash>?network=devnet`.

### APT Native

- Deposit tx:
  `0xdd7cf27d412dcfe112556f0674bf7bb0ab4fe7c8964b101608bc08e4ac7cb57d`
- Withdrawal tx:
  `0xae6c1cdb173f58e632a92fc7df0109e7021ff6e0446f29d52bf5f4903d1931ea`
- Collection tx:
  `0x67349361098b848ce26e813ccd6c40251750d26c799781bf3ad2d735dc3baeb1`
- Deposit amount: `100000000` octas.
- Withdrawal amount: `10000000` octas plus `5000000` octas fee reserve.
- Collection amount: `10000000` octas.

### Mock MUSD Token

- Mock package publish tx:
  `0x0d9db37ba5bcf9a968713be3602df5e80306588b90359d2fb98890584521b483`
- Deposit/mint tx:
  `0xae178ede89e8c7e903b010af60904cd88a9c7708fbb28380b1c6845a63e17e69`
- Withdrawal tx:
  `0xcf108fd2a631fa4486169c47d3c7bc80abfe313cdf8605c717568aed6936344f`
- Collection tx:
  `0xe6b8131fa318aaeb9b96acf53511f606ada9bf45de226b74cdccae177f5f2e5d`
- Deposit amount: `10000000` atomic units.
- Withdrawal amount: `1000000` atomic units.
- Collection amount: `1000000` atomic units.

## Scanner, Idempotency, And Recovery

- Native APT scanner parses `0x1::fungible_asset::Deposit` events and resolves
  `FungibleStore` metadata/owner from transaction changes.
- Token scanner supports both FA metadata mapping through
  `0x1::coin::paired_metadata<CoinType>` and legacy CoinStore deposit-event
  fallback for Move coins.
- Deposit uniqueness remains `(chain, tx_hash, log_index)`.
- Immediate scanner replay preserved the same credited ledger balance.
- `chain_scan_height`: `aptos-coin-event-scanner`, final observed
  `best_height=112983882`, `safe_height=112983882`, `status=ACTIVE`.
- Withdrawal and collection idempotency: repeated business ids returned the
  existing tx hash and did not broadcast again.
- Public devnet RPC returned a real `429` rate-limit during an intermediate run;
  the live token test was adjusted to scan from the mint transaction version
  instead of backfilling a large unrelated public-RPC window.

## Ledger Results

Final DB evidence:

- APT owner: available/total `85000000`, locked `0`.
- MUSD owner: available/total `18000000`, locked `0`.
- `deposit_record` APT: 1 `CREDITED` row.
- `deposit_record` MUSD: 2 `CREDITED` rows. The second row is the final
  post-fix token live run; the earlier credited row remains as cumulative devnet
  evidence and was not double-credited.
- `withdrawal_order` APT: 1 `CONFIRMED`.
- `withdrawal_order` MUSD: 2 `CONFIRMED`.
- `collection_record` APT: 1 `CONFIRMED`.
- `collection_record` MUSD: 2 `CONFIRMED`.
- Negative Aptos ledger rows: `0`.

## Fee / Dust / Sequence

- Aptos is not UTXO; UTXO dust and lock/spent states are not applicable.
- `default_fee_rate` for devnet is `5000000` octas.
- Signed transactions use the RPC gas-unit estimate and enforce at least
  `50000` max gas units to satisfy devnet minimum gas constraints.
- `account_sequence` synchronizes sender sequence after confirmed transactions
  and prevents duplicate sequence reservation during retry/recovery.

## Database Schema And MBG

Added:

- `aptos_transaction`, unique `(chain, tx_hash)`.
- Aptos devnet/testnet/mainnet `chain_profile` rows.
- APT `chain_asset` row.
- Disabled placeholder Aptos Coin<T> token config row for DB-driven activation.
- Runtime `MUSD` token config row for the devnet mock coin.

Reused:

- `chain_address`
- `deposit_record`
- `withdrawal_order`
- `collection_record`
- `ledger_balance`
- `chain_scan_height`
- `account_sequence`

No MBG generation was required. No service, scanner, signer, RPC, fee, or
business logic was generated by MBG.

## Files

Added production files:

- `currency-sdks/wallet-common/src/main/java/com/surprising/wallet/common/chain/AptosTransactionRecord.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosHex.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosBcs.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosKeyService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosAddressService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosRpcClient.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosTransactionSigner.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosTransactionService.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosDepositScanner.java`
- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/chain/aptos/AptosChainAdapter.java`
- `infra/aptos/mock-coin/Move.toml`
- `infra/aptos/mock-coin/sources/mock_coin.move`

Modified:

- `backendservices/wallet-parent/wallet-service/src/main/java/com/surprising/wallet/service/dao/ChainJdbcRepository.java`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml`
- `backendservices/wallet-parent/wallet-server/src/main/resources/application-prod.yaml`
- `multi-chain-wallet-schema.sql`
- `CURRENCY_MODEL_COMPATIBILITY_REPORT.md`
- `regression-report.md`

Deleted:

- None.

## Tests Added

- `AptosAddressGenerationTest`
- `AptosSigningTest`
- `AptosDatabaseFlowIntegrationTest`
- `AptosLiveNativeFlowIntegrationTest`
- `AptosLiveTokenFlowIntegrationTest`

## Test Commands And Results

- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.aptos.*Test' -Dsurefire.failIfNoSpecifiedTests=false`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.aptos.AptosDatabaseFlowIntegrationTest' -Daptos.db.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`: passed.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.aptos.AptosLiveNativeFlowIntegrationTest' -Daptos.live.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`: passed with real devnet txs above.
- `mvn -q -pl backendservices/wallet-parent/wallet-service -am test -Dtest='com.surprising.wallet.service.chain.aptos.AptosLiveTokenFlowIntegrationTest' -Daptos.token.live.enabled=true -Dsurefire.failIfNoSpecifiedTests=false`: passed with real devnet txs above.
- `mvn -q clean install -DskipTests=false`: passed, 95 tests, 0 failures,
  0 errors, 20 environment-conditioned skips.
- PostgreSQL `select 1`: passed.
- Redis `PING`: `PONG`.
- wallet-server test profile: started; `/actuator/health` returned `UP`.
- wallet-sig1 test profile: started; `FirstSignJob` active.
- wallet-sig2 test profile: started; `SecondSignJob` active.
- Production YAML / Aptos source secret scan: no plaintext private key, RPC key,
  API key, or master seed value. Only environment-variable placeholders and code
  variable names were found.
- Startup note: wallet-server emitted transient BTC public-RPC
  `SSLHandshakeException/connection reset` logs during scheduled BTC scan, but
  service startup and health stayed `UP`.

## Blocked And Risk Items

- Aptos public devnet anonymous RPC can return `429`. Production requires an
  authenticated fullnode/indexer provider and bounded scanner pagination.
- Mock MUSD validates Aptos token mechanics, not issuer controls for canonical
  USDT/USDC.
- Canonical Aptos USDT/USDC test assets were not available through a reliable
  public faucet; production token addresses must remain database-driven.

## Commit / Push

- Commit message: `feat: add aptos wallet flow`.
- Commit hash: reported after commit because a commit cannot contain its own hash.
- Push: no.
