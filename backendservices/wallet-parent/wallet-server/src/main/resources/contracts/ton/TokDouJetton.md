# TokDou TON Jetton

This template maps the wallet deployment flow to the standard TON Jetton
minter contract provided by ton4j.

Deployment creates a TEP-74 Jetton minter with:

1. the deployment address as admin,
2. off-chain metadata URI stored in the Jetton content cell,
3. an optional initial mint to the deployment/admin address,
4. deterministic TON contract address derived from StateInit.

This is a standard Jetton minter deployment through Wallet V4R2. The backend
does not accept arbitrary FunC source in this version.
