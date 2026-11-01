package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.ton.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.ton.ton4j.cell.*;
import org.ton.ton4j.tlb.*;
import tools.jackson.databind.*;
import static org.mockito.Mockito.*;

/** Actual native V4R2/Ed25519 component. Formal business and Jetton execution are separate acceptance. */
public final class MpcTonNativeProbe {
    private MpcTonNativeProbe() { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,
                              MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var endpoint=URI.create(config.path("tonRpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()),"TON component requires an isolated loopback node");
        var json=new ObjectMapper();var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("TON").orElseThrow();
        jdbc.update("update chain_rpc_node set enabled=false where chain='TON'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('TON',?,'mpc-test','mpc-ton','rpc',?,0)",profile.getNetwork(),endpoint.toString());
        var rpc=new TonCenterClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));
        require(rpc.masterchainInfo().path("last").path("seqno").asLong()>0,"TON isolated node has no blocks");
        long sourceId=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='TON' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,sourceId).orElseThrow();var key=signing.requireKey(tenant,source.getMpcKeyId());
        String fundingOwner=com.surprising.wallet.testutil.MpcTonTestFunding.fund(rpc,Path.of(config.path("tonFunderKeyFile").asText()),source.getAddress(),25_000_000_000L);
        var quorum=spy(nodes);doNothing().when(quorum).close();var transaction=new TransactionTemplate(manager);
        int index=0;
        for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
            byte[] receiverBytes=new byte[32];new java.security.SecureRandom().nextBytes(receiverBytes);
            String recipient=index==2?fundingOwner:"0:"+HexFormat.of().formatHex(receiverBytes);
            doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
            String reference="ton-codec-"+UUID.randomUUID();
            var unsigned=new MpcTonTransactionCodec.Unsigned(profile.getNetwork(),source.getAddress(),recipient,
                    BigInteger.valueOf(100_000_000),null,null,null,rpc.seqno(source.getAddress()),
                    Instant.now().plusSeconds(600).getEpochSecond(),0,reference,key.publicKeyHex());
            var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.TON,
                    MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"TON_V4R2_BODY_HASH",
                    HexFormat.of().formatHex(MpcTonTransactionCodec.body(unsigned).toBoc(false)),
                    HexFormat.of().formatHex(MpcTonTransactionCodec.signingBytes(unsigned))),quorum.online()));
            signing.awaitSignature(tenant,request.id(),quorum);
            var selected=MpcTonTransactionCodec.assemble(unsigned,signing.signature(tenant,request.id()).orElseThrow());
            MpcTonTransactionCodec.verifySelected(unsigned,selected);
            long before=rpc.balance(recipient),sourceBefore=rpc.balance(source.getAddress());
            String actual=rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64()));
            require(sameHash(actual,selected.messageHashHex()),"TON node returned a different external message hash");
            JsonNode receipt=await(rpc,source.getAddress(),selected.messageHashHex());
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-ton-native-"+(index++)+".json"),json.writeValueAsString(Map.of("unsigned",unsigned,"selected",selected,"receipt",receipt)));
            var chain=Transaction.deserialize(CellSlice.beginParse(Cell.fromBocBase64(receipt.path("data").asText())));
            require(chain.getDescription() instanceof TransactionDescriptionOrdinary ordinary && !ordinary.isAborted()
                    && ordinary.getComputePhase() instanceof ComputePhaseVM vm && vm.isSuccess()
                    && ordinary.getActionPhase()!=null && ordinary.getActionPhase().isSuccess(),"TON V4R2 payment failed on chain");
            var outgoing=(Message)chain.getInOut().getOut().elements.values().iterator().next();
            String outgoingHash=HexFormat.of().formatHex(outgoing.toCell().hash());
            var delivered=awaitIncoming(rpc,recipient,outgoingHash);
            var receiver=Transaction.deserialize(CellSlice.beginParse(Cell.fromBocBase64(delivered.path("data").asText())));
            require(HexFormat.of().formatHex(receiver.getInOut().getIn().toCell().hash()).equals(outgoingHash),"TON receiver transaction does not consume the exact sender message");
            require(receiver.getOutMsgCount()==0,"TON component receiver unexpectedly forwarded funds");
            long receiverFee=receiver.getTotalFees().getCoins().longValueExact();
            require(rpc.balance(recipient)-before==100_000_000L-receiverFee,"TON receiver credit differs from exact principal and its actual processing/storage fee");
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-ton-delivery-"+(index-1)+".json"),json.writeValueAsString(delivered));
            var info=(InternalMessageInfo)outgoing.getInfo();
            require(info.getExtraFlags().signum()==0,"TON component message has unexpected extra flags");
            long sourceFee=chain.getTotalFees().getCoins().add(info.getFwdFee()).longValueExact();
            require(sourceFee>0 && sourceBefore-rpc.balance(source.getAddress())==100_000_000L+sourceFee,
                    "TON source debit differs from exact principal and actual sender/forwarding/storage fees");
            require(TonExecutionReceipt.verifySuccessful(new TonExecutionReceipt.Trace(receipt,null,delivered,List.of()),unsigned,selected).feeNano()==sourceFee,
                    "TON successful receipt component differs from actual native sender fee");
            try {require(sameHash(rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64())),selected.messageHashHex()),"TON repeated raw returned a different hash");}
            catch(IllegalStateException duplicate){await(rpc,source.getAddress(),selected.messageHashHex());}
            require(rpc.balance(recipient)-before==100_000_000L-receiverFee,"TON identical-message replay paid twice");
        }
        var skipped=new MpcTonTransactionCodec.Unsigned(profile.getNetwork(),source.getAddress(),fundingOwner,
                BigInteger.valueOf(1_000_000_000_000L),null,null,null,rpc.seqno(source.getAddress()),
                Instant.now().plusSeconds(600).getEpochSecond(),0,"ton-skipped-"+UUID.randomUUID(),key.publicKeyHex());
        var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.TON,
                MpcSignCommand.BusinessType.DEV_FAUCET,skipped.memo(),"transaction",MpcSignCommand.Purpose.TRANSACTION,"TON_V4R2_BODY_HASH",
                HexFormat.of().formatHex(MpcTonTransactionCodec.body(skipped).toBoc(false)),
                HexFormat.of().formatHex(MpcTonTransactionCodec.signingBytes(skipped))),quorum.online()));
        signing.awaitSignature(tenant,request.id(),quorum);
        var selected=MpcTonTransactionCodec.assemble(skipped,signing.signature(tenant,request.id()).orElseThrow());
        long sourceBefore=rpc.balance(source.getAddress()),recipientBefore=rpc.balance(fundingOwner);
        rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64()));
        var receipt=await(rpc,source.getAddress(),selected.messageHashHex());
        var proof=new TonExecutionReceipt.Trace(receipt,null,null,List.of());
        var failure=TonExecutionReceipt.verifySkippedPayment(proof,skipped,selected);
        require(failure.feeNano()>0 && sourceBefore-rpc.balance(source.getAddress())==failure.feeNano(),"TON skipped payment charged more than its actual fee");
        require(rpc.seqno(source.getAddress())==skipped.sequence()+1 && rpc.balance(fundingOwner)==recipientBefore,
                "TON skipped payment did not consume seqno or paid the recipient");
        Files.writeString(Path.of("/tmp/surprising-wallet-mpc-ton-skipped.json"),json.writeValueAsString(Map.of("unsigned",skipped,"selected",selected,"receipt",receipt)));
        long after=rpc.balance(source.getAddress());
        try {rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64()));}catch(IllegalStateException duplicate){/* seqno rejects the identical external message */}
        Thread.sleep(3_000);
        require(rpc.seqno(source.getAddress())==skipped.sequence()+1 && rpc.balance(source.getAddress())==after,
                "TON skipped-payment replay consumed the seqno or charged a fee again");
    }
    private static JsonNode await(TonCenterClient rpc,String sender,String hash)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<deadline){var receipt=rpc.findExternalMessageTransaction(sender,hash,100);if(receipt.isPresent())return receipt.get();Thread.sleep(100);}
        throw new AssertionError("TON node did not commit selected external message");
    }
    private static JsonNode awaitIncoming(TonCenterClient rpc,String recipient,String hash)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<deadline){for(var tx:rpc.transactions(recipient,100))if(sameHash(tx.path("in_msg").path("hash").asText(),hash))return tx;Thread.sleep(100);}
        throw new AssertionError("TON recipient did not consume the exact internal message");
    }
    private static boolean sameHash(String a,String hex){try{return Arrays.equals(Base64.getDecoder().decode(a),HexFormat.of().parseHex(hex));}catch(IllegalArgumentException invalid){return hex.equalsIgnoreCase(a);}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
