# ADA, DOT, NEAR, XMR Integration Notes

This document records the production boundary for adding Cardano, Polkadot,
NEAR and Monero to `surprising-wallet`.

## Current State

- Chain identifiers are reserved in `ChainType`: `ADA`, `DOT`, `NEAR`, `XMR`.
- `ADA`, `DOT` and `NEAR` deterministic address derivation is implemented from
  the existing wallet Ed25519 master seed, and the wallet app can expose them
  when the environment enables both `chain_profile` and `chain_asset`.
- Token withdrawal targets are stricter than native withdrawal targets.
  EVM/TRON/ADA/DOT token rows can be materialized from an existing project
  address because the token address is the same account and needs no
  recipient-side preparation. XRP and NEAR token rows must already exist through
  the token deposit page, because the wallet prepares XRPL trustlines and NEAR
  token storage there. Solana, TON, Aptos and Sui token targets also require
  token-specific deposit rows before withdrawal.
- `XMR` is integrated through `monero-wallet-rpc`: user deposit addresses are
  wallet subaddresses, deposit scanning uses wallet-rpc transfers, and native
  withdrawal/collection uses wallet-rpc transfers.
- Database initialization contains disabled native assets and disabled chain
  profiles for `ADA`, `DOT` and `NEAR`, plus disabled `USDC`/`USDT` token
  templates for their token models. They must stay disabled until scan,
  withdrawal, collection and token support have passed real end-to-end tests in
  that environment. After that, exposure is controlled by data only: replace
  template token identifiers with real values, enable the target
  `chain_profile`, target `chain_asset` rows, matching `token_config` rows, and
  required `chain_rpc_node` rows.
- `XMR` has pre-seeded `regtest`, `stagenet` and `mainnet` profiles. All stay
  disabled by default; enable only the environment that has a managed
  `monero-wallet-rpc` wallet and backup plan.
- No new public/testnet chain is exposed to wallet users by default.

## Chain Requirements

### Cardano ADA

- Native coin: ADA, 6 decimals.
- Token model: Cardano native assets, identified by policy id plus asset name,
  not by an EVM-style contract address.
- Required adapter work:
  - Cardano-compatible key derivation and Shelley address generation.
  - UTXO scan with multi-asset output decoding.
  - Transaction build/sign/submit for ADA and native assets.
  - Collection that preserves enough ADA for min-ADA output and fees.
- Implemented adapter work:
  - Deterministic Shelley enterprise address generation and validation.
  - User deposit address persistence through the wallet app native-address path.
  - Blockfrost-compatible backend integration is implemented through
    `chain_rpc_node` failover. Store the project id in `chain_rpc_node.api_key`
    and use a `BLOCKFROST`/HTTP node URL such as the preprod API base URL.
  - Address-history deposit scanning is implemented. The scanner reads address
    transactions, fetches transaction UTXO outputs, and credits native ADA plus
    configured native assets idempotently by `tx_hash + output_index +
    asset_index`.
  - Native ADA and configured native-asset withdrawal/collection use
    `cardano-client-lib` transaction building and the project Ed25519 seed for
    signing.
  - Cardano token `token_config.contract_address` must be stored as
    `policyId.assetNameHex`. The scanner also accepts Blockfrost's concatenated
    `policyId + assetNameHex` unit internally.
- Token support: required. Cardano native assets are not optional once ADA is
  exposed in this wallet, because user balances and withdrawals must understand
  multi-asset UTXO outputs.
- Remaining adapter work:
  - Add a real preprod Blockfrost-compatible node/key in the target
    environment and run end-to-end deposit, withdrawal and collection. Keep
    `chain_profile.enabled=false` until this is done.
- Suggested Java library: `com.bloxbean.cardano:cardano-client-lib`.
- Suggested test network: preprod.

### Polkadot DOT

