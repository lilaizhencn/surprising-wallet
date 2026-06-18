package com.surprising.wallet.service.config;

import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.MultiSignAddressGenerator;
import org.bitcoinj.core.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author atomex
 * @data 28/03/2018
 */
@Component
public class PubKeyConfig {

    public Bip32Node NODE1;
    public Bip32Node NODE2;
    public Bip32Node NODE3;
    @Value("${atomex.wallet.pubKey1}")
    private String pub1;
    @Value("${atomex.wallet.pubKey2}")
    private String pub2;
    @Value("${atomex.wallet.pubKey3}")
    private String pub3;

    @PostConstruct
    public void init() {
        NODE1 = Bip32Node.decode(pub1);
        NODE2 = Bip32Node.decode(pub2);
        NODE3 = Bip32Node.decode(pub3);
    }

    //hd的公钥推导path: bip44-currency-biz-userId-index
    public String genThree_TwoAddress(int currency, int userId, int biz, int index) {
        MultiSignAddressGenerator generator = new MultiSignAddressGenerator();
        ECKey ecKey1 = NODE1.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey();
        ECKey ecKey2 = NODE2.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey();
        ECKey ecKey3 = NODE3.getChild(44).getChild(currency).getChild(biz).getChild(userId).getChild(index).getEcKey();
        generator.addECKey(ecKey1);
        generator.addECKey(ecKey2);
        generator.addECKey(ecKey3);
        return generator.generateAddress(Constants.NET_PARAMS, 2);
    }

    /**
     * 生成redeem script
     *
     * @param currency
     * @param address
     * @return
     */
//    public String genScript(final int currency, final Address address) {
//        final MultiSignAddressGenerator generator = new MultiSignAddressGenerator();
//        final ECKey ecKey1 = this.NODE1.getChild(44).getChild(currency).getChild(address.getBiz()).getChild(address.getUserId().intValue()).getChild(address.getIndex()).getEcKey();
//        final ECKey ecKey2 = this.NODE2.getChild(44).getChild(currency).getChild(address.getBiz()).getChild(address.getUserId().intValue()).getChild(address.getIndex()).getEcKey();
//        final ECKey ecKey3 = this.NODE3.getChild(44).getChild(currency).getChild(address.getBiz()).getChild(address.getUserId().intValue()).getChild(address.getIndex()).getEcKey();
//        generator.addECKey(ecKey1);
//        generator.addECKey(ecKey2);
//        generator.addECKey(ecKey3);
//        generator.generateAddress(Constants.NET_PARAMS, 2);
//        final String scriptStr = generator.getScriptStr();
//        return scriptStr;
//    }

}
