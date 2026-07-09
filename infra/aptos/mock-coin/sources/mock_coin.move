module MockCoin::mock_coin {
    use aptos_framework::managed_coin;

    struct MockCoin {}

    fun init_module(sender: &signer) {
        managed_coin::initialize<MockCoin>(
            sender,
            b"Surprising Wallet Mock USD",
            b"SWMUSD",
            6,
            false,
        );
    }

    public entry fun register(account: &signer) {
        managed_coin::register<MockCoin>(account);
    }

    public entry fun mint(account: &signer, receiver: address, amount: u64) {
        managed_coin::mint<MockCoin>(account, receiver, amount);
    }
}
