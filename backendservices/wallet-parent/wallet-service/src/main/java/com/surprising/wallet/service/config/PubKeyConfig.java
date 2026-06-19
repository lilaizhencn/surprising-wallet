package com.surprising.wallet.service.config;

import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class PubKeyConfig {
    public Bip32Node NODE1, NODE2, NODE3;
    @Value("${atomex.wallet.pubKey1}") private String pub1;
    @Value("${atomex.wallet.pubKey2}") private String pub2;
    @Value("${atomex.wallet.pubKey3}") private String pub3;

    @PostConstruct public void init() { NODE1=Bip32Node.decode(pub1); NODE2=Bip32Node.decode(pub2); NODE3=Bip32Node.decode(pub3); }

    public String genThree_TwoAddress(int currency, int userId, int biz, int index) {
        SegwitMultiSignAddressGenerator g = new SegwitMultiSignAddressGenerator();
        g.addECKey(NODE1.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey());
        g.addECKey(NODE2.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey());
        g.addECKey(NODE3.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey());
        return g.generateAddress(Constants.NET_PARAMS, 2);
    }
}
