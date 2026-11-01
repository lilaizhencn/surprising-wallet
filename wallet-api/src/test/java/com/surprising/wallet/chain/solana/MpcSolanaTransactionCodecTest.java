package com.surprising.wallet.chain.solana;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.p2p.solanaj.core.*;
import org.p2p.solanaj.programs.*;
import org.p2p.solanaj.utils.Base58;
import static org.junit.jupiter.api.Assertions.*;

class MpcSolanaTransactionCodecTest {
    private final Account payer=new Account(),source=new Account(),recipient=new Account(),mint=new Account();
    private final String blockhash=Base58.encode(new byte[32]);
    @Test void publicOnlySolMessageAndExternalWitnessMatchSdkReferenceExactly() {
        var instructions=List.of(SystemProgram.transfer(payer.getPublicKey(),recipient.getPublicKey(),12345),MpcSolanaTransactionCodec.memo(payer.getPublicKeyBase58(),"business-one"));
        verify(instructions,List.of(payer));
    }
    @Test void twoSignerSplTransferAndIdempotentAtaUseTheFixedRequiredOrder() {
        var instructions=List.of(AssociatedTokenProgram.createIdempotent(payer.getPublicKey(),recipient.getPublicKey(),mint.getPublicKey()),
                TokenProgram.transferChecked(new Account().getPublicKey(),new Account().getPublicKey(),12345,(byte)6,source.getPublicKey(),mint.getPublicKey()),MpcSolanaTransactionCodec.memo(payer.getPublicKeyBase58(),"business-two"));
        var unsigned=verify(instructions,List.of(payer,source));assertEquals(List.of(payer.getPublicKeyBase58(),source.getPublicKeyBase58()),MpcSolanaTransactionCodec.restore(unsigned).signers());
    }
    @Test void mutatedMessageMissingExtraAndWrongWitnessCannotAssemble() {
        var instructions=List.of(SystemProgram.transfer(payer.getPublicKey(),recipient.getPublicKey(),12345));
        var reference=sdk(instructions,List.of(payer));byte[] signature=Arrays.copyOfRange(reference,1,65);
        var unsigned=MpcSolanaTransactionCodec.prepare(payer.getPublicKeyBase58(),blockhash,instructions);
        var changed=MpcSolanaTransactionCodec.prepare(payer.getPublicKeyBase58(),blockhash,List.of(SystemProgram.transfer(payer.getPublicKey(),recipient.getPublicKey(),12346)));
        assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.assemble(changed,Map.of(payer.getPublicKeyBase58(),signature)));
        assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.assemble(unsigned,Map.of()));
        assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.assemble(unsigned,Map.of(source.getPublicKeyBase58(),signature)));
        assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.assemble(unsigned,Map.of(payer.getPublicKeyBase58(),signature,source.getPublicKeyBase58(),signature)));
        signature[0]^=1;assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.assemble(unsigned,Map.of(payer.getPublicKeyBase58(),signature)));
    }
    @Test void malformedHeadersCompactLengthsDuplicateKeysAndOversizedPacketsAreRejected() {
        var unsigned=MpcSolanaTransactionCodec.prepare(payer.getPublicKeyBase58(),blockhash,List.of(SystemProgram.transfer(payer.getPublicKey(),recipient.getPublicKey(),12345)));
        byte[] raw=MpcSolanaTransactionCodec.signingBytes(unsigned);var hex=HexFormat.of();
        byte[] missing=Arrays.copyOf(raw,raw.length-1);assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.restore(new MpcSolanaTransactionCodec.Unsigned(hex.formatHex(missing))));
        byte[] header=raw.clone();header[0]=0;assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.restore(new MpcSolanaTransactionCodec.Unsigned(hex.formatHex(header))));
        byte[] duplicate=raw.clone();System.arraycopy(duplicate,4,duplicate,36,32);assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.restore(new MpcSolanaTransactionCodec.Unsigned(hex.formatHex(duplicate))));
        byte[] overlong=new byte[raw.length+1];System.arraycopy(raw,0,overlong,0,4);overlong[3]|=(byte)128;System.arraycopy(raw,4,overlong,5,raw.length-4);
        assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.restore(new MpcSolanaTransactionCodec.Unsigned(hex.formatHex(overlong))));
        assertThrows(IllegalArgumentException.class,()->MpcSolanaTransactionCodec.prepare(payer.getPublicKeyBase58(),blockhash,List.of(MpcSolanaTransactionCodec.memo(payer.getPublicKeyBase58(),"x".repeat(1232)))));
    }
    @Test void distinctBusinessMemosSeparateOtherwiseEqualPayments() {
        var a=MpcSolanaTransactionCodec.prepare(payer.getPublicKeyBase58(),blockhash,List.of(SystemProgram.transfer(payer.getPublicKey(),recipient.getPublicKey(),1),MpcSolanaTransactionCodec.memo(payer.getPublicKeyBase58(),"business-A")));
        var b=MpcSolanaTransactionCodec.prepare(payer.getPublicKeyBase58(),blockhash,List.of(SystemProgram.transfer(payer.getPublicKey(),recipient.getPublicKey(),1),MpcSolanaTransactionCodec.memo(payer.getPublicKeyBase58(),"business-B")));
        assertNotEquals(a,b);
    }
    private MpcSolanaTransactionCodec.Unsigned verify(List<TransactionInstruction> instructions,List<Account> signers) {
        byte[] reference=sdk(instructions,signers);
        var unsigned=MpcSolanaTransactionCodec.prepare(signers.getFirst().getPublicKeyBase58(),blockhash,instructions);
        assertArrayEquals(Arrays.copyOfRange(reference,1+64*signers.size(),reference.length),MpcSolanaTransactionCodec.signingBytes(unsigned));
        var witnesses=new HashMap<String,byte[]>();for(int i=0;i<signers.size();i++)witnesses.put(signers.get(i).getPublicKeyBase58(),Arrays.copyOfRange(reference,1+64*i,1+64*(i+1)));
        var signed=MpcSolanaTransactionCodec.assemble(unsigned,witnesses);assertArrayEquals(reference,Base64.getDecoder().decode(signed.transactionBase64()));assertEquals(Base58.encode(witnesses.get(signers.getFirst().getPublicKeyBase58())),signed.signature());return unsigned;
    }
    private byte[] sdk(List<TransactionInstruction> instructions,List<Account> signers) {
        var transaction=new Transaction();instructions.forEach(transaction::addInstruction);transaction.setRecentBlockHash(blockhash);transaction.sign(signers);return transaction.serialize();
    }
}
