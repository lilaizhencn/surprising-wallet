package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bouncycastle.util.encoders.Hex;
import org.dogecoinj.DogeSdk;
import org.libdohj.params.DogecoinMainNetParams;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author atomex
 */
@Component
@Slf4j
public class DogeSecondSignService extends AbstractBtcLikeSecondSign implements ISignService {

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.DOGE;
    }

    @Override
    protected NetworkParameters getNetworkParameters() {
        return DogecoinMainNetParams.get();
    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        JSONObject signature;
        signature = JSONObject.parseObject(transaction.getSignature());
        try {

            List<UtxoTransaction> utxos = signature.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
            List<Address> addresses = signature.getJSONArray("addresses").toJavaList(Address.class);
            List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);

            //创建transaction
            Transaction transaction1 = new Transaction(getNetworkParameters());

            int size = utxos.size();
            //官方钱包模式1个doge，在doge网络发生交易时，也必须是至少一个doge
            long fee = 10000_0000;
//             预估交易大小，用来计算手续费 147是一个输入的字节数量  44是一个输出的字节数量 没有输入和输出 是10
//            long minFee = 10000_0000;
//            long totalByte = (147 * size + 44 * (records.size()) + 10);
//            long feePerKb = signature.getLongValue("feePerKb");
//            long fee = totalByte * feePerKb;
//            if (fee < minFee) {
//                fee = minFee;
//            }
//            log.info("doge coin fee={}", fee);

            long sentAmount = 0;
            //先添加输出，单签的时添加script 需要 output列表
            for (WithdrawRecord record : records) {
                long amount = record.getBalance().multiply(getCurrency().getDecimal()).longValue();
                transaction1.addOutput(Coin.valueOf(amount), org.bitcoinj.core.Address.fromString(getNetworkParameters(), record.getAddress()));
                sentAmount = sentAmount + amount;
            }
            long totalAmount = transaction.getBalance().multiply(getCurrency().getDecimal()).longValue();

            long changeAmount = totalAmount - sentAmount - fee;
            if (changeAmount > 0) {
                String changeAddr = signature.getString("changeAddress");
                transaction1.addOutput(Coin.valueOf(changeAmount), org.bitcoinj.core.Address.fromString(getNetworkParameters(), changeAddr));
            }
            //添加输入 并且带上privateKey
            for (int i = 0; i < size; i++) {
                Address address = addresses.get(i);
                Bip32Node node = getBipNODE(address);
                ECKey ecKey = node.getEcKey();
                TransactionOutPoint outPoint = new TransactionOutPoint(getNetworkParameters(), utxos.get(i).getSeq(), Sha256Hash.wrap(utxos.get(i).getTxId()));
                Script script = DogeSdk.createSignleSignScript(ecKey.getPubKeyHash());
                transaction1.addSignedInput(outPoint, script, ecKey);
            }
            //获取交易hex
            return new String(Hex.encode(transaction1.bitcoinSerialize()));
        } catch (Exception e) {
            log.error("DOGE币签名失败", e);
            return null;
        }
    }

    private Bip32Node getBipNODE(Address address) {
        Bip32Node dogeNode = BipNodeUtil.getMainBipNODE();
        return dogeNode
                .getChildH(getCurrency().getIndex())
                .getChild(44)
                .getChild(getCurrency().getIndex())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
    }

//    public static void main(String[] args) {
//        File keyFile = new File("/Volumes/security/key.conf");
//
//
//        try {
//            KeyConfig.init(keyFile.toURI().toURL());
//        } catch (MalformedURLException e) {
//            e.printStackTrace();
//        }
//
//        String mk = KeyConfig.getValue("masterNode");
//        Bip32Node dogeNode = Bip32Node.decode(mk);
//        System.out.println(dogeNode.getChildH(CurrencyEnum.DOGE.getIndex()).pubSerialize(0, true));
//
//        //Bip32Node dogeNode = Bip32Node.decode("xprv9s21ZrQH143K2h7viVVN9ioJhm7VoxwZGY6MAAXbtaToopzE2pkv4KKiHDrPePfZY999rgLbEx23N6t9WxUeDjEcCb1xMrME5HN5quSYPPF");
//        Bip32Node child = dogeNode.getChild(44)
//                .getChild(74)
//                .getChild(1)
//                .getChild(1)
//                .getChild(0);
//        ECKey ecKey = child.getEcKey();
//        String newAddress = DogeSdk.getNewAddress(ecKey);
//        System.out.println(newAddress);
//        Transaction transaction1 = new Transaction(DogecoinMainNetParams.get());
//        TransactionOutPoint outPoint = new TransactionOutPoint(DogecoinMainNetParams.get(), 0, Sha256Hash.wrap("25ecfbf78b51fcccf47343107704704fe642e9bd4e62e00e785c7375892d2b15"));
//        System.out.println(ecKey.getPublicKeyAsHex());
//        ScriptBuilder scriptBuilder = new ScriptBuilder();
//        scriptBuilder.op(OP_DUP).op(OP_HASH160).data(ecKey.getPubKeyHash()).op(OP_EQUALVERIFY).op(OP_CHECKSIG);
//        Script script = scriptBuilder.build();
//        long unit = 10000_0000;
//        transaction1.addOutput(Coin.valueOf(138 * unit), LegacyAddress.fromBase58(DogecoinMainNetParams.get(), "DBwU6vXgWPRCTGEJFyfzJocehYdJZDENzZ"));
//        transaction1.addSignedInput(outPoint, script, ecKey, Transaction.SigHash.ALL, true);
//        String tx = Utils.HEX.encode(transaction1.bitcoinSerialize());
//        System.out.println(tx);
//    }
}
