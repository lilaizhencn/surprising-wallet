package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotWalletAddressServiceTest {
    private static final String XPUB_2 =
            "tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT";

    @Test
    void hyperCoreUsesSecp256k1AccountDerivation() {
        Bip32Node node = Bip32Node.decode(XPUB_2);
        PubKeyConfig pubKeyConfig = new PubKeyConfig(node, node, node);
        HotWalletAddressService service = new HotWalletAddressService(
                null, pubKeyConfig, null, null, null, null, null, null, null, null, null);
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("HYPERCORE")
                .network("testnet")
                .family("hypercore")
                .bip44CoinType(60)
                .nativeSymbol("USDC")
                .build();

        ChainAddressRecord address = service.deriveAddress(profile, 1, 0, 0, "DEPOSIT");

        assertEquals("HYPERCORE", address.getChain());
        assertEquals("USDC", address.getAssetSymbol());
        assertEquals("m/44/60/0/1/0", address.getDerivationPath());
        assertEquals(address.getAddress(), address.getOwnerAddress());
        assertTrue(address.getAddress().matches("^0x[0-9a-f]{40}$"));
    }
}
