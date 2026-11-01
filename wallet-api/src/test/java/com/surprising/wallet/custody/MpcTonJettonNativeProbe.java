package com.surprising.wallet.custody;

import com.iwebpp.crypto.TweetNaclFast;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.ton.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.MpcTonTestFunding;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.*;
import org.ton.ton4j.smartcontract.token.ft.JettonMinter;
import org.ton.ton4j.smartcontract.token.nft.NftUtils;
import org.ton.ton4j.smartcontract.utils.MsgUtils;
import org.ton.ton4j.tlb.*;
import org.ton.ton4j.utils.Utils;
import tools.jackson.databind.*;
import static org.mockito.Mockito.*;

/** Actual TEP-74 source, destination, notification and excess trace; formal wallet settlement is separate. */
public final class MpcTonJettonNativeProbe {
    private static final HexFormat HEX=HexFormat.of();
    private MpcTonJettonNativeProbe() { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,
                              MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var json=new ObjectMapper();var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("TON").orElseThrow();
        var rpc=new TonCenterClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));
        long id=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='TON' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,id).orElseThrow();var key=signing.requireKey(tenant,source.getMpcKeyId());
        var quorum=spy(nodes);doNothing().when(quorum).close();var transaction=new TransactionTemplate(manager);
        try(var fixture=new Admin(rpc,profile.getNetwork(),Path.of(config.path("tonFunderKeyFile").asText()))) {
            var masters=new LinkedHashMap<String,String>();
            for(String symbol:List.of("USDT","USDC")) {
                var minter=JettonMinter.builder().adminAddress(Address.of(fixture.address))
                        .content(NftUtils.createOffChainUriCell("https://example.invalid/mpc-ton/"+symbol+"/"+UUID.randomUUID())).wc(0).build();
                String master=minter.getAddress().toRaw();
                fixture.send(master,500_000_000L,minter.getStateInit(),null,false);
                awaitActive(rpc,master);
                fixture.send(master,150_000_000L,null,JettonMinter.createMintBody(1,Address.of(source.getAddress()),
                        BigInteger.valueOf(80_000_000),BigInteger.valueOf(20_000_000),null,Address.of(fixture.address),BigInteger.ZERO,null),true);
                String wallet=rpc.jettonWallet(source.getAddress(),master);awaitActive(rpc,wallet);
                require(rpc.jettonData(wallet).balance().equals(BigInteger.valueOf(20_000_000)),"TON fixture mint did not reach actual joint-owner Jetton wallet");
                masters.put(symbol,master);
            }
            int index=0;
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String recipient="0:"+HEX.formatHex(bytes);
                for(var token:masters.entrySet()) {
                    String reference="ton-jetton-codec-"+UUID.randomUUID(),master=token.getValue();
                    String sourceWallet=rpc.jettonWallet(source.getAddress(),master),targetWallet=rpc.jettonWallet(recipient,master);
                    var before=rpc.jettonData(sourceWallet);String sourceRaw=MpcTonTransactionCodec.canonicalAddress(source.getAddress());
                    require(before.owner().equals(sourceRaw)&&before.master().equals(master),"TON source getter owner/master differs from configured mint");
                    var unsigned=new MpcTonTransactionCodec.Unsigned(profile.getNetwork(),source.getAddress(),recipient,BigInteger.valueOf(1_000_000),
                            master,sourceWallet,targetWallet,rpc.seqno(source.getAddress()),Instant.now().plusSeconds(600).getEpochSecond(),++index,reference,key.publicKeyHex());
                    var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.TON,
                            MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"TON_V4R2_BODY_HASH",
                            HEX.formatHex(MpcTonTransactionCodec.body(unsigned).toBoc(false)),HEX.formatHex(MpcTonTransactionCodec.signingBytes(unsigned))),quorum.online()));
                    signing.awaitSignature(tenant,request.id(),quorum);
                    var selected=MpcTonTransactionCodec.assemble(unsigned,signing.signature(tenant,request.id()).orElseThrow());MpcTonTransactionCodec.verifySelected(unsigned,selected);
                    long sourceTonBefore=rpc.balance(source.getAddress());
                    Files.writeString(Path.of("/tmp/surprising-wallet-mpc-ton-jetton-intent-"+index+".json"),json.writeValueAsString(Map.of("unsigned",unsigned,"selected",selected,"sourceTonBefore",sourceTonBefore)));
                    require(TonCenterClient.sameHash(rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64())),selected.messageHashHex()),"TON node returned a different selected Jetton message hash");
                    JsonNode owner=await(rpc,unsigned.sender(),selected.messageHashHex());var ownerTx=parse(owner,unsigned.sender());success(ownerTx);
                    var toSource=onlyTo(owner,sourceWallet);JsonNode sourceReceipt=await(rpc,sourceWallet,hash(toSource));var sourceTx=parse(sourceReceipt,sourceWallet);success(sourceTx);
                    var toTarget=onlyTo(sourceReceipt,targetWallet);JsonNode targetReceipt=await(rpc,targetWallet,hash(toTarget));var targetTx=parse(targetReceipt,targetWallet);success(targetTx);
                    var returns=new ArrayList<JsonNode>();
                    Files.writeString(Path.of("/tmp/surprising-wallet-mpc-ton-jetton-progress.json"),json.writeValueAsString(Map.of("unsigned",unsigned,"selected",selected,"owner",owner,"sourceJetton",sourceReceipt,"recipientJetton",targetReceipt)));
                    for(var message:outputs(targetReceipt)) {
                        var destination=message.message().getInfo().getDestinationAddress();
                        require(destination.equals(unsigned.sender())||destination.equals(unsigned.recipient()),"TON fixture transfer emitted an unexpected destination");
                        if(destination.equals(unsigned.sender()))returns.add(await(rpc,destination,hash(message)));
                        // TEP-74 credit is committed by the destination Jetton wallet. Its notification
                        // may bounce when the external owner's wallet is undeployed; token credit survives.
                    }
                    var after=rpc.jettonData(sourceWallet);var target=rpc.jettonData(targetWallet);
                    require(before.balance().subtract(after.balance()).equals(unsigned.amountAtomic()),"TON actual source Jetton debit differs from signed principal");
                    require(target.balance().equals(unsigned.amountAtomic())&&target.owner().equals(unsigned.recipient())&&target.master().equals(master),"TON actual target Jetton credit/owner/master differs");
                    BigInteger netCost=ownerTx.getTotalFees().getCoins().add(toSource.message().getInfo().getValueCoins())
                            .add(((InternalMessageInfo)toSource.message().getInfo()).getFwdFee());
                    for(var returned:returns){var returnedTx=parse(returned,unsigned.sender());success(returnedTx);
                        require(returnedTx.getOutMsgCount()==0,"TON excess recipient unexpectedly forwarded native funds");
                        netCost=netCost.subtract(returnedTx.getInOut().getIn().getInfo().getValueCoins()).add(returnedTx.getTotalFees().getCoins());}
                    require(sourceTonBefore-rpc.balance(source.getAddress())==netCost.longValueExact(),"TON source native debit differs from exact attachment/forwarding cost and actual excess refunds");
                    require(TonExecutionReceipt.verifySuccessful(new TonExecutionReceipt.Trace(owner,sourceReceipt,targetReceipt,returns),unsigned,selected).feeNano()==netCost.longValueExact(),
                            "TON successful Jetton receipt component differs from actual sender native debit");
                    Files.writeString(Path.of("/tmp/surprising-wallet-mpc-ton-jetton-"+index+".json"),json.writeValueAsString(Map.of("symbol",token.getKey(),"unsigned",unsigned,"selected",selected,
                            "owner",owner,"sourceJetton",sourceReceipt,"recipientJetton",targetReceipt,"returns",returns,"sourceTonBefore",sourceTonBefore,"sourceTonAfter",rpc.balance(source.getAddress()))));
                    try{rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64()));}catch(IllegalStateException duplicate){await(rpc,unsigned.sender(),selected.messageHashHex());}
                    require(rpc.jettonData(targetWallet).balance().equals(unsigned.amountAtomic()),"TON same-message replay transferred Jettons twice");
                }
            }
            String master=masters.get("USDC"),sourceWallet=rpc.jettonWallet(source.getAddress(),master);
            var rejected=new MpcTonTransactionCodec.Unsigned(profile.getNetwork(),source.getAddress(),fixture.address,BigInteger.valueOf(25_000_000),
                    master,sourceWallet,rpc.jettonWallet(fixture.address,master),rpc.seqno(source.getAddress()),Instant.now().plusSeconds(600).getEpochSecond(),++index,
                    "ton-source-rejected-"+UUID.randomUUID(),key.publicKeyHex());
            var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.TON,
                    MpcSignCommand.BusinessType.DEV_FAUCET,rejected.memo(),"transaction",MpcSignCommand.Purpose.TRANSACTION,"TON_V4R2_BODY_HASH",
                    HEX.formatHex(MpcTonTransactionCodec.body(rejected).toBoc(false)),HEX.formatHex(MpcTonTransactionCodec.signingBytes(rejected))),quorum.online()));
            signing.awaitSignature(tenant,request.id(),quorum);
            var selected=MpcTonTransactionCodec.assemble(rejected,signing.signature(tenant,request.id()).orElseThrow());
            var tokenBefore=rpc.jettonData(sourceWallet).balance();long tonBefore=rpc.balance(source.getAddress());
            rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64()));
            var owner=await(rpc,rejected.sender(),selected.messageHashHex());var sent=onlyTo(owner,sourceWallet);
            var sourceReceipt=await(rpc,sourceWallet,hash(sent));var bounce=onlyTo(sourceReceipt,rejected.sender());
            var returned=await(rpc,rejected.sender(),hash(bounce));
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-ton-jetton-rejected.json"),json.writeValueAsString(Map.of("unsigned",rejected,"selected",selected,"owner",owner,
                    "sourceJetton",sourceReceipt,"returns",List.of(returned),"sourceTonBefore",tonBefore,"sourceTonAfter",rpc.balance(source.getAddress()))));
            var failure=TonExecutionReceipt.verifyRejectedJetton(new TonExecutionReceipt.Trace(owner,sourceReceipt,null,List.of(returned)),rejected,selected);
            require(rpc.jettonData(sourceWallet).balance().equals(tokenBefore) && rpc.seqno(rejected.sender())==rejected.sequence()+1,
                    "TON rejected source transfer spent tokens or did not consume owner sequence");
            require(tonBefore-rpc.balance(source.getAddress())==failure.feeNano() && failure.feeNano()>0,"TON rejected Jetton fee differs from actual owner debit and bounce credit");
            long after=rpc.balance(source.getAddress());
            try{rpc.sendBoc(Base64.getDecoder().decode(selected.bocBase64()));}catch(IllegalStateException duplicate){/* original seqno already consumed */}
            Thread.sleep(3_000);require(rpc.balance(source.getAddress())==after && rpc.jettonData(sourceWallet).balance().equals(tokenBefore),"TON rejected Jetton replay charged or transferred again");
        }
    }
    private record Output(Cell raw,Message message) { }
    /** Preserve each on-chain cell's inline/ref layout; reserializing a parsed Message may change its hash. */
    private static List<Output> outputs(JsonNode receipt){
        var io=CellSlice.beginParse(CellSlice.beginParse(Cell.fromBocBase64(receipt.path("data").asText())).loadRef());
        if(io.loadBit())io.loadRef();
        var dictionary=io.loadDictE(15,k->k.readUint(15),v->CellSlice.beginParse(v).loadRef());
        return dictionary.elements.values().stream().map(Cell.class::cast)
                .map(c->new Output(c,Message.deserialize(CellSlice.beginParse(c)))).toList();
    }
    private static Output onlyTo(JsonNode receipt,String destination){var messages=outputs(receipt).stream().filter(m->m.message().getInfo().getDestinationAddress().equals(destination)).toList();require(messages.size()==1,"TON trace has no unique expected internal delivery");return messages.getFirst();}
    private static String hash(Output m){return HEX.formatHex(m.raw().hash());}
    private static Transaction parse(JsonNode receipt,String address){var cell=Cell.fromBocBase64(receipt.path("data").asText());var tx=Transaction.deserialize(CellSlice.beginParse(cell));require(TonCenterClient.sameHash(receipt.path("transaction_id").path("hash").asText(),HEX.formatHex(cell.hash()))&&tx.getAccountAddr().equals(HEX.formatHex(Address.of(address).hashPart)),"TON transaction raw/hash/account differs");return tx;}
    private static void success(Transaction tx){require(tx.getDescription() instanceof TransactionDescriptionOrdinary d&&!d.isAborted()&&d.getComputePhase() instanceof ComputePhaseVM vm&&vm.isSuccess()&&d.getActionPhase()!=null&&d.getActionPhase().isSuccess(),"TON expected transfer hop failed");}
    private static JsonNode await(TonCenterClient rpc,String address,String hash)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(System.nanoTime()<deadline){var tx=rpc.findIncomingMessageTransaction(address,hash,100);if(tx.isPresent())return tx.get();Thread.sleep(100);}throw new AssertionError("TON exact internal message was not consumed: "+address+" "+hash);}
    static void awaitActive(TonCenterClient rpc,String address)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(System.nanoTime()<deadline){if("active".equals(rpc.addressInformation(address).path("state").asText()))return;Thread.sleep(100);}throw new AssertionError("TON fixture contract did not become active");}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static final class Admin implements AutoCloseable {
        final TweetNaclFast.Signature.KeyPair pair=Utils.generateSignatureKeyPair();final TonCenterClient rpc;final String network,address;
        Admin(TonCenterClient rpc,String network,Path funder)throws Exception{this.rpc=rpc;this.network=network;address=MpcTonTransactionCodec.canonicalAddress(MpcTonTransactionCodec.address(network,pair.getPublicKey()));MpcTonTestFunding.fund(rpc,funder,address,25_000_000_000L);}
        void send(String destination,long amount,StateInit init,Cell payload,boolean bounce)throws Exception{long sequence=rpc.seqno(address);var wallet=MpcTonTransactionCodec.wallet(network,pair.getPublicKey());
            var internal=MsgUtils.createInternalMessageRelaxed(Address.of(destination),BigInteger.valueOf(amount),null,init,payload,bounce);
            var body=CellBuilder.beginCell().storeUint(MpcTonTransactionCodec.walletId(network),32).storeUint(Instant.now().plusSeconds(600).getEpochSecond(),32).storeUint(sequence,32).storeUint(0,8).storeUint(1,8).storeRef(internal.toCell()).endCell();
            var cell=MsgUtils.createExternalMessageWithSignedBody(Utils.signData(pair.getPublicKey(),pair.getSecretKey(),body.hash()),wallet.getAddress(),sequence==0?wallet.getStateInit():null,body).toCell();
            require(TonCenterClient.sameHash(rpc.sendBoc(cell.toBoc(false)),HEX.formatHex(cell.hash())),"TON fixture admin returned a different hash");success(parse(await(rpc,address,HEX.formatHex(cell.hash())),address));}
        public void close(){Arrays.fill(pair.getSecretKey(),(byte)0);}
    }
}
