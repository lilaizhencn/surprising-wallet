package com.surprising.wallet.service.chain.xrp;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.config.PubKeyConfig;
import org.bitcoinj.crypto.ECKey;
import org.spongycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;
import org.xrpl.xrpl4j.codec.addresses.KeyType;
import org.xrpl.xrpl4j.codec.addresses.UnsignedByteArray;
import org.xrpl.xrpl4j.crypto.keys.PrivateKey;
import org.xrpl.xrpl4j.crypto.keys.PublicKey;

import java.util.Arrays;

@Service
public
class XrpKeyService {
    private final PubKeyConfig pubKeyConfig;
    private final AccountSecp256k1KeyService signerKeyService;
    public XrpKeyService(PubKeyConfig pubKeyConfig, AccountSecp256k1KeyService signerKeyService) {
        this.pubKeyConfig = pubKeyConfig;
        this.signerKeyService = signerKeyService;
    }
    public String address(AccountChainProfile profile, long userId, int biz, long derivationIndex) {
        ECKey ecKey = pubKeyConfig.node2().getChild(44)
                .getChild(ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType()))
                .getChild(biz)
                .getChild(Math.toIntExact(userId))
                .getChild(Math.toIntExact(derivationIndex))
                .getEcKey();
        return address(ecKey);
    }
    public PrivateKey privateKey(AccountChainProfile profile, ChainAddressRecord address) {
        return privateKey(signerKeyService.key(profile, address));
    }
    public static String address(ECKey ecKey) {
        return publicKey(ecKey).deriveAddress().value();
    }
    public static PublicKey publicKey(ECKey ecKey) {
        return PublicKey.fromBase16EncodedPublicKey(Hex.toHexString(ecKey.getPubKey()).toUpperCase());
    }
    public static PrivateKey privateKey(ECKey ecKey) {
        byte[] privateBytes = ecKey.getPrivKeyBytes();
        try {
            return PrivateKey.fromNaturalBytes(UnsignedByteArray.of(privateBytes), KeyType.SECP256K1);
        } finally {
            Arrays.fill(privateBytes, (byte) 0);
        }
    }
}
