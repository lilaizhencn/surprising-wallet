package com.surprising.wallet.service.config;

import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class PubKeyConfig {
    private static final HexFormat HEX = HexFormat.of();
    public Bip32Node NODE1, NODE2, NODE3;
    @Value("${atomex.wallet.pubKey1}") private String pub1;
    @Value("${atomex.wallet.pubKey2}") private String pub2;
    @Value("${atomex.wallet.pubKey3}") private String pub3;

    @PostConstruct public void init() { NODE1=Bip32Node.decode(pub1); NODE2=Bip32Node.decode(pub2); NODE3=Bip32Node.decode(pub3); }

    public String genThree_TwoAddress(int currency, int userId, int biz, int index) {
        return genThreeTwoAddressMetadata(currency, userId, biz, index).address;
    }

    public AddressMetadata genThreeTwoAddressMetadata(int currency, int userId, int biz, int index) {
        SegwitMultiSignAddressGenerator g = new SegwitMultiSignAddressGenerator();
        ECKey key1 = childKey(NODE1, currency, biz, userId, index);
        ECKey key2 = childKey(NODE2, currency, biz, userId, index);
        ECKey key3 = childKey(NODE3, currency, biz, userId, index);
        g.addECKey(key1);
        g.addECKey(key2);
        g.addECKey(key3);
        String address = g.generateAddress(Constants.NET_PARAMS, 2);
        String pubKeys = Stream.of(key1, key2, key3)
                .map(key -> HEX.formatHex(key.getPubKey()))
                .collect(Collectors.joining(","));
        String path = String.format("m/44/%d/%d/%d/%d", currency, biz, userId, index);
        return new AddressMetadata(address, path, g.getWitnessScriptStr(), pubKeys);
    }

    private ECKey childKey(Bip32Node node, int currency, int biz, int userId, int index) {
        return node.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey();
    }

    public static class AddressMetadata {
        private final String address;
        private final String path;
        private final String witnessScript;
        private final String publicKeys;

        private AddressMetadata(String address, String path, String witnessScript, String publicKeys) {
            this.address = address;
            this.path = path;
            this.witnessScript = witnessScript;
            this.publicKeys = publicKeys;
        }

        public String getAddress() {
            return address;
        }

        public String getPath() {
            return path;
        }

        public String getWitnessScript() {
            return witnessScript;
        }

        public String getPublicKeys() {
            return publicKeys;
        }
    }
}
