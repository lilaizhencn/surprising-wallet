module mock_coin::mock_coin;

use std::option;
use sui::coin::{Self, TreasuryCap};
use sui::transfer;
use sui::tx_context::{Self, TxContext};

public struct MOCK_COIN has drop {}

fun init(witness: MOCK_COIN, ctx: &mut TxContext) {
    let (treasury, metadata) = coin::create_currency<MOCK_COIN>(
        witness,
        6,
        b"SWMUSD",
        b"Surprising Wallet Mock USD",
        b"Sui testnet mock USD for wallet integration tests",
        option::none(),
        ctx,
    );
    transfer::public_freeze_object(metadata);
    transfer::public_transfer(treasury, tx_context::sender(ctx));
}

public entry fun mint(
    treasury: &mut TreasuryCap<MOCK_COIN>,
    amount: u64,
    recipient: address,
    ctx: &mut TxContext,
) {
    let coin = coin::mint(treasury, amount, ctx);
    transfer::public_transfer(coin, recipient);
}
