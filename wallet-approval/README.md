# Independent withdrawal approval authority

`wallet-approval` is a separate Spring Boot process. It does not load Wallet application beans, reuse Wallet login sessions or hold any MPC share. Its independently managed runtime database role accesses only the `approval` schema; a separate NOLOGIN maintenance role owns the schema. Wallet's runtime role must have no privileges on this schema, and must not own the database, role, schema, deployment identity or authority signing key.

The canonical empty-database baseline remains `resources/docs/db/surprising-wallet-init-pgsql.sql`. Apply it only to a new database under an installation identity; the authority runtime role receives access to `approval` tables only. Wallet business runtime roles receive access to their own business tables only. Prefer separate databases in the same PostgreSQL 18 instance and separate operating-system/deployment identities. Never run the baseline against a funded existing database. Tests use only a uniquely named temporary database on the developer's existing `127.0.0.1:5432` PostgreSQL 18.

## Trusted initial enrollment

Create the Wallet tenant/subaccount first, and retain its public tenant ID, member ID and tenant slug. On the independently controlled approval host, prepare a local owner-only (`0600`) JSON file with these fields:

```json
{
  "tenantId": "TENANT_UUID",
  "tenantSlug": "tenant-slug",
  "memberId": "WALLET_TENANT_ADMIN_OR_APPROVER_UUID",
  "email": "reviewer@example.test",
  "displayName": "Reviewer",
  "role": "APPROVER",
  "password": "REPLACE_WITH_A_SEPARATE_APPROVAL_PASSWORD"
}
```

Use `TENANT_ADMIN` for the independently authenticated administrator who may edit approval rules; use `APPROVER` for reviewers. Do not reuse Wallet passwords. Enrollment does not overwrite existing identities or reset their credentials. Both this file and database installation credentials remain outside Wallet application access. Delete the enrollment file using the host's secure operational procedure after enrollment; never commit it or put its password in shell arguments/logs.

Build with `mvn -pl wallet-approval -am package`, then run with `APPROVAL_DB_URL`, `APPROVAL_DB_USERNAME`, `APPROVAL_DB_PASSWORD` set in the host's secret environment:

```sh
java -jar wallet-approval/target/wallet-approval-1.0.0-SNAPSHOT.jar enroll /secure/member.json
```

This command enrolls the identity and mandatory audit in one transaction and exits without starting HTTP. There is no Wallet-admin or service-client endpoint that can enroll an independent reviewer. A Wallet role or password reset does not create or reset an independent approval credential; Wallet subaccount disable/removal of APPROVER durably sends a one-way revocation to the independent identity domain. Wallet cannot enroll, reset or enable credentials. The independent administrator may invite reviewers and issue single-use activation links, revoke or reset their credentials through the independent portal. These routes never accept Wallet or service-client authentication; administrators themselves remain managed through the trusted host.

## HTTPS runtime

Start the same JAR without `enroll`. Required settings are:

- `APPROVAL_DB_URL`, `APPROVAL_DB_USERNAME`, `APPROVAL_DB_PASSWORD` for the restricted authority role.
- `APPROVAL_PUBLIC_ORIGIN`, an exact HTTPS origin without a path.
- `APPROVAL_TLS_KEYSTORE`, `APPROVAL_TLS_KEYSTORE_PASSWORD`, `APPROVAL_TLS_TRUSTSTORE`, `APPROVAL_TLS_TRUSTSTORE_PASSWORD`; stores are PKCS12. TLS 1.3 is required. Client certificates are optional for browser routes and mandatory for service routes.
- Independent local `application.yaml` mapping `approval.service-clients` from each allowed Wallet client certificate SHA-256 SPKI to its explicitly enrolled tenant UUIDs. Empty configuration denies all service requests. Do not trust caller-provided certificate headers.

Host the approval frontend and `/approval/v1/*` under the configured independent origin. Keep its static deployment access separate from Wallet Java. The browser receives only a host-only secure HttpOnly SameSite=Strict approval cookie and a session CSRF token. Mutations require exact Origin and CSRF checks. Authentication uses separate PBKDF2 credentials, persistent failure throttling, current member/tenant state and mandatory audits.

