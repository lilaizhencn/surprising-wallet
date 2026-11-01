package com.surprising.wallet.custody;

import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.mockito.Mockito.*;

/** Actual three-node MPC and official isolated NEAR consensus, final receipt fees and same-wire replay. */
public final class MpcNearNativeProbe {
    private MpcNearNativeProbe() { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config)throws Exception {
        var json=new ObjectMapper();var endpoint=URI.create(config.path("nearRpcUrl").asText());var fixturePath=Path.of(config.path("nearFixture").asText()).toRealPath();var fixture=json.readTree(Files.readAllBytes(fixturePath));
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()) && endpoint.toString().equals(fixture.path("endpoint").asText()),"NEAR component requires its isolated loopback fixture ledger");
        var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("NEAR").orElseThrow();
        jdbc.update("update chain_rpc_node set enabled=false where chain='NEAR'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('NEAR',?,'mpc-test','mpc-near','rpc',?,0)",profile.getNetwork(),endpoint.toString());
        var rpc=new NearRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));var status=rpc.status();
        require("mpc-localnet-2134".equals(status.path("chain_id").asText()) && fixture.path("genesisHash").asText().equals(status.path("genesis_hash").asText()),"NEAR fixture genesis identity differs");
        long validity=rpc.genesisConfig().path("transaction_validity_period").asLong();require(validity>0,"NEAR reference validity period is absent");
        long id=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='NEAR' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,id).orElseThrow();var key=signing.requireKey(tenant,source.getMpcKeyId());
        fixtureCall(fixturePath,fixture,"fund",source.getAddress(),"100000000000000000000000000");
        require(rpc.accountBalanceYocto(source.getAddress()).equals(new BigInteger("100000000000000000000000000")),"NEAR joint source funding differs");
        String recipient;try(var input=MpcNearNativeProbe.class.getResourceAsStream("/mpc/near-codec-reference.json")){recipient=json.readTree(input).path("vectors").get(0).path("unsigned").path("sender").asText();}
        var quorum=spy(nodes);doNothing().when(quorum).close();var transaction=new TransactionTemplate(manager);int index=0;
        for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
            doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
            var access=rpc.accessKey(source.getAddress(),org.bitcoinj.base.Base58.encode(HexFormat.of().parseHex(key.publicKeyHex())));
            require("FullAccess".equals(access.path("permission").asText()),"NEAR joint access key is not FullAccess");long height=access.path("block_height").asLong();
            var u=new MpcNearTransactionCodec.Unsigned(profile.getNetwork(),status.path("genesis_hash").asText(),source.getAddress(),Math.addExact(access.path("nonce").asLong(),1),recipient,index==2?BigInteger.ONE:new BigInteger("100000000000000000000000"),null,0,null,height,Math.addExact(height,validity),access.path("block_hash").asText(),key.publicKeyHex());
            String reference="near-codec-"+UUID.randomUUID();var request=transaction.execute(tx->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.NEAR,MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"NEAR_BORSH_JSON",HexFormat.of().formatHex(json.writeValueAsBytes(u)),HexFormat.of().formatHex(MpcNearTransactionCodec.signingBytes(u))),quorum.online()));
            signing.awaitSignature(tenant,request.id(),quorum);var selected=MpcNearTransactionCodec.assemble(u,signing.signature(tenant,request.id()).orElseThrow());MpcNearTransactionCodec.verifySelected(u,selected);
            BigInteger sourceBefore=rpc.accountBalanceYocto(source.getAddress()),targetBefore=rpc.accountExists(recipient)?rpc.accountBalanceYocto(recipient):BigInteger.ZERO;
            var answer=rpc.broadcastTxCommit(selected.signedTransactionBase64());require(selected.txHash().equals(answer.path("transaction").path("hash").asText()),"NEAR accepted another selected hash");
            var receipt=rpc.finalTransaction(selected.txHash(),source.getAddress());require("FINAL".equals(receipt.path("final_execution_status").asText()) && receipt.path("status").has("SuccessValue"),"NEAR did not finalize the actual MPC payment");
            require(NearExecutionReceipt.verify(receipt,u,selected).paid(),"NEAR original payment receipt verification failed");
            BigInteger sourceAfter=rpc.accountBalanceYocto(source.getAddress()),targetAfter=rpc.accountBalanceYocto(recipient),fee=fee(receipt);
            require(targetAfter.subtract(targetBefore).equals(u.amountAtomic()),"NEAR receiver delta differs from signed principal");
            require(sourceBefore.subtract(sourceAfter).subtract(u.amountAtomic()).equals(fee),"NEAR source delta differs from actual outcome tokens_burnt");
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-near-native-"+(index++)+".json"),json.writeValueAsString(Map.of("unsigned",u,"selected",selected,"receipt",receipt,"feeYocto",fee,"sourceBefore",sourceBefore,"sourceAfter",sourceAfter)));
            try{require(selected.txHash().equals(rpc.broadcastTxCommit(selected.signedTransactionBase64()).path("transaction").path("hash").asText()),"NEAR raw replay returned another hash");}catch(IllegalStateException known){rpc.finalTransaction(selected.txHash(),source.getAddress());}
            require(sourceAfter.equals(rpc.accountBalanceYocto(source.getAddress())) && targetAfter.equals(rpc.accountBalanceYocto(recipient)),"NEAR same-wire replay paid principal or Gas twice");
        }
        for(String symbol:List.of("USDT","USDC")) {
            String contract=fixture.path(symbol).asText();fixtureCall(fixturePath,fixture,"register",contract,source.getAddress());
            fixtureCall(fixturePath,fixture,"token-fund",contract,source.getAddress(),"20000000");fixtureCall(fixturePath,fixture,"register",contract,recipient);
            int pairIndex=0;
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                var access=rpc.accessKey(source.getAddress(),org.bitcoinj.base.Base58.encode(HexFormat.of().parseHex(key.publicKeyHex())));long height=access.path("block_height").asLong();
                String reference="near-token-"+symbol+"-"+UUID.randomUUID();
                var u=new MpcNearTransactionCodec.Unsigned(profile.getNetwork(),status.path("genesis_hash").asText(),source.getAddress(),Math.addExact(access.path("nonce").asLong(),1),recipient,BigInteger.valueOf(1_000_000),contract,30_000_000_000_000L,reference,height,Math.addExact(height,validity),access.path("block_hash").asText(),key.publicKeyHex());
                var request=transaction.execute(tx->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.NEAR,MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"NEAR_BORSH_JSON",HexFormat.of().formatHex(json.writeValueAsBytes(u)),HexFormat.of().formatHex(MpcNearTransactionCodec.signingBytes(u))),quorum.online()));
                signing.awaitSignature(tenant,request.id(),quorum);var selected=MpcNearTransactionCodec.assemble(u,signing.signature(tenant,request.id()).orElseThrow());MpcNearTransactionCodec.verifySelected(u,selected);
                BigInteger before=rpc.accountBalanceYocto(source.getAddress()),targetBefore=tokenBalance(rpc,contract,recipient,json),sourceBefore=tokenBalance(rpc,contract,source.getAddress(),json);
                require(selected.txHash().equals(rpc.broadcastTxCommit(selected.signedTransactionBase64()).path("transaction").path("hash").asText()),"NEAR token returned another selected hash");
                var receipt=rpc.finalTransaction(selected.txHash(),source.getAddress());require("FINAL".equals(receipt.path("final_execution_status").asText()) && receipt.path("status").has("SuccessValue"),"NEAR actual MPC token payment failed");
                require(NearExecutionReceipt.verify(receipt,u,selected).paid(),"NEAR original token receipt verification failed");
                BigInteger after=rpc.accountBalanceYocto(source.getAddress()),expense=fee(receipt).add(BigInteger.ONE);
                require(before.subtract(after).equals(expense),"NEAR actual token expense must include the retained one yoctoNEAR in addition to outcome fees");
                require(tokenBalance(rpc,contract,recipient,json).subtract(targetBefore).equals(u.amountAtomic()) && sourceBefore.subtract(tokenBalance(rpc,contract,source.getAddress(),json)).equals(u.amountAtomic()),"NEAR actual token balances differ from exact principal");
                Files.writeString(Path.of("/tmp/surprising-wallet-mpc-near-token-"+symbol+"-"+(pairIndex++)+".json"),json.writeValueAsString(Map.of("unsigned",u,"selected",selected,"receipt",receipt,"feeYocto",fee(receipt),"expenseYocto",expense,"sourceBefore",before,"sourceAfter",after)));
                try{rpc.broadcastTxCommit(selected.signedTransactionBase64());}catch(IllegalStateException known){rpc.finalTransaction(selected.txHash(),source.getAddress());}
                require(after.equals(rpc.accountBalanceYocto(source.getAddress())) && tokenBalance(rpc,contract,recipient,json).subtract(targetBefore).equals(u.amountAtomic()),"NEAR token replay paid or charged twice");
            }
        }
        for(int failureKind:List.of(0,1,2)) {
            boolean tokenFailure=failureKind!=0,outOfGas=failureKind==2;
            var access=rpc.accessKey(source.getAddress(),org.bitcoinj.base.Base58.encode(HexFormat.of().parseHex(key.publicKeyHex())));long height=access.path("block_height").asLong();
            String reference="near-failed-"+UUID.randomUUID(),receiver=tokenFailure?recipient:"missing-"+UUID.randomUUID().toString().replace("-","")+".near";
            var u=new MpcNearTransactionCodec.Unsigned(profile.getNetwork(),status.path("genesis_hash").asText(),source.getAddress(),Math.addExact(access.path("nonce").asLong(),1),receiver,tokenFailure?BigInteger.valueOf(outOfGas?1_000_000:18_000_000):new BigInteger("100000000000000000000000"),tokenFailure?fixture.path("USDC").asText():null,tokenFailure?(outOfGas?1_000_000_000_000L:30_000_000_000_000L):0,tokenFailure?reference:null,height,Math.addExact(height,validity),access.path("block_hash").asText(),key.publicKeyHex());
            var request=transaction.execute(tx->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.NEAR,MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"NEAR_BORSH_JSON",HexFormat.of().formatHex(json.writeValueAsBytes(u)),HexFormat.of().formatHex(MpcNearTransactionCodec.signingBytes(u))),quorum.online()));
            signing.awaitSignature(tenant,request.id(),quorum);var selected=MpcNearTransactionCodec.assemble(u,signing.signature(tenant,request.id()).orElseThrow());
            BigInteger before=rpc.accountBalanceYocto(source.getAddress()),targetBefore=tokenFailure?tokenBalance(rpc,u.tokenContract(),recipient,json):BigInteger.ZERO;
            require(selected.txHash().equals(rpc.broadcastTxCommit(selected.signedTransactionBase64()).path("transaction").path("hash").asText()),"NEAR accepted another failed transaction hash");
            var receipt=rpc.finalTransaction(selected.txHash(),source.getAddress());var verified=NearExecutionReceipt.verify(receipt,u,selected);
            BigInteger after=rpc.accountBalanceYocto(source.getAddress());require(!verified.paid() && before.subtract(after).equals(verified.networkFeeYocto()) && verified.expenseYocto().equals(verified.networkFeeYocto()),"NEAR committed failure did not restore the native principal or one-yocto deposit");
            require(tokenFailure?targetBefore.equals(tokenBalance(rpc,u.tokenContract(),recipient,json)):!rpc.accountExists(receiver),"NEAR failed action paid principal");
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-near-failed-"+(outOfGas?"out-of-gas":tokenFailure?"token":"native")+".json"),json.writeValueAsString(Map.of("unsigned",u,"selected",selected,"receipt",receipt,"feeYocto",verified.networkFeeYocto(),"expenseYocto",verified.expenseYocto(),"sourceBefore",before,"sourceAfter",after)));
            try{rpc.broadcastTxCommit(selected.signedTransactionBase64());}catch(IllegalStateException known){rpc.finalTransaction(selected.txHash(),source.getAddress());}
            require(after.equals(rpc.accountBalanceYocto(source.getAddress())),"NEAR failed raw replay burned gas again");
        }
    }
    private static BigInteger tokenBalance(NearRpcClient rpc,String contract,String owner,ObjectMapper json){return new BigInteger(rpc.viewFunctionJson(contract,"ft_balance_of",json.writeValueAsBytes(Map.of("account_id",owner))).asText());}
    private static void fixtureCall(Path fixturePath,JsonNode fixture,String... arguments)throws Exception {
        var command=new ArrayList<String>(List.of("node",Path.of("resources/infra/mpc-signer/tests/near_fixture.cjs").toRealPath().toString(),fixturePath.toString()));command.addAll(Arrays.asList(arguments));
        var log=Files.createTempFile("surprising-wallet-mpc-near-admin-",".log");var builder=new ProcessBuilder(command);builder.environment().put("NODE_PATH",fixture.path("nodeModules").asText());
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("NEAR fixture action timed out; see "+log);}require(process.exitValue()==0,"NEAR fixture action failed; see "+log);
    }
    private static BigInteger fee(JsonNode receipt){var total=new BigInteger(receipt.path("transaction_outcome").path("outcome").path("tokens_burnt").asText());for(var item:receipt.path("receipts_outcome"))total=total.add(new BigInteger(item.path("outcome").path("tokens_burnt").asText()));return total;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
