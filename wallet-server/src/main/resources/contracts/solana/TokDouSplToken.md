# TokDou SPL Token Mint

This fixed deployment path creates a standard Solana SPL Token mint.

Runtime actions:

1. Create a rent-exempt SPL mint account paid by the contract deployment address.
2. Initialize the mint with validated decimals.
3. Create the owner's associated token account idempotently.
4. Mint the validated initial supply to the owner.
5. Transfer mint authority to the owner when `mintable=true`, otherwise revoke it.
6. Revoke freeze authority.
7. Write a deployment memo containing the validated name and symbol.

The wallet does not deploy custom Solana programs or arbitrary user code.
