# Solana Wallet Report

Generated: 2026-06-23 Asia/Shanghai.

## Overall Conclusion

Solana SOL and SPL wallet support passed the devnet gate. The implementation uses a
database-driven profile, SLIP-0010 Ed25519 derivation from the shared master seed,
Associated Token Accounts, signed version-legacy transactions, scanner replay
idempotency, guarded ledger state transitions, withdrawal broadcast idempotency,
collection broadcast idempotency, bounded RPC retry, and explicit failure handling.

The live gate used real Solana devnet transactions. USDT and USDC were represented by
two independently created six-decimal mock SPL mints because public devnet does not
provide a dependable funded canonical USDT/USDC faucet.

## Key Architecture

- Shared secret source: `ATOMEX_MASTER_SEED`; no seed or private key is stored in source or YAML.
- Derivation: SLIP-0010 Ed25519.
- SOL base path: `m/44'/501'/0'/0'`; the account component is replaced by the global user derivation index.
- Existing BTC/LTC/DOGE/BCH/EVM/TRON secp256k1 behavior was not changed.
- The Solana funder/test seed existed only under `/tmp` with mode `0600`.
- Runtime currency id: `50`, loaded from `chain_profile`.
- BIP44 coin type: `501`, independently loaded/stored and never inferred from runtime id.
- `CurrencyEnum` and `CurrencyIds` are not used by Solana.

## Addresses

- User A: `3devUaLAHLsA6ANBdCGQU81q7L6t5u18RewoLyocEXAh`
- User B: `HFPwqMMLn8fTtGemKcTPvk9ZWUuBMkBFotQUVV9gP8g4`
- Hot wallet: `3yQFxzGKvt7xVUnQLQ3qXHQCHfPntA9AxGGUwuk9oR3X`
- Funder: `CaQNcXwqCkyVNBs73sYmNW55yrNoxxFQxsNZ8xKhENSM`
- USDT mint: `2VHdVaW1jBGmvU1PdQpvmPnWV5AiAxrmxmUTLvf8951y`
- USDC mint: `FUez5CPP3C4VN7JGkgKxamC8f4cvt397R3unqUX6tekt`

All user, token ATA, and hot-wallet metadata is persisted in `chain_address`; no
private material is persisted.

## Live Transaction Results

### SOL

- User A deposit:
  `4F4cZdy39pEDqQA6TteEYvXmxJkW2tstpMAY3aU34m4eDWFrSYfnT666vXu1td384xBnxQU9JXcvR1DusiqvD9sZ`
- User B deposit:
  `2bRy3NN6pm3yoMQLEQSTKXaRZrpK6KbqwTLXovU1y2XZr759rvn1fu9LVjcf92pN5BnMch6yPVjMdUUB9pVsjEKv`
- Withdrawal:
  `54WzTvv62Vt4NARYJvzrb9KUdwtpCLpkBPUqjQNK5cKspn9yj3MkWfRTD3Go8CCN1U3Q6RqLM1NW5Komt9cprNEr`
- Collection:
  `5PvuJukmM1wdPm9LqVgnLSsMh4SLpHjCdXwkSvjgVcWZrDt6c6c6NUp14WmSV5rQMAtG8JeBgYhGtYPp1G59nFdZ`
- Each deposit: `20,000,000` lamports.
- Withdrawal: `5,000,000` lamports plus `5,000` lamports recorded fee.
- Collection: `10,000,000` lamports; collection network fee is an explained
  operational asset delta and does not debit the customer liability ledger.

### Mock SPL USDT

- Deposit:
  `2VDcnx6TejLGbzqPiXZMkJxkoGv6nCTcBFR9Kw7tWBpqJ2wp5gSowYLkddjKLPMrsxjGxUHy4gdTzwh1tNnXVXap`
- Withdrawal:
  `3mzBFbKYVAJwWH5b7vFt7DVKx36fvD5kAPwUmEG5y7d8AVmsrYGR4JY7dpWVJw3VpqPawwVQT8fADA25hD4GcA7V`
- Collection:
  `4CCpN7U69y6c3uaVSeGZHLatePKg7xoSPvX9m6JGT7j2V42RJCHNEgTwj2wtbbfoG8cUeKydsJzaDCcV4ZcLxbKC`

### Mock SPL USDC

- Deposit:
  `5Lgtta9846dXzxQoSywEWVQbNDwwGnFjByAxRXBxyGhpDe94vfqTdvz4YcXLAyfqj6AbTnnQP5HKmXPowNLw1u6U`
