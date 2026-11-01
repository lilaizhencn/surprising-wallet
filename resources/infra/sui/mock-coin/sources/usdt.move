// Copyright (c) Surprising Wallet contributors
// SPDX-License-Identifier: Apache-2.0

/// Local-only USDT fixture used by the Sui end-to-end test.
module sui_mock_coin::usdt;

use sui::coin::{Self, TreasuryCap};

public struct USDT has drop {}

#[allow(deprecated_usage)]
fun init(witness: USDT, ctx: &mut TxContext) {
    let (treasury, metadata) = coin::create_currency(
        witness,
        6,
        b"USDT",
        b"Local USDT",
        b"Local-only Sui integration-test coin",
        option::none(),
        ctx,
    );
    transfer::public_freeze_object(metadata);
    transfer::public_transfer(treasury, ctx.sender());
}

public fun mint(
    treasury: &mut TreasuryCap<USDT>,
    amount: u64,
    recipient: address,
    ctx: &mut TxContext,
) {
    let minted = coin::mint(treasury, amount, ctx);
    transfer::public_transfer(minted, recipient);
}
