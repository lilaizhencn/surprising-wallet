package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;

import java.util.List;

/**
 * @author atomex
 */
@Slf4j
abstract public class AbstractBtcLikeSecondSign implements ISignService {

    protected NetworkParameters getNetworkParameters() {
        return Constants.NET_PARAMS;
    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        log.info("二次签名开始");
        String signature = transaction.getSignature();
        JSONObject sigJson = JSONObject.parseObject(signature);
        String firstSignTx = sigJson.getString("firstSignTx");
        List<Address> addresses = sigJson.getJSONArray("addresses").toJavaList(Address.class);
        Transaction spendTx = new Transaction(getNetworkParameters(), Utils.HEX.decode(firstSignTx));

        for (int i = 0; i < spendTx.getInputs().size(); i++) {
            TransactionInput input = spendTx.getInput(i);
            List<ScriptChunk> chunkList = input.getScriptSig().getChunks();
            int size = chunkList.size();
            // 多签的签名信息最后一项内容为多签脚本。内容： “0 签名1 签名2 ... 多签脚本”
            if (size < 2) {
                log.error("签名失败");
                sigJson.put("valid", false);
                transaction.setSignature(sigJson.toJSONString());
                return "";
            }
            ECKey ecKey = BipNodeUtil.getBipNODE(addresses.get(i)).getEcKey();
            Script multiSigScript = new Script(chunkList.get(size - 1).data);
            Sha256Hash sigHash =
                    spendTx.hashForSignature(i, multiSigScript, Transaction.SigHash.ALL, false);
            ECKey.ECDSASignature ecSignature = ecKey.sign(sigHash);
            TransactionSignature secondSig =
                    new TransactionSignature(ecSignature, Transaction.SigHash.ALL, false);

            ScriptBuilder builder = new ScriptBuilder();
            // 多签chunks中数据格式： 0 [signature...][redeemScript]
            if (size == 2 + 1) {
                int pos = 0;
                // 加入之前的各个签名内容。
                while (pos < size - 1) {
                    builder.addChunk(chunkList.get(pos++));
                }
            } else {
                // bitcoinj的处理：没有的签名位置使用“0”进行占位，0 signature1 0 redeemscript
                builder.addChunk(chunkList.get(0));
                for (int pos = 1; pos < size; pos++) {
                    ScriptChunk chunk = chunkList.get(pos);
                    if (chunk.data == null || chunk.data.length == 0) {
                        break;
                    }
                    builder.addChunk(chunk);
                }
            }
            // 加入网站私钥签名信息。
            builder.data(secondSig.encodeToBitcoin());
            // 加入多签脚本。
            builder.data(multiSigScript.getProgram());
            Script result = builder.build();
            input.setScriptSig(result);
        }
        String tx = Utils.HEX.encode(spendTx.bitcoinSerialize());

        log.info("二次签名完成 交易id:{}", tx);
        return tx;
    }
}