## Rules and decisions

Policies are configured per tenant/chain/network/asset by the independent administrator. New policies default to reviewing every withdrawal; the administrator must explicitly choose whether to retain this preset. Additional available scenarios are single amount, rolling 24-hour amount, new recipient and console withdrawal; one or two distinct reviewers are supported. Threshold comparisons include equality. Admission serializes on the independent tenant row and includes pending requests in the rolling sum. Missing enrollment/policy fails closed. The stable intent includes amount, recipient, chain/network and a network-fee ceiling. Editing a policy does not silently change previously accepted intents.

Review decisions require the exact displayed intent hash, reject console self-approval and cannot be rewritten. Identical retries are idempotent. Decision, state change and audit commit atomically. The expiry job has its own scheduler and expires only requests that have not entered signing. Actual Wallet fund release must still check the original signing/broadcast state; approval expiry alone never proves funds are safe to release.

## Implementation and validation scope

All supported-chain verifiers, bounded automated-operation rules, original Monero relay authorization, no-grant termination and one-way Wallet revocation propagation are implemented. Validation distinguishes canonical transaction policy tests, real PostgreSQL/HTTPS/native integration and actual chain broadcast. See the validation record below; unit or simulated-node results never imply a funded live-network transfer. Deployment requires independently installed assets, read-only nodes, operating permissions, fees and identities for each enabled network. No production deployment has been performed.

Authorized expired, partial or unknown transactions retain their original immutable binding and frozen funds until their canonical outcome is reconciled. No generic replacement payment or automatic refund is authorized by a missing result. Exact original-session recovery is supported; fresh-session recovery additionally requires the explicit verifier's nonce/input exclusivity. Bounded BTC RBF is a separate installed permission.

## Reproduce verification

Run `resources/scripts/regtest/run-approval-db-tests.sh` to create two isolated temporary databases in the existing local PostgreSQL 18 instance, run tenant member/withdrawal projection/independent policy/authentication/concurrency/crypto tests, package both JARs and remove those databases. Run `resources/scripts/regtest/run-approval-https.sh` after packaging to exercise actual HTTPS and mTLS identities with the executable authority. No database server/container is started by either script.

The ordinary EVM wire verifier checks all EVM `ChainType` families for native/ERC-20 payment semantics and independent fee bounds. The Aptos verifier accepts only classic sequence-number legacy Ed25519 native APT or registered primary fungible-asset transfers. EIP-7702 and other non-EVM families require their own independent verifiers.

## Independent native key registry

Configure `approval.native-nodes` with the exact ordered three holder `partyId`/HTTPS endpoints, CA path, authority-only transport certificate/private-key paths and call timeout. The approval transport identity must be distinct from Wallet Java. Add that identity to each holder's client map for explicit tenants with `READ_RESULT` only; it does not need `SIGN`, `CREATE_KEY` or `REFRESH` permission.

An enrolled service may submit the original `CREATE_KEY`/`REFRESH` descriptor to `POST /approval/service/v1/keys`. The authority directly reads all three authenticated holders outside a database transaction, verifies completed consistent identities with the original intent hash, then atomically records its own `approval.key` and audit. A Wallet-provided public key or result document is never evidence. Initial registration requires epoch one; refresh preserves the public identity and advances one epoch. Disabled keys cannot be reactivated through this service route. An unconfigured registry profile denies registration while leaving the independent review UI available.

Registry identity is namespaced by `(tenant_id, key_id)`, including its independent epoch and disabled state. A UUID collision or equal public key in another tenant does not read, overwrite or block the original tenant's registry.

## Frozen independent asset/network profiles

Install `approval.assets.entries` on the independent host with chain, network label, asset/native symbols, stable network identity, token identifier, precision and native network-fee ceiling. These are operator-controlled facts, not request fields. A tenant administrator explicitly configures policies for each registered network; a testnet rule cannot authorize mainnet, and rolling request volumes do not mix networks.

