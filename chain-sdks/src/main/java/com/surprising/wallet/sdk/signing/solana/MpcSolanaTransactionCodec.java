package com.surprising.wallet.sdk.signing.solana;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.p2p.solanaj.core.AccountKeysList;
import org.p2p.solanaj.core.AccountMeta;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.TransactionInstruction;
import org.p2p.solanaj.utils.Base58;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;

/** Public-only legacy Solana message encoding and externally verified, ordered Ed25519 witnesses. */
public final class MpcSolanaTransactionCodec {
    public static final String MEMO_PROGRAM_ID="MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";
    private static final HexFormat HEX=HexFormat.of();
    private static final int PACKET=1232;
    private MpcSolanaTransactionCodec() { }
    public record Unsigned(String messageHex) {
        public Unsigned {
            require(messageHex!=null && !messageHex.isEmpty() && messageHex.length()<=PACKET*2
                    && messageHex.length()%2==0 && messageHex.matches("[0-9a-f]+"),"invalid immutable Solana message");
        }
    }
    public record Message(List<AccountMeta> accounts,int requiredSignatures,String blockhash,List<TransactionInstruction> instructions) {
        public Message {accounts=List.copyOf(accounts);instructions=List.copyOf(instructions);}
        public String feePayer(){return accounts.getFirst().getPublicKey().toBase58();}
        public List<String> signers(){return accounts.subList(0,requiredSignatures).stream().map(a->a.getPublicKey().toBase58()).toList();}
    }
    public record Signed(String transactionBase64,String signature) { }
    public static String associatedTokenAddress(String owner,String mint) {
        return PublicKey.findProgramAddress(List.of(new PublicKey(owner).toByteArray(),TokenProgram.PROGRAM_ID.toByteArray(),new PublicKey(mint).toByteArray()),
                AssociatedTokenProgram.PROGRAM_ID).getAddress().toBase58();
    }
    /** Only the two existing wallet payment forms; ATA creation is idempotent. */
    public static Unsigned payment(String source,String feePayer,String recipient,String mint,long atomic,int decimals,String blockhash,String memo) {
        require(atomic>0 && decimals>=0 && decimals<=18,"invalid Solana payment amount or precision");
        var instructions=new ArrayList<TransactionInstruction>();
        if(mint==null)instructions.add(SystemProgram.transfer(new PublicKey(source),new PublicKey(recipient),atomic));
        else {
            instructions.add(AssociatedTokenProgram.createIdempotent(new PublicKey(feePayer),new PublicKey(recipient),new PublicKey(mint)));
            instructions.add(TokenProgram.transferChecked(new PublicKey(associatedTokenAddress(source,mint)),new PublicKey(associatedTokenAddress(recipient,mint)),atomic,(byte)decimals,new PublicKey(source),new PublicKey(mint)));
        }
        instructions.add(memo(feePayer,memo));return prepare(feePayer,blockhash,instructions);
    }
    /** The current Memo v2 program; the SDK's legacy Memo1 constant fails on the tested Agave runtime. */
    public static TransactionInstruction memo(String signer,String memo) {
        require(memo!=null && !memo.isEmpty() && memo.length()<=PACKET,"invalid Solana business memo");
        return new TransactionInstruction(new PublicKey(MEMO_PROGRAM_ID),List.of(new AccountMeta(new PublicKey(signer),true,false)),
                memo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static Unsigned prepare(String feePayer,String blockhash,List<TransactionInstruction> instructions) {
        var unsigned=new Unsigned(HEX.formatHex(encode(feePayer,blockhash,instructions)));restore(unsigned);return unsigned;
    }
    public static byte[] signingBytes(Unsigned unsigned) {restore(unsigned);return HEX.parseHex(unsigned.messageHex());}
    public static Message restore(Unsigned unsigned) {
        var input=new Reader(HEX.parseHex(unsigned.messageHex()));
        int signatures=input.u8(),readonlySigned=input.u8(),readonlyUnsigned=input.u8(),count=input.compact();
        require(signatures>=1 && signatures<=16 && count>=signatures && count<=256 && readonlySigned<signatures
                && readonlyUnsigned<=count-signatures,"invalid Solana signer/account header");
        var accounts=new ArrayList<AccountMeta>();var distinct=new HashSet<String>();
        for(int i=0;i<count;i++) {
            var key=new PublicKey(input.bytes(32));require(distinct.add(key.toBase58()),"duplicate Solana account key");
            accounts.add(new AccountMeta(key,i<signatures,i<signatures?i<signatures-readonlySigned:i<count-readonlyUnsigned));
        }
        String blockhash=Base58.encode(input.bytes(32));int instructionCount=input.compact();
        require(instructionCount>0 && instructionCount<=256,"invalid Solana instruction count");
        var instructions=new ArrayList<TransactionInstruction>();
        for(int i=0;i<instructionCount;i++) {
            int program=input.u8(),keyCount=input.compact();require(program<count && keyCount<=256,"invalid Solana instruction account index");
            var keys=new ArrayList<AccountMeta>();
            for(int k=0;k<keyCount;k++){int index=input.u8();require(index<count,"Solana instruction account out of bounds");keys.add(accounts.get(index));}
            int length=input.compact();instructions.add(new TransactionInstruction(accounts.get(program).getPublicKey(),keys,input.bytes(length)));
        }
        require(input.done(),"trailing Solana message data");
        var result=new Message(accounts,signatures,blockhash,instructions);
        require(Arrays.equals(input.value,encode(result.feePayer(),blockhash,instructions)),"Solana message is not canonical");
        require(input.value.length+compact(signatures).length+64*signatures<=PACKET,"Solana signed transaction exceeds the packet limit");
        return result;
    }
    public static Signed assemble(Unsigned unsigned,Map<String,byte[]> signatures) {
        var message=restore(unsigned);byte[] raw=HEX.parseHex(unsigned.messageHex());
        require(signatures!=null && signatures.keySet().equals(new HashSet<>(message.signers())),"Solana signature slots differ from the fixed required signers");
        var wire=new ByteArrayOutputStream();wire.writeBytes(compact(message.requiredSignatures()));
        for(String signer:message.signers()) {
            byte[] supplied=signatures.get(signer);require(supplied!=null,"missing Solana MPC signature");
            byte[] signature=supplied.clone();
            require(ThresholdSignatureVerifier.ed25519(new PublicKey(signer).toByteArray(),raw,signature),"Solana MPC signature differs from the exact message and public key");
            wire.writeBytes(signature);
        }
        wire.writeBytes(raw);
        byte[] transaction=wire.toByteArray();int prefix=compact(message.requiredSignatures()).length;
        return new Signed(Base64.getEncoder().encodeToString(transaction),Base58.encode(Arrays.copyOfRange(transaction,prefix,prefix+64)));
    }
    private static byte[] encode(String feePayer,String blockhash,List<TransactionInstruction> instructions) {
        require(instructions!=null && !instructions.isEmpty() && instructions.size()<=256,"Solana instructions are required");
        var accounts=new AccountKeysList();accounts.add(new AccountMeta(new PublicKey(feePayer),true,true));
        for(var instruction:instructions){accounts.addAll(instruction.getKeys());accounts.add(new AccountMeta(instruction.getProgramId(),false,false));}
        var keys=accounts.getList();require(keys.size()<=256 && keys.getFirst().getPublicKey().toBase58().equals(feePayer),"invalid Solana fee payer or account limit");
        var index=new HashMap<String,Integer>();int signed=0,readonlySigned=0,readonlyUnsigned=0;
        for(int i=0;i<keys.size();i++) {
            var key=keys.get(i);index.put(key.getPublicKey().toBase58(),i);
            if(key.isSigner()){signed++;if(!key.isWritable())readonlySigned++;}else if(!key.isWritable())readonlyUnsigned++;
        }
        require(signed>=1 && signed<=16,"invalid Solana signature count");
        byte[] block=Base58.decode(blockhash);require(block.length==32,"invalid Solana recent blockhash");
        var out=new ByteArrayOutputStream();out.write(signed);out.write(readonlySigned);out.write(readonlyUnsigned);out.writeBytes(compact(keys.size()));
        for(var key:keys)out.writeBytes(key.getPublicKey().toByteArray());out.writeBytes(block);out.writeBytes(compact(instructions.size()));
        for(var instruction:instructions) {
            out.write(index.get(instruction.getProgramId().toBase58()));out.writeBytes(compact(instruction.getKeys().size()));
            for(var key:instruction.getKeys())out.write(index.get(key.getPublicKey().toBase58()));
            out.writeBytes(compact(instruction.getData().length));out.writeBytes(instruction.getData());
        }
        require(out.size()<=PACKET,"Solana message exceeds the packet limit");return out.toByteArray();
    }
    private static byte[] compact(int value) {
        require(value>=0 && value<=65535,"Solana compact length is outside u16");
        var out=new ByteArrayOutputStream();do{int next=value&127;value>>>=7;out.write(next|(value==0?0:128));}while(value!=0);return out.toByteArray();
    }
    private static final class Reader {
        private final byte[] value;private int at;
        Reader(byte[] value){this.value=value;}
        int u8(){require(at<value.length,"truncated Solana message");return value[at++]&255;}
        byte[] bytes(int count){require(count>=0 && count<=value.length-at,"truncated Solana message field");var result=Arrays.copyOfRange(value,at,at+count);at+=count;return result;}
        int compact(){int start=at,result=0;for(int shift=0;shift<=14;shift+=7){int b=u8();result|=(b&127)<<shift;if((b&128)==0){require(result<=65535 && Arrays.equals(Arrays.copyOfRange(value,start,at),MpcSolanaTransactionCodec.compact(result)),"noncanonical Solana compact length");return result;}}throw new IllegalArgumentException("invalid Solana compact length");}
        boolean done(){return at==value.length;}
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
