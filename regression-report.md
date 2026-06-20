# Regression Report

## Completed

- BTC SDK tests passed.
- BTC SegWit multisig tests passed.
- BTC UTXO optimizer tests passed.
- wallet-service adapter registry tests passed.
- Tatum faucet helper request-shape test passed.
- wallet-server compiled successfully.

## Notes

- BTC witness structure, multisig script, signing flow, and scan core were not changed.
- BTC remains the highest-priority isolated path.

## Pending external verification

- Live EVM faucet-funded transfer execution.
- Live TRON faucet-funded transfer execution.
- Solana transfer execution after test funds are available.
- TON transfer execution after test funds are available.

## Current constraint

- If the external faucet cannot allocate funds, keep the helper and resume live-chain validation in the next run after manual funding.