- Withdrawal:
  `wLAxhmgaXadE2mZPiQ4RNVLeaLaYvAHg28oPesLLef1pgh82mYTQufh2FX32y2sNXsitisQH9ihSpEPm82pM78d`
- Collection:
  `CeuAXHs2jEewMDqHcrLW5oz2xDVRRKhixKUiLJJnHcqwhovuimnj8ojA6fJakJZsxpiU5eakc7AxzzYDZZsnjTS`

Explorer form:
`https://explorer.solana.com/tx/<signature>?cluster=devnet`.

## Scanner, Idempotency, And Recovery

- Scanner uses `getSignaturesForAddress` plus `getTransaction` with `jsonParsed`.
- Native System Program transfers and SPL `transfer`/`transferChecked` instructions
  are normalized into `deposit_record`.
- Deposit uniqueness remains `(chain, tx_hash, log_index)`.
- Two immediate rescans preserved one deposit row and one ledger credit.
- `chain_scan_height` ended at best/safe slot `471360225`.
- Public RPC `429` and transient EOF behavior were reproduced; bounded retry with
  increasing delay was added and the complete live run then passed.
- A deliberately oversized withdrawal moved to `FAILED` before signing/broadcast
  and did not create a tx hash or negative ledger balance.
- Replaying successful withdrawal and collection business ids returned the original
  signature and did not broadcast again.

## Ledger Results

- User A SOL: available/total `14,995,000`, locked `0`.
- User A USDT: available/total `8,000,000`, locked `0`.
- User A USDC: available/total `8,000,000`, locked `0`.
- User B SOL: available/total `20,000,000`, locked `0`.
- User B USDT: available/total `5,000,000`, locked `0`.
- User B USDC: available/total `5,000,000`, locked `0`.
- Negative Solana ledger rows: `0`.
- Customer collection changes controlled-wallet location, not customer liability.
- Withdrawal settlement occurs only after confirmed signature status.

## Database Schema And MBG

Added:

- `chain_address` with chain/asset/user/biz/index/role uniqueness.
- Solana devnet/mainnet `chain_profile` rows.
- SOL `chain_asset` row.
- Runtime-created USDT/USDC `token_config` and `chain_asset` rows for the live mints.

Existing unified tables reused:

- `sol_transaction`
- `deposit_record`
- `withdrawal_order`
- `collection_record`
- `ledger_balance`
- `chain_scan_height`

No MBG generation was required. No service, scanner, signer, fee, or RPC logic was generated.

## Files

New production files include the unified Ed25519 provider/model classes and the
`service/chain/solana` RPC, key, address, transaction, scanner, and adapter classes.
New tests cover Ed25519 stability/signature verification, address/ATA generation,
PostgreSQL idempotency/locking, and the full devnet flow.

Modified files include:

- `multi-chain-wallet-schema.sql`
- wallet-common and wallet-service POMs
- `ChainType`
- `ChainJdbcRepository`
- wallet-server application YAML files
- `CURRENCY_MODEL_COMPATIBILITY_REPORT.md`
- `regression-report.md`

Deleted:

- the fail-fast future `SolanaChainAdapter` placeholder.

## Test Commands And Results

- `mvn -q -pl wallet-common -am test -DskipTests=false`: passed.
- PostgreSQL Solana test with `-Dsolana.db.enabled=true`: passed.
- Devnet test with `-Dsolana.live.enabled=true`: passed with real signatures above.
- `mvn -q clean install -DskipTests=false`: passed, 79 tests, 0 failures,
  0 errors, 14 environment-conditioned skips.
- PostgreSQL `select 1`: passed.
- Redis `PING`: `PONG`.
- wallet-server test profile: started; `/actuator/health` returned `UP`.
- wallet-sig1 test profile: started with a temporary external test key.
- wallet-sig2 test profile: started.
- Production YAML secret scan: no plaintext private key, RPC key, or master seed.

## Blocked And Risk Items

- Initial official `requestAirdrop` calls were rate-limited (`429`). The official
  Solana PoW faucet funded the test account and the gate continued.
- Public devnet RPC can rate-limit exchange-style scanners. Production deployment
  requires an authenticated/stake-backed RPC provider and scanner pagination.
- Mock SPL mints validate SPL mechanics, not issuer controls of canonical USDT/USDC.
- Solana transaction fees and ATA rent require an operational fee-payer policy;
  customer-token liabilities must never be reduced to cover SOL fees.

## Commit / Push

- Commit message: `feat: add solana spl wallet flow`.
- Commit hash: reported after commit because a commit cannot contain its own hash.
- Push: no.