- Native coin: DOT, 10 decimals.
- Token model: relay-chain DOT is separate from Asset Hub assets. Token support
  should be implemented against Asset Hub only after the target asset and test
  network are selected.
- Implemented adapter work:
  - SS58 address generation is implemented for Ed25519 accounts.
  - User deposit address persistence is implemented.
  - Java wallet runtime integration is implemented through a metadata-aware
    Polkadot runtime service. The Java process calls `chain_rpc_node` rows with
    `purpose=runtime`; that service uses the official `@polkadot/api` stack to
    read runtime metadata, scan finalized transfer events, construct
    `balances.transferKeepAlive` transactions, sign Ed25519 transactions,
    submit them, and wait for finality.
  - Native DOT deposit scanning is implemented by scanning finalized
    `balances.Transfer` events from the runtime service and crediting wallet
    ledger balances idempotently by transaction hash and event index.
  - Native DOT withdrawal and collection are wired into the account-chain
    workflow through the runtime service.
  - Basic Asset Hub `assets.Transferred` scanning and `assets.transfer` /
    `assets.transferKeepAlive` submission support are implemented behind
    `token_config.contract_address = <assetId>`. This uses a separate
    `chain_rpc_node` purpose named `asset_rpc`, because relay-chain DOT and
    Asset Hub tokens are different runtimes with different block heights. The
    scanner stores native progress in `polkadot-runtime-scanner` and Asset Hub
    progress in `polkadot-assethub-scanner`.
  - Wallet-app DOT token deposit addresses mirror the native DOT SS58 account
    row. A token asset still needs its own `chain_address` row for the token
    symbol, but the visible address and derivation index stay the same as the
    native DOT row so the Asset Hub scanner can credit the token ledger account.
  - The runtime service can be run directly with Node.js or through
    `services/polkadot-runtime-service/docker-compose.yml`. It binds locally by
    default and must be protected with `POLKADOT_RUNTIME_API_KEY`. Hosts
    without Docker Compose can use
    `scripts/regtest/polkadot-runtime-service.sh up`.
  - In split-host test environments, bind the runtime service to the Linux
    private IP, open only private-network access to port `8787`, and set the
    `purpose=runtime` row to that private URL. A localhost SSH tunnel is
    acceptable for short verification runs, but it is not the final stable
    deployment model.
  - Westend relay-chain tests use `wss://westend-rpc.polkadot.io`. Westend
    Asset Hub token tests use `wss://westend-asset-hub-rpc.polkadot.io`.
  - Keep token configs disabled until a target Asset Hub test asset is selected
    and verified.
- Remaining adapter work:
  - Run the runtime service in `services/polkadot-runtime-service`, configure
    `purpose=rpc`, `purpose=asset_rpc` and `purpose=runtime` nodes for the
    target environment, then perform Westend end-to-end deposit, withdrawal and
    collection tests.
  - Select an Asset Hub test asset before enabling DOT token assets.
- Important implementation boundary: the legacy Java PolkaJ library is not a
  safe production dependency for modern Polkadot runtimes because it does not
  support current metadata versions. This project uses a metadata-aware runtime
  service instead of hard-coding SCALE call indexes in Java.
- Token support: implement through Asset Hub after the target test asset is
  selected. Relay-chain DOT must not pretend to support tokens by itself.
- Suggested test network: Westend for relay-chain behavior; Asset Hub testnet
  for token behavior.

### NEAR

