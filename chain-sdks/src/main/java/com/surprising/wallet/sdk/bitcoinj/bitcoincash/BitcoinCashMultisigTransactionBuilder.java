package com.surprising.wallet.sdk.bitcoinj.bitcoincash;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * 负责构建交易、脚本或请求对象，并执行必要的输入校验。
 */
public final class BitcoinCashMultisigTransactionBuilder {
    /**
     * 定义 {@code SIGHASH_ALL_FORKID} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final int SIGHASH_ALL_FORKID = 0x41;
    /**
     * 定义 {@code HEX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final HexFormat HEX = HexFormat.of();
    /**
     * 保存 {@code params}，用于承载当前对象的运行配置或业务数据。
     */
    private final BitcoinCashNetworkParameters params;
    /**
     * 保存 {@code inputs}，用于承载当前对象的运行配置或业务数据。
     */
    private final List<Input> inputs = new ArrayList<>();
    /**
     * 保存 {@code outputs}，用于承载当前对象的运行配置或业务数据。
     */
    private final List<Output> outputs = new ArrayList<>();
    /**
     * 保存 {@code transaction}，用于标识交易、区块或业务记录。
     */
    private Transaction transaction;

    /**
     * 构造 {@code BitcoinCashMultisigTransactionBuilder}，初始化该组件运行所需的状态和依赖。
     */
    public BitcoinCashMultisigTransactionBuilder(BitcoinCashNetworkParameters params) { this.params = params; }
    /**
     * 添加 {@code addInput} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public void addInput(String txid, int vout, String redeem, Coin value) {
        inputs.add(new Input(txid, vout, redeem, value));
    }
    /**
     * 添加 {@code addOutput} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public void addOutput(String address, Coin value) {
        LegacyAddress legacy;
        if (address.contains(":") || address.toLowerCase(Locale.ROOT).startsWith(params.cashPrefix())) {
            legacy = BitcoinCashAddressCodec.toLegacy(params, params.cashPrefix(), address);
        } else {
            legacy = LegacyAddress.fromBase58(params, address);
        }
        outputs.add(new Output(legacy, value));
    }
    /**
     * 构建或生成 {@code buildFirstSign} 对应的结果，并执行输入和状态校验。
     */
    public String buildFirstSign(List<ECKey> keys) {
        transaction = unsigned();
        for (int i=0;i<inputs.size();i++) signAt(transaction,i,keys.get(i),inputs.get(i).redeem,true);
        return HEX.formatHex(transaction.bitcoinSerialize());
    }
    /**
     * 构建或生成 {@code buildSecondSign} 对应的结果，并执行输入和状态校验。
     */
    public String buildSecondSign(String hex,List<ECKey> keys,List<String> redeems) {
        transaction=Transaction.read(ByteBuffer.wrap(HEX.parseHex(hex)));
        for(int i=0;i<transaction.getInputs().size();i++) signAt(transaction,i,keys.get(i),redeems.get(i),false);
        return HEX.formatHex(transaction.bitcoinSerialize());
    }
    /**
     * 获取或查询 {@code getTransaction} 对应的数据，供调用方读取当前状态。
     */
    public Transaction getTransaction(){return transaction;}
    /**
     * 执行 {@code unsigned} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Transaction unsigned(){
        Transaction tx=new Transaction(params);
        for(Input in:inputs) tx.addInput(new TransactionInput(tx,new byte[0],
                new TransactionOutPoint(in.vout,Sha256Hash.wrap(in.txid)),in.value));
        for(Output out:outputs) tx.addOutput(out.value,out.address);
        return tx;
    }
    /**
     * 为 {@code signAt} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    private void signAt(Transaction tx,int index,ECKey key,String redeemHex,boolean first){
        Script redeem=new Script(HEX.parseHex(redeemHex));
        Sha256Hash hash=tx.hashForWitnessSignature(index,redeem.program(),inputsValue(index),(byte)SIGHASH_ALL_FORKID);
        ECKey.ECDSASignature signed=key.sign(hash);
        TransactionSignature sig=new TransactionSignature(signed.r,signed.s,SIGHASH_ALL_FORKID);
        Script current=first?ScriptBuilder.createP2SHMultiSigInputScript(null,redeem):tx.getInput(index).getScriptSig();
        int pos=insertionIndex(current,hash,redeem,key);
        Script completed=ScriptBuilder.updateScriptWithSignature(
                current,sig.encodeToBitcoin(),pos,1,1);
        tx.replaceInput(index,tx.getInput(index).withScriptSig(completed));
        if(!first) verify(tx,index,redeem,completed);
    }
    /**
     * 执行 {@code inputsValue} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Coin inputsValue(int index){
        if(index<inputs.size()) return inputs.get(index).value;
        throw new IllegalStateException("missing BCH input value");
    }
    /**
     * 验证 {@code verify} 对应的签名、交易或数据证明是否有效。
     */
    private void verify(Transaction tx,int index,Script redeem,Script scriptSig){
        Sha256Hash hash=tx.hashForWitnessSignature(index,redeem.program(),inputsValue(index),(byte)SIGHASH_ALL_FORKID);
        int valid=0;
        for(int i=1;i<scriptSig.chunks().size()-1;i++){
            byte[] data=scriptSig.chunks().get(i).data;
            if(data==null||data.length==0) continue;
            TransactionSignature sig;
            try {
                sig=TransactionSignature.decodeFromBitcoin(data,false,false);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid BCH signature", e);
            }
            if(sig.sighashFlags!=SIGHASH_ALL_FORKID) throw new IllegalArgumentException("missing BCH FORKID");
            if(redeem.getPubKeys().stream().anyMatch(key->key.verify(hash,sig))) valid++;
        }
        if(valid<redeem.getNumberOfSignaturesRequiredToSpend()) throw new IllegalArgumentException("insufficient BCH signatures");
    }
    /**
     * 记录或保存 {@code insertionIndex} 对应的数据，并遵守幂等和事务约束。
     */
    private int insertionIndex(Script scriptSig,Sha256Hash hash,Script redeem,ECKey signingKey){
        List<ECKey> pubKeys=redeem.getPubKeys();
        int target=-1;
        for(int i=0;i<pubKeys.size();i++) if(Arrays.equals(pubKeys.get(i).getPubKey(),signingKey.getPubKey())) target=i;
        if(target<0) throw new IllegalArgumentException("BCH signing key not in redeem script");
        int before=0;
        for(int i=1;i<scriptSig.chunks().size()-1;i++){
            byte[] data=scriptSig.chunks().get(i).data;
            if(data==null||data.length==0) continue;
            try{
                TransactionSignature existing=TransactionSignature.decodeFromBitcoin(data,false,false);
                for(int p=0;p<pubKeys.size();p++){
                    if(pubKeys.get(p).verify(hash,existing)){
                        if(p<target) before++;
                        break;
                    }
                }
            }catch(Exception e){throw new IllegalArgumentException("invalid existing BCH signature",e);}
        }
        return before;
    }
    private record Input(String txid,int vout,String redeem,Coin value){}
    private record Output(LegacyAddress address,Coin value){}
}