At admission the authority validates exact asset/native precision and fee bounds, stores the public asset snapshot on the request and includes it in the reviewed hash. The portal displays the frozen network and asset identifiers and the fee currency. Original idempotent requests retain the original snapshot after configuration changes; a new signing grant must not reinterpret an old review under a changed contract or network. Unknown fields in network request JSON are rejected. RPC credentials are never part of the public snapshot.

## Independent authorization issuer key

`approval.signing.private-key-file` accepts only an owner-only `0600` regular Ed25519 PKCS8 DER file. `approval.signing.public-key-hex` is its raw 32-byte lowercase public key; startup verifies the pair using a separate test domain. The private key stays exclusively on the authority host. Holders and Wallet receive only the verification public key. An absent profile cannot issue proofs; this does not enable unsigned signing.

`POST /approval/service/v1/signing-grants` accepts the shared immutable `SigningCandidate` only through actual enrolled mTLS. For supported payment families it checks the reviewed intent, unchanged independent asset snapshot, active registered key/epoch, trusted holder roster, actual wire recipient/amount/contract/network/fee and signing digest. It commits transaction/version/slot, exact native session proof, request binding, fee reservation and audit before returning any proof. Exact retries retrieve the original proof without extending validity. An explicitly audited fresh native session can recover the identical command and original EVM account nonce or Aptos sequence; unsupported replacements and unknown transaction families are rejected. Bitcoin replacement versions require the separate bounded same-input RBF policy.

Configure `approval.fees.budgets` independently per tenant/chain/network using `approval-fees.example.yaml`. The native symbol, precision and network identity must match the reviewed asset snapshot. Pending or unknown signatures consume the rolling 24-hour fee allowance, once per original transaction. No configured budget means no new grant. Wallet callers and browser threshold policies cannot increase this operator ceiling.

## Wallet admission and shared public contracts

`ApprovalRequest`, `SigningGrant`, `WithdrawalIntent` and `SigningCandidate` live in `common.approval`; neither application depends on the other. Intent hashes bind original external business reference and explicit `ADDRESS`/`TENANT_POOL` funding source, as well as proposer, network, recipient, exact amount and maximum native fee. The independent review page shows these original fields.

Wallet creates every new withdrawal in internal `PENDING_REVIEW` and commits a permanent `wallet_withdrawal_approval` intent with its original order, principal/Gas freeze, accepted-response receipt, audit and events. The previous global approval toggle is removed. The outward state remains `FROZEN`. Its dedicated withdrawal job submits/reconciles the same intent outside a database transaction through a Wallet-only mTLS certificate, pinned authority TLS SPKI and bounded response/timeout. An absent authority profile fails closed and retains the original frozen request.

Independent approval allows chain preparation; a pending request stays frozen. Rejection/expiry releases principal only after a fresh matching authority receipt proves signing never started and every related local slot, batch and broadcast fence permits cancellation. Cancellation, input/unused nonce cleanup, principal refund, Gas projection, normal failure event, local receipt and audit commit atomically. Any authorized, conflicting or unknown transaction stays frozen for its original recovery path. Late pending replies cannot overwrite an approved state. Configure Wallet transport using `wallet-api/src/main/resources/withdrawal-approval.example.yaml`; the authority separately enrolls that certificate SPKI for explicit tenant UUIDs. The authority's private issuer key is never installed in Wallet.

Wallet `MpcAuthorizationService` obtains the final chain-specific grant outside its transaction, verifies the exact signed business/version/network/slot/native-session scope, and commits the original proof plus audit before dispatch. `MpcSigningService` commits the attempt's dispatch fence, then passes the persisted proof to native `submit(Request,Proof)`. Cached original proofs remain available after expiry or authority outage; a holder still rejects an expired proof on first admission. Every supported chain uses its explicit verifier; Monero relay requires the original authorized native payment artifact and a separately scoped relay proof. Unknown purposes and transaction encodings fail closed. Native acceptance probes can supply `SW_TEST_APPROVAL_CLIENT_PROFILE` with a real Wallet-only authority client profile; they never embed an issuer or auto-approve unsupported transactions.

