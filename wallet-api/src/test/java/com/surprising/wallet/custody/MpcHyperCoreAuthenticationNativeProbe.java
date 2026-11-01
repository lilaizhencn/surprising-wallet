package com.surprising.wallet.custody;

import com.surprising.wallet.chain.hypercore.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Official testnet signer recovery for unfunded accounts. This is NOT funded transfer acceptance. */
public final class MpcHyperCoreAuthenticationNativeProbe {
    private MpcHyperCoreAuthenticationNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var endpoint=URI.create(config.path("hypercoreTestnetUrl").asText());
        require(endpoint.toString().equals("https://api.hyperliquid-testnet.xyz"),"HyperCore auth probe requires the official testnet origin");
        var json=new ObjectMapper();var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("HYPERCORE").orElseThrow();
        jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true,transfer_enabled=true where chain='HYPERCORE' and enabled");
        require("testnet".equals(profile.getNetwork()),"HyperCore auth probe cannot submit mainnet actions");
        jdbc.update("update chain_rpc_node set enabled=false where chain='HYPERCORE'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url) values ('HYPERCORE','testnet','mpc-test','official-testnet','info',?),('HYPERCORE','testnet','mpc-test','official-testnet','exchange',?)",endpoint.toString(),endpoint.toString());
        var realApi=new HyperCoreApiClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));
        long sourceId=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='HYPERCORE' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,sourceId).orElseThrow();
        var role=realApi.postInfo(json.createObjectNode().put("type","userRole").put("user",source.getAddress()));
        require("missing".equals(role.path("role").asText()),"Authentication-only probe refuses an activated or funded account");
        var meta=realApi.postInfo(json.createObjectNode().put("type","spotMeta"));
        var token=java.util.stream.StreamSupport.stream(meta.path("tokens").spliterator(),false).filter(item->"HYPE".equals(item.path("name").asText())).findFirst().orElseThrow();
        new HyperCoreTokenMetadataRepository(jdbc).upsert("testnet","HYPE",token.path("index").asInt(),token.path("tokenId").asText(),token.path("szDecimals").asInt(),token.path("weiDecimals").asInt(),token.path("isCanonical").asBoolean(),null,null,Timestamp.from(Instant.now()));
        var orders=new WithdrawalOrderRepository(jdbc);var actions=new HyperCoreActionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);
        var current=new AtomicReference<HyperCoreSubmissionService.Operation>();var response=new AtomicReference<JsonNode>();var sent=new ArrayList<String>();
        var api=spy(realApi);var quorum=spy(nodes);doNothing().when(quorum).close();
        doAnswer(call->{
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"HyperCore API submit held a business transaction");
            var op=current.get();String id=orders.find("HYPERCORE",op.reference(),tenant).orElseThrow().getTxHash();
            require(id!=null && actions.find(id).orElseThrow().raw().equals(call.getArgument(0).toString()),"HyperCore network preceded raw/action ID commit");
            require(requests.findBusiness(tenant,ChainType.HYPERCORE,MpcSignCommand.BusinessType.WITHDRAWAL,op.reference(),"transaction").orElseThrow().state()==MpcSignRequest.State.COMPLETED,"HyperCore network preceded durable MPC result");
            sent.add(call.getArgument(0).toString());var result=(JsonNode)call.callRealMethod();response.set(result);return result;
        }).when(api).postExchange(any());
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
            context.registerBean(HyperCoreActionRepository.class,()->actions);context.registerBean(HyperCoreTokenMetadataRepository.class,()->new HyperCoreTokenMetadataRepository(jdbc));
            context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));context.registerBean(WalletMpcSignRequestRepository.class,()->requests);
            context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);context.registerBean(ChainProfileRepository.class,()->profiles);
            context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
            context.registerBean(ObjectMapper.class,()->json);context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(WithdrawalSettlementService.class);
            context.registerBean(HyperCoreSubmissionService.class);context.registerBean(HyperCoreApiClient.class,()->api);
            context.registerBean(WalletRuntimeConfigService.class,()->com.surprising.wallet.testutil.RuntimeTestSupport.database(jdbc));context.registerBean(HyperCoreTransactionService.class);context.refresh();
            var transfers=context.getBean(HyperCoreTransactionService.class);var submissions=context.getBean(HyperCoreSubmissionService.class);
            var transaction=new TransactionTemplate(manager);
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                String reference="hypercore-auth-"+UUID.randomUUID(),account=reference+"-principal";
                current.set(new HyperCoreSubmissionService.Operation(tenant,reference,HyperCoreSubmissionService.Kind.WITHDRAWAL));
                transaction.executeWithoutResult(status->{
                    jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'HYPERCORE','HYPE',?,5,5)",tenant,account);
                    orders.createForTenant(tenant,reference,42,"HYPERCORE","HYPE",source.getAddress(),account,"0x"+"ab".repeat(20),BigDecimal.ONE,new BigDecimal("0.1"));
                    require(balances.freeze("HYPERCORE","HYPE",account,new BigDecimal("1.1"),tenant),"HyperCore test principal was not frozen");
                    orders.updateStatus(tenant,"HYPERCORE",reference,"FROZEN",source.getAddress(),null,null);require(orders.claimSigningDurable(tenant,"HYPERCORE",reference,source.getAddress())==1,"HyperCore test order not claimed");
                });
                response.set(null);int before=sent.size();
                // Authentication-only component probe: deliberately submit an unfunded signer directly.
                // Production payouts separately require an already activated recipient to exclude activation fees.
                var request=submissions.reserve(current.get(),source,"0x"+"ab".repeat(20));submissions.sign(current.get(),request.id());
                var prepared=submissions.commit(current.get(),request.id());
                try {
                    var answer=api.postExchange(json.readValue(prepared.body(),ObjectNode.class));
                    submissions.accepted(current.get(),prepared.actionId(),answer);
                    throw new AssertionError("Unfunded official HyperCore account unexpectedly executed a transfer");
                }
                catch(IllegalStateException expected) {
                    require(sent.size()>before && response.get()!=null,"Official HyperCore returned no authentication result: "+expected);
                    String message=response.get().toString().toLowerCase(Locale.ROOT);
                    require("err".equals(response.get().path("status").asText()) && message.contains(source.getAddress().toLowerCase(Locale.ROOT)),"Official HyperCore did not recover the joint owner from the signature: "+response.get());
                }
                submissions.unknown(current.get(),"official unfunded signer authentication probe");
                String id=orders.find("HYPERCORE",reference,tenant).orElseThrow().getTxHash();
                require("UNKNOWN".equals(actions.find(id).orElseThrow().status()) && submissions.releaseUnsigned(current.get())==0,"HyperCore rejection lost the original intent or released funds");
                require(balances.find(tenant,"HYPERCORE","HYPE",account).orElseThrow().getLockedBalance().compareTo(new BigDecimal("1.1"))==0,"HyperCore unknown result released principal");
            }
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
