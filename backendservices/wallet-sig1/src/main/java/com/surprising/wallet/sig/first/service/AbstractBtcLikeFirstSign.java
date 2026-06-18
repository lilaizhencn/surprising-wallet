package com.surprising.wallet.sig.first.service;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.TransactionBuilder;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * @author lilaizhen
 * @data 10/04/2018
 */
@Slf4j
abstract public class AbstractBtcLikeFirstSign implements com.surprising.wallet.sig.first.service.ISignService {
    /**
     * 交易签名
     */
    public Bip32Node NODE;
    @Autowired
    protected PubKeyConfig pubKeyConfig;
    @Value("${atomex.wallet.masterKey}")
    String masterKey;

    @PostConstruct
    public void init() {
        NODE = Bip32Node.decode(masterKey);
    }

    protected NetworkParameters getNetworkParameters() {
        return Constants.NET_PARAMS;
    }

    @Override
    public void signTransaction(WithdrawTransaction transaction) {

        TransactionBuilder transactionBuilder = new TransactionBuilder(getNetworkParameters());
        JSONObject signature;
        signature = JSONObject.parseObject(transaction.getSignature());
        try {

            List<UtxoTransaction> utxos = signature.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
            List<Address> addresses = signature.getJSONArray("addresses").toJavaList(Address.class);
            List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
            int size = utxos.size();
            String privateKey;
            for (int i = 0; i < size; i++) {
                Address address = addresses.get(i);
                Bip32Node node = getBipNODE(address);
                privateKey = node.getEcKey().getPrivateKeyEncoded(getNetworkParameters()).toString();
                UtxoTransaction utxo = utxos.get(i);
                transactionBuilder.addInput(utxo.getTxId(), utxo.getSeq());
                String redeem = pubKeyConfig.genScript(address);
                transactionBuilder.addSignInfo(i, privateKey, redeem);
            }

            //预估交易大小，用来计算手续费 可以从这个api取 https://bitcoinfees.earn.com/api  响应为每字节需要多少比特币
            long totalByte = (325 * size + 35 * (records.size()) + 15);
            long feePerKb = signature.getLongValue("feePerKb");
            long fee = totalByte * feePerKb;
            log.info("需要的手续费为:{} 字节数:{} 每字节手续费:{}", fee, totalByte, feePerKb);
            long sentAmount = 0;

            for (WithdrawRecord record : records) {
                long amount = record.getBalance().multiply(getCurrency().getDecimal()).longValue();
                transactionBuilder.addOutput(record.getAddress(), Coin.valueOf(amount));
                //userFee = userFee + record.getFee().multiply(this.getCurrency().getDecimal()).longValue();
                sentAmount = sentAmount + amount;
            }
            long totalAmount = transaction.getBalance().multiply(getCurrency().getDecimal()).longValue();

            //userFee = userFee > fee ? userFee : fee;
            //userFee = userFee > maxFee ? maxFee : userFee;
            long changeAmount = totalAmount - sentAmount - fee;
            if (changeAmount > 0) {
                String changeAddr = signature.getString("changeAddress");
                transactionBuilder.addOutput(changeAddr, Coin.valueOf(changeAmount));
            }
            String firstSignTx = transactionBuilder.buildIncomplete();
            signature.put("firstSignTx", firstSignTx);
            signature.put("valid", true);


        } catch (Throwable e) {
            log.error("signTransaction error", e);
            signature.put("valid", false);
        }
        transaction.setSignature(signature.toJSONString());
    }

    /**
     * 获取currency对应的签名类
     */
    @Override
    abstract public CurrencyEnum getCurrency();

    public Bip32Node getBipNODE(Address address) {
        CurrencyEnum currencyEnum = CurrencyEnum.parseName(address.getCurrency());
        Bip32Node node = NODE.getChild(44)
                .getChild(currencyEnum.getIndex())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
        return node;
    }
}
