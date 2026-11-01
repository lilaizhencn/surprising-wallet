package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.aptos.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.mockito.Mockito.*;

/** Actual legacy-Ed25519 MPC/BCS/hash/consensus component. Formal Aptos business remains separate. */
public final class MpcAptosNativeProbe {
    private MpcAptosNativeProbe() { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var endpoint=URI.create(config.path("aptosRpcUrl").asText());var faucet=URI.create(config.path("aptosFaucetUrl").asText());
        for(var uri:List.of(endpoint,faucet))require("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()),"Aptos component requires isolated loopback localnet and faucet");
        var json=new ObjectMapper();var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("APTOS").orElseThrow();
        jdbc.update("update chain_rpc_node set enabled=false where chain='APTOS'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url) values ('APTOS',?,'mpc-test','mpc-aptos','rpc',?),('APTOS',?,'mpc-test','mpc-faucet','faucet',?)",profile.getNetwork(),endpoint.toString(),profile.getNetwork(),faucet.toString());
        var rpc=new AptosRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));require(rpc.chainId()==4,"Aptos probe refuses a nonlocal chain ID");
        long sourceId=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='APTOS' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,sourceId).orElseThrow();var key=signing.requireKey(tenant,source.getMpcKeyId());
        String recipient="0x"+"ab".repeat(32);rpc.fundDevnetAccount(source.getAddress(),1_000_000_000L);
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(rpc.aptBalance(source.getAddress())<1_000_000_000L && System.nanoTime()<deadline)Thread.sleep(100);
        require(rpc.aptBalance(source.getAddress())>=1_000_000_000L,"Aptos fixture faucet did not fund joint owner");
        var quorum=spy(nodes);doNothing().when(quorum).close();var transaction=new TransactionTemplate(manager);var ids=new HashSet<String>();
        for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
            doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();String reference="aptos-codec-"+UUID.randomUUID();
            var unsigned=new MpcAptosTransactionCodec.Unsigned(source.getAddress(),rpc.sequenceNumber(source.getAddress()),recipient,12345,null,200_000,rpc.estimateGasPrice(),Instant.now().plusSeconds(600).getEpochSecond(),4,key.publicKeyHex());
            String preimage=HexFormat.of().formatHex(MpcAptosTransactionCodec.signingBytes(unsigned));
            var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.APTOS,
                    MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"APTOS_RAW_BCS",
                    HexFormat.of().formatHex(MpcAptosTransactionCodec.raw(unsigned)),preimage),quorum.online()));
            signing.awaitSignature(tenant,request.id(),quorum);var signed=MpcAptosTransactionCodec.assemble(unsigned,signing.signature(tenant,request.id()).orElseThrow());
            require(ids.add(signed.txId()),"Aptos distinct sequences selected the same hash");long before=rpc.aptBalance(recipient),sourceBefore=rpc.aptBalance(source.getAddress());
            require(signed.txId().equals(rpc.submitTransaction(json.readValue(signed.json(),ObjectNode.class))),"Aptos node hash differs from selected BCS transaction");
            var receipt=await(rpc,signed.txId());
            require(MpcAptosTransactionCodec.canonicalAddress(receipt.path("sender").asText()).equals(unsigned.sender()) && receipt.path("sequence_number").asLong()==unsigned.sequence()
                    && receipt.path("signature").equals(json.readTree(signed.json()).path("signature")),"Aptos committed receipt differs from fixed source, sequence or witness");
            require(rpc.aptBalance(recipient)-before==12345,"Aptos MPC payment recipient delta differs");
            require(sourceBefore-rpc.aptBalance(source.getAddress())-12345==receipt.path("gas_used").asLong()*unsigned.gasUnitPrice(),"Aptos source debit differs from principal and actual fee");
            try {require(rpc.submitTransaction(json.readValue(signed.json(),ObjectNode.class)).equals(signed.txId()),"Aptos duplicate submission returned a different hash");}
            catch(IllegalStateException duplicate){await(rpc,signed.txId());}
            require(rpc.aptBalance(recipient)-before==12345,"Aptos identical-wire replay paid twice");
        }
    }
    private static JsonNode await(AptosRpcClient rpc,String hash) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(System.nanoTime()<deadline){var receipt=rpc.transactionByHash(hash);
            if(receipt!=null && "user_transaction".equals(receipt.path("type").asText())){require(receipt.path("success").asBoolean() && hash.equals(receipt.path("hash").asText()),"Aptos localnet rejected MPC payment: "+receipt.path("vm_status"));return receipt;}Thread.sleep(100);}
        throw new AssertionError("Aptos localnet did not commit selected MPC payment");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
