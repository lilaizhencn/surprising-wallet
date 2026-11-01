package com.surprising.wallet.custody;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.solana.MpcSolanaTransactionCodec;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.MpcSigningService;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.programs.SystemProgram;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.mockito.Mockito.*;

/** Actual Ed25519 MPC and Agave legacy-message consensus; wallet business integration is separate. */
public final class MpcSolanaNativeProbe {
    private MpcSolanaNativeProbe() { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        URI endpoint=URI.create(config.path("solanaRpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()),"Solana probe requires isolated loopback Agave");
        var json=new ObjectMapper();var transaction=new TransactionTemplate(manager);
        long sourceId=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='SOLANA' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,sourceId).orElseThrow();
        var key=signing.requireKey(tenant,source.getMpcKeyId());
        var target=signing.awaitKey(tenant,ChainType.SOLANA,0,source.getBiz(),9301,nodes);
        String recipient=MpcPublicAddressCodec.encode(new ChainProfileRepository(jdbc).findAccountByChain("SOLANA").orElseThrow(),HexFormat.of().parseHex(target.publicKeyHex()));
        var quorum=spy(nodes);doNothing().when(quorum).close();
        try(var web=HttpClient.newHttpClient()) {
            String airdrop=rpc(web,json,endpoint,"requestAirdrop",List.of(source.getAddress(),1_000_000_000L,Map.of("commitment","confirmed"))).asText();
            await(web,json,endpoint,airdrop);
            String blockhash=rpc(web,json,endpoint,"getLatestBlockhash",List.of(Map.of("commitment","confirmed"))).path("value").path("blockhash").asText();
            var ids=new HashSet<String>();
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                String reference="solana-codec-"+UUID.randomUUID();
                var unsigned=MpcSolanaTransactionCodec.prepare(source.getAddress(),blockhash,List.of(
                        SystemProgram.transfer(new PublicKey(source.getAddress()),new PublicKey(recipient),1_000_000L),
                        MpcSolanaTransactionCodec.memo(source.getAddress(),reference)));
                var request=transaction.execute(status->signing.reserveSignature(new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.SOLANA,
                        MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,"SOLANA_MESSAGE_HEX",
                        unsigned.messageHex(),unsigned.messageHex()),quorum.online()));
                signing.awaitSignature(tenant,request.id(),quorum);
                var signed=MpcSolanaTransactionCodec.assemble(unsigned,Map.of(source.getAddress(),signing.signature(tenant,request.id()).orElseThrow()));
                require(ids.add(signed.signature()),"Different Solana business memos produced the same transaction ID");
                BigInteger before=balance(web,json,endpoint,recipient);
                String observed=rpc(web,json,endpoint,"sendTransaction",List.of(signed.transactionBase64(),Map.of("encoding","base64","preflightCommitment","confirmed","maxRetries",0))).asText();
                require(observed.equals(signed.signature()),"Agave returned a different selected transaction signature");await(web,json,endpoint,observed);
                var receipt=rpc(web,json,endpoint,"getTransaction",List.of(observed,Map.of("encoding","base64","commitment","confirmed","maxSupportedTransactionVersion",0)));
                require(!receipt.isNull() && receipt.path("meta").path("err").isNull()
                        && signed.transactionBase64().equals(receipt.path("transaction").path(0).asText()),"Agave receipt does not contain the fixed raw transaction");
                require(balance(web,json,endpoint,recipient).subtract(before).equals(BigInteger.valueOf(1_000_000)),"Solana native MPC transfer delivered a different amount");
                try {require(rpc(web,json,endpoint,"sendTransaction",List.of(signed.transactionBase64(),Map.of("encoding","base64","preflightCommitment","confirmed","maxRetries",0))).asText().equals(observed),"Solana duplicate returned another signature");}
                catch(IllegalStateException duplicate){await(web,json,endpoint,observed);}
                require(balance(web,json,endpoint,recipient).subtract(before).equals(BigInteger.valueOf(1_000_000)),"Solana identical-byte replay paid twice");
            }
        }
    }
    private static BigInteger balance(HttpClient web,ObjectMapper json,URI endpoint,String address) throws Exception {
        return new BigInteger(rpc(web,json,endpoint,"getBalance",List.of(address,Map.of("commitment","confirmed"))).path("value").asText());
    }
    private static void await(HttpClient web,ObjectMapper json,URI endpoint,String signature) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline) {
            var result=rpc(web,json,endpoint,"getSignatureStatuses",List.of(List.of(signature),Map.of("searchTransactionHistory",true))).path("value").path(0);
            if(!result.isNull() && !result.isMissingNode()) {
                require(result.path("err").isNull(),"Agave executed a failed transaction: "+result.path("err"));
                if(Set.of("confirmed","finalized").contains(result.path("confirmationStatus").asText()))return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Solana transaction did not reach confirmed commitment");
    }
    private static JsonNode rpc(HttpClient web,ObjectMapper json,URI endpoint,String method,List<?> parameters) throws Exception {
        var body=json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",1,"method",method,"params",parameters));
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response=web.send(request,HttpResponse.BodyHandlers.ofByteArray());var root=json.readTree(response.body());
        if(root.hasNonNull("error"))throw new IllegalStateException("Agave RPC "+method+" failed: "+root.path("error"));
        require(response.statusCode()==200 && root.has("result"),"Agave RPC returned no result");return root.path("result");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
