package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.sui.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.mockito.Mockito.*;

/** Actual native PTB, three MPC quorum pairs, owned object mutation, exact principal/net Gas and same-wire replay. */
public final class MpcSuiNativeProbe {
    private MpcSuiNativeProbe() { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,
                              MpcNodes nodes,UUID tenant,JsonNode config)throws Exception {
        var endpoint=local(config.path("suiRpcUrl").asText());var faucet=local(config.path("suiFaucetUrl").asText());
        var json=new ObjectMapper();var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("SUI").orElseThrow();
        jdbc.update("update chain_rpc_node set enabled=false where chain='SUI'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('SUI',?,'mpc-test','mpc-sui','rpc',?,0)",profile.getNetwork(),endpoint.toString());
        var rpc=new SuiRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));
        var identity=rpc.serviceInfo();require(identity.checkpoint()>0,"Sui isolated ledger has no checkpoints");
        long id=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='SUI' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,id).orElseThrow();var key=signing.requireKey(tenant,source.getMpcKeyId());
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response=http.send(HttpRequest.newBuilder(faucet.resolve("/v2/gas")).timeout(Duration.ofSeconds(60))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("FixedAmountRequest",Map.of("recipient",source.getAddress()))))).build(),HttpResponse.BodyHandlers.ofByteArray());
            var result=json.readTree(response.body());require(response.statusCode()==200 && "Success".equals(result.path("status").asText()),"Sui isolated faucet rejected the public joint address");
        }
        long fundingDeadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(rpc.balance(source.getAddress(),SuiRpcClient.SUI_COIN_TYPE).signum()<=0 && System.nanoTime()<fundingDeadline)Thread.sleep(100);
        require(rpc.balance(source.getAddress(),SuiRpcClient.SUI_COIN_TYPE).signum()>0,"Sui joint source was not funded");
        var quorum=spy(nodes);doNothing().when(quorum).close();var transaction=new TransactionTemplate(manager);int index=0;
        for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
            doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
            byte[] recipientBytes=new byte[32];new java.security.SecureRandom().nextBytes(recipientBytes);String recipient="0x"+HexFormat.of().formatHex(recipientBytes);
            var gas=rpc.coins(source.getAddress(),SuiRpcClient.SUI_COIN_TYPE,100).stream().filter(c->c.balance().signum()>0).toList();
            require(!gas.isEmpty(),"Sui source has no owned native gas objects");
            var unsigned=new MpcSuiTransactionCodec.Unsigned(profile.getNetwork(),identity.chainIdentifier(),source.getAddress(),recipient,100_000_000,
                    SuiRpcClient.SUI_COIN_TYPE,List.of(),gas,rpc.referenceGasPrice(),10_000_000,key.publicKeyHex());
            String reference="sui-codec-"+UUID.randomUUID();
            var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.SUI,
                    MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"SUI_PTB_JSON",
                    HexFormat.of().formatHex(json.writeValueAsBytes(unsigned)),HexFormat.of().formatHex(MpcSuiTransactionCodec.signingBytes(unsigned))),quorum.online()));
            signing.awaitSignature(tenant,request.id(),quorum);
            var selected=MpcSuiTransactionCodec.assemble(unsigned,signing.signature(tenant,request.id()).orElseThrow());MpcSuiTransactionCodec.verifySelected(unsigned,selected);
            BigDecimal sourceBefore=rpc.balance(source.getAddress(),SuiRpcClient.SUI_COIN_TYPE),before=rpc.balance(recipient,SuiRpcClient.SUI_COIN_TYPE);
            var answer=rpc.executeSignedTransaction(selected.txBytesBase64(),selected.signatureBase64());
            require(selected.digest().equals(answer.path("digest").asText()),"Sui accepted digest differs from fixed BCS");
            var receipt=await(rpc,selected.digest());require("success".equals(receipt.path("effects").path("status").path("status").asText()),"Sui MPC payment failed on chain");
            var verified=SuiExecutionReceipt.verify(receipt,unsigned,selected);
            BigDecimal net=fee(receipt),sourceAfter=rpc.balance(source.getAddress(),SuiRpcClient.SUI_COIN_TYPE);
            require(verified.paid() && BigDecimal.valueOf(verified.feeMist()).compareTo(net)==0,"Sui original execution proof differs from exact payment/net fee");
            require(rpc.balance(recipient,SuiRpcClient.SUI_COIN_TYPE).subtract(before).compareTo(BigDecimal.valueOf(unsigned.amountAtomic()))==0,"Sui receiver delta differs from signed principal");
            require(sourceBefore.subtract(sourceAfter).subtract(BigDecimal.valueOf(unsigned.amountAtomic())).compareTo(net)==0,"Sui owner delta differs from actual computation, storage and rebate; negative net Gas must remain signed");
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-sui-native-"+(index++)+".json"),json.writeValueAsString(Map.of("unsigned",unsigned,"selected",selected,"receipt",receipt,"sourceBefore",sourceBefore,"sourceAfter",sourceAfter,"feeMist",net)));
            try{require(selected.digest().equals(rpc.executeSignedTransaction(selected.txBytesBase64(),selected.signatureBase64()).path("digest").asText()),"Sui raw replay returned another digest");}
            catch(IllegalStateException known){await(rpc,selected.digest());}
            require(sourceAfter.compareTo(rpc.balance(source.getAddress(),SuiRpcClient.SUI_COIN_TYPE))==0
                    && rpc.balance(recipient,SuiRpcClient.SUI_COIN_TYPE).subtract(before).compareTo(BigDecimal.valueOf(unsigned.amountAtomic()))==0,"Sui same-wire replay paid or charged twice");
        }
    }
    private static URI local(String value){var uri=URI.create(value);require("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()),"Sui component requires its isolated loopback chain and faucet");return uri;}
    private static BigDecimal fee(JsonNode tx){var gas=tx.path("effects").path("gasUsed");return new BigDecimal(gas.path("computationCost").asText()).add(new BigDecimal(gas.path("storageCost").asText())).subtract(new BigDecimal(gas.path("storageRebate").asText()));}
    private static JsonNode await(SuiRpcClient rpc,String hash)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(System.nanoTime()<deadline){var tx=rpc.transactionBlock(hash);if(hash.equals(tx.path("digest").asText()) && tx.path("checkpoint").asLong()>0)return tx;Thread.sleep(100);}throw new AssertionError("Sui fixed digest did not reach a finalized checkpoint");}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
