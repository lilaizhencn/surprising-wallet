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
 * BCH P2SH signer using the mandatory SIGHASH_FORKID digest (ALL|FORKID = 0x41).
 */
public final class BitcoinCashMultisigTransactionBuilder {
    public static final int SIGHASH_ALL_FORKID = 0x41;
    private static final HexFormat HEX = HexFormat.of();
    private final BitcoinCashNetworkParameters params;
    private final List<Input> inputs = new ArrayList<>();
    private final List<Output> outputs = new ArrayList<>();
    private Transaction transaction;

    public BitcoinCashMultisigTransactionBuilder(BitcoinCashNetworkParameters params) { this.params = params; }
    public void addInput(String txid, int vout, String redeem, Coin value) {
        inputs.add(new Input(txid, vout, redeem, value));
    }
    public void addOutput(String address, Coin value) {
        LegacyAddress legacy;
        if (address.contains(":") || address.toLowerCase(Locale.ROOT).startsWith(params.cashPrefix())) {
            legacy = BitcoinCashAddressCodec.toLegacy(params, params.cashPrefix(), address);
        } else {
            legacy = LegacyAddress.fromBase58(params, address);
        }
        outputs.add(new Output(legacy, value));
    }
    public String buildFirstSign(List<ECKey> keys) {
        transaction = unsigned();
        for (int i=0;i<inputs.size();i++) signAt(transaction,i,keys.get(i),inputs.get(i).redeem,true);
        return HEX.formatHex(transaction.bitcoinSerialize());
    }
    public String buildSecondSign(String hex,List<ECKey> keys,List<String> redeems) {
        transaction=Transaction.read(ByteBuffer.wrap(HEX.parseHex(hex)));
        for(int i=0;i<transaction.getInputs().size();i++) signAt(transaction,i,keys.get(i),redeems.get(i),false);
        return HEX.formatHex(transaction.bitcoinSerialize());
    }
    public Transaction getTransaction(){return transaction;}
    private Transaction unsigned(){
        Transaction tx=new Transaction(params);
        for(Input in:inputs) tx.addInput(new TransactionInput(tx,new byte[0],
                new TransactionOutPoint(in.vout,Sha256Hash.wrap(in.txid)),in.value));
        for(Output out:outputs) tx.addOutput(out.value,out.address);
        return tx;
    }
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
    private Coin inputsValue(int index){
        if(index<inputs.size()) return inputs.get(index).value;
        throw new IllegalStateException("missing BCH input value");
    }
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
