package com.surprising.wallet.sig.second.impl;
import com.alibaba.fastjson.JSONObject;import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.*;import com.surprising.wallet.sig.second.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;import java.util.*;
@Component public class BchSecondSignService implements ISignService{
 @Value("${atomex.wallet.network:test}") String network;
 @Override public CurrencyEnum getCurrency(){return CurrencyEnum.BCH;}
 @Override public String signTransaction(WithdrawTransaction tx){
  JSONObject s=JSONObject.parseObject(tx.getSignature());
  try{
   if(!"bch-p2sh".equals(s.getString("scriptType")))throw new IllegalArgumentException("not BCH P2SH");
   List<Address> as=s.getJSONArray("addresses").toJavaList(Address.class);
   List<org.bitcoinj.crypto.ECKey> keys=new ArrayList<>();for(var a:as)keys.add(BipNodeUtil.getBipNODE(a).getEcKey());
   var b=new BitcoinCashMultisigTransactionBuilder(networkParameters());
   return b.buildSecondSign(s.getString("firstSignTx"),keys,s.getJSONArray("redeemScripts").toJavaList(String.class));
  }catch(Throwable e){s.put("valid",false);s.put("error",e.getMessage());tx.setSignature(s.toJSONString());return"";}
 }
 private BitcoinCashNetworkParameters networkParameters(){
  return "main".equalsIgnoreCase(network)||"mainnet".equalsIgnoreCase(network)
    ?BitcoinCashNetworkParameters.mainnet():BitcoinCashNetworkParameters.testnet();
 }
}
