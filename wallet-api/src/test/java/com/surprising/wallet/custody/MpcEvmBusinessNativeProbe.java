package com.surprising.wallet.custody;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.chain.evm.EvmHttpServiceFactory;
import com.surprising.wallet.chain.evm.EvmTransactionBuilder;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.service.EvmSubmissionService;
import com.surprising.wallet.service.MpcSigningService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import tools.jackson.databind.ObjectMapper;

/** Actual custody key -> business nonce/intent -> native MPC -> Anvil -> frozen-principal settlement. */
final class MpcEvmBusinessNativeProbe {
    private static final String RECIPIENT="0x"+"cd".repeat(20);
    private static final String PRINCIPAL="mpc-native-business-principal";
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    private MpcEvmBusinessNativeProbe() { }

    static void verify(JdbcTemplate jdbc, DataSourceTransactionManager manager, MpcSigningService signing,
                       MpcNodes nodes, UUID tenant, String endpoint) throws Exception {
        var json=new ObjectMapper();
        long addressId=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='ETH' and subject='native-customer'",
                Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,addressId).orElseThrow();
        var orders=new WithdrawalOrderRepository(jdbc);
        var transactions=new EvmTransactionRepository(jdbc);
        var collections=new CollectionRecordRepository(jdbc);
        var recoveries=new CustodyAssetRecoveryRepository(jdbc);
        var nonces=new EvmNonceRepository(jdbc);
        var requests=new WalletMpcSignRequestRepository(jdbc);
        var profiles=new ChainProfileRepository(jdbc);
        var balances=new LedgerBalanceRepository(jdbc);
        var transaction=new TransactionTemplate(manager);
        var broadcasts=new AtomicInteger();
        var loseReplies=new java.util.concurrent.atomic.AtomicBoolean(true);
        var uniqueBroadcasts=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        String[] reference={null};
        var proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try (var context=new AnnotationConfigApplicationContext();var http=new EvmHttpServiceFactory();var forward=HttpClient.newHttpClient()) {
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);
            context.registerBean(LedgerBalanceRepository.class,()->balances);
            context.registerBean(WithdrawalSettlementService.class);
            context.registerBean(EvmSubmissionService.class,()->new EvmSubmissionService(orders,collections,transactions,nonces,recoveries,
                    requests,signing,nodes,json,profiles));
            context.refresh();
            var submissions=context.getBean(EvmSubmissionService.class);
            proxy.createContext("/",exchange->{
                try {
                    byte[] body=exchange.getRequestBody().readAllBytes();
                    var request=json.readTree(body);
                    String method=request.path("method").asText();
                    if (method.equals("eth_sendRawTransaction") || method.equals("eth_call")) {
                        var order=orders.find("ETH",reference[0],tenant).orElseThrow();
                        require(order.getTxHash()!=null,"RPC received usable signature before business hash commit");
                        var persisted=transactions.findSigned("ETH",order.getTxHash()).orElseThrow();
                        require(Hash.sha3(persisted.getRawPayload()).equalsIgnoreCase(order.getTxHash()),"committed raw/hash differ");
                        require(submissions.intent(new EvmSubmissionService.Operation(tenant,reference[0],EvmSubmissionService.Kind.WITHDRAWAL),"ETH")
                                .orElseThrow().state()==com.surprising.wallet.model.mpc.MpcSignRequest.State.COMPLETED,"RPC preceded durable MPC result");
                        if (method.equals("eth_sendRawTransaction")) {
                            require(request.path("params").get(0).asText().equals(persisted.getRawPayload()),"broadcast bytes differ from committed bytes");
                            uniqueBroadcasts.add(persisted.getRawPayload());
                            broadcasts.incrementAndGet();
                        }
                    }
                    var response=forward.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
                    // The node really accepted and mined the first transaction; deliberately lose only its HTTP reply.
                    if(method.equals("eth_sendRawTransaction") && loseReplies.get()) { exchange.close();return; }
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(response.statusCode(),response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (InterruptedException interrupted) {Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
                finally {exchange.close();}
            });
            proxy.start();
            jdbc.update("update chain_profile set chain_id=31337,withdraw_confirmations=1,fee_model='standard' where chain='ETH' and enabled=true");
            jdbc.update("update chain_rpc_node set enabled=false where chain='ETH'");
            jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url) values ('ETH',?,'mpc-test','native-business','rpc',?)",
                    profiles.findAccountByChain("ETH").orElseThrow().getNetwork(),"http://127.0.0.1:"+proxy.getAddress().getPort());
            jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH',?,5,0,5)",tenant,PRINCIPAL);
            rpc(forward,json,endpoint,"anvil_setBalance",List.of(source.getAddress(),"0x56bc75e2d63100000"));
            var service=new EvmAccountTransactionService(orders,collections,transactions,profiles,new ChainAssetRepository(jdbc),
                    new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",1),new EvmTransactionBuilder(),http,
                    context.getBean(WithdrawalSettlementService.class),submissions,recoveries);
            BigInteger before=Numeric.decodeQuantity(rpc(forward,json,endpoint,"eth_getBalance",List.of(RECIPIENT,"latest")).asText());
            for(String policy:List.of("legacy-gas-price","eip1559")) {
                jdbc.update("update chain_profile set gas_policy=? where chain='ETH' and enabled=true",policy);
                reference[0]="native-business-"+policy;
                String orderNo=reference[0];
                transaction.executeWithoutResult(status->{
                    orders.createForTenant(tenant,orderNo,source.getUserId(),"ETH","ETH",source.getAddress(),PRINCIPAL,RECIPIENT,BigDecimal.ONE,BigDecimal.ZERO);
                    require(balances.freeze("ETH","ETH",PRINCIPAL,BigDecimal.ONE,tenant),"principal freeze failed");
                    orders.updateStatus(tenant,"ETH",orderNo,"FROZEN",source.getAddress(),null,null);
                    require(orders.claimSigningDurable(tenant,"ETH",orderNo,source.getAddress())==1,"withdrawal not claimed");
                });
                var operation=new EvmSubmissionService.Operation(tenant,orderNo,EvmSubmissionService.Kind.WITHDRAWAL);
                boolean lost=false;
                try { service.sendNative(operation,"ETH",source,RECIPIENT,BigDecimal.ONE); }
                catch(RuntimeException lostReply) {
                    require(policy.equals("legacy-gas-price") && broadcasts.get()>=1,"unexpected native business failure: "+lostReply);
                    lost=true;
                } finally {loseReplies.set(false);}
                require(!policy.equals("legacy-gas-price") || lost,"the accepted broadcast must lose its application response");
                int beforeRecovery=broadcasts.get();
                require(service.recover(operation,"ETH"),"committed withdrawal was not recovered");
                require(beforeRecovery==broadcasts.get(),"recovery rebroadcast an already known transaction");
                var intent=submissions.intent(operation,"ETH").orElseThrow();
                require("SENT".equals(submissions.existing(operation,"ETH").getStatus()),"observed broadcast did not advance the transaction state");
                String raw=submissions.existing(operation,"ETH").getRawPayload();
                require(policy.equals("legacy-gas-price") ? !raw.startsWith("0x02"):raw.startsWith("0x02"),"gas policy produced wrong envelope");
                require(service.confirmWithdrawal(tenant,"ETH",orderNo),"real receipt did not settle frozen principal");
                require(!service.confirmWithdrawal(tenant,"ETH",orderNo),"principal settled twice");
                require(!service.recover(operation,"ETH"),"confirmed order reopened");
                require(intent.id().equals(submissions.intent(operation,"ETH").orElseThrow().id()),"recovery replaced accepted intent");
                require(raw.equals(submissions.existing(operation,"ETH").getRawPayload()),"recovery signed new bytes");
            }
            require(uniqueBroadcasts.size()==2,"network retries changed the accepted transaction");
            BigInteger after=Numeric.decodeQuantity(rpc(forward,json,endpoint,"eth_getBalance",List.of(RECIPIENT,"latest")).asText());
            require(after.subtract(before).equals(BigInteger.TEN.pow(18).multiply(BigInteger.TWO)),"recipient was paid a different amount or twice");
            require(jdbc.queryForObject("select locked_balance=0 and available_balance=3 and total_balance=3 from ledger_balance where tenant_id=? and chain='ETH' and account_id=?",
                    Boolean.class,tenant,PRINCIPAL),"frozen principal ledger did not settle exactly once");
            require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where tenant_id=? and business_reference like 'native-business-%'",Long.class,tenant)==2,
                    "business replay allocated another MPC intent");
        } finally {proxy.stop(0);}
    }
    private static tools.jackson.databind.JsonNode rpc(HttpClient http,ObjectMapper json,String endpoint,String method,List<?> params) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params)))).build(),HttpResponse.BodyHandlers.ofByteArray());
        var result=json.readTree(response.body());require(response.statusCode()==200 && !result.has("error"),"Anvil rejected "+method+": "+result);
        return result.path("result");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
