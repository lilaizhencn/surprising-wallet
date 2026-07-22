## NEAR Contract Templates

`TokDouNep141.wasm` and `TokDouNep171.wasm` are precompiled near-sdk-js 2.0.0
templates used by wallet contract deployment.

The TypeScript sources are based on the official near-sdk-js examples:

- `TokDouNep141.ts`: fungible token example implementing NEP-141 and storage management.
- `TokDouNep171.ts`: non-fungible token example implementing NEP-171 metadata, enumeration, approval, and owner mint.

Build command used for the checked-in Wasm artifacts:

```bash
npm install near-sdk-js@2.0.0 near-contract-standards@2.0.0 typescript@4.7.4 @types/node@16.18.126
mkdir -p build
npx near-sdk-js build src/fungible-token/my-ft.ts build/TokDouNep141.wasm package.json
npx near-sdk-js build src/non-fungible-token/my-nft.ts build/TokDouNep171.wasm package.json
```

The runtime does not compile user-provided code. It only deploys these fixed
Wasm templates and passes validated initialization JSON.
