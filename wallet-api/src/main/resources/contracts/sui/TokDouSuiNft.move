module {{MODULE_NAME}}::{{MODULE_NAME}};

use std::string::String;
use sui::event;
use sui::object::{Self, ID, UID};
use sui::transfer;
use sui::tx_context;

const E_NOT_OWNER: u64 = 1;
const E_MAX_SUPPLY: u64 = 2;

public struct Collection has key, store {
    id: UID,
    name: String,
    symbol: String,
    base_uri: String,
    owner: address,
    minted: u64,
    max_supply: u64,
}

public struct Nft has key, store {
    id: UID,
    collection: ID,
    name: String,
    description: String,
    uri: String,
    number: u64,
}

public struct Minted has copy, drop {
    collection: ID,
    nft: ID,
    recipient: address,
    number: u64,
}

fun init(ctx: &mut TxContext) {
    let collection = Collection {
        id: object::new(ctx),
        name: x"{{NAME_HEX}}".to_string(),
        symbol: x"{{SYMBOL_HEX}}".to_string(),
        base_uri: x"{{BASE_URI_HEX}}".to_string(),
        owner: @{{OWNER_ADDRESS}},
        minted: 0,
        max_supply: {{MAX_SUPPLY}},
    };
    transfer::public_transfer(collection, @{{OWNER_ADDRESS}});
}

public fun mint(
    collection: &mut Collection,
    name: vector<u8>,
    description: vector<u8>,
    uri: vector<u8>,
    recipient: address,
    ctx: &mut TxContext,
) {
    assert!(tx_context::sender(ctx) == collection.owner, E_NOT_OWNER);
    assert!(collection.minted < collection.max_supply, E_MAX_SUPPLY);
    collection.minted = collection.minted + 1;
    let nft = Nft {
        id: object::new(ctx),
        collection: object::id(collection),
        name: name.to_string(),
        description: description.to_string(),
        uri: uri.to_string(),
        number: collection.minted,
    };
    event::emit(Minted {
        collection: object::id(collection),
        nft: object::id(&nft),
        recipient,
        number: collection.minted,
    });
    transfer::public_transfer(nft, recipient);
}
