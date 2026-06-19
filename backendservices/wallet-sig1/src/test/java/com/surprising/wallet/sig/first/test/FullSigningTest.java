package com.surprising.wallet.sig.first.test;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import com.surprising.wallet.sig.first.service.BtcFirstSignService;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.params.TestNet3Params;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

public class FullSigningTest {
    // sig1 的私钥 (hot wallet — KEY 1 of 2-of-3)
    static final String SIG1_MK = "tprv8ZgxMBicQKsPdbs7SqHoQoG3z9zbLhaqKkC6puxUCiT6iR656FezRhnAhdykJjLanDqqZRGimT8yvHM7sZmWAXeEvk8UPxVMSZ1sh6uJFJc";
    // sig2 的私钥 (cold wallet — KEY 2 of 2-of-3)
    static final String SIG2_MK = "tprv8ZgxMBicQKsPdMCWhEWHyiZCAu45dhRgupjpWBJ4vaYojzWURTemEm51Kf8tGQVcuerPJhCh98aCL9E81C2je6k3aeT7AJ5xELrVMprXf8U";
    // KEY 3 的 xpub (用于生成多签地址，不在线)
    static final String PK1 = "tpubD6NzVbkrYhZ4X4tuLUxPpCvAZBWXW2mju3nt7RzmczFVYuLqieUacCQ2snjhu8zE9EagGrkG5gxthYUFhpgYx4bEVM9dYraywwZHycJNg5d";
    static final String PK2 = "tpubD6NzVbkrYhZ4WpEJatAtP8DJjva1o2cbV8LbnhLNLrMCaUmF3rUMRFgsVneh53JipwKMwUp3QGGzqff7avf5M9QLR7RZaEy3ha5ihtrkDRQ";
    static final String PK3 = "tpubD6NzVbkrYhZ4XDJNtex5Gm1Hzj4bbewgop6stxxZv4trZ3YFdLk1p9VvzQtYTk8Lf4BMo5pWA5TwJGgFbbEFaBr5Ft951V2wYQcMLWQNji4";

