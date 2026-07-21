# Multi-tenant Custody

[中文版本](../zh/multi-tenant-custody.md)

Surprising Wallet is a custody infrastructure service. It does not own a
tenant's customer model, username, merchant, order, or internal accounting
rules.

## Isolation model

Every externally reachable custody query is scoped by the authenticated
credential:

| Credential | Scope source |
|---|---|
| Platform Console session | Platform administrator; no tenant data-plane access |
| Tenant Console session | `tenant_id` stored with the tenant user and session |
| API key | `tenant_id` stored with the encrypted API credential |

Clients do not submit a trusted `tenantId`. The service derives it from the
credential and includes it in every address, transfer, event, delivery, and
audit query.

## Tenant-side users and address allocation

To allocate a new deposit address, the tenant submits the chain code as
`chainId` and its account identifier as `subject`. The service selects the network from the chain's one enabled
`chain_profile`:

```http
POST /custody/api/v1/addresses
X-Custody-Key: swk_...
X-Custody-Timestamp: 1784486400
X-Custody-Nonce: 2FSvJwQp1QdwLk2B
X-Custody-Signature: ...
Content-Type: application/json

{
  "chainId": "ETH",
  "subject": "user_10086",
  "addressVersion": 0
}
```

`chainId` is a chain code such as `ETH`, `BTC`, or `SOLANA`, not an EVM numeric
chain ID. `subject` is a stable tenant-defined user, merchant, or system-account
identifier. `addressVersion` is managed by the tenant and defaults to `0`.
`(tenant, chain, subject, addressVersion)` is the business idempotency key, so
repeated requests return the existing address without an extra idempotency
header. Increment the version to `1`, `2`, and so on when the user needs a new
address. Older versions remain monitored, and late deposits are still credited
to the same `subject`. Because all EVM chains use `coinType=60` and the same
derivation coordinates, the same tenant, subject, and version receive the same
address on every EVM chain. The caller should store the returned address ID,
address, and version.

The Console can create an address without using the tenant API. Console users
can also change its label, metadata, and active/disabled state. Disabled
addresses remain monitored and remain part of tenant asset totals so late
deposits and existing funds never disappear.

### Tenant address derivation

Each tenant receives a unique `tenantNamespace` from
`custody_derivation_namespace_seq`. The service maps `(tenantId, subject)` to a
stable internal `derivationSubject` allocated from 1. The same tenant subject
always uses that derivation number, while different tenants never share one.
Secp256k1 and Bitcoin-like addresses use this non-hardened, BIP44-shaped custom
path:

```text
m / 44 / coinType / tenantNamespace / derivationSubject / childIndex
```

Ed25519 addresses use a hardened SLIP-0010 path:

```text
m / 44' / coinType' / tenantNamespace' / derivationSubject' / childIndex'
```

EVM chains always use `coinType=60`; other secp256k1 and Bitcoin-like chains
use `chain_profile.bip44_coin_type`; Ed25519 chains use their fixed SLIP-0044
coin type. An ordinary user's `addressVersion` maps to the internal derivation
`childIndex`; it is not a wallet-fixed slot. Only one address row is allowed for
a tenant, chain, subject, and version. A database transaction lock plus a unique
constraint makes concurrent requests return one stable address. `childIndex` is
internal derivation data and is not exposed by the tenant address API. The `__sw_` prefix
is reserved for wallet-managed system accounts.

For every enabled tenant chain, Tenant chains can generate one
wallet-managed collection address. It uses the reserved derivation subject and
is fixed at `childIndex=1`. All EVM chains for one tenant share the
`__sw_collection__:evm` derivation subject and therefore the exact same address;
non-EVM chains retain chain-specific subjects. Repeated generation for the same
tenant and chain returns the same address. Confirmed native coins at this address also fund that
chain's network-fee operations. Tenant chains displays and copies this address;
there is no separate Gas-pool page.

A tenant can see tokens configured by a platform administrator, but can only
enable tokens currently supported by the platform. Each token has three tenant
switches: enabled, deposits, and withdrawals. When platform support is turned
off, the token and its assets remain visible while deposits and withdrawals stop
immediately. A new enable attempt returns a clear platform-unavailable message.

