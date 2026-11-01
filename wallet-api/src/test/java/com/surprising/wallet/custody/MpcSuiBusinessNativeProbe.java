package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.sui.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual three-node MPC, official Sui consensus and local Coin fixtures. */
public final class MpcSuiBusinessNativeProbe {
    private MpcSuiBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var json=new ObjectMapper();var fixture=json.readTree(Files.readAllBytes(Path.of(config.path("suiCoinFixture").asText())));var endpoint=URI.create(config.path("suiRpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()) && endpoint.toString().equals(fixture.path("endpoint").asText()),"Sui business requires isolated loopback fixture chain");
        var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("SUI").orElseThrow();
        jdbc.update("update chain_profile set default_fee_rate=10000000,withdraw_confirmations=1,deposit_confirmations=1,scan_batch_size=250,scan_max_blocks_per_run=250,withdraw_enabled=true,scan_enabled=true,collection_enabled=true,transfer_enabled=true where chain='SUI' and enabled");
        var realRpc=new SuiRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));var rpc=spy(realRpc);
        var custody=MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager);var addresses=new ChainAddressRepository(jdbc);
        UUID actor=jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);
        var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-mpc-probe","TENANT_ADMIN",Set.of("*"));
        var sourceView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("SUI","sui-business-source",0L,null,Map.of()),"API","127.0.0.1");
        var targetView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("SUI","sui-business-collector",0L,null,Map.of()),"API","127.0.0.1");
        var custodyRows=new CustodyAddressRepository(jdbc);var source=addresses.findOwned(tenant,custodyRows.require(tenant,sourceView.id()).chainAddressId()).orElseThrow();var collector=addresses.findOwned(tenant,custodyRows.require(tenant,targetView.id()).chainAddressId()).orElseThrow();
        var tokens=new TokenConfigRepository(jdbc);var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);var transactions=new SuiTransactionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);var transaction=new TransactionTemplate(manager);
        var runtime=new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc));var quorum=spy(nodes);doNothing().when(quorum).close();
        var lost=new AtomicBoolean();var broadcasts=new ArrayList<String>();SuiSubmissionService.Operation[] current={null};
        doAnswer(call->{
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"Sui RPC submission held a business transaction");var op=Objects.requireNonNull(current[0]);
            String hash=op.kind()==SuiSubmissionService.Kind.WITHDRAWAL?orders.findTxHash("SUI",op.reference(),tenant).orElseThrow():collections.findTxHash(tenant,"SUI",op.reference()).orElseThrow();
            var wire=json.readValue(transactions.find(hash).orElseThrow().getRawPayload(),MpcSuiTransactionCodec.Signed.class);
            require(hash.equals(wire.digest()) && wire.txBytesBase64().equals(call.getArgument(0)) && wire.signatureBase64().equals(call.getArgument(1)),"Sui selected raw/digest reached RPC before durable business commit");
            require(requests.findBusiness(tenant,ChainType.SUI,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),"transaction").orElseThrow().state()==MpcSignRequest.State.COMPLETED,"Sui RPC preceded durable MPC result");
            broadcasts.add(hash);var answer=call.callRealMethod();if(lost.getAndSet(false))throw new IllegalStateException("deliberately lost Sui broadcast reply");return answer;
        }).when(rpc).executeSignedTransaction(anyString(),anyString());
        try(var context=new AnnotationConfigApplicationContext();var depositContext=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper())) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);context.registerBean(SuiTransactionRepository.class,()->transactions);
            context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);context.registerBean(ChainProfileRepository.class,()->profiles);context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(TokenConfigRepository.class,()->tokens);
            context.registerBean(ObjectMapper.class,()->json);context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(WithdrawalSettlementService.class);context.registerBean(SuiSubmissionService.class);
            context.registerBean(SuiRpcClient.class,()->rpc);context.registerBean(WalletRuntimeConfigService.class,()->runtime);context.registerBean(SuiTransactionService.class);context.refresh();
            var service=context.getBean(SuiTransactionService.class);var submissions=context.getBean(SuiSubmissionService.class);var heights=new ChainScanHeightRepository(jdbc);
            var scanner=new SuiDepositScanner(realRpc,profiles,tokens,heights,addresses,transactions,depositContext.getBean(DepositService.class),new ChainAssetRepository(jdbc),new DepositRecordRepository(jdbc),runtime);
            long fundingStart=realRpc.latestCheckpoint();
            for(String symbol:List.of("USDC","USDT")) {
                String metadata=MpcSuiTransactionCodec.canonicalCoinType(fixture.path(symbol).path("coinType").asText());
                jdbc.update("insert into token_config(chain,network,symbol,standard,contract_address,contract_address_hex,decimals,enabled,collect_enabled,gas_strategy) values ('SUI',?,?,'COIN',?,?,6,true,true,'SUI_GAS_OBJECT') on conflict(chain,network,symbol) do update set contract_address=excluded.contract_address,contract_address_hex=excluded.contract_address_hex,decimals=6,enabled=true",profile.getNetwork(),symbol,metadata,metadata);
                new ChainAssetRepository(jdbc).upsertNonNative("SUI",symbol,"COIN",metadata,6,true,BigDecimal.ZERO,BigDecimal.ZERO);
                mint(fixture,symbol,source.getAddress());
            }
            String fundingHash=fund(URI.create(config.path("suiFaucetUrl").asText()),source.getAddress(),json);await(realRpc,fundingHash,false);
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(balance(realRpc,"SUI",fixture,source.getAddress())!=1_000_000_000_000L && System.nanoTime()<deadline)Thread.sleep(100);
            require(balance(realRpc,"SUI",fixture,source.getAddress())==1_000_000_000_000L,"Sui fresh source was not funded exactly");
            heights.upsert("SUI","sui-balance-change-scanner",fundingStart-1,fundingStart-1);var observedDeposits=scanner.scanAndCredit();
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-sui-business-deposit-observation.json"),json.writeValueAsString(Map.of("source",source.getAddress(),"fundingStart",fundingStart,"fundingHash",fundingHash,"latest",realRpc.latestCheckpoint(),"safeHeight",heights.findSafeHeight("SUI","sui-balance-change-scanner").orElse(-1L),"deposits",observedDeposits)));
            var totals=new HashMap<String,BigDecimal>();for(String symbol:List.of("SUI","USDT","USDC"))totals.put(symbol,balances.find(tenant,"SUI",symbol,source.getAccountId()).orElseThrow(()->new IllegalStateException("Sui finalized funding deposit missing: "+symbol)).getTotalBalance());
            require(totals.get("SUI").compareTo(new BigDecimal("1000"))==0 && totals.get("USDT").compareTo(new BigDecimal("20"))==0 && totals.get("USDC").compareTo(new BigDecimal("20"))==0,"Sui actual SUI/Coin deposits did not credit exact tenant amounts");
            long depositCount=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SUI'",Long.class,tenant);scanner.scanAndCredit();require(depositCount==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SUI'",Long.class,tenant),"Sui deposit replay duplicated tenant projection");
            long beforeControlled=Math.addExact(balance(realRpc,"SUI",fixture,source.getAddress()),balance(realRpc,"SUI",fixture,collector.getAddress())),fees=0;String recipient="0x"+"cd".repeat(32);var hashes=new HashSet<String>();int round=0;
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for(String symbol:List.of("SUI","USDT","USDC")) {
                    BigDecimal amount=symbol.equals("SUI") && round==0?new BigDecimal("601"):BigDecimal.ONE;
                    String reference="sui-business-"+UUID.randomUUID();var op=new SuiSubmissionService.Operation(tenant,reference,SuiSubmissionService.Kind.WITHDRAWAL);current[0]=op;
                    createOrder(transaction,orders,balances,tenant,source,reference,symbol,recipient,amount,new BigDecimal("0.1"));long before=balance(realRpc,symbol,fixture,recipient);int count=broadcasts.size();lost.set(true);
                    try{service.send(op,source);throw new AssertionError("Sui fixture did not lose a real accepted reply");}catch(IllegalStateException expected){require(broadcasts.size()==count+1,"Sui failed before deliberate lost reply: "+expected);submissions.unknown(op,"fixture lost response");}
                    String hash=orders.findTxHash("SUI",reference,tenant).orElseThrow();await(realRpc,hash,false);require(hashes.add(hash),"Sui different owned operations selected the same hash");String raw=transactions.find(hash).orElseThrow().getRawPayload();
                    jdbc.update("update chain_profile set withdraw_enabled=false,collection_enabled=false where chain='SUI' and enabled");jdbc.update("update token_config set enabled=false where chain='SUI'");
                    require(service.recover(op),"Sui did not recover original message after new-work switches disabled");require(broadcasts.size()==count+1,"Sui known transaction was submitted again");
                    require(service.confirmWithdrawal(tenant,reference) && !service.confirmWithdrawal(tenant,reference),"Sui principal did not settle exactly once");require(raw.equals(transactions.find(hash).orElseThrow().getRawPayload()),"Sui recovery changed selected raw");
                    require(balance(realRpc,symbol,fixture,recipient)-before==amount.movePointRight(symbol.equals("SUI")?9:6).longValueExact(),"Sui receiver delta differs from exact payment");
                    long expense=transactions.find(hash).orElseThrow().getFeeMist();fees+=expense;if(round==0 && symbol.equals("SUI"))require(expense<0,"Sui formal payout did not exercise an actual storage rebate");totals.put(symbol,totals.get(symbol).subtract(amount.add(new BigDecimal("0.1"))));
                    var book=balances.find(tenant,"SUI",symbol,source.getAccountId()).orElseThrow();require(book.getTotalBalance().compareTo(totals.get(symbol))==0 && book.getLockedBalance().signum()==0,"Sui exact frozen principal settlement differs");
                    jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true where chain='SUI' and enabled");jdbc.update("update token_config set enabled=true where chain='SUI' and network=?",profile.getNetwork());
                }
                round++;
            }
            for(String symbol:List.of("SUI","USDT","USDC")) {
                String reference="sui-collection-"+UUID.randomUUID();collections.create(tenant,sourceView.id(),reference,"SUI",symbol,source.getAddress(),collector.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null);
                current[0]=new SuiSubmissionService.Operation(tenant,reference,SuiSubmissionService.Kind.COLLECTION);long before=balance(realRpc,symbol,fixture,collector.getAddress());String hash=service.collect(tenant,reference,source);await(realRpc,hash,false);
                require(service.confirmCollection(tenant,reference) && !service.confirmCollection(tenant,reference),"Sui collection did not settle exactly once");require(balance(realRpc,symbol,fixture,collector.getAddress())-before==BigDecimal.ONE.movePointRight(symbol.equals("SUI")?9:6).longValueExact(),"Sui collector delta differs");fees+=transactions.find(hash).orElseThrow().getFeeMist();
            }
            // Valid objects and signature; actual committed insufficient Gas rolls principal back.
            jdbc.update("update chain_profile set default_fee_rate=1000000 where chain='SUI' and enabled");
            String failure="sui-out-of-gas-"+UUID.randomUUID();current[0]=new SuiSubmissionService.Operation(tenant,failure,SuiSubmissionService.Kind.WITHDRAWAL);
            long recipientBefore=balance(realRpc,"USDC",fixture,recipient);createOrder(transaction,orders,balances,tenant,source,failure,"USDC",recipient,BigDecimal.ONE,BigDecimal.ZERO);
            String failedHash=service.send(current[0],source);submissions.restoreSent(current[0],failedHash);var failedReceipt=await(realRpc,failedHash,true);
            require(service.confirmWithdrawal(tenant,failure) && !service.confirmWithdrawal(tenant,failure),"Sui committed failure did not refund once");
            require("FAILED".equals(orders.find("SUI",failure,tenant).orElseThrow().getStatus()) && recipientBefore==balance(realRpc,"USDC",fixture,recipient),"Sui failed payout was treated as payment");
            var refunded=balances.find(tenant,"SUI","USDC",source.getAccountId()).orElseThrow();require(refunded.getTotalBalance().compareTo(totals.get("USDC"))==0 && refunded.getLockedBalance().signum()==0,"Sui failure refund differs from exact principal");fees+=transactions.find(failedHash).orElseThrow().getFeeMist();
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-sui-actual-failed-receipt.json"),json.writeValueAsString(Map.of("unsigned",submissions.payload(submissions.intent(current[0]).orElseThrow()).unsigned(),"selected",json.readValue(transactions.find(failedHash).orElseThrow().getRawPayload(),MpcSuiTransactionCodec.Signed.class),"receipt",failedReceipt)));
            require(beforeControlled-balance(realRpc,"SUI",fixture,source.getAddress())-balance(realRpc,"SUI",fixture,collector.getAddress())-603_000_000_000L==fees,"Sui controlled native balances do not reconcile to exact payments and actual net fees");
            long count=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SUI'",Long.class,tenant);scanner.scanAndCredit();require(count==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SUI'",Long.class,tenant),"Sui internal collections or MPC withdrawals were credited as external deposits");
        }
    }
    private static void mint(JsonNode fixture,String symbol,String recipient)throws Exception {
        var client=Path.of(fixture.path("clientConfig").asText()).toRealPath();require(client.toString().startsWith("/tmp/surprising-wallet-mpc-sui-") || client.toString().startsWith("/private/tmp/surprising-wallet-mpc-sui-"),"Sui administrator config must stay isolated outside the checkout");
        var log=Files.createTempFile("surprising-wallet-mpc-sui-mint-",".log");var process=new ProcessBuilder("sui","client","--client.config",client.toString(),"--client.env",fixture.path("environment").asText(),"call","--package",fixture.path("packageId").asText(),"--module",symbol.toLowerCase(Locale.ROOT),"--function","mint","--args",fixture.path(symbol).path("treasury").asText(),"20000000",recipient,"--gas-budget","10000000","--json").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Sui fixture mint timed out; see "+log);}require(process.exitValue()==0,"Sui fixture mint failed; see "+log);
    }
    private static String fund(URI faucet,String recipient,ObjectMapper json)throws Exception {
        require("http".equals(faucet.getScheme()) && "127.0.0.1".equals(faucet.getHost()),"Sui faucet must be its isolated loopback fixture");
        try(var http=java.net.http.HttpClient.newHttpClient()) {
            var answer=http.send(java.net.http.HttpRequest.newBuilder(faucet.resolve("/v2/gas")).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("FixedAmountRequest",Map.of("recipient",recipient))))).build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            var result=json.readTree(answer.body());require(answer.statusCode()==200 && "Success".equals(result.path("status").asText()),"Sui business source faucet rejected funding");
            String hash=result.path("coins_sent").path(0).path("transferTxDigest").asText();require(!hash.isEmpty(),"Sui faucet returned no funding digest");return hash;
        }
    }
    private static long balance(SuiRpcClient rpc,String symbol,JsonNode fixture,String owner){return rpc.balance(owner,symbol.equals("SUI")?SuiRpcClient.SUI_COIN_TYPE:fixture.path(symbol).path("coinType").asText()).longValueExact();}
    private static void createOrder(TransactionTemplate tx,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,UUID tenant,ChainAddressRecord source,String reference,String symbol,String recipient,BigDecimal amount,BigDecimal fee){tx.executeWithoutResult(status->{orders.createForTenant(tenant,reference,source.getUserId(),"SUI",symbol,source.getAddress(),source.getAccountId(),recipient,amount,fee);require(balances.freeze("SUI",symbol,source.getAccountId(),amount.add(fee),tenant),"Sui principal could not freeze");orders.updateStatus(tenant,"SUI",reference,"FROZEN",source.getAddress(),null,null);require(orders.claimSigningDurable(tenant,"SUI",reference,source.getAddress())==1,"Sui order could not claim signing");});}
    private static JsonNode await(SuiRpcClient rpc,String hash,boolean failure)throws Exception {long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(System.nanoTime()<end){var receipt=rpc.transactionBlock(hash);if(hash.equals(receipt.path("digest").asText()) && receipt.path("checkpoint").asLong()>0){require("success".equals(receipt.path("effects").path("status").path("status").asText())!=failure,"Sui execution differs: "+receipt.path("effects").path("status"));return receipt;}Thread.sleep(100);}throw new AssertionError("Sui did not finalize selected digest");}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
