package com.surprising.wallet.common.queue;

/** Fixed queue identifiers; callers cannot choose arbitrary database tables. */
public enum WalletQueue {
    MPC_SIGN("mpc_sign"), SIGN_DONE("sign_done"),
    WITHDRAW("withdraw"), DEPOSIT_EVENT("deposit_event"), WITHDRAW_EVENT("withdraw_event"), RBF("rbf");
    private final String name;
    WalletQueue(String name) { this.name = "wallet_" + name; }
    public String queueName() { return name; }
}
