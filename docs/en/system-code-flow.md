# Runtime Code Flow

[中文版本](../zh/system-code-flow.md)

![System code flow](../assets/system-code-flow-diagram.svg)

## Multi-Tenant Custody Flow

The custody control plane keeps a tenant exchange's user model outside this repository. A tenant sends `chainId` and its own `subject`; the service selects the enabled network and allocates the next address under that subject.

```text
tenant backend
  -> HMAC-authenticated POST /custody/api/v1/addresses
  -> create a fresh custody_address on every call
  -> allocate the subject's next chain_address child
  -> return address ID, chain, selected network, subject, childIndex, and address

chain scanner
  -> deposit_record + ledger_balance credit in one transaction
  -> custody_deposit projection
  -> durable custody_event
  -> per-endpoint webhook_delivery
  -> signed DEPOSIT.CONFIRMED webhook with subject and the deposit address
```

Console users may attach labels and metadata when creating addresses; the public API does not accept those management fields. Addresses remain visible in tenant asset totals. Address creation itself does not produce a webhook. Tenant isolation is applied to every Console/API query and mutation.

Withdrawal requests use a permanent idempotency key, pass through the existing ledger lock/broadcast/confirmation workflow, and produce signed lifecycle Webhooks from durable delivery rows.

## Asset Resolution

Runtime code should resolve assets through DB metadata:

```text
request chain/symbol/contract
  -> chain_profile
  -> chain_asset
  -> token_config when token asset
  -> BlockchainRuntimeService/BlockchainAdapter input
```

Business routing should use `chain + asset` lookup. Numeric runtime currency ids should remain an internal DB compatibility mapping, not a public API input.

## Deposit Flow

```text
chain RPC/indexer
  -> chain scanner
  -> chain_address match
  -> deposit_record insert or idempotent skip
  -> ledger_balance credit
  -> notification/API layer
```

Idempotency is enforced by transaction identity and chain/address/asset constraints. Scanner replay must not double-credit `ledger_balance`.

## Withdrawal Flow

```text
external withdrawal request
  -> asset lookup from chain_profile/chain_asset/token_config
  -> ledger lock
  -> chain transaction builder
  -> signer service or local Ed25519 signer
  -> broadcast
  -> confirm
  -> ledger finalize
```

Retrying a withdrawal order should return or reuse the existing transaction state instead of rebroadcasting a duplicate transaction.

## Collection Flow

```text
collect job
  -> find eligible chain_address balances
  -> asset policy from token_config
  -> build transfer to hot wallet
  -> sign/broadcast
  -> confirm collection
  -> ledger update
```

Token collection uses token-specific policy and native gas policy from the chain service.

## Chain-Specific Notes

Bitcoin-like chains:

- Use UTXO records with `AVAILABLE`, `LOCKED`, and `SPENT` states.
- Local regtest covers BTC/LTC/DOGE/BCH.
- Broadcast/concurrency tests are driven by `scripts/regtest/all-chain-regtest.sh test-utxo`.

EVM chains:

- Share an EVM engine with chain profile differences.
- ERC20 token behavior comes from `token_config`.
- Fork tests run one chain at a time on `127.0.0.1:8545`.

TRON:

- Uses the TRON resource model.
- TRC20 behavior is separate from EVM even when token concepts look similar.

SOL/TON/APTOS/SUI:

- Use Ed25519 key derivation.
- DB tests cover deterministic scanner/ledger/transaction behavior.
- Live tests depend on external devnet/testnet RPC, faucet limits, and funded addresses.
