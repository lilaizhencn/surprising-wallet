package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.BchCommand;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import jakarta.annotation.PostConstruct;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;

@Component
public class BchWallet extends AbstractBtcLikeWallet {
    @Autowired private BchCommand bchCommand;
    @Value("${atomex.bch.network:testnet}") private String network;
    private BitcoinLikeChainProfile profile;

    @PostConstruct public void init(){
        setCommand(bchCommand);
        profile=chainJdbcRepository.findBitcoinLikeProfile("BCH",isMain()?"mainnet":"testnet")
                .orElseThrow(()->new IllegalStateException("missing BCH chain_profile"));
        if(profile.getRuntimeCurrencyId()!=getCurrency().getIndex()||profile.getBip44CoinType()!=145)
            throw new IllegalStateException("BCH currency/profile mismatch");
    }
    @Override public CurrencyEnum getCurrency(){return CurrencyEnum.BCH;}
    @Override public NetworkParameters getNetworkParameters(){return isMain()?BitcoinCashNetworkParameters.mainnet():BitcoinCashNetworkParameters.testnet();}
    @Override protected int getBip44CoinType(){return profile.getBip44CoinType();}
    @Override protected Address buildAddress(Long userId,Integer biz,int index){
        PubKeyConfig.AddressMetadata m=pubKeyConfig.genLegacyThreeTwoAddressMetadata(getNetworkParameters(),145,userId.intValue(),biz,index);
        var params=(BitcoinCashNetworkParameters)getNetworkParameters();
        String cash=BitcoinCashAddressCodec.fromLegacy(LegacyAddress.fromBase58(params,m.getAddress()),params.cashPrefix());
        return Address.builder().address(cash).network(getNetworkName()).scriptType("P2SH")
                .redeemScript(m.getRedeemScript()).witnessScript("").derivationPath(m.getPath())
                .publicKeys(m.getPublicKeys()).biz(biz).currency("bch").userId(userId).index(index)
                .balance(BigDecimal.ZERO).nonce(0).status((byte)0)
                .createDate(Date.from(Instant.now())).updateDate(Date.from(Instant.now())).build();
    }
    @Override protected String normalizeScannedAddress(String address){
        if(address==null)return null;
        var params=(BitcoinCashNetworkParameters)getNetworkParameters();
        if(address.contains(":")) return address.toLowerCase();
        return BitcoinCashAddressCodec.fromLegacy(LegacyAddress.fromBase58(params,address),params.cashPrefix());
    }
    @Override public boolean checkAddress(String address){
        try{
            var params=(BitcoinCashNetworkParameters)getNetworkParameters();
            if(address.contains(":")||address.toLowerCase().startsWith(params.cashPrefix())){
                BitcoinCashAddressCodec.decode(params.cashPrefix(),address); return true;
            }
            LegacyAddress.fromBase58(params,address); return true;
        }catch(Throwable e){return false;}
    }
    private boolean isMain(){return "main".equalsIgnoreCase(network)||"mainnet".equalsIgnoreCase(network);}
}
