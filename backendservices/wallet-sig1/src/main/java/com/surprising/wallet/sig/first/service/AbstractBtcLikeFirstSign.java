package com.surprising.wallet.sig.first.service;
import com.alibaba.fastjson.JSONArray;import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.common.utils.Constants;import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessTransactionBuilder;import com.surprising.wallet.sig.first.config.PubKeyConfig;
import lombok.extern.slf4j.Slf4j;import org.bitcoinj.base.Coin;import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;import java.math.BigDecimal;import java.util.ArrayList;import java.util.List;
@Slf4j
abstract public class AbstractBtcLikeFirstSign implements ISignService {
    public Bip32Node NODE; @Autowired protected PubKeyConfig pubKeyConfig; @Value("${atomex.wallet.masterKey}") String masterKey;
    private static final long DEFAULT_FEE_RATE = 10L;
    @PostConstruct public void init(){NODE=Bip32Node.decode(masterKey);}
    protected NetworkParameters getNetworkParameters(){return Constants.NET_PARAMS;}
    @Override public void signTransaction(WithdrawTransaction transaction){
        WitnessTransactionBuilder wtxBuilder=new WitnessTransactionBuilder(getNetworkParameters());
        JSONObject signature=JSONObject.parseObject(transaction.getSignature());
        try{
            List<UtxoTransaction> utxos=signature.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
            List<Address> addresses=signature.getJSONArray("addresses").toJavaList(Address.class);
            List<WithdrawRecord> records=signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
            int inputCount=utxos.size();
            List<String> witnessScriptHexes=new ArrayList<>(inputCount);
            List<Coin> utxoValues=new ArrayList<>(inputCount);
            List<ECKey> ecKeys=new ArrayList<>(inputCount);
            BigDecimal cd=getCurrency().getDecimal();
            for(int i=0;i<inputCount;i++){Address a=addresses.get(i); UtxoTransaction u=utxos.get(i);
                Bip32Node n=getBipNODE(a); ECKey ek=n.getEcKey(); ecKeys.add(ek);
                String wsh=pubKeyConfig.genWitnessScript(a); witnessScriptHexes.add(wsh);
                Coin uv=Coin.valueOf(u.getBalance().multiply(cd).longValue()); utxoValues.add(uv);
                wtxBuilder.addInput(u.getTxId(),u.getSeq(),wsh,uv);}
            long total=transaction.getBalance().multiply(cd).longValue(), sent=0;
            for(WithdrawRecord r:records) sent+=r.getBalance().multiply(cd).longValue();
            long feeRate=signature.getLongValue("feeRate"); if(feeRate<=0){feeRate=DEFAULT_FEE_RATE;}
            long vBytes=WitnessTransactionBuilder.estimateVBytes(inputCount,records.size()), fee=vBytes*feeRate;
            long change=total-sent-fee;
            if(change>0){vBytes=WitnessTransactionBuilder.estimateVBytes(inputCount,records.size()+1); fee=vBytes*feeRate; change=total-sent-fee;}
            log.info("P2WSH fee: {} vB * {} sat/vB = {} sat",vBytes,feeRate,fee);
            for(WithdrawRecord r:records) wtxBuilder.addOutput(r.getAddress(),Coin.valueOf(r.getBalance().multiply(cd).longValue()));
            if(change>0){String ca=signature.getString("changeAddress"); if(ca!=null&&!ca.isEmpty()) wtxBuilder.addOutput(ca,Coin.valueOf(change));}
            String firstSignTx=wtxBuilder.buildFirstSign(ecKeys);
            signature.put("firstSignTx",firstSignTx); signature.put("valid",true);
            JSONArray wsa=new JSONArray(); witnessScriptHexes.forEach(wsa::add); signature.put("witnessScripts",wsa);
            JSONArray uva=new JSONArray(); utxoValues.forEach(v->uva.add(v.getValue())); signature.put("utxoValues",uva);
            signature.put("scriptType","p2wsh"); signature.put("fee",fee); signature.put("vBytes",vBytes);
            log.info("P2WSH first sign done: txid={}", wtxBuilder.getHash());
        }catch(Throwable e){log.error("sign error",e); signature.put("valid",false); signature.put("error",e.getMessage());}
        transaction.setSignature(signature.toJSONString());
    }
    @Override abstract public CurrencyEnum getCurrency();
    public Bip32Node getBipNODE(Address a){CurrencyEnum ce=CurrencyEnum.parseName(a.getCurrency());
        return NODE.getChild(44).getChild(ce.getIndex()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex());}
}
