package com.surprising.wallet.sig.first.service;
import com.alibaba.fastjson.JSONArray;import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.utils.Constants;import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessTransactionBuilder;import com.surprising.wallet.sig.first.config.PubKeyConfig;
import lombok.extern.slf4j.Slf4j;import org.bitcoinj.base.Coin;import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;import java.util.ArrayList;import java.util.List;
/**
 * BTC-like 链（BTC、LTC）P2WSH（SegWit 多签）一签抽象基类。
 *
 * <p>负责构建见证交易、计算手续费和找零、生成 P2WSH 见证脚本、
 * 使用 sig1 密钥分片对每个 UTXO 输入生成第一次部分签名。
 *
 * <p>派生路径：m/44'/{coinType}'/{biz}'/{userId}'/{index}
 * 签名使用 2-of-3 多签模型（sig1 + sig2 + recovery）。
 *
 * <p>子类可覆盖 {@link #getNetworkParameters()}、{@link #defaultFeeRate()}、
 * {@link #dustThresholdSat()} 来适配不同链的网络参数。
 */
@Slf4j
abstract public class AbstractBtcLikeFirstSign implements ISignService {

    /** 测试用 BIP32 根节点（为 null 时从 keyMaterial 获取） */
    public Bip32Node NODE;
    /** 多签公钥配置 */
    @Autowired
    protected PubKeyConfig pubKeyConfig;
    /** 密钥材料提供者 */
    @Autowired
    protected WalletKeyMaterialProvider keyMaterial;

    /** 默认费率（sat/vByte），子类可覆盖 */
    private static final long DEFAULT_FEE_RATE = 10L;
    /** 默认粉尘阈值（sat），子类可覆盖 */
    private static final long DUST_THRESHOLD_SAT = 546L;

    /**
     * 返回当前链的网络参数。
     *
     * @return 网络参数，默认从 {@link Constants#NET_PARAMS} 获取
     */
    protected NetworkParameters getNetworkParameters() { return Constants.NET_PARAMS; }

    /**
     * 对 BTC-like P2WSH 提现交易执行第一次签名。
     *
     * <p>处理流程：
     * <ol>
     *   <li>解析 utxos、addresses、withdraw 数组</li>
     *   <li>逐 UTXO 派生 BIP44 子密钥、生成 witness script、构建输入</li>
     *   <li>计算手续费（包含找零优化）和粉尘检查</li>
     *   <li>构建输出（提现 + 找零）</li>
     *   <li>使用 sig1 密钥分片生成 firstSignTx</li>
     *   <li>将 witnessScripts、utxoValues、scriptType 写入 signature</li>
     * </ol>
     *
     * @param transaction 提现交易
     */
    @Override
    public void signTransaction(WithdrawTransaction transaction) {
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
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
            BigDecimal cd=currency.getDecimal();
            for(int i=0;i<inputCount;i++){Address a=addresses.get(i); UtxoTransaction u=utxos.get(i);
                Bip32Node n=getBipNODE(a,currency); ECKey ek=n.getEcKey(); ecKeys.add(ek);
                String wsh=pubKeyConfig.genWitnessScript(a,currency); witnessScriptHexes.add(wsh);
                Coin uv=Coin.valueOf(u.getBalance().multiply(cd).longValue()); utxoValues.add(uv);
                wtxBuilder.addInput(u.getTxId(),u.getSeq(),wsh,uv);}
            long total=transaction.getBalance().multiply(cd).longValue(), sent=0;
            for(WithdrawRecord r:records) sent+=r.getBalance().multiply(cd).longValue();
            long feeRate=signature.getLongValue("feeRate"); if(feeRate<=0){feeRate=defaultFeeRate();}
            long vBytes=WitnessTransactionBuilder.estimateVBytes(inputCount,records.size()), fee=vBytes*feeRate;
            long change=total-sent-fee;
            long dust=dustThresholdSat();
            if(change>=dust){long cv=WitnessTransactionBuilder.estimateVBytes(inputCount,records.size()+1), cf=cv*feeRate, cc=total-sent-cf; if(cc>=dust){vBytes=cv; fee=cf; change=cc;} else {fee=total-sent; change=0;}}
            else if(change>0){fee=total-sent; change=0;}
            if(change<0){throw new IllegalArgumentException("insufficient input for SegWit fee: need "+(sent+fee)+", have "+total);}
            log.info("P2WSH fee: {} vB * {} sat/vB = {} sat",vBytes,feeRate,fee);
            for(WithdrawRecord r:records) { long out=r.getBalance().multiply(cd).longValue(); if(out>0&&out<dust){throw new IllegalArgumentException("withdraw output dust: "+out+" sat");} wtxBuilder.addOutput(r.getAddress(),Coin.valueOf(out)); }
            if(change>0){String ca=signature.getString("changeAddress"); if(ca!=null&&!ca.isEmpty()) wtxBuilder.addOutput(ca,Coin.valueOf(change));}
            long estimatedWeight=com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator.estimateWeight(inputCount,records.size()+(change>0?1:0),2,3);
            String firstSignTx=wtxBuilder.buildFirstSign(ecKeys);
            signature.put("firstSignTx",firstSignTx); signature.put("valid",true);
            JSONArray wsa=new JSONArray(); witnessScriptHexes.forEach(wsa::add); signature.put("witnessScripts",wsa);
            JSONArray uva=new JSONArray(); utxoValues.forEach(v->uva.add(v.getValue())); signature.put("utxoValues",uva);
            signature.put("scriptType","p2wsh"); signature.put("fee",fee); signature.put("estimatedVBytes",vBytes); signature.put("estimatedWeight",estimatedWeight); signature.put("vBytes",vBytes);
            log.info("P2WSH first sign done: txid={}", wtxBuilder.getHash());
        }catch(Throwable e){log.error("sign error",e); signature.put("valid",false); signature.put("error",e.getMessage());}
        transaction.setSignature(signature.toJSONString());
    }
    /**
     * 按 BIP44 路径派生地址对应的 BIP32 节点及私钥。
     *
     * <p>派生路径：m/44'/{coinType}'/{biz}'/{userId}'/{index}
     * 优先使用测试节点 {@link #NODE}，为 null 时从 {@code keyMaterial.sig1Root()} 获取。
     *
     * @param a        地址信息
     * @param currency 资产元数据
     * @return 派生后的 BIP32 节点
     */
    public Bip32Node getBipNODE(Address a, AssetRuntimeMetadata currency) {
        Bip32Node root = NODE != null ? NODE : keyMaterial.sig1Root();
        return root.getChild(44).getChild(currency.getBip44CoinType()).getChild(a.getBiz()).getChild(a.getUserId().intValue()).getChild(a.getIndex());
    }

    /** @return 默认费率（sat/vByte） */
    protected long defaultFeeRate() { return DEFAULT_FEE_RATE; }
    /** @return 默认粉尘阈值（sat） */
    protected long dustThresholdSat() { return DUST_THRESHOLD_SAT; }
}
