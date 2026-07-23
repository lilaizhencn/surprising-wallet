// Copyright (c) Surprising Wallet contributors
// SPDX-License-Identifier: Apache-2.0

/// Local-only USDC fixture used by the Sui end-to-end test.
module sui_mock_coin::usdc;

use sui::coin::{Self, TreasuryCap};

public struct USDC has drop {}

#[allow(deprecated_usage)]
fun init(witness: USDC, ctx: &mut TxContext) {
    let (treasury, metadata) = coin::create_currency(
        witness,
        6,
        b"USDC",
        b"Local USDC",
        b"Local-only Sui integration-test coin",
        option::none(),
        ctx,
    );
    transfer::public_freeze_object(metadata);
    transfer::public_transfer(treasury, ctx.sender());
}

public fun mint(
    treasury: &mut TreasuryCap<USDC>,
    amount: u64,
    recipient: address,
    ctx: &mut TxContext,
) {
    let minted = coin::mint(treasury, amount, ctx);
    transfer::public_transfer(minted, recipient);
}
