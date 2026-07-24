package com.surprising.wallet.service.config;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public
class PubKeyConfig {
    private static final HexFormat HEX = HexFormat.of();
    private final WalletKeyMaterialProvider keyMaterial;
    private final Bip32Node[] testNodes;

    @Autowired
    public PubKeyConfig(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
        this.testNodes = null;
    }
    public PubKeyConfig(Bip32Node node1, Bip32Node node2, Bip32Node node3) {
        this.keyMaterial = null;
        this.testNodes = new Bip32Node[]{node1, node2, node3};
    }
    public Bip32Node node1() {
        return testNodes == null ? keyMaterial.sig1PublicRoot() : testNodes[0];
    }
    public Bip32Node node2() {
        return testNodes == null ? keyMaterial.sig2PublicRoot() : testNodes[1];
    }
    public Bip32Node node3() {
        return testNodes == null ? keyMaterial.recoveryPublicRoot() : testNodes[2];
    }
    public String genThree_TwoAddress(int currency, int userId, int biz, int index) {
        return genThreeTwoAddressMetadata(currency, userId, biz, index).address;
    }
    public AddressMetadata genThreeTwoAddressMetadata(int currency, int userId, int biz, int index) {
        return genThreeTwoAddressMetadata(Constants.NET_PARAMS, currency, userId, biz, index);
    }
    public AddressMetadata genThreeTwoAddressMetadata(NetworkParameters params, int currency, int userId, int biz, int index) {
        return genThreeTwoAddressMetadata(params, currency, userId, biz, index, node1(), node2(), node3());
    }

    public static AddressMetadata genThreeTwoAddressMetadata(NetworkParameters params, int currency, int userId,
                                                             int biz, int index, Bip32Node node1,
                                                             Bip32Node node2, Bip32Node node3) {
        SegwitMultiSignAddressGenerator g = new SegwitMultiSignAddressGenerator();
        ECKey key1 = childKey(node1, currency, biz, userId, index);
        ECKey key2 = childKey(node2, currency, biz, userId, index);
        ECKey key3 = childKey(node3, currency, biz, userId, index);
        g.addECKey(key1);
        g.addECKey(key2);
        g.addECKey(key3);
        String address = g.generateAddress(params, 2);
        String pubKeys = Stream.of(key1, key2, key3)
                .map(key -> HEX.formatHex(key.getPubKey()))
                .collect(Collectors.joining(","));
        String path = String.format("m/44/%d/%d/%d/%d", currency, biz, userId, index);
        return new AddressMetadata(address, path, "", g.getWitnessScriptStr(), pubKeys);
    }

    public AddressMetadata genLegacyThreeTwoAddressMetadata(
            NetworkParameters params, int coinType, int userId, int biz, int index) {
        return genLegacyThreeTwoAddressMetadata(params, coinType, userId, biz, index, node1(), node2(), node3());
    }

    public static AddressMetadata genLegacyThreeTwoAddressMetadata(
            NetworkParameters params, int coinType, int userId, int biz, int index,
            Bip32Node node1, Bip32Node node2, Bip32Node node3) {
        LegacyMultiSignAddressGenerator generator = new LegacyMultiSignAddressGenerator();
        ECKey key1 = childKey(node1, coinType, biz, userId, index);
        ECKey key2 = childKey(node2, coinType, biz, userId, index);
        ECKey key3 = childKey(node3, coinType, biz, userId, index);
        generator.addECKey(key1);
        generator.addECKey(key2);
        generator.addECKey(key3);
        String address = generator.generateAddress(params, 2);
        String pubKeys = Stream.of(key1, key2, key3)
                .map(key -> HEX.formatHex(key.getPubKey()))
                .collect(Collectors.joining(","));
        String path = String.format("m/44/%d/%d/%d/%d", coinType, biz, userId, index);
        return new AddressMetadata(address, path, generator.getRedeemScriptHex(), "", pubKeys);
    }
    private static ECKey childKey(Bip32Node node, int currency, int biz, int userId, int index) {
        return node.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey();
    }
    public static class AddressMetadata {
        private final String address;
        private final String path;
        private final String redeemScript;
        private final String witnessScript;
        private final String publicKeys;

        private AddressMetadata(String address, String path, String redeemScript,
                                String witnessScript, String publicKeys) {
            this.address = address;
            this.path = path;
            this.redeemScript = redeemScript;
            this.witnessScript = witnessScript;
            this.publicKeys = publicKeys;
        }

        public String getAddress() {
            return address;
        }

        public String getPath() {
            return path;
        }

        public String getRedeemScript() {
            return redeemScript;
        }

        public String getWitnessScript() {
            return witnessScript;
        }

        public String getPublicKeys() {
            return publicKeys;
        }
    }
}
