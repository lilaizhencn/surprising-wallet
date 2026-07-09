module {{MODULE_NAME}}::{{MODULE_NAME}};

use sui::coin::{Self, Coin, TreasuryCap};
use sui::coin_registry;
use sui::object::{Self, UID};
use sui::transfer;
use sui::tx_context;

const E_NOT_OWNER: u64 = 1;
const E_MAX_SUPPLY: u64 = 2;

public struct {{WITNESS_NAME}} has drop {}

public struct MintAuthority has key, store {
    id: UID,
    treasury_cap: TreasuryCap<{{WITNESS_NAME}}>,
    owner: address,
    max_supply: u64,
}

fun init(witness: {{WITNESS_NAME}}, ctx: &mut TxContext) {
    let owner = @{{OWNER_ADDRESS}};
    let max_supply = {{MAX_SUPPLY}};
    let (currency, mut treasury_cap) = coin_registry::new_currency_with_otw(
        witness,
        {{DECIMALS}},
        x"{{SYMBOL_HEX}}".to_string(),
        x"{{NAME_HEX}}".to_string(),
        x"{{DESCRIPTION_HEX}}".to_string(),
        x"".to_string(),
        ctx
    );
    let initial = {{INITIAL_SUPPLY}};
{{INITIAL_MINT_BLOCK}}
    let metadata_cap = currency.finalize(ctx);
{{AUTHORITY_BLOCK}}
    transfer::public_transfer(metadata_cap, owner);
}

public fun mint(authority: &mut MintAuthority, amount: u64, recipient: address, ctx: &mut TxContext) {
    assert!(tx_context::sender(ctx) == authority.owner, E_NOT_OWNER);
    assert!(amount <= authority.max_supply - coin::total_supply(&authority.treasury_cap), E_MAX_SUPPLY);
    let minted = coin::mint(&mut authority.treasury_cap, amount, ctx);
    transfer::public_transfer(minted, recipient);
}

public fun burn(authority: &mut MintAuthority, coin: Coin<{{WITNESS_NAME}}>): u64 {
    coin::burn(&mut authority.treasury_cap, coin)
}