Wallet now automatically registers a completed native DKG/refresh before publishing its key or preparing signatures. Authority observation occurs outside Wallet transactions; the validated registration receipt and audit commit together on the original epoch. The receipt is immutable. A refresh requires its source epoch's receipt, and registration failure retries the same original ceremony. Existing receipts are checked locally, so routine two-holder signing does not require a third holder or repeat the three-holder registration observation.

Explicit ordinary EVM signing recovery carries the operator actor/reason from the durable Wallet attempt. The authority requires the exact original command, reviewed binding, version and account nonce, then commits a new per-session execution/proof/audit. It retains one original transaction/version/slot/fee reservation. Signatures for the same account nonce are mutually exclusive on EVM; this permission is not generalized to other chains. An admitted session's participants or replay reason cannot be rewritten, and the original proof remains retrievable unchanged.


## Aptos fixed transaction boundary

The public-only BCS codec and address helpers live in `chain-sdks/sdk/signing/aptos`; the shared `AptosSigningPayload` lives in `common.signing`. Wallet and the independent authority reconstruct the same canonical framework native APT / primary fungible-asset transfer. The authority compares every actual signing byte against the frozen reviewed recipient, exact atomic amount, registered metadata, sender key, chain ID, sequence, expiry and gas bounds. Scripts, sponsored transactions, orderless transactions and a changed payload are rejected.

