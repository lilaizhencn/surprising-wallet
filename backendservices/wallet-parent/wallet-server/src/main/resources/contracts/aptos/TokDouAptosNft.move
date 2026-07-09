module {{ADDRESS_NAME}}::{{MODULE_NAME}} {
    use std::signer;
    use aptos_framework::managed_coin;

    const E_NOT_OWNER: u64 = 1;
    const E_ALREADY_MINTED: u64 = 2;

    struct {{COIN_TYPE}} {}

    struct Config has key {
        owner: address,
        minted: bool,
    }

    fun init_module(sender: &signer) {
        managed_coin::initialize<{{COIN_TYPE}}>(
            sender,
            b"{{NAME}}",
            b"{{SYMBOL}}",
            0,
            false,
        );
        move_to(sender, Config {
            owner: signer::address_of(sender),
            minted: false,
        });
        mint(sender, signer::address_of(sender));
    }

    public entry fun register(account: &signer) {
        managed_coin::register<{{COIN_TYPE}}>(account);
    }

    public entry fun mint(account: &signer, receiver: address) acquires Config {
        let sender = signer::address_of(account);
        let config = borrow_global_mut<Config>(sender);
        assert!(sender == config.owner, E_NOT_OWNER);
        assert!(!config.minted, E_ALREADY_MINTED);
        config.minted = true;
        managed_coin::mint<{{COIN_TYPE}}>(account, receiver, 1);
    }

    public fun owner(): address acquires Config {
        borrow_global<Config>(@{{ADDRESS_NAME}}).owner
    }

    public fun minted(): bool acquires Config {
        borrow_global<Config>(@{{ADDRESS_NAME}}).minted
    }
}
