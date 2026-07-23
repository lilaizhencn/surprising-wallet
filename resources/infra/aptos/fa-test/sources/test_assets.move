module test_fa::test_assets {
    use std::option;
    use std::signer;
    use std::string;

    use aptos_framework::fungible_asset::{Self, Metadata, MintRef};
    use aptos_framework::object::{Self, Object};
    use aptos_framework::primary_fungible_store;

    const ENOT_ADMIN: u64 = 1;

    struct AssetController has key {
        mint_ref: MintRef,
    }

    struct Registry has key {
        usdc: Object<Metadata>,
        usdt: Object<Metadata>,
    }

    fun init_module(admin: &signer) {
        let usdc_constructor = &object::create_named_object(admin, b"USDC");
        primary_fungible_store::create_primary_store_enabled_fungible_asset(
            usdc_constructor,
            option::none(),
            string::utf8(b"Test USD Coin"),
            string::utf8(b"USDC"),
            6,
            string::utf8(b""),
            string::utf8(b"https://surprising-wallet.local/test-assets/usdc"),
        );
        let usdc = object::object_from_constructor_ref(usdc_constructor);
        let usdc_signer = &object::generate_signer(usdc_constructor);
        move_to(usdc_signer, AssetController {
            mint_ref: fungible_asset::generate_mint_ref(usdc_constructor),
        });

        let usdt_constructor = &object::create_named_object(admin, b"USDT");
        primary_fungible_store::create_primary_store_enabled_fungible_asset(
            usdt_constructor,
            option::none(),
            string::utf8(b"Test Tether USD"),
            string::utf8(b"USDT"),
            6,
            string::utf8(b""),
            string::utf8(b"https://surprising-wallet.local/test-assets/usdt"),
        );
        let usdt = object::object_from_constructor_ref(usdt_constructor);
        let usdt_signer = &object::generate_signer(usdt_constructor);
        move_to(usdt_signer, AssetController {
            mint_ref: fungible_asset::generate_mint_ref(usdt_constructor),
        });

        move_to(admin, Registry { usdc, usdt });
    }

    public entry fun mint_usdc(admin: &signer, recipient: address, amount: u64)
    acquires Registry, AssetController {
        assert!(signer::address_of(admin) == @test_fa, ENOT_ADMIN);
        let registry = borrow_global<Registry>(@test_fa);
        let controller = borrow_global<AssetController>(object::object_address(&registry.usdc));
        primary_fungible_store::mint(&controller.mint_ref, recipient, amount);
    }

    public entry fun mint_usdt(admin: &signer, recipient: address, amount: u64)
    acquires Registry, AssetController {
        assert!(signer::address_of(admin) == @test_fa, ENOT_ADMIN);
        let registry = borrow_global<Registry>(@test_fa);
        let controller = borrow_global<AssetController>(object::object_address(&registry.usdt));
        primary_fungible_store::mint(&controller.mint_ref, recipient, amount);
    }

    #[view]
    public fun metadata_addresses(): (address, address) acquires Registry {
        let registry = borrow_global<Registry>(@test_fa);
        (object::object_address(&registry.usdc), object::object_address(&registry.usdt))
    }
}
