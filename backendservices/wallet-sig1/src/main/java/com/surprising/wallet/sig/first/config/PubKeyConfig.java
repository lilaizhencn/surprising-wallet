package com.surprising.wallet.sig.first.config;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.MultiSignAddressGenerator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * @author atomex
 */
@Slf4j
@Component
@Data
public class PubKeyConfig {
    @Value("${atomex.wallet.pubKey1}")
    private String pub1;
    @Value("${atomex.wallet.pubKey2}")
    private String pub2;
    @Value("${atomex.wallet.pubKey3}")
    private String pub3;

    private Bip32Node NODE1;
    private Bip32Node NODE2;
    private Bip32Node NODE3;


    @PostConstruct
    public void init() {
        NODE1 = Bip32Node.decode(pub1);
        NODE2 = Bip32Node.decode(pub2);
        NODE3 = Bip32Node.decode(pub3);
    }

    public String genScript(Address address) {
        CurrencyEnum currencyEnum = CurrencyEnum.parseName(address.getCurrency());
        MultiSignAddressGenerator generator = new MultiSignAddressGenerator();
        ECKey ecKey1 = NODE1.getChild(44).getChild(currencyEnum.getIndex()).getChild(address.getBiz()).getChild(address.getUserId().intValue()).getChild(address.getIndex()).getEcKey();
        ECKey ecKey2 = NODE2.getChild(44).getChild(currencyEnum.getIndex()).getChild(address.getBiz()).getChild(address.getUserId().intValue()).getChild(address.getIndex()).getEcKey();
        ECKey ecKey3 = NODE3.getChild(44).getChild(currencyEnum.getIndex()).getChild(address.getBiz()).getChild(address.getUserId().intValue()).getChild(address.getIndex()).getEcKey();
        generator.addECKey(ecKey1);
        generator.addECKey(ecKey2);
        generator.addECKey(ecKey3);
        //生成redeem
        String addressGen = generator.generateAddress(Constants.NET_PARAMS, 2);
        log.info("第一次签名服务 生成redeem 当前地址:{} 原始地址:{}", addressGen, address.getAddress());
        return generator.getScriptStr();
    }

}