- Native coin: NEAR, 24 decimals.
- Token model: NEP-141 fungible token contracts.
- Address model: implicit accounts are 64-character lowercase hex public keys.
- Implemented adapter work:
  - Address generation is implemented.
  - User deposit address persistence is implemented.
  - Native NEAR deposit scanning is implemented through official JSON-RPC
    finalized `block` and `chunk` reads.
  - NEP-141 token deposit scanning is implemented for successful `ft_transfer`
    function-call actions into project deposit addresses. Token contracts are
    configured through `token_config.contract_address`.
  - Native and NEP-141 scans record action-level `near_transaction` rows, write
    idempotent `deposit_record` rows, and credit `ledger_balance`.
  - Native NEAR Borsh transaction serialization, Ed25519 signing,
    `broadcast_tx_commit` submission, and confirmation polling are implemented.
  - NEP-141 `ft_transfer`, `storage_deposit`, `storage_balance_of`, and
    `storage_balance_bounds` support is implemented at the transaction-service
    layer.
  - NEAR token deposit-address creation mirrors the native NEAR account and
    prepares the account automatically through the default hot NEAR wallet. If
    the implicit account does not exist, the hot wallet first sends the
    configured `near.token.account.activation.yocto` native amount, then submits
    token-contract `storage_deposit` when the token storage row is missing. The
    address response includes a `nearPreparation` object with activation,
    storage readiness and related transaction hashes.
  - Withdrawal/collection signing paths are wired into the account-chain
    workflow for native NEAR and NEP-141 tokens. User-facing operations remain
    disabled by default through `chain_profile.enabled=false` and
    `chain_asset.active=false` until real testnet end-to-end verification is
    complete.
- Remaining adapter work:
  - Real testnet token contract configuration and end-to-end verification for
    deposit, withdrawal and collection.
- Token support: required for NEP-141 tokens. Deposits need event/indexer
  coverage; scanning only native account balance changes is not enough.
  Current chunk scanning handles direct `ft_transfer` calls. If a token relies
  on cross-contract minting or custom transfer wrappers, add NEAR Lake/Indexer
  event ingestion before enabling that asset.
- Important runtime rule: a token-only NEAR deposit address must still be a
  live NEAR account before it can later sign withdrawals. Do not disable the
  activation step unless all token addresses are pre-funded through another
  controlled process.
- Suggested test network: NEAR testnet.

### Monero XMR

- Native coin: XMR, 12 decimals.
- Token model: none.
- Monero is a privacy chain. Public RPC alone cannot scan arbitrary wallet
  deposits. The wallet service must integrate with `monero-wallet-rpc`.
- Implemented adapter work:
  - Dedicated wallet-rpc client and RPC failover through `chain_rpc_node`.
  - Subaddress creation per user/address index.
  - Deposit scan with wallet-rpc `refresh`, `get_height` and `get_transfers`.
  - Withdrawal and collection with wallet-rpc `transfer`.
  - Confirmation checks with wallet-rpc `get_transfer_by_txid`.
  - A dedicated XMR account-chain workflow runs independently of the general
    account-chain scanner. This keeps local/regtest XMR deposits, withdrawals
    and collections moving even when external testnet RPC providers for other
    chains are slow or rate-limited.
  - Non-production wallet faucet endpoint:
    `POST /wallet/v1/app/test-faucet/xmr`. It sends from the configured
    `purpose=faucet` wallet-rpc, mines enough confirmation blocks through the
    `purpose=daemon` node for the transfer to be spendable, refreshes both
    wallets, and leaves the balance update to the normal scanner.
  - XMR withdrawal orders reserve an estimated network fee in
    `withdrawal_order.fee`. The workflow freezes and settles
    `amount + fee`, while the chain transfer amount remains the user-entered
    withdrawal amount.
  - Project-internal withdrawal crediting: if a confirmed XMR withdrawal target
    is another project `chain_address`, the confirmation step writes an
    idempotent `deposit_record` and credits the recipient ledger. This is needed
    because transfers between subaddresses in the same Monero wallet are not a
    reliable substitute for external incoming-transfer scanning.
  - Wallet-app regtest verification has covered the user-facing flow:
    register/login, create deposit address, faucet deposit, scanner credit,
    user-to-user transfer, project-internal withdrawal, withdrawal confirmation
    and collection confirmation.
