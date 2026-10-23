package com.surprising.wallet.config;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 从角色公钥派生 2-of-3 地址；调用方显式指定网络和 m/44/coinType/biz/userId/index 坐标。 */
@Component
public class PubKeyConfig {
    private static final HexFormat HEX = HexFormat.of();
    private final WalletKeyMaterialProvider keyMaterial;

    public PubKeyConfig(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = Objects.requireNonNull(keyMaterial, "keyMaterial");
    }

    public Bip32Node node2() {
        return keyMaterial.sig2PublicRoot();
    }

    public AddressMetadata genThreeTwoAddressMetadata(
            NetworkParameters params, int coinType, int userId, int biz, int index) {
        var generator = new SegwitMultiSignAddressGenerator();
        ECKey first = childKey(keyMaterial.sig1PublicRoot(), coinType, biz, userId, index);
        ECKey second = childKey(keyMaterial.sig2PublicRoot(), coinType, biz, userId, index);
        ECKey recovery = childKey(keyMaterial.recoveryPublicRoot(), coinType, biz, userId, index);
        generator.addECKey(first);
        generator.addECKey(second);
        generator.addECKey(recovery);
        String address = generator.generateAddress(params, 2);
        return new AddressMetadata(address, path(coinType, biz, userId, index), "",
                generator.getWitnessScriptStr(), publicKeys(first, second, recovery));
    }

    public AddressMetadata genLegacyThreeTwoAddressMetadata(
            NetworkParameters params, int coinType, int userId, int biz, int index) {
        var generator = new LegacyMultiSignAddressGenerator();
        ECKey first = childKey(keyMaterial.sig1PublicRoot(), coinType, biz, userId, index);
        ECKey second = childKey(keyMaterial.sig2PublicRoot(), coinType, biz, userId, index);
        ECKey recovery = childKey(keyMaterial.recoveryPublicRoot(), coinType, biz, userId, index);
        generator.addECKey(first);
        generator.addECKey(second);
        generator.addECKey(recovery);
        String address = generator.generateAddress(params, 2);
        return new AddressMetadata(address, path(coinType, biz, userId, index),
                generator.getRedeemScriptHex(), "", publicKeys(first, second, recovery));
    }

    private static ECKey childKey(Bip32Node node, int coinType, int biz, int userId, int index) {
        return node.getChild(44).getChild(coinType).getChild(biz).getChild(userId).getChild(index).getEcKey();
    }

    private static String path(int coinType, int biz, int userId, int index) {
        return String.format("m/44/%d/%d/%d/%d", coinType, biz, userId, index);
    }

    private static String publicKeys(ECKey first, ECKey second, ECKey recovery) {
        return Stream.of(first, second, recovery).map(key -> HEX.formatHex(key.getPubKey()))
                .collect(Collectors.joining(","));
    }

    public record AddressMetadata(
            String address, String path, String redeemScript, String witnessScript, String publicKeys) { }
}
