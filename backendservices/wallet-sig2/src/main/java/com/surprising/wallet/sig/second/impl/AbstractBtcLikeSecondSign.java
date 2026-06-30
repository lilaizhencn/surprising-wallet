package com.surprising.wallet.sig.second.impl;
import com.alibaba.fastjson.JSONArray;import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;import com.surprising.wallet.sdk.bitcoinj.core.WitnessSigner;
import com.surprising.wallet.sig.second.BipNodeUtil;import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;import org.bitcoinj.base.Coin;import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;import org.bitcoinj.core.TransactionInput;import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.crypto.ECKey;import org.bitcoinj.crypto.TransactionSignature;import org.bitcoinj.script.Script;
import java.nio.ByteBuffer;import java.util.HexFormat;import java.util.List;
@Slf4j
abstract public class AbstractBtcLikeSecondSign implements ISignService {
    private static final HexFormat HEX=HexFormat.of(); private final WitnessSigner ws=new WitnessSigner();
    protected NetworkParameters getNetworkParameters(){return Constants.NET_PARAMS;}
    @Override public String signTransaction(WithdrawTransaction tx){
        AssetRuntimeMetadata currency=AssetRuntimeMetadata.fromTransaction(tx);
        JSONObject sj=JSONObject.parseObject(tx.getSignature());
        String fst=sj.getString("firstSignTx"); if(fst==null||fst.isEmpty()){sj.put("valid",false);sj.put("error","no firstSignTx");tx.setSignature(sj.toJSONString());return"";}
        if(!"p2wsh".equals(sj.getString("scriptType"))){sj.put("valid",false);sj.put("error","not p2wsh");tx.setSignature(sj.toJSONString());return"";}
        List<Address> ads=sj.getJSONArray("addresses").toJavaList(Address.class);
        JSONArray uva=sj.getJSONArray("utxoValues"); if(uva==null||uva.isEmpty()){sj.put("valid",false);sj.put("error","no utxoValues");tx.setSignature(sj.toJSONString());return"";}
        try{Transaction stx=Transaction.read(ByteBuffer.wrap(HEX.parseHex(fst)));
            for(int i=0;i<stx.getInputs().size();i++){TransactionInput in=stx.getInput(i); TransactionWitness ew=in.getWitness();
                if(ew==null){sj.put("valid",false);sj.put("error","no witness");tx.setSignature(sj.toJSONString());return"";}
                int pc=ew.getPushCount(); if(pc<3){sj.put("valid",false);sj.put("error","bad witness");tx.setSignature(sj.toJSONString());return"";}
                byte[] wsb=ew.getPush(pc-1); if(wsb==null||wsb.length==0){sj.put("valid",false);sj.put("error","no witnessScript");tx.setSignature(sj.toJSONString());return"";}
                Script script=new Script(wsb); long vs=uva.getLongValue(i); if(vs<=0){sj.put("valid",false);sj.put("error","bad utxoValue");tx.setSignature(sj.toJSONString());return"";}
                Coin uv=Coin.valueOf(vs); ECKey ek=BipNodeUtil.getBipNODE(ads.get(i),currency).getEcKey();
                TransactionSignature s2=stx.calculateWitnessSignature(i,ek,script,uv,Transaction.SigHash.ALL,false);
                int required=script.getNumberOfSignaturesRequiredToSpend();
                stx.replaceInput(i,in.withWitness(ws.mergeMultisigWitness(stx,i,ew,s2,ek,script,uv,required)));}
            String hex=HEX.formatHex(stx.bitcoinSerialize()); log.info("P2WSH second sign done: txid={}",stx.getTxId()); return hex;
        }catch(Exception e){log.error("P2WSH second sign error",e); sj.put("valid",false);sj.put("error",e.getMessage());tx.setSignature(sj.toJSONString());return"";}
    }
}
