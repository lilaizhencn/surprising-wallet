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

The tenant creates and owns its user. To obtain that user's ETH address:

```http
POST /custody/api/v1/addresses
Idempotency-Key: address-user-10086-eth
X-Custody-Key: swk_...
X-Custody-Timestamp: 1784486400
X-Custody-Nonce: 2FSvJwQp1QdwLk2B
X-Custody-Signature: ...
Content-Type: application/json

{
  "chain": "ETH",
  "externalReference": "user_10086",
  "label": "Primary deposit address",
  "metadata": {
    "customerTier": "business"
  }
}
```

`externalReference` is opaque. It may be a customer ID, merchant ID, account
ID, or other tenant-owned allocation key.

The permanent allocation key is:

```text
tenant_id + chain + external_reference
```

Concurrent and repeated requests for that key return the same address. A
different tenant can use the same reference without sharing data. Omitting the
reference allocates a fresh address on every successful request.

The Console can create an address without using the tenant API. Console users
can also change its label, metadata, and active/disabled state. Disabled
addresses remain monitored and remain part of tenant asset totals so late
deposits and existing funds never disappear.

Address creation does not produce a Webhook. A confirmed deposit does:

```json
{
  "id": "5f2034d1-39f0-4c07-83b0-e0d8dfb8ed48",
  "type": "DEPOSIT.CONFIRMED",
  "createdAt": "2026-07-20T01:00:00Z",
  "data": {
    "depositId": "13d45f9e-d8a1-4fae-a591-75b62dad5df4",
    "externalReference": "user_10086",
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

The tenant consumes the event and credits its own customer ledger.

## Asset truth

`ledger_balance` is the balance source of truth. Tenant asset overview joins
all account IDs derived for every custody address, including token accounts and
disabled addresses, then aggregates by `chain + asset_symbol`.

The overview returns:

- `availableBalance`;
- `lockedBalance`;
- `totalBalance`;
- distinct custody address count.

Deposits are credited once using chain transaction uniqueness. Withdrawal
creation freezes the selected address account atomically in the existing
wallet ledger. The custody withdrawal record follows the underlying order
through signing, broadcast, confirmation, or failure.

## API authentication

API keys have explicit scopes and their secrets are displayed once. The secret
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
a different request is rejected.

When a tenant enables the IP allowlist, every API request must originate from
an enabled IPv4/IPv6 CIDR rule. The application intentionally uses the direct
HTTP peer address. A reverse proxy must therefore preserve a trustworthy peer
boundary; never accept arbitrary client-supplied forwarding headers without a
trusted-proxy configuration.

## Webhooks

Supported events:

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

## Console capabilities

Platform administrators:

- create isolated tenants and the first tenant administrator;
- list tenant activity counts;
- suspend/reactivate a tenant.

Tenant administrators:

- view aggregate assets;
- create, search, label, disable, and reactivate addresses;
- query deposit and withdrawal records;
- create a Console withdrawal;
- create/revoke API keys and choose scopes;
- enable/configure an IP allowlist;
- create, verify, disable, and reactivate Webhook endpoints;
- inspect and retry Webhook deliveries;
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
