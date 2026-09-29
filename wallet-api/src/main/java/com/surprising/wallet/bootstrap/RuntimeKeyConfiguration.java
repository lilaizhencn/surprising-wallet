package com.surprising.wallet.bootstrap;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.key.WalletSeedCodec;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RuntimeKeyConfiguration {
    @Bean("apiKeyMaterial")
    @Primary
    @WalletModeEnabled({WalletMode.api, WalletMode.all})
    WalletKeyMaterialProvider apiKeys(Environment env) {
        String firstPublic = WalletMode.from(env) == WalletMode.all
                ? publicFromSeed(env, "sig1-seed") : required(env, "sig1-public-root");
        return WalletKeyMaterialProvider.forApi(required(env, "sig2-seed"), required(env, "ed25519-seed"),
                firstPublic, required(env, "recovery-public-root"));
    }
    @Bean("sig1KeyMaterial")
    @WalletModeEnabled({WalletMode.sig1, WalletMode.all})
    WalletKeyMaterialProvider firstKeys(Environment env) {
        String secondPublic = WalletMode.from(env) == WalletMode.all
                ? publicFromSeed(env, "sig2-seed") : required(env, "sig2-public-root");
        return WalletKeyMaterialProvider.forSig1(required(env, "sig1-seed"), secondPublic,
                required(env, "recovery-public-root"));
    }
    @Bean("sig2KeyMaterial")
    @WalletModeEnabled({WalletMode.sig2, WalletMode.all})
    WalletKeyMaterialProvider secondKeys(Environment env) {
        return WalletKeyMaterialProvider.forSig2(required(env, "sig2-seed"));
    }
    private String required(Environment env, String key) { return env.getRequiredProperty("sw.wallet.keys." + key); }
    private String publicFromSeed(Environment env, String key) {
        return WalletSeedCodec.bip32Root(key, required(env, key)).pubSerialize(0, false);
    }
}
