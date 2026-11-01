package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.chain.polkadot.PolkadotRuntimeClient;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.MpcSigningService;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.mockito.Mockito.*;

/** Independent native MPC + official relay/AssetHub consensus. Production business admission is tested separately. */
public final class MpcPolkadotNativeProbe {
    private MpcPolkadotNativeProbe() { }
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HexFormat HEX=HexFormat.of();
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config)throws Exception {
        var path=Path.of(config.path("dotFixture").asText()).toRealPath();var fixture=JSON.readTree(Files.readAllBytes(path));
        for(String role:List.of("RELAY","ASSET_HUB")){var endpoint=URI.create(fixture.path(role).path("endpoint").asText());require("ws".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost()),"DOT component requires isolated loopback consensus");}
        var profile=new ChainProfileRepository(jdbc).findAccountByChain("DOT").orElseThrow();
        var gateway=URI.create(fixture.path("runtimeUrl").asText());require("http".equals(gateway.getScheme())&&"127.0.0.1".equals(gateway.getHost()),"DOT public gateway must be isolated loopback");
        jdbc.update("update chain_rpc_node set enabled=false where chain='DOT'");
        for(var role:Role.values())jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('DOT',?,'mpc-test',?,?,?,0)",profile.getNetwork(),"mpc-dot-component-"+role,role==Role.RELAY?"relay_rpc":"rpc",fixture.path("publicRpcUrls").path(role.name()).asText());
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,auth_type,api_key,min_request_interval_ms) values ('DOT',?,'mpc-test','mpc-dot-component-runtime','runtime',?,'BEARER',?,0)",profile.getNetwork(),gateway.toString(),fixture.path("runtimeApiKey").asText());
        var rpc=new PolkadotRuntimeClient(new ChainProfileRepository(jdbc),new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));

        long id=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='DOT' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,id).orElseThrow();var key=signing.requireKey(tenant,source.getMpcKeyId());String recipient=MpcPolkadotTransactionCodec.address(HEX.parseHex("cd".repeat(32)),42);
        for(String role:List.of("RELAY","ASSET_HUB")){call(path,fixture,"fund",role,source.getAddress(),"100000000000000");call(path,fixture,"fund",role,recipient,"1000000000000");}
        for(String symbol:List.of("USDT","USDC"))call(path,fixture,"asset-init","ASSET_HUB",symbol,source.getAddress(),"20000000");
        var quorum=spy(nodes);doNothing().when(quorum).close();var transaction=new TransactionTemplate(manager);int index=0;
        for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
            doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
            for(String mode:List.of("RELAY:DOT","ASSET_HUB:DOT","ASSET_HUB:USDT","ASSET_HUB:USDC")) {
                var parts=mode.split(":");String role=parts[0],symbol=parts[1];var info=rpc.networkInfo(Role.valueOf(role),source.getAddress(),symbol.equals("DOT")?null:fixture.path(symbol).asText());
                require(fixture.path(role).path("genesisHash").asText().equals(info.path("genesisHash").asText()),"DOT component network genesis differs");
                var amount=symbol.equals("DOT")?BigInteger.TEN.pow(info.path("decimals").asInt()).divide(BigInteger.TEN):BigInteger.valueOf(1_000_000);
                var extensions=new ArrayList<String>();info.path("extensions").forEach(item->extensions.add(item.asText()));
                var u=new MpcPolkadotTransactionCodec.Unsigned(profile.getNetwork(),MpcPolkadotTransactionCodec.Role.valueOf(role),info.path("genesisHash").asText(),info.path("specVersion").asLong(),info.path("transactionVersion").asLong(),source.getAddress(),key.publicKeyHex(),recipient,amount,info.path("assetId").isNull()?null:info.path("assetId").asText(),info.path("nonce").asLong(),64,info.path("blockHeight").asLong(),info.path("blockHash").asText(),extensions,JSON.treeToValue(info.path("callIndices"),MpcPolkadotTransactionCodec.CallIndices.class),true);
                var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.DOT,MpcSignCommand.BusinessType.DEV_FAUCET,"dot-codec-"+UUID.randomUUID(),"transaction",MpcSignCommand.Purpose.TRANSACTION,"DOT_SCALE_PAYMENT_JSON",HEX.formatHex(JSON.writeValueAsBytes(u)),HEX.formatHex(MpcPolkadotTransactionCodec.signingBytes(u))),quorum.online()));
                signing.awaitSignature(tenant,request.id(),quorum);var selected=MpcPolkadotTransactionCodec.assemble(u,signing.signature(tenant,request.id()).orElseThrow());MpcPolkadotTransactionCodec.verifySelected(u,selected);
                var before=balance(rpc,fixture,role,source.getAddress(),symbol);var beforeNative=balance(rpc,fixture,role,source.getAddress(),"DOT");var recipientBefore=balance(rpc,fixture,role,recipient,symbol);
                require(selected.txHash().equals(rpc.broadcast(Role.valueOf(role),selected.extrinsicHex())),"DOT consensus accepted another selected transaction hash");
                var proof=proof(rpc,u,selected.txHash());
                require(selected.txHash().equals(proof.path("txHash").asText())&&selected.extrinsicHex().equals(proof.path("extrinsicHex").asText())&&proof.path("finalizedHeight").asLong()>=proof.path("blockHeight").asLong(),"DOT original wire did not finalize");
                int paid=0,success=0,feeCount=0;BigInteger fee=BigInteger.ZERO;
                for(var event:proof.path("events")) {
                    String section=event.path("section").asText(),method=event.path("method").asText();var data=event.path("data");
                    if(section.equals("system")&&method.equals("ExtrinsicSuccess"))success++;
                    if(section.equals("system")&&method.equals("ExtrinsicFailed"))throw new AssertionError("DOT actual MPC extrinsic failed: "+data);
                    if(section.equals("transactionPayment")&&method.equals("TransactionFeePaid")) {
                        require(Arrays.equals(MpcPolkadotTransactionCodec.account(data.get(0).asText()),MpcPolkadotTransactionCodec.account(source.getAddress()))&&"0".equals(data.get(2).asText()),"DOT fee payer or zero tip differs");fee=fee.add(new BigInteger(data.get(1).asText()));feeCount++;
                    }
                    if(symbol.equals("DOT")&&section.equals("balances")&&method.equals("Transfer")) {
                        require(account(data.get(0),source.getAddress())&&account(data.get(1),recipient)&&amount.equals(new BigInteger(data.get(2).asText())),"DOT native event principal differs");paid++;
                    }
                    if(!symbol.equals("DOT")&&section.equals("assets")&&method.equals("Transferred")) {
                        require(u.assetId().equals(data.get(0).asText())&&account(data.get(1),source.getAddress())&&account(data.get(2),recipient)&&amount.equals(new BigInteger(data.get(3).asText())),"DOT actual asset event differs");paid++;
                    }
                }
                require(success==1&&paid==1&&feeCount==1,"DOT finalized payment or actual native fee event is absent/duplicated");
                var after=balance(rpc,fixture,role,source.getAddress(),symbol);var afterNative=balance(rpc,fixture,role,source.getAddress(),"DOT");var recipientAfter=balance(rpc,fixture,role,recipient,symbol);
                require(recipientAfter.subtract(recipientBefore).equals(amount),"DOT actual recipient delta differs from exact principal");
                require(beforeNative.subtract(afterNative).subtract(symbol.equals("DOT")?amount:BigInteger.ZERO).equals(fee),"DOT actual payer native delta differs from finalized fee");
                if(!symbol.equals("DOT"))require(before.subtract(after).equals(amount),"DOT actual asset source delta differs");
                Files.writeString(Path.of("/tmp/surprising-wallet-mpc-dot-native-"+role+"-"+symbol+"-"+index+".json"),JSON.writeValueAsString(Map.of("unsigned",u,"selected",selected,"proof",proof,"feePlanck",fee,"sourceBefore",before,"sourceAfter",after,"nativeBefore",beforeNative,"nativeAfter",afterNative)));
                try{rpc.broadcast(Role.valueOf(role),selected.extrinsicHex());}catch(IllegalStateException known){require(known.getMessage().matches("(?is).*(outdated|already imported|temporarily banned|priority is too low).*"),"DOT raw replay failed for an unrelated reason: "+known.getMessage());}
                require(afterNative.equals(balance(rpc,fixture,role,source.getAddress(),"DOT"))&&recipientAfter.equals(balance(rpc,fixture,role,recipient,symbol)),"DOT selected raw replay paid principal or fees again");
            }
            index++;
        }
    }
    private static boolean account(JsonNode value,String expected){return Arrays.equals(MpcPolkadotTransactionCodec.account(value.asText()),MpcPolkadotTransactionCodec.account(expected));}
    private static BigInteger balance(PolkadotRuntimeClient rpc,JsonNode fixture,String role,String owner,String symbol){return symbol.equals("DOT")?rpc.nativeBalance(Role.valueOf(role),owner):rpc.assetBalance(fixture.path(symbol).asText(),owner);}
    private static JsonNode proof(PolkadotRuntimeClient rpc,MpcPolkadotTransactionCodec.Unsigned u,String hash)throws Exception {
        for(int attempt=0;attempt<45;attempt++){var proof=rpc.proof(u,hash);if(proof.path("found").asBoolean(false))return proof;Thread.sleep(1000);}throw new AssertionError("DOT component original wire did not finalize");
    }
    private static JsonNode call(Path path,JsonNode fixture,String... arguments)throws Exception {
        var command=new ArrayList<String>(List.of("node",Path.of("resources/infra/mpc-signer/tests/polkadot_fixture.cjs").toRealPath().toString(),path.toString()));command.addAll(Arrays.asList(arguments));var output=Files.createTempFile("surprising-wallet-mpc-dot-public-",".json");var log=Files.createTempFile("surprising-wallet-mpc-dot-fixture-",".log");
        var builder=new ProcessBuilder(command);builder.environment().put("NODE_PATH",fixture.path("nodeModules").asText());var process=builder.redirectOutput(output.toFile()).redirectError(log.toFile()).start();if(!process.waitFor(55,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("DOT isolated fixture timed out; see "+log);}
        if(process.exitValue()!=0)throw new IllegalStateException("DOT isolated fixture failed: "+Files.readString(log));return JSON.readTree(Files.readAllBytes(output));
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
