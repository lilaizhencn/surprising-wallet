package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.ECKey;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.tron.TronWalletApi;
import org.tron.protos.Protocol;
import org.tron.wallet.util.ByteArray;

import java.io.IOException;
import java.math.BigDecimal;

import static com.surprising.wallet.common.currency.CurrencyEnum.TRX;


/**
 * @author atomex
 */
@Component
@Slf4j
public class TronSecondSignService implements ISignService {
    @Override
    public CurrencyEnum getCurrency() {
        return TRX;
    }

//    public static void main(String[] args) {
//
//        File keyFile = new File("/Volumes/Secure/key-cmx.conf");
//
//
//        try {
//            KeyConfig.init(keyFile.toURI().toURL());
//        } catch (MalformedURLException e) {
//            e.printStackTrace();
//        }
//
//        String mk = KeyConfig.getValue("masterNode");
//        Bip32Node pubNODE = Bip32Node.decode(mk);
//        Bip32Node tronNode1 = pubNODE.getChildH(TRX.getIndex());
//
//
//        Bip32Node tronNode = tronNode1
//                .getChild(44)
//                .getChild(TRX.getIndex())
//                .getChild(0)
//                .getChild(0)
//                .getChild(0);
//        ECKey tronNodeEcKey = tronNode.getEcKey();
//        ECPoint pubKeyPoint = tronNodeEcKey.getPubKeyPoint();
//        System.out.println("pub   " + tronNode1.pubSerialize(0, true));
//        String address = TronWalletApi.getAddress(pubKeyPoint.getEncoded());
//        System.out.println(address);
//
////
////        Bip32Node NODE2 = Bip32Node.decode("xprv9s21ZrQH143K2h7viVVN9ioJhm7VoxwZGY6MAAXbtaToopzE2pkv4KKiHDrPePfZY999rgLbEx23N6t9WxUeDjEcCb1xMrME5HN5quSYPPF");
////        ECKey ecKey = NODE2.getChildH(23).getChild(41).getChild(0).getChild(0).getChild(0).getEcKey();
////        ECPoint point = ecKey.getPubKeyPoint();
////        address = TronWalletApi.getAddress(point);
////        System.out.println("web ==== " + address);
////        Bip32Node node = BipNodeUtil.getMainBipNODE();
////        Bip32Node tronNode = node.getChildH(CurrencyEnum.TRON.getIndex())
////                .getChild(41)
////                .getChild(1)
////                .getChild(1)
////                .getChild(1);
////        ECKey tronKey = tronNode.getEcKey();
////        byte[] bytes = org.tron.wallet.crypto.ECKey.publicKeyFromPrivate(tronKey.getPrivKey(), true);
////        String s = ByteArray.toHexString(bytes);
////        String encode = Base58.encode(bytes);
////        System.out.println(encode);
//
//    }

    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        JSONObject sigJson = JSONObject.parseObject(transaction.getSignature());
        CurrencyEnum currency = CurrencyEnum.parseValue(transaction.getCurrency());
        Address address = sigJson.getJSONObject("address").toJavaObject(Address.class);
        String from = sigJson.getString("from");
        String toAddr = sigJson.getString("to");
        BigDecimal value = transaction.getBalance();
        try {
            Protocol.Transaction waitSignTx = TronWalletApi.createTransaction(TronWalletApi.decodeFromBase58Check(from), TronWalletApi.decodeFromBase58Check(toAddr),
                    value.multiply(currency.getDecimal()).longValue(),
                    sigJson.getString("block"));
            Protocol.Transaction signedTxObject = TronWalletApi.signTransaction2Object(waitSignTx.toByteArray(), getKeyByAddress(address));
            if (!ObjectUtils.isEmpty(signedTxObject)) {
                return ByteArray.toHexString(signedTxObject.toByteArray());
            }
            return "";
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    private byte[] getKeyByAddress(Address address) {
        Bip32Node node = BipNodeUtil.getMainBipNODE();
        Bip32Node tronNode = node.getChildH(TRX.getIndex())
                .getChild(44)
                .getChild(getCurrency().getIndex())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
        ECKey tronKey = tronNode.getEcKey();
        return tronKey.getPrivKeyBytes();
    }

}
