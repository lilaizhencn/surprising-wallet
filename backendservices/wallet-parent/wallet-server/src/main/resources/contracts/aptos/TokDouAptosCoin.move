module {{ADDRESS_NAME}}::{{MODULE_NAME}} {
    use std::signer;
    use aptos_framework::managed_coin;

    const E_NOT_OWNER: u64 = 1;
    const E_MINT_DISABLED: u64 = 2;
    const E_SUPPLY_EXCEEDED: u64 = 3;

    struct {{COIN_TYPE}} {}

    struct Config has key {
        owner: address,
        minted: u64,
        max_supply: u64,
        mintable: bool,
    }

    fun init_module(sender: &signer) {
        managed_coin::initialize<{{COIN_TYPE}}>(
            sender,
            b"{{NAME}}",
            b"{{SYMBOL}}",
            {{DECIMALS}},
            false,
        );
        move_to(sender, Config {
            owner: signer::address_of(sender),
            minted: 0,
            max_supply: {{MAX_SUPPLY}},
            mintable: {{MINTABLE}},
        });
        {{INITIAL_MINT_BLOCK}}
    }

    public entry fun register(account: &signer) {
        managed_coin::register<{{COIN_TYPE}}>(account);
    }

    public entry fun mint(account: &signer, receiver: address, amount: u64) acquires Config {
        let sender = signer::address_of(account);
        let config = borrow_global_mut<Config>(sender);
        assert!(sender == config.owner, E_NOT_OWNER);
        assert!(config.mintable, E_MINT_DISABLED);
        assert!(config.minted + amount <= config.max_supply, E_SUPPLY_EXCEEDED);
        config.minted = config.minted + amount;
        managed_coin::mint<{{COIN_TYPE}}>(account, receiver, amount);
    }

    public fun owner(): address acquires Config {
        borrow_global<Config>(@{{ADDRESS_NAME}}).owner
    }

    public fun minted(): u64 acquires Config {
        borrow_global<Config>(@{{ADDRESS_NAME}}).minted
    }

    public fun max_supply(): u64 acquires Config {
        borrow_global<Config>(@{{ADDRESS_NAME}}).max_supply
    }

    public fun mintable(): bool acquires Config {
        borrow_global<Config>(@{{ADDRESS_NAME}}).mintable
    }
}
