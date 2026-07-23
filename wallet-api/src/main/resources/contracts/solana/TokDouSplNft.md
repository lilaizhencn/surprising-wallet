# TokDou SPL NFT Mint

This fixed deployment path creates a single-supply Solana SPL mint suitable for
an NFT-style asset in this wallet version.

Runtime actions:

1. Create a rent-exempt SPL mint account paid by the contract deployment address.
2. Initialize the mint with `decimals=0`.
3. Create the owner's associated token account idempotently.
4. Mint exactly one unit to the owner.
5. Revoke mint authority and freeze authority.
6. Write a deployment memo containing the validated name and symbol.

This version does not deploy a custom Solana program and does not yet attach
Metaplex Token Metadata. Metadata onboarding should be handled explicitly before
adding the created mint to any public token list.