Monero does not use BIP44. Its wallet RPC creates a subaddress recorded as
`monero-wallet-rpc:m/0/{subaddressIndex}`.

Address creation does not produce a Webhook. A confirmed deposit does:

```json
{
  "id": "5f2034d1-39f0-4c07-83b0-e0d8dfb8ed48",
  "type": "DEPOSIT.CONFIRMED",
  "createdAt": "2026-07-20T01:00:00Z",
  "data": {
    "depositId": "13d45f9e-d8a1-4fae-a591-75b62dad5df4",
    "subject": "user_10086",
    "chain": "ETH",
    "asset": "USDT",
    "address": "0x...",
    "amount": 100.000000,
    "txHash": "0x...",
    "logIndex": 0,
    "confirmations": 20
  }
}
```

The tenant consumes the event, maps `subject` to its customer, and credits its ledger.

## Asset truth

`ledger_balance` is the balance source of truth. The overview asset details list
native coins for opened chains and tenant-enabled tokens, including zero balances.
Native coins and tokens are peers. A token enabled on multiple chains is shown as
one aggregate row that expands into per-chain balances. Values aggregate all
related account IDs by `chain + asset_symbol`.

The overview returns:

- `availableBalance`;
- `lockedBalance`;
- `totalBalance`;
- distinct custody address count.

Deposits are credited once using chain transaction uniqueness. Withdrawal
creation freezes the selected address account atomically in the existing
wallet ledger. The custody withdrawal record follows the underlying order
through signing, broadcast, confirmation, or failure. A withdrawal destination
is a validated external chain address; it does not need to be an address
previously allocated by this wallet service. Chains that require prepared token
accounts keep their adapter-specific preparation checks.

## API authentication

API keys can call every tenant API operation without selecting scopes. Their secrets are displayed once. The secret
is encrypted at rest with `SW_CUSTODY_SECRET_MASTER_KEY`.

Canonical request:

```text
timestampSeconds + "\n" +
nonce + "\n" +
upper(method) + "\n" +
requestPathAndRawQuery + "\n" +
hexLowercase(SHA-256(rawBodyBytes))
```

`X-Custody-Signature` is Base64URL without padding:

```text
HMAC-SHA256(apiSecret, canonicalRequest)
```

The server enforces the timestamp window and permanently rejects a repeated
nonce during that window. Address and withdrawal creates additionally require
an `Idempotency-Key`. Withdrawal idempotency is permanent; reusing its key with
a different request is rejected. Withdrawal requests must also send
`"confirmed": true`; the Console collects that confirmation in a separate
review step. Validation and downstream business failures use the same JSON
error envelope with an actionable message; request-signing failures never echo
credentials or signature material.

When a tenant enables the IP allowlist, every API request must originate from
an enabled IPv4/IPv6 CIDR rule. The application intentionally uses the direct
HTTP peer address. A reverse proxy must therefore preserve a trustworthy peer
boundary; never accept arbitrary client-supplied forwarding headers without a
trusted-proxy configuration.

## Webhooks

Webhook event selection is not configurable. Every event below that belongs to
an API-created custody address is delivered automatically to every verified and
active endpoint for the tenant. Events for Console-created addresses remain in
the internal audit trail and are not sent to tenant business systems:

- `DEPOSIT.CONFIRMED`
- `WITHDRAWAL.CREATED`
- `WITHDRAWAL.BROADCAST`
- `WITHDRAWAL.BROADCAST_UNKNOWN`
- `WITHDRAWAL.CONFIRMED`
- `WITHDRAWAL.FAILED`

Each endpoint is verified before activation. The service sends
`WEBHOOK.VERIFICATION` with `data.challenge`; the endpoint must return:

```json
{ "challenge": "<same value>" }
```

Every delivery includes:

```text
X-Custody-Event-Id
X-Custody-Event-Type
X-Custody-Timestamp
X-Custody-Signature: v1=<base64url-hmac>
```

