package com.surprising.wallet.sig.first.config;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata; import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.utils.Constants; import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import lombok.Data; import lombok.extern.slf4j.Slf4j; import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Autowired; import org.springframework.stereotype.Component;
@Slf4j @Component @Data
public class PubKeyConfig {
    private final WalletKeyMaterialProvider keyMaterial;
    private final Bip32Node[] testNodes;
    @Autowired public PubKeyConfig(WalletKeyMaterialProvider keyMaterial){this.keyMaterial=keyMaterial;this.testNodes=null;}
    public PubKeyConfig(Bip32Node node1,Bip32Node node2,Bip32Node node3){this.keyMaterial=null;this.testNodes=new Bip32Node[]{node1,node2,node3};}
    private Bip32Node node1(){return testNodes==null?keyMaterial.sig1PublicRoot():testNodes[0];}
    private Bip32Node node2(){return testNodes==null?keyMaterial.sig2PublicRoot():testNodes[1];}
    private Bip32Node node3(){return testNodes==null?keyMaterial.recoveryPublicRoot():testNodes[2];}
    public String genWitnessScript(Address a, AssetRuntimeMetadata ce){
        ECKey k1=node1().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k2=node2().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k3=node3().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        SegwitMultiSignAddressGenerator g=new SegwitMultiSignAddressGenerator(); g.addECKey(k1);g.addECKey(k2);g.addECKey(k3);
        String addr=g.generateAddress(Constants.NET_PARAMS,2);
        log.info("P2WSH witnessScript: bech32={}, addrRef={}",addr,a.getAddress());
        return g.getWitnessScriptStr();
    }
    public String genRedeemScript(Address a, AssetRuntimeMetadata ce){
        ECKey k1=node1().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k2=node2().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
        ECKey k3=node3().getChild(44).getChild(ce.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex()).getEcKey();
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
