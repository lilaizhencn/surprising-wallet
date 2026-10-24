package com.surprising.wallet.chain;

import com.surprising.wallet.chain.aptos.AptosKeyService;
import com.surprising.wallet.chain.cardano.CardanoKeyService;
import com.surprising.wallet.chain.near.NearKeyService;
import com.surprising.wallet.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.chain.solana.SolanaKeyService;
import com.surprising.wallet.chain.sui.SuiKeyService;
import com.surprising.wallet.chain.ton.TonKeyService;
import com.surprising.wallet.sdk.ed25519.Ed25519Chain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Ed25519KeyServicesTest {
    @ParameterizedTest
    @EnumSource(Ed25519Chain.class)
    void normalKeyMaterialPreservesSingleAndTenantDerivation(Ed25519Chain chain) {
        var material = WalletKeyTestFixture.provider();
        var single = switch (chain) {
            case SOLANA -> new SolanaKeyService(material).derive(17);
            case TON -> new TonKeyService(material).derive(17);
            case APTOS -> new AptosKeyService(material).derive(17);
            case SUI -> new SuiKeyService(material).derive(17);
            case CARDANO -> new CardanoKeyService(material).derive(17);
            case POLKADOT -> new PolkadotKeyService(material).derive(17);
            case NEAR -> new NearKeyService(material).derive(17);
        };
        var tenant = switch (chain) {
            case SOLANA -> new SolanaKeyService(material).derive(42, 3, 17);
            case TON -> new TonKeyService(material).derive(42, 3, 17);
            case APTOS -> new AptosKeyService(material).derive(42, 3, 17);
            case SUI -> new SuiKeyService(material).derive(42, 3, 17);
            case CARDANO -> new CardanoKeyService(material).derive(42, 3, 17);
            case POLKADOT -> new PolkadotKeyService(material).derive(42, 3, 17);
            case NEAR -> new NearKeyService(material).derive(42, 3, 17);
        };
        assertArrayEquals(material.ed25519().derive(chain, 17).publicKey(), single.publicKey());
        assertEquals(chain.pathString(17), single.derivationPath());
        assertArrayEquals(material.ed25519().derive(chain, 3, 42, 17).publicKey(), tenant.publicKey());
        assertEquals(chain.pathString(3, 42, 17), tenant.derivationPath());
    }
}