The signature input is:

```text
timestamp + "." + exactRawRequestBody
```

Persist the event ID before processing, verify the signature over the raw body,
and reject stale timestamps. Delivery rows are created in the same database
transaction as the custody event. Workers claim rows with `SKIP LOCKED`,
retry with exponential delay, cap response/error storage, and expose failed
deliveries for manual retry.

Automatic retries start after 30 seconds, use deterministic jittered
exponential backoff, honor a valid `Retry-After`, and stop after 10 attempts.
The maximum automatic delay is six hours. A manual retry starts a new cycle
without deleting earlier attempts. Console delivery details show the trigger,
HTTP result, duration, error, next attempt, retry cycle, and lifetime attempt
number. Expired worker leases are recovered as explicit history entries, and
attempt fencing prevents a late stale worker from overwriting the newer result.

## Tenant activation and network-fee address

The Console onboarding checklist is complete only after the tenant has:

1. created an API key and copied its one-time secret;
2. enabled an IP allowlist with at least one CIDR;
3. created and verified a Webhook endpoint;
4. generated the collection/network-fee address in Tenant chains;
5. funded that account with confirmed native coin;
6. allocated at least one customer address.

A collection/network-fee account is a dedicated tenant address for a network's native
coin. Confirmed funding is collected through the normal chain workflow and
becomes an auditable prepaid network-fee balance. It does not appear in
customer addresses, customer deposits, or customer asset totals.

When a custody withdrawal is accepted, the service atomically freezes both the
requested customer amount and a conservative amount from the matching Gas
station account. The customer address is not charged a second network-fee
reserve. Failed, rejected, or cancelled withdrawals release the Gas reserve.
Confirmed withdrawals settle it against the chain-recorded fee where the
adapter exposes one; otherwise the configured conservative reserve is charged.
Every reservation, release, settlement, and transaction hash remains visible
in Gas station usage history.

If an actual fee exceeds the funded amount, the usage becomes `OVERDUE` and new
withdrawals for that network stop. After a confirmed top-up, reconciliation
settles the overdue charge automatically and processing resumes.

## Console capabilities

Platform administrators:

- create isolated tenants and the first tenant administrator;
- search and page tenants by name, slug, and status;
- open a tenant operations view containing settings, onboarding readiness,
  administrators, customer assets and addresses, Gas reserves, API keys, IP
  rules, Webhooks, recent transfers, and audit activity;
- update the tenant name and reporting currency while keeping the slug and
  derivation namespace immutable;
- clear a temporary tenant-administrator login lock without reading or
  replacing password material;
- suspend/reactivate a tenant. Suspension immediately blocks signed API access
  and revokes all active tenant Console sessions; reactivation does not restore
  those revoked sessions.

Tenant administrators:

- view aggregate assets;
- follow the onboarding checklist and fund per-network Gas station accounts;
- create, search, label, disable, and reactivate addresses;
- query deposit and withdrawal records;
- create a Console withdrawal;
- create/revoke full-access API keys;
- enable/configure an IP allowlist;
- create, verify, disable, and reactivate Webhook endpoints;
- inspect complete Webhook attempt history and manually retry failed deliveries;
- inspect the tenant audit log.

## Production requirements

- Generate independent production BIP32/Ed25519/signing material. Never reuse
  the test seeds.
- Store `SW_CUSTODY_SECRET_MASTER_KEY`, signer roots, database credentials, RPC
  credentials, and administrator passwords in a secrets system.
- Run behind TLS and restrict custody/Console CORS to the deployed Console
  origin.
- Keep wallet/signing networks private. Permit Webhook egress through an
  allowlisted egress proxy or firewall. Application DNS checks reduce SSRF risk
  but cannot replace network-level egress control.
- Back up PostgreSQL with point-in-time recovery. Custody state is durable in
  PostgreSQL; Redis is runtime coordination/cache, not the custody source of
  truth.
- Monitor Webhook backlog, failed delivery count, withdrawal reconciliation
  lag, scanner lag, ledger exceptions, RPC health, database saturation, and
  signer availability.