    public static void main(String[] args) throws Exception {
        System.setProperty("atomex.wallet.masterKey", SIG1_MK);
        System.setProperty("atomex.wallet.network", "test");
        Constants c0 = new Constants(); c0.NETWORK = "test"; c0.init();

        // Init sig2's KeyConfig with a DIFFERENT master key
        Properties props = new Properties();
        props.setProperty("masterNode", SIG2_MK);
        Class.forName("com.surprising.wallet.signature.KeyConfig")
            .getMethod("init", Properties.class).invoke(null, props);

        UtxoTransaction utxo = UtxoTransaction.builder()
            .txId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .seq((short)0).balance(new BigDecimal("0.001")).build();
        Address addr = Address.builder().userId(1L).currency("BTC").biz(1).index(0).build();
        WithdrawRecord rec = WithdrawRecord.builder()
            .address("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx")
            .balance(new BigDecimal("0.0005")).build();

        JSONObject sigJson = new JSONObject();
        sigJson.put("utxos", List.of(utxo)); sigJson.put("addresses", List.of(addr));
        sigJson.put("withdraw", List.of(rec)); sigJson.put("feeRate", 10L);
        sigJson.put("changeAddress", "2N2ky5hJ1b5j8E4GT14GiKcxcdUoTuhDRG7");

        WithdrawTransaction tx = WithdrawTransaction.builder()
            .signature(sigJson.toJSONString()).balance(new BigDecimal("0.001")).currency(1).build();

        // Phase 1: First Sign with SIG1_MK
        BtcFirstSignService firstSign = new BtcFirstSignService();
        set(firstSign, "masterKey", SIG1_MK);
        PubKeyConfig pk = new PubKeyConfig();
        set(pk, "pub1", PK1); set(pk, "pub2", PK2); set(pk, "pub3", PK3);
        pk.init(); set(firstSign, "pubKeyConfig", pk); firstSign.init();

        System.out.println("=============================================");
        System.out.println("  Phase 1: FirstSign (KEY 1 — hot wallet)");
        System.out.println("=============================================");
        firstSign.signTransaction(tx);

        JSONObject r1 = JSONObject.parseObject(tx.getSignature());
        System.out.println("valid: " + r1.getBooleanValue("valid") + "  fee: " + r1.getLongValue("fee") + " sat");
        if (!r1.getBooleanValue("valid")) { System.out.println("FAIL"); System.exit(1); }
        String firstSignTx = r1.getString("firstSignTx");

        // Phase 2: Second Sign with SIG2_MK (different key!)
        System.out.println("");
        System.out.println("=============================================");
        System.out.println("  Phase 2: SecondSign (KEY 2 — cold wallet)");
        System.out.println("=============================================");
        Class<?> ssClass = Class.forName("com.surprising.wallet.sig.second.impl.BtcSecondSignService");
        Object ss = ssClass.getDeclaredConstructor().newInstance();
        String fullTx = (String) ssClass.getMethod("signTransaction", WithdrawTransaction.class).invoke(ss, tx);

        if (fullTx == null || fullTx.isEmpty()) {
            r1 = JSONObject.parseObject(tx.getSignature());
            System.out.println("SECOND SIGN FAILED: " + r1.getString("error"));
            System.exit(1);
        }
        System.out.println("fullTx: " + fullTx.length() + " chars");

        // Parse and verify witness
        HexFormat hf = HexFormat.of();
        Transaction firstTx = Transaction.read(ByteBuffer.wrap(hf.parseHex(firstSignTx)));
        Transaction fullSignTx = Transaction.read(ByteBuffer.wrap(hf.parseHex(fullTx)));

        System.out.println("");
        System.out.println("First TXID: " + firstTx.getTxId());
        System.out.println("Full  TXID: " + fullSignTx.getTxId());
        System.out.println("TXID unchanged (SegWit): " + firstTx.getTxId().equals(fullSignTx.getTxId()));

        // Analyze witness
        TransactionWitness w = fullSignTx.getInput(0).getWitness();
        int pc = w.getPushCount();
        System.out.println("Witness pushes: " + pc);

        byte[] s1 = w.getPush(1);
        byte[] s2 = w.getPush(2);
        byte[] ws = w.getPush(pc-1);

        System.out.println("sig1 DER: " + s1.length + " bytes, lastByte: 0x" + Integer.toHexString(s1[s1.length-1]&0xFF));
        System.out.println("sig2 DER: " + s2.length + " bytes, lastByte: 0x" + Integer.toHexString(s2[s2.length-1]&0xFF));
        System.out.println("Same sig? " + java.util.Arrays.equals(s1, s2));

        String sig1Hex = hf.formatHex(s1);
        String sig2Hex = hf.formatHex(s2);
        System.out.println("");
        System.out.println("sig1: " + sig1Hex.substring(0, 32) + "..." + sig1Hex.substring(sig1Hex.length()-16));
        System.out.println("sig2: " + sig2Hex.substring(0, 32) + "..." + sig2Hex.substring(sig2Hex.length()-16));
        System.out.println("ScriptSig empty (native SegWit): " + (fullSignTx.getInput(0).getScriptSig().getProgram().length == 0));

        System.out.println("");
        System.out.println("*** FULL SIGN TX (HEX) ***");
        System.out.println(fullTx);
        System.out.println("");
        
        if (java.util.Arrays.equals(s1, s2)) {
            System.out.println("❌ 两个签名相同 — 使用了同一把私钥，2-of-3 多签无效！");
            System.exit(1);
        } else {
            System.out.println("✅ 两个签名不同 — 两把不同的私钥成功完成 2-of-3 多签！");
        }
    }

    static void set(Object obj, String field, Object val) {
        try { for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField(field);
                  f.setAccessible(true); f.set(obj, val); return; }
            catch (NoSuchFieldException e) {}
        }} catch(Exception e) { throw new RuntimeException(e); }
    }
}
