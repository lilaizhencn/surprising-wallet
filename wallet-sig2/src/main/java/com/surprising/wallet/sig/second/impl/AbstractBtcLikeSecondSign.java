package com.surprising.wallet.sig.second.impl;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.Address;import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessSigner;
import com.surprising.wallet.sig.second.BipNodeUtil;import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;import org.bitcoinj.base.Coin;import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;import org.bitcoinj.core.TransactionInput;import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.crypto.ECKey;import org.bitcoinj.crypto.TransactionSignature;import org.bitcoinj.params.TestNet3Params;import org.bitcoinj.script.Script;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;import java.util.HexFormat;import java.util.List;
/**
 * BTC-like 链（BTC、LTC）P2WSH（SegWit 多签）二签抽象基类。
 *
 * <p>负责解析第一次签名后的交易，逐输入校验 witness 结构（至少 3 个 push：
 * zeroSig/pubKey/witnessScript），计算第二次签名并合并多签见证数据。
 * 子类只需覆盖 {@link #chain()} 返回对应链名称。
 *
 * <p>当前仅支持 P2WSH 脚本类型，其他类型会在签名校验中提前终止。
 */
@Slf4j
abstract public class AbstractBtcLikeSecondSign implements ISignService {

    /** 十六进制编解码器 */
    private static final HexFormat HEX = HexFormat.of();
    /** 见证签名合并器 */
    private final WitnessSigner ws = new WitnessSigner();
    /** Jackson 3 对象映射器，用于解析和序列化签名元数据。 */
    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * 返回当前链的网络参数。
     *
     * @return BTC 测试网参数
     */
    protected NetworkParameters getNetworkParameters() {
        return TestNet3Params.get();
    }

    /**
     * 对 BTC-like P2WSH 提现交易执行第二次签名。
     *
     * <p>处理流程：
     * <ol>
     *   <li>解析签名 JSON 中的 firstSignTx 和 utxoValues</li>
     *   <li>校验脚本类型为 p2wsh</li>
     *   <li>反序列化第一次签名交易</li>
     *   <li>逐输入校验 witness 结构（需至少 3 个 push）</li>
     *   <li>从 witnessScript 的最后一个 push 提取 redeem script</li>
     *   <li>使用 BIP32 派生的私钥计算第二次见证签名</li>
     *   <li>合并多签见证数据后输出完整签名交易</li>
     * </ol>
     *
     * @param tx 提现交易
     * @return 完整签名的交易十六进制字符串，失败时设置 valid=false 并返回空字符串
     */
    @Override
    public String signTransaction(WithdrawTransaction tx) {
        AssetRuntimeMetadata currency=AssetRuntimeMetadata.fromTransaction(tx);
        ObjectNode sj = JacksonJson.readObject(objectMapper, tx.getSignature());
        String fst = JacksonJson.text(sj, "firstSignTx"); if(fst==null||fst.isEmpty()){sj.put("valid",false);sj.put("error","no firstSignTx");tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
        if(!"p2wsh".equals(JacksonJson.text(sj, "scriptType"))){sj.put("valid",false);sj.put("error","not p2wsh");tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
        List<Address> ads = JacksonJson.toList(objectMapper, sj.get("addresses"), Address.class);
        JsonNode uva = sj.get("utxoValues"); if(uva==null||uva.isEmpty()){sj.put("valid",false);sj.put("error","no utxoValues");tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
        try{Transaction stx=Transaction.read(ByteBuffer.wrap(HEX.parseHex(fst)));
            for(int i=0;i<stx.getInputs().size();i++){TransactionInput in=stx.getInput(i); TransactionWitness ew=in.getWitness();
                if(ew==null){sj.put("valid",false);sj.put("error","no witness");tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
                int pc=ew.getPushCount(); if(pc<3){sj.put("valid",false);sj.put("error","bad witness");tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
                byte[] wsb=ew.getPush(pc-1); if(wsb==null||wsb.length==0){sj.put("valid",false);sj.put("error","no witnessScript");tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
                Script script=new Script(wsb); long vs=uva.get(i).asLong(); if(vs<=0){sj.put("valid",false);sj.put("error","bad utxoValue");tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
                Coin uv=Coin.valueOf(vs); ECKey ek=BipNodeUtil.getBipNODE(ads.get(i),currency).getEcKey();
                TransactionSignature s2=stx.calculateWitnessSignature(i,ek,script,uv,Transaction.SigHash.ALL,false);
                int required=script.getNumberOfSignaturesRequiredToSpend();
                stx.replaceInput(i,in.withWitness(ws.mergeMultisigWitness(stx,i,ew,s2,ek,script,uv,required)));}
            String hex=HEX.formatHex(stx.bitcoinSerialize()); log.info("P2WSH second sign done: txid={}",stx.getTxId()); return hex;
        }catch(Exception e){log.error("P2WSH second sign error",e); sj.put("valid",false);sj.put("error",e.getMessage());tx.setSignature(JacksonJson.writeValue(objectMapper, sj));return"";}
    }
}
