package com.surprising.wallet.sig.first.service;
import com.alibaba.fastjson.JSONArray;import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.*;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import jakarta.annotation.PostConstruct;import org.bitcoinj.base.Coin;import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.*;import org.springframework.stereotype.Component;
import java.math.BigDecimal;import java.util.*;
@Component public class BchFirstSignService implements ISignService{
 @Autowired PubKeyConfig pub; @Value("${atomex.wallet.masterKey}") String master;
 @Value("${atomex.wallet.network:test}") String network; Bip32Node root;
 @PostConstruct public void init(){root=Bip32Node.decode(master);}
 @Override public CurrencyEnum getCurrency(){return CurrencyEnum.BCH;}
 @Override public void signTransaction(WithdrawTransaction tx){
  JSONObject s=JSONObject.parseObject(tx.getSignature());
  try{
   List<UtxoTransaction> us=s.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
   List<Address> as=s.getJSONArray("addresses").toJavaList(Address.class);
   List<WithdrawRecord> rs=s.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
   var b=new BitcoinCashMultisigTransactionBuilder(networkParameters());
   List<ECKey> keys=new ArrayList<>(); List<String> redeems=new ArrayList<>(); BigDecimal d=CurrencyEnum.BCH.getDecimal();
   for(int i=0;i<us.size();i++){String r=pub.genRedeemScript(as.get(i));redeems.add(r);keys.add(node(as.get(i)).getEcKey());b.addInput(us.get(i).getTxId(),us.get(i).getSeq(),r,Coin.valueOf(us.get(i).getBalance().multiply(d).longValueExact()));}
   long total=tx.getBalance().multiply(d).longValueExact(),sent=rs.stream().map(WithdrawRecord::getBalance).reduce(BigDecimal.ZERO,BigDecimal::add).multiply(d).longValueExact();
   long rate=s.getLongValue("feeRate");if(rate<=0)rate=1;
   long bytes=P2shMultisigFeeCalculator.estimateBytes(us.size(),rs.size()+1,2,3),fee=bytes*rate,change=total-sent-fee;
   if(change<0)throw new IllegalArgumentException("insufficient BCH input");
   for(var r:rs){long v=r.getBalance().multiply(d).longValueExact();if(v<BitcoinCashFeePolicy.DUST_THRESHOLD_SAT)throw new IllegalArgumentException("BCH dust");b.addOutput(r.getAddress(),Coin.valueOf(v));}
   if(change>=BitcoinCashFeePolicy.DUST_THRESHOLD_SAT)b.addOutput(s.getString("changeAddress"),Coin.valueOf(change));else fee+=change;
   s.put("firstSignTx",b.buildFirstSign(keys));s.put("scriptType","bch-p2sh");s.put("fee",fee);s.put("estimatedBytes",bytes);s.put("valid",true);
   JSONArray a=new JSONArray();redeems.forEach(a::add);s.put("redeemScripts",a);
  }catch(Throwable e){s.put("valid",false);s.put("error",e.getMessage());}
  tx.setSignature(s.toJSONString());
 }
 private Bip32Node node(Address a){return root.getChild(44).getChild(145).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex());}
 private BitcoinCashNetworkParameters networkParameters(){
  return "main".equalsIgnoreCase(network)||"mainnet".equalsIgnoreCase(network)
    ?BitcoinCashNetworkParameters.mainnet():BitcoinCashNetworkParameters.testnet();
 }
}
