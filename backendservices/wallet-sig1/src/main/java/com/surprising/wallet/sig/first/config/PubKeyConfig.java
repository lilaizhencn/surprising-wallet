package com.surprising.wallet.sig.first.config;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata; import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.utils.Constants; import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import lombok.Data; import lombok.extern.slf4j.Slf4j; import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Component; import jakarta.annotation.PostConstruct;
@Slf4j @Component @Data
public class PubKeyConfig {
    @Value("${sw.wallet.pubKey1}") private String pub1;
    @Value("${sw.wallet.pubKey2}") private String pub2;
    @Value("${sw.wallet.pubKey3}") private String pub3;
    private Bip32Node NODE1,NODE2,NODE3;
    @PostConstruct public void init(){NODE1=Bip32Node.decode(pub1); NODE2=Bip32Node.decode(pub2); NODE3=Bip32Node.decode(pub3);}
    public String genWitnessScript(Address a, AssetRuntimeMetadata ce){
        ECKey k1=NODE1.getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k2=NODE2.getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k3=NODE3.getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        SegwitMultiSignAddressGenerator g=new SegwitMultiSignAddressGenerator(); g.addECKey(k1);g.addECKey(k2);g.addECKey(k3);
        String addr=g.generateAddress(Constants.NET_PARAMS,2);
        log.info("P2WSH witnessScript: bech32={}, addrRef={}",addr,a.getAddress());
        return g.getWitnessScriptStr();
    }
    public String genRedeemScript(Address a, AssetRuntimeMetadata ce){
        ECKey k1=NODE1.getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k2=NODE2.getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k3=NODE3.getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        LegacyMultiSignAddressGenerator g=new LegacyMultiSignAddressGenerator(); g.addECKey(k1);g.addECKey(k2);g.addECKey(k3);
        g.generateAddress(com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters.testnet(),2);
        String redeemScript=g.getRedeemScriptHex();
        if(a.getRedeemScript()!=null&&!a.getRedeemScript().isBlank()&&!a.getRedeemScript().equalsIgnoreCase(redeemScript)){
            throw new IllegalStateException(
                    "stored redeemScript does not match derived multisig keys");
        }
        return redeemScript;
    }
}
