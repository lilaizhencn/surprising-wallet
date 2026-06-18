package com.surprising.wallet.sig.first.service;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import lombok.extern.slf4j.Slf4j;
import org.bitcoincashj.core.*;
import org.bitcoincashj.crypto.TransactionSignature;
import org.bitcoincashj.params.MainNetParams;
import org.bitcoincashj.params.TestNet3Params;
import org.bitcoincashj.script.Script;
import org.bitcoincashj.script.ScriptBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * @author lilaizhen
 * @data 10/04/2018
 */
@Component
@Slf4j
public class BchFirstSignService extends AbstractBtcLikeFirstSign implements ISignService {

    @Autowired
    Constants CONS;
    private NetworkParameters params = MainNetParams.get();

    /**
     * 获取currency对应的签名类
     *
     * @return
     */
    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.BCH;
    }

    //? TestNet3Params.get() : MainNetParams.get();
    @Override
    @PostConstruct
    public void init() {
        NODE = Bip32Node.decode(masterKey);
        if (CONS.NETWORK.equals("test")) {
            params = TestNet3Params.get();
        } else {
            params = MainNetParams.get();
        }
    }


    @Override
    public void signTransaction(WithdrawTransaction transaction) {

        //final TransactionBuilder transactionBuilder = new TransactionBuilder(Constants.NET_PARAMS);
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        try {

            List<UtxoTransaction> utxos = signature.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
            List<Address> addresses = signature.getJSONArray("addresses").toJavaList(Address.class);
            List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
            Transaction tx = new Transaction(params);
            int size = utxos.size();

            //List<ECKey> ecKeyList = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                Address address = addresses.get(i);
                //final Bip32Node node = this.getBipNODE(address);
                //ecKeyList.add(convertECKey(node.getEcKey()));
                //privateKey = node.getEcKey().getPrivateKeyEncoded(Constants.NET_PARAMS).toString();
                UtxoTransaction utxo = utxos.get(i);
                String redeemStr = pubKeyConfig.genScript(address);
                Script redeem = new Script(Utils.HEX.decode(redeemStr));
                long amount = utxo.getBalance().multiply(getCurrency().getDecimal()).longValue();
                //tx.addInput(new Sha256Hash(utxo.getTxId()), utxo.getSeq(), redeem, Coin.valueOf(amount));
                TransactionOutPoint outPoint = new TransactionOutPoint(params, utxo.getSeq(), new Sha256Hash(utxo.getTxId()));
                TransactionInput input = new TransactionInput(params, tx, redeem.getProgram(), outPoint, Coin.valueOf(amount));
                tx.addInput(input);

//                final Sha256Hash hash = tx.hashForSignature4MultiSign(i, redeem, Transaction.SigHash.ALL, false, amount);
//                final ECKey key = this.convertECKey(node.getEcKey());
//                final ECKey.ECDSASignature sig = key.sign(hash);
//                final TransactionSignature inputSignature = new TransactionSignature(sig, Transaction.SigHash.ALL, false, true);
//                final List<TransactionSignature> inputSignatures = new ArrayList<>();
//                inputSignatures.add(inputSignature);
//                final Script scriptSig = ScriptBuilder.createP2SHMultiSigInputScript(inputSignatures, redeem);
//                input.setScriptSig(scriptSig);

                //tx.addInput(input);
            }
            //预估交易大小，用来计算手续费
            long totalByte = (325 * size + 35 * (records.size()) + 15);
            long feePerKb = signature.getLongValue("feePerKb");
            long fee = totalByte * feePerKb;
            //final long maxFee = fee * 10;
            //long userFee = 0;

            long sentAmount = 0;

            for (WithdrawRecord record : records) {
                long amount = record.getBalance().multiply(getCurrency().getDecimal()).longValue();
                org.bitcoincashj.core.Address outputAddr = org.bitcoincashj.core.Address.fromBase58(params,
                        record.getAddress());
                tx.addOutput(Coin.valueOf(amount), outputAddr);
                //userFee = userFee + record.getFee().multiply(this.getCurrency().getDecimal()).longValue();
                sentAmount = sentAmount + amount;
            }

            long totalAmount = transaction.getBalance().multiply(getCurrency().getDecimal()).longValue();

            //userFee = userFee > maxFee ? maxFee : userFee;
            long changeAmount = totalAmount - sentAmount - fee;
            if (changeAmount > 0) {
                String changeAddr = signature.getString("changeAddress");
                tx.addOutput(Coin.valueOf(changeAmount), org.bitcoincashj.core.Address.fromBase58(params, changeAddr));
            }
            List<TransactionInput> txInputs = tx.getInputs();
            for (int i = 0; i < txInputs.size(); i++) {
                TransactionInput input = txInputs.get(i);
                UtxoTransaction utxo = utxos.get(i);

                long amount = utxo.getBalance().multiply(getCurrency().getDecimal()).longValue();

                Script redeemScript = new Script(input.getScriptSig().getProgram());
                Address address = addresses.get(i);
                Bip32Node node = getBipNODE(address);
                //TODO
                Sha256Hash hash = null;//tx.hashForSignature4MultiSign(i, redeemScript, Transaction.SigHash.ALL, false, amount);
                ECKey key = convertECKey(node.getEcKey());
                ECKey.ECDSASignature sig = key.sign(hash);
                TransactionSignature inputSignature = new TransactionSignature(sig, Transaction.SigHash.ALL, false, true);
                List<TransactionSignature> inputSignatures = new ArrayList<>();
                inputSignatures.add(inputSignature);
                Script scriptSig = ScriptBuilder.createP2SHMultiSigInputScript(inputSignatures, redeemScript);
                input.setScriptSig(scriptSig);
            }


            String firstSignTx = Utils.HEX.encode(tx.bitcoinSerialize());
            signature.put("firstSignTx", firstSignTx);
            signature.put("valid", true);


        } catch (Throwable e) {
            log.error("signTransaction error", e);
            signature.put("valid", false);
        }
        transaction.setSignature(signature.toJSONString());
    }

    // 把bitcoinj 中的ECkey转变成bitcoincashj中的Eckey
    private ECKey convertECKey(org.bitcoinj.core.ECKey key) {
        BigInteger pri = key.getPrivKey();
        return ECKey.fromPrivate(pri);
    }
}
