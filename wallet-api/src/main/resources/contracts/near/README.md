## NEAR Contract Templates

`TokDouNep141.wasm` and `TokDouNep171.wasm` are fixed near-sdk-js 2.0.0
templates used by wallet contract deployment. The adjacent TypeScript files are
based on the official NEAR fungible and non-fungible token examples.

The NEP-141 template explicitly builds storage-deposit refund promises.
`near-contract-standards@2.0.0` constructs these promises without executing them:
a duplicate registration logs a refund but retains the attached deposit.
The wrapper preserves storage-management state and the returned storage balance,
refunds the full deposit for an existing account, and refunds the excess above
the required balance for a new account. The wallet verifies actual FINAL receipts
and never treats the refund log as proof of payment.

Build in an isolated directory from the repository root:

```bash
NEAR_TEMPLATE_SOURCE="$PWD/wallet-api/src/main/resources/contracts/near"
NEAR_TEMPLATE_BUILD=$(mktemp -d /tmp/surprising-wallet-near-template.XXXXXX)
cd "$NEAR_TEMPLATE_BUILD"
npm init -y
npm install --ignore-scripts --no-audit --no-fund --save-exact near-sdk-js@2.0.0 near-contract-standards@2.0.0 typescript@4.7.4 @types/node@16.18.126
(cd node_modules/near-sdk-js && node lib/cli/post-install.js)
cp "$NEAR_TEMPLATE_SOURCE/TokDouNep141.ts" .
cp "$NEAR_TEMPLATE_SOURCE/TokDouNep171.ts" .
mkdir build
npx --no-install near-sdk-js build TokDouNep141.ts build/TokDouNep141.wasm package.json
npx --no-install near-sdk-js build TokDouNep171.ts build/TokDouNep171.wasm package.json
```

Scripts are disabled during dependency installation; the SDK's compiler setup is
then run explicitly. The runtime does not compile user-provided code. It deploys
only these fixed Wasm resources with validated initialization JSON. Only commit
a checked-in Wasm resource change with its corresponding source and actual
local-node verification. The MPC automatic-registration acceptance command is
`resources/scripts/regtest/run-near-registration-flow.sh`; use fresh test token
contract accounts on the existing isolated ledger, preserving prior contracts
and transaction history.

References: [NEP-145](https://github.com/near/NEPs/blob/master/neps/nep-0145.md),
[NearPromise.build](https://near.github.io/near-sdk-js/classes/promise.NearPromise.html#build).
