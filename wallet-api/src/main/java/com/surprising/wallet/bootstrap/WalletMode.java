package com.surprising.wallet.bootstrap;

import org.springframework.core.env.Environment;

public enum WalletMode {
    all, api, sig1, sig2;
    public static WalletMode from(Environment environment) {
        String value = environment.getProperty("sw.wallet.mode", "api");
        try { return valueOf(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("sw.wallet.mode must be all, api, sig1 or sig2"); }
    }
    public boolean web() { return this == all || this == api; }
}