- Local test network:
  - `scripts/regtest/monero-regtest.sh init` starts `monerod --regtest`
    and the project `monero-wallet-rpc` on `127.0.0.1:18088`. The wallet-rpc
    process uses `--allow-mismatched-daemon-version`; without it, Monero 0.18.5
    regtest refresh can fail with an unexpected hard-fork-version error.
  - The script also starts a separate funder `monero-wallet-rpc` on
    `127.0.0.1:18090`. Regtest coinbase mining only supports primary wallet
    addresses, so test deposits are produced by mining funds to the funder
    wallet and transferring from that wallet to a project user subaddress.
  - `scripts/regtest/monero-regtest.sh fund <address> [amount_xmr]` sends XMR
    from the funder wallet to the target project address, mines confirmation
    blocks, and refreshes both wallets.
  - `scripts/regtest/monero-regtest.sh self-test` resets nothing by itself, but
    verifies the full local loop: ensure wallets, mine funder balance, create a
    project subaddress, transfer to it, mine confirmations, and assert that
    project wallet-rpc returns an incoming transfer for that subaddress.
  - `scripts/regtest/all-chain-regtest.sh init|status|stop|reset` now includes
    XMR alongside BTC/LTC/DOGE/BCH.
  - `scripts/regtest/all-chain-regtest.sh test-xmr` starts XMR regtest and runs
    `MoneroRegtestFullFlowIntegrationTest`, covering real deposit scan/credit,
    internal withdrawal confirmation, scanner idempotency for the same tx, and
    collection confirmation.
  - When Docker requires sudo, run the XMR scripts with
    `DOCKER_USE_SUDO=true`; the script will prefix Docker commands
    consistently.
  - Default Docker images are `ghcr.io/sethforprivacy/simple-monerod:latest`
    and `ghcr.io/sethforprivacy/simple-monero-wallet-rpc:latest`. Override
    them with `MONERO_REGTEST_DAEMON_IMAGE` and `MONERO_REGTEST_WALLET_IMAGE`
    when running a pinned or internally mirrored image.
  - For cross-machine `test2`, keep wallet-rpc bound to a private interface and
    set `MONERO_REGTEST_BIND_HOST=<private-ip>`,
    `MONERO_REGTEST_CLIENT_HOST=<private-ip>` and
    `MONERO_REGTEST_RPC_LOGIN=user:password` when running the script. The Java
    wallet-rpc client supports Monero Digest authentication, so store the same
    username/password on the `chain_rpc_node` row instead of exposing an
    unauthenticated RPC port.
  - The wallet app faucet button is safe only when the active `XMR` profile is
    `regtest`; the endpoint rejects production environments and non-regtest
    XMR profiles.
- Production/stagenet requirements before enabling:
  - Run one managed `monero-wallet-rpc` wallet per environment.
  - Back up the wallet seed, wallet cache files and password together.
  - Set the wallet restore height before first scan; do not restore from
    genesis unless explicitly required.
  - Keep `chain_profile.scan_start_height` near the deployment height for a new
    system to avoid unnecessary RPC and wallet refresh cost.
- Official reference points:
  - Monero daemon RPC documents JSON-RPC and `generateblocks`; the regtest
    example uses `--regtest --offline --fixed-difficulty 1`.
  - Monero wallet-rpc documents `create_address`, `get_transfers`,
    `get_transfer_by_txid`, and `transfer`.

## Safety Rule

Do not set `chain_profile.enabled = true` for any of these chains until the
chain has passed:

1. Address creation from the configured production/test seed.
2. Real testnet deposit credit.
3. User-to-user transfer.
4. Withdrawal signing and confirmation.
5. Collection to hot wallet.
6. Token flow, if the chain supports tokens and a test token is configured.

For wallet-app exposure, `chain_asset.active` must also remain `false` until
the same checklist has passed. Avoid reintroducing chain-name hard blocks in
application code; the deployment state should be controlled by the database so
dev, test2 and prod can be isolated cleanly.