Only a classic account sequence provides mutually exclusive recovery here: [Aptos account sequence rules](https://aptos.dev/network/blockchain/accounts). Orderless transactions have a different replay model ([AIP-123](https://aptos.dev/build/aips/aip-123)) and are excluded. Each grant charges the maximum `max_gas_amount * gas_unit_price` against the reviewed and independent native fee ceilings ([Aptos fee specification](https://aptos.dev/network/blockchain/gas-txn-fee)). Wallet derives max gas by flooring its configured positive total native fee budget divided by gas price, rejects a unit price above that budget and applies no minimum gas floor that could exceed it. Nonnegative customer service fees do not change the approved on-chain amount or native gas bound. Exact original sequence/message recovery retains one fee reservation and requires an explicit operator reason; no signature authorizes moving the approved business payment to a different sequence.

The single canonical cross-language golden vector is `resources/test-fixtures/mpc/aptos-codec-reference.json`, copied into SDK and Wallet test classpaths. Aptos native-transfer acceptance passed: two independent reviewers, automatic three-holder Ed25519 registration, actual Wallet proof/audit persistence and two-holder BCS signature with holder1 offline. This is unfunded signing only; no chain broadcast.


## Reviewer invitations, revocation and audit

An independently authenticated TENANT_ADMIN can invite an APPROVER using that person's public Wallet subaccount UUID, email and display name, plus a mandatory audit reason. The response contains a single-use random activation token valid for 24 hours. Share the portal's fragment link privately with the verified person; it is an activation capability. The recipient sets their own separate 12–256 character approval password. No password is returned to the administrator and activation creates no session automatically. Neither raw capabilities nor passwords are stored in member lists or audits. The approval host, administrator and private delivery channel remain trusted; a Wallet administrator/session/service certificate cannot issue this capability.

Reissuing a link invalidates the previous link, immediately blocks the old password and revokes sessions. Disabling an APPROVER clears pending activation and revokes sessions in the same audited transaction. Reactivation requires another independently issued link and a new password. The browser cannot modify itself or another TENANT_ADMIN; use trusted host administration for those credentials. Member row SHARE locks order in-flight decisions against revocation without holding the tenant admission lock. The independent administrator can inspect paginated decision/policy/member audits under the same tenant only. Wallet disable propagation retains a durable monotonic fence even for a not-yet-enrolled ID, credentials/sessions are revoked atomically, and an independent administrator must explicitly acknowledge that fence before re-enrollment. Delayed old notifications cannot disable a newly acknowledged enrollment.

Member lifecycle, revocation races and UI restoration acknowledgments are included in the unified verification entrypoints. No production deployment has been performed.


## Independent UTXO observations (fixed-batch grant integration)

Install `approval.utxo-rpc.profiles` using `approval-utxo-rpc.example.yaml` on the independent host. Each chain/network is bound to a verified genesis hash, exact node chain name, confirmation floor and read-only node endpoint. Credentials are an owner-only0600 regular file with one username:password line; they are never service-request fields, public asset metadata or audit data. Use HTTPS remotely; plain HTTP is allowed only for explicit local loopback. Redirects are forbidden. Each observation has one total timeout (maximum30s) and each response is limited to2MiB.

The gateway runs outside database transactions, verifies [genesis via getblockhash(0)](https://bitcoincore.org/en/doc/30.0.0/rpc/blockchain/getblockhash/), network/sync state and one consistent tip, then reads each [unspent previous output](https://bitcoincore.org/en/doc/30.0.0/rpc/blockchain/gettxout/) including mempool spends. Amounts retain exact decimal precision. Missing/spent/low-confirmation outputs, inconsistent tips and network mismatches deny evidence. The explicit UTXO verifier combines these observations with multi-order/multi-input slot binding, independently owned change, immutable input ownership and once-per-batch fees. The gateway itself is read-only.


UTXO pure verification now consumes shared `common.signing.UtxoSigningPayload`, including an explicit independently registered changeKeyId. Public address/script derivation stays in the SDK. Every payment needs its reviewed intent; every input must match independent previous-output facts and the registered joint key, and change must derive from a same-tenant active joint key. The verifier reconstructs the full canonical transaction and current input digest; it compares the whole fee against the sum of reviewed fee contributions and the independent per-transaction ceiling. The batch route now combines it with permanent multi-approval/multi-slot bindings, immutable original evidence and once-per-batch fee reservation; the integration is covered by policy/database tests and the four-Core HTTPS/native acceptance entrypoint. The pure verifier alone never issues a grant.


Fixed UTXO batches use one stable authority transaction (`utxo:<task.transactionId>`), one immutable payload version and one fee reservation across all reviewed payments and input slots. The first grant atomically binds every review and claims every individual outpoint in `approval.spend_lock`, stores independently fetched `signing_version.chain_evidence`, then records the input slot/grant/audit. A later input does not query spent outputs or consume the fee budget again. Exact original-slot retries retrieve the original proof, and an explicit unchanged-input recovery gets its own audited session proof. A second Wallet ID for an existing input or a changed payload/approval set is rejected. Input ownership and evidence are permanent; errors roll back the whole first admission. Shared `ApprovalTransactionHash` V2 binds all reviewed IDs/hashes and the fixed version, independently of input order/slot/key; the native proof serialization remains V1. Wallet verifies the same whole batch before storing and dispatching each proof. The HTTPS acceptance exercises this route against actual BTC/LTC/DOGE/BCH regtest nodes, including a lost broadcast reply and identical raw retry.


## Chain and automatic-operation boundaries

Signing families, automatic operating permissions, pre-admitted collection plans and Gas-deficit reads use explicit independent policies. `automatic-plans` reserves the original installed policy's principal/count/maximum native exposure before Gas preparation, reads balances directly and binds the eventual transaction exactly once. It grants no native signing capability. Public Wallet metadata supplies neither balances nor trusted endpoints. XRP setup uses an explicit zero-principal `ADDRESS_PREPARATION` receipt; activation XRP and TrustSet fees are native exposure, with fixed owned targets, issuer/currency, validated-ledger reserve evidence, installed Gas/trustLimit and NoRipple. Ordinary payments remain positive. Bitcoin-like routing explicitly excludes Cardano.

EVM requires installed `feeModel` and `maximumSignedTransactionBytes`; separate OP Stack/Scroll data/operator expense uses installed `maximumExtraFeeAtomic` and `extraFeeHeadroomBps`. The complete native exposure is reserved once, including all 7702 slots, and the final outer must fit its original size limit. Quotes/headroom are policy reservations, not protocol-enforced absolute fee caps. Monero relay can query the other original holder after a holder communication failure, while retaining the same original payment/hash. No new payment is created.

Policy fixtures exercise canonical native/token messages for each family. EIP-7702 fixtures include actual test signatures for payout and multi-source collection, with or without installing delegation. NEAR registration and XRP setup require actual observed deficits/minimums and fixed targets. These fixtures do not broadcast transactions. Live-network rollout must separately validate each installed network, contract and RPC configuration.


BTC bounded same-input RBF has explicit version/slot/database regression coverage. Only an explicit independent BTC profile enables up to five total versions and bounded rate/absolute fees. Initial admission reserves the full reviewed/asset fee allowance once. Every version must preserve root ownership, exact inputs/change and reviewed withdrawal outputs; automatic collection can only deduct the exact additional fee from the original fixed treasury output. Stable automatic reference `utxo:<transactionId>` prevents request UUID changes from replenishing quotas. Root review binding, each version hash/slot/proof, original input evidence and replacement audits are permanent. Other chain families do not inherit this permission. The four-Core end-to-end fixture proves ordinary original-batch signing/broadcast, not an RBF replacement broadcast. Verify replacements against the intended Bitcoin node policy before enabling this permission operationally.


## Revocation and deployment under unified verification

Wallet now persists subaccount revocation generations and retries one-way mTLS delivery from a dedicated custody scheduler. While any tenant revocation is pending, new grant requests pause; cached original proofs remain available for original-transaction recovery. The independent `approval.member_revocation` single-table repository records a permanent monotonic fence even for unknown members. New notifications disable APPROVER credentials/activation/sessions atomically with audit. Independent invitations and resets must explicitly acknowledge the current fence; the portal checks and asks the independent administrator to confirm restoration. Wallet can never re-enable or reset independent credentials. This is eventual cross-process delivery, not atomic revocation of already issued proofs or past approvals.

Both boot applications now check actual PostgreSQL 18 runtime privileges via `DatabaseRoleIsolation`, without a bypass setting. Owner/admin/member-of-other-role credentials or access across the independent boundary fail startup. Public-only role provisioning, systemd/private transport, monitoring and rollback/backup instructions are in `resources/infra/approval/README.md`. No production roles or services have been changed. The HTTPS fixture provisions separate temporary owner/runtime identities and passwords in its own local PostgreSQL 18 test database. The executable HTTPS acceptance uses these actual restricted identities and both startup isolation guards. This does not provision production identities.


## Unsigned termination and nonce admission

Wallet now re-reads terminal authority receipts until cleanup completes. Only EXPIRED/REJECTED with `signingStarted=false`, matching original frozen intent/snapshot and no local proof/signature/selected transaction permits atomic cancellation. Related native requests retain immutable `CANCELLED` receipts and cannot dispatch again. Unsigned batch/input/Gas cleanup, sibling requeue, conditional refund, normal outward event/projection, audit and cleanup marker commit or roll back together. Already authorized Gas/storage children keep their own lifecycle. Conflicting or unknown evidence stays frozen.

Account/EVM nonce rows now fence one `pending_owner` before its first durable proof. The first exact wire/key/nonce admission and proof persist together; later same-version slots verify the original issuer proof. Only then can another business reserve the next nonce. No-grant cancellation returns only this exclusive unused suffix, bounded below by observed chain state, and cannot create a hole before another admitted transaction. HyperCore timestamps use their own noncontiguous rule. The original owner is serialized by a transaction-scoped advisory lock before the atomic UPSERT checks both uniqueness constraints. Regression covers this race, admission, suffix return and rollback; the HTTPS fixture covers real authority admission and four-Core batch confirmation. Authorized expired/partial/unknown payments retain their original binding and require canonical outcome reconciliation.

TRON memo, TON text memo/Jetton query identity and NEAR Token memo are bound to the original tenant/business purpose/reference. The authority checks these alongside the canonical SDK message, so caller-selected auxiliary instructions cannot pass merely by preserving the recipient and amount. Account-chain policy fixtures exercise native/token variants and do not substitute for live-node acceptance.

## Validation record

See [the dated validation record](../resources/docs/zh/approval-validation.md) for exact test counts, real native/HTTPS/Core evidence, conditional skips and live-network rollout boundaries.
