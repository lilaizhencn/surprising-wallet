package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.aptos.*;
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

/** Actual three-node MPC, official Aptos localnet and real FA fixture contracts. */
public final class MpcAptosBusinessNativeProbe {
    private MpcAptosBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var json=new ObjectMapper();var fixture=json.readTree(Files.readAllBytes(Path.of(config.path("aptosFaFixture").asText())));var endpoint=URI.create(config.path("aptosRpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()) && endpoint.toString().equals(fixture.path("endpoint").asText()),"Aptos business requires isolated loopback fixture chain");
        var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("APTOS").orElseThrow();
        jdbc.update("update chain_profile set chain_id=4,withdraw_confirmations=1,deposit_confirmations=1,scan_batch_size=100,withdraw_enabled=true,scan_enabled=true,collection_enabled=true,transfer_enabled=true where chain='APTOS' and enabled");
        var realRpc=new AptosRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));require(realRpc.chainId()==4,"Aptos fixture cannot use public-chain funds");var rpc=spy(realRpc);
        var custody=MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager);var addresses=new ChainAddressRepository(jdbc);
        UUID actor=jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);
        var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-mpc-probe","TENANT_ADMIN",Set.of("*"));
        var sourceView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("APTOS","aptos-business-source",0L,null,Map.of()),"API","127.0.0.1");
        var targetView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("APTOS","aptos-business-collector",0L,null,Map.of()),"API","127.0.0.1");
        var custodyRows=new CustodyAddressRepository(jdbc);var source=addresses.findOwned(tenant,custodyRows.require(tenant,sourceView.id()).chainAddressId()).orElseThrow();var collector=addresses.findOwned(tenant,custodyRows.require(tenant,targetView.id()).chainAddressId()).orElseThrow();
        var tokens=new TokenConfigRepository(jdbc);var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);var transactions=new AptosTransactionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);var transaction=new TransactionTemplate(manager);
        var runtime=new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc));var quorum=spy(nodes);doNothing().when(quorum).close();
        var lost=new AtomicBoolean();var broadcasts=new ArrayList<String>();AptosSubmissionService.Operation[] current={null};
        doAnswer(call->{
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"Aptos RPC submission held business transaction");var op=Objects.requireNonNull(current[0]);
            String hash=op.kind()==AptosSubmissionService.Kind.WITHDRAWAL?orders.findTxHash("APTOS",op.reference(),tenant).orElseThrow():collections.findTxHash(tenant,"APTOS",op.reference()).orElseThrow();
            require(hash!=null && call.getArgument(0).toString().equals(transactions.findSigned(hash).orElseThrow().getRawPayload()),"Aptos selected raw/hash reached RPC before durable business commit");
            require(requests.findBusiness(tenant,ChainType.APTOS,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),"transaction").orElseThrow().state()==MpcSignRequest.State.COMPLETED,"Aptos RPC preceded durable MPC result");
            broadcasts.add(hash);String answer=(String)call.callRealMethod();if(lost.getAndSet(false))throw new IllegalStateException("deliberately lost Aptos broadcast reply");return answer;
        }).when(rpc).submitTransaction(any());
        try(var context=new AnnotationConfigApplicationContext();var depositContext=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper())) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);context.registerBean(AptosTransactionRepository.class,()->transactions);context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
            context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);context.registerBean(ChainProfileRepository.class,()->profiles);context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(TokenConfigRepository.class,()->tokens);
            context.registerBean(ObjectMapper.class,()->json);context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(WithdrawalSettlementService.class);context.registerBean(AptosSubmissionService.class);
            context.registerBean(AptosRpcClient.class,()->rpc);context.registerBean(WalletRuntimeConfigService.class,()->runtime);context.registerBean(AptosTransactionService.class);context.refresh();
            var service=context.getBean(AptosTransactionService.class);var submissions=context.getBean(AptosSubmissionService.class);var heights=new ChainScanHeightRepository(jdbc);
            var scanner=new AptosDepositScanner(realRpc,addresses,profiles,tokens,heights,transactions,depositContext.getBean(DepositService.class),new ChainAssetRepository(jdbc),runtime);
            for(String symbol:List.of("USDC","USDT")) {
                String metadata=MpcAptosTransactionCodec.canonicalAddress(fixture.path(symbol).asText());
                jdbc.update("update token_config set contract_address=?,contract_address_hex=?,decimals=6,enabled=true where chain='APTOS' and network=? and symbol=?",metadata,metadata,profile.getNetwork(),symbol);
                jdbc.update("update chain_asset set contract_address=?,decimals=6,active=true where chain='APTOS' and symbol=?",metadata,symbol);
                mint(fixture,symbol,source.getAddress());
            }
            realRpc.fundDevnetAccount(source.getAddress(),1_000_000_000L);long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(realRpc.aptBalance(source.getAddress())<1_000_000_000L && System.nanoTime()<deadline)Thread.sleep(100);
            require(realRpc.aptBalance(source.getAddress())==1_000_000_000L,"Aptos fresh business source was not funded exactly");
            heights.upsert("APTOS","aptos-fa-event-scanner",0,0);scanner.scanAndCredit();var totals=new HashMap<String,BigDecimal>();for(String symbol:List.of("APT","USDT","USDC"))totals.put(symbol,balances.find(tenant,"APTOS",symbol,source.getAccountId()).orElseThrow().getTotalBalance());
            require(totals.get("APT").compareTo(new BigDecimal("10"))==0 && totals.get("USDT").compareTo(new BigDecimal("20"))==0 && totals.get("USDC").compareTo(new BigDecimal("20"))==0,"Aptos actual APT/FA deposits did not credit exact tenant amounts");
            long depositCount=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='APTOS'",Long.class,tenant);scanner.scanAndCredit();require(depositCount==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='APTOS'",Long.class,tenant),"Aptos deposit replay duplicated tenant projection");
            long beforeControlled=Math.addExact(realRpc.aptBalance(source.getAddress()),realRpc.aptBalance(collector.getAddress())),fees=0;String recipient="0x"+"cd".repeat(32);var hashes=new HashSet<String>();
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for(String symbol:List.of("APT","USDT","USDC")) {
                    String reference="aptos-business-"+UUID.randomUUID();var op=new AptosSubmissionService.Operation(tenant,reference,AptosSubmissionService.Kind.WITHDRAWAL);current[0]=op;
                    createOrder(transaction,orders,balances,tenant,source,reference,symbol,recipient,BigDecimal.ONE,new BigDecimal("0.1"));long before=balance(realRpc,symbol,fixture,recipient);int count=broadcasts.size();lost.set(true);
                    try{service.send(op,source);throw new AssertionError("Aptos fixture did not lose a real accepted reply");}catch(IllegalStateException expected){require(broadcasts.size()==count+1,"Aptos failed before deliberate lost reply: "+expected);submissions.unknown(op,"fixture lost response");}
                    String hash=orders.findTxHash("APTOS",reference,tenant).orElseThrow();await(realRpc,hash,false);require(hashes.add(hash),"Aptos different owned operations selected the same hash");String raw=transactions.findSigned(hash).orElseThrow().getRawPayload();
                    jdbc.update("update chain_profile set withdraw_enabled=false,collection_enabled=false where chain='APTOS' and enabled");jdbc.update("update token_config set enabled=false where chain='APTOS'");
                    require(service.recover(op),"Aptos did not recover original message after new-work switches disabled");require(broadcasts.size()==count+1,"Aptos known transaction was submitted again");
                    require(service.confirmWithdrawal(tenant,reference) && !service.confirmWithdrawal(tenant,reference),"Aptos principal did not settle exactly once");require(raw.equals(transactions.findSigned(hash).orElseThrow().getRawPayload()),"Aptos recovery changed selected raw");
                    require(balance(realRpc,symbol,fixture,recipient)-before==BigDecimal.ONE.movePointRight(symbol.equals("APT")?8:6).longValueExact(),"Aptos receiver delta differs from exact payment");
                    fees+=transactions.findSigned(hash).orElseThrow().getFeeOctas();totals.put(symbol,totals.get(symbol).subtract(new BigDecimal("1.1")));
                    var book=balances.find(tenant,"APTOS",symbol,source.getAccountId()).orElseThrow();require(book.getTotalBalance().compareTo(totals.get(symbol))==0 && book.getLockedBalance().signum()==0,"Aptos exact frozen principal settlement differs");
                    jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true where chain='APTOS' and enabled");jdbc.update("update token_config set enabled=true where chain='APTOS' and network=?",profile.getNetwork());
                }
            }
            for(String symbol:List.of("APT","USDT","USDC")) {
                String reference="aptos-collection-"+UUID.randomUUID();collections.create(tenant,sourceView.id(),reference,"APTOS",symbol,source.getAddress(),collector.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null);
                current[0]=new AptosSubmissionService.Operation(tenant,reference,AptosSubmissionService.Kind.COLLECTION);long before=balance(realRpc,symbol,fixture,collector.getAddress());String hash=service.collect(tenant,reference,source);await(realRpc,hash,false);
                require(service.confirmCollection(tenant,reference) && !service.confirmCollection(tenant,reference),"Aptos collection did not settle exactly once");require(balance(realRpc,symbol,fixture,collector.getAddress())-before==BigDecimal.ONE.movePointRight(symbol.equals("APT")?8:6).longValueExact(),"Aptos collector delta differs");fees+=transactions.findSigned(hash).orElseThrow().getFeeOctas();
            }
            // Source USDC=16; customer book=16.7, backed by source+collector=17. A valid frozen
            // payout exceeds only the selected source. The real VM must abort and still charge Gas.
            String failure="aptos-failed-fa-"+UUID.randomUUID();current[0]=new AptosSubmissionService.Operation(tenant,failure,AptosSubmissionService.Kind.WITHDRAWAL);
            long recipientBefore=balance(realRpc,"USDC",fixture,recipient);createOrder(transaction,orders,balances,tenant,source,failure,"USDC",recipient,new BigDecimal("16.5"),BigDecimal.ZERO);
            String failedHash=service.send(current[0],source);submissions.restoreSent(current[0],failedHash);await(realRpc,failedHash,true);
            require(service.confirmWithdrawal(tenant,failure) && !service.confirmWithdrawal(tenant,failure),"Aptos committed failure did not refund once");
            require("FAILED".equals(orders.find("APTOS",failure,tenant).orElseThrow().getStatus()) && recipientBefore==balance(realRpc,"USDC",fixture,recipient),"Aptos failed payout was treated as payment");
            var refunded=balances.find(tenant,"APTOS","USDC",source.getAccountId()).orElseThrow();require(refunded.getTotalBalance().compareTo(totals.get("USDC"))==0 && refunded.getLockedBalance().signum()==0,"Aptos failure refund differs from exact principal");fees+=transactions.findSigned(failedHash).orElseThrow().getFeeOctas();
            require(beforeControlled-realRpc.aptBalance(source.getAddress())-realRpc.aptBalance(collector.getAddress())-300_000_000L==fees,"Aptos controlled native balances do not reconcile to exact payments and actual net fees");
            long count=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='APTOS'",Long.class,tenant);scanner.scanAndCredit();require(count==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='APTOS'",Long.class,tenant),"Aptos internal collections or MPC withdrawals were credited as external deposits");
        }
    }
    private static void mint(JsonNode fixture,String symbol,String recipient) throws Exception {
        String publisher=fixture.path("publisher").asText();require(publisher.matches("0x[0-9a-f]{64}"),"Aptos local fixture publisher is invalid");var directory=Path.of(fixture.path("adminDirectory").asText()).toRealPath();require(directory.toString().startsWith("/tmp/surprising-wallet-mpc-aptos-admin") || directory.toString().startsWith("/private/tmp/surprising-wallet-mpc-aptos-admin"),"Aptos fixture profile must be isolated outside the checkout");
        var log=Files.createTempFile("surprising-wallet-mpc-aptos-mint-",".log");var process=new ProcessBuilder("aptos","move","run","--function-id",publisher+"::test_assets::mint_"+symbol.toLowerCase(Locale.ROOT),"--args","address:"+recipient,"u64:20000000","--profile","mpc-fixture","--assume-yes")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Aptos fixture mint timed out; see "+log);}require(process.exitValue()==0,"Aptos fixture mint failed; see "+log);
    }
    private static long balance(AptosRpcClient rpc,String symbol,JsonNode fixture,String owner){return symbol.equals("APT")?rpc.aptBalance(owner):rpc.fungibleAssetBalance(owner,fixture.path(symbol).asText());}
    private static void createOrder(TransactionTemplate tx,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,UUID tenant,ChainAddressRecord source,String reference,String symbol,String recipient,BigDecimal amount,BigDecimal fee){tx.executeWithoutResult(status->{orders.createForTenant(tenant,reference,source.getUserId(),"APTOS",symbol,source.getAddress(),source.getAccountId(),recipient,amount,fee);require(balances.freeze("APTOS",symbol,source.getAccountId(),amount.add(fee),tenant),"Aptos principal could not freeze");orders.updateStatus(tenant,"APTOS",reference,"FROZEN",source.getAddress(),null,null);require(orders.claimSigningDurable(tenant,"APTOS",reference,source.getAddress())==1,"Aptos order could not claim signing");});}
    private static JsonNode await(AptosRpcClient rpc,String hash,boolean failure) throws Exception {long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(System.nanoTime()<end){var receipt=rpc.transactionByHash(hash);if(receipt!=null && "user_transaction".equals(receipt.path("type").asText())){require(receipt.path("success").asBoolean()!=failure,"Aptos execution differs: "+receipt.path("vm_status"));return receipt;}Thread.sleep(100);}throw new AssertionError("Aptos did not commit selected hash");}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
