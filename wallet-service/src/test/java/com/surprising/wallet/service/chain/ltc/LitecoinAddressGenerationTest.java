package com.surprising.wallet.service.chain.ltc;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.params.TestNet3Params;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitecoinAddressGenerationTest {
    private static final String XPUB_1 = "tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB";
    private static final String XPUB_2 = "tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT";
    private static final String XPUB_3 = "tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4";

    @Test
    void sameRootKeysShouldGenerateLitecoinTestnetP2wshAddress() {
        String first = addressAt(0);
        String second = addressAt(1);

        assertTrue(first.startsWith("tltc1"));
        assertTrue(second.startsWith("tltc1"));
        assertNotEquals(first, second);
        assertThrows(AddressFormatException.class, () -> Address.fromString(TestNet3Params.get(), first));
    }

    private static String addressAt(int index) {
        SegwitMultiSignAddressGenerator generator = new SegwitMultiSignAddressGenerator();
        generator.addECKey(Bip32Node.decode(XPUB_1).getChild(44).getChild(2).getChild(1).getChild(9001).getChild(index).getEcKey());
        generator.addECKey(Bip32Node.decode(XPUB_2).getChild(44).getChild(2).getChild(1).getChild(9001).getChild(index).getEcKey());
        generator.addECKey(Bip32Node.decode(XPUB_3).getChild(44).getChild(2).getChild(1).getChild(9001).getChild(index).getEcKey());
        return generator.generateAddress(LitecoinNetworkParameters.testnet(), 2);
    }
}
