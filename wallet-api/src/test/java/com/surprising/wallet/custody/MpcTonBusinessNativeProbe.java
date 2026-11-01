package com.surprising.wallet.custody;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.ton.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.smartcontract.token.ft.JettonMinter;
import org.ton.ton4j.smartcontract.token.nft.NftUtils;
import tools.jackson.databind.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual three-node MPC and isolated TON consensus through production deposits, submissions and settlement. */
public final class MpcTonBusinessNativeProbe {
    private MpcTonBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,
                              MpcNodes nodes,UUID tenant,JsonNode config)throws Exception {
        var json=new ObjectMapper();var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("TON").orElseThrow();
        jdbc.update("update chain_profile set withdraw_confirmations=1,deposit_confirmations=1,scan_batch_size=100,withdraw_enabled=true,collection_enabled=true,scan_enabled=true,transfer_enabled=true where chain='TON' and enabled");
        // Component-only sources already made off-ledger payments; keep this business acceptance independent.
        jdbc.update("update chain_address set enabled=false where chain='TON'");
        var realRpc=new TonCenterClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));var rpc=spy(realRpc);
        var custody=MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager);
        UUID actor=jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);
        var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-mpc-probe","TENANT_ADMIN",Set.of("*"));
        var sourceView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("TON","ton-business-source",0L,null,Map.of()),"API","127.0.0.1");
        var collectorView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("TON","ton-business-collector",0L,null,Map.of()),"API","127.0.0.1");
        var addresses=new ChainAddressRepository(jdbc);var custodyRows=new CustodyAddressRepository(jdbc);
        var source=addresses.findOwned(tenant,custodyRows.require(tenant,sourceView.id()).chainAddressId()).orElseThrow();
        var collector=addresses.findOwned(tenant,custodyRows.require(tenant,collectorView.id()).chainAddressId()).orElseThrow();
        var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);
        var transactions=new TonTransactionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);
        var tokens=new TokenConfigRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);var tx=new TransactionTemplate(manager);
        var runtime=new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc));
        var quorum=spy(nodes);doNothing().when(quorum).close();var lost=new AtomicBoolean();var broadcasts=new ArrayList<String>();
        TonSubmissionService.Operation[] current={null};
        doAnswer(call->{
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"TON network submission held a business transaction");
            var op=Objects.requireNonNull(current[0]);String hash=op.kind()==TonSubmissionService.Kind.WITHDRAWAL
                    ?orders.findTxHash("TON",op.reference(),tenant).orElseThrow():collections.findTxHash(tenant,"TON",op.reference()).orElseThrow();
            require(Arrays.equals(call.getArgument(0),Base64.getDecoder().decode(transactions.findSigned(hash).orElseThrow().getRawPayload())),"TON selected BOC/hash reached RPC before durable business commit");
            require(requests.findBusiness(tenant,ChainType.TON,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),"transaction").orElseThrow().state()==MpcSignRequest.State.COMPLETED,"TON RPC preceded durable MPC signature");
            broadcasts.add(hash);String result=(String)call.callRealMethod();if(lost.getAndSet(false))throw new IllegalStateException("deliberately lost TON broadcast reply");return result;
        }).when(rpc).sendBoc(any());
        try(var fixture=new MpcTonJettonNativeProbe.Admin(realRpc,profile.getNetwork(),Path.of(config.path("tonFunderKeyFile").asText()));
            var context=new AnnotationConfigApplicationContext();
            var depositContext=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper())) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);
            context.registerBean(TonTransactionRepository.class,()->transactions);context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
            context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(MpcSigningService.class,()->signing);
            context.registerBean(MpcNodes.class,()->quorum);context.registerBean(ChainProfileRepository.class,()->profiles);
            context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(TokenConfigRepository.class,()->tokens);
            context.registerBean(ObjectMapper.class,()->json);context.registerBean(LedgerBalanceRepository.class,()->balances);
            context.registerBean(WithdrawalSettlementService.class);context.registerBean(TonSubmissionService.class);
            context.registerBean(TonCenterClient.class,()->rpc);context.registerBean(WalletRuntimeConfigService.class,()->runtime);
            context.registerBean(TonTransactionService.class);context.refresh();
            var service=context.getBean(TonTransactionService.class);var submissions=context.getBean(TonSubmissionService.class);
            var masters=new LinkedHashMap<String,String>();
            for(String symbol:List.of("USDT","USDC")) {
                var minter=JettonMinter.builder().adminAddress(Address.of(fixture.address))
                        .content(NftUtils.createOffChainUriCell("https://example.invalid/mpc-ton-business/"+symbol+"/"+UUID.randomUUID())).wc(0).build();
                String master=minter.getAddress().toRaw();fixture.send(master,500_000_000L,minter.getStateInit(),null,false);
                MpcTonJettonNativeProbe.awaitActive(realRpc,master);
                fixture.send(master,150_000_000L,null,JettonMinter.createMintBody(1,Address.of(source.getAddress()),BigInteger.valueOf(80_000_000),BigInteger.valueOf(20_000_000),null,Address.of(fixture.address),BigInteger.ZERO,null),true);
                String wallet=realRpc.jettonWallet(source.getAddress(),master);MpcTonJettonNativeProbe.awaitActive(realRpc,wallet);
                require(realRpc.jettonData(wallet).balance().equals(BigInteger.valueOf(20_000_000)),"TON business fixture mint did not reach owned wallet");
                masters.put(symbol,master);
                jdbc.update("update token_config set contract_address=?,contract_address_hex=?,decimals=6,enabled=true where chain='TON' and network=? and symbol=?",master,master,profile.getNetwork(),symbol);
                jdbc.update("update chain_asset set contract_address=?,decimals=6,active=true where chain='TON' and symbol=?",master,symbol);
            }
            MpcTonTestFunding.fund(realRpc,Path.of(config.path("tonFunderKeyFile").asText()),source.getAddress(),25_000_000_000L);
            var registration=new TonAddressService(realRpc,addresses,profiles,tokens);
            var scanner=new TonDepositScanner(realRpc,registration,addresses,profiles,tokens,new ChainScanHeightRepository(jdbc),transactions,
                    depositContext.getBean(DepositService.class),new DepositRecordRepository(jdbc),runtime);
            scanner.scanAndCredit();var totals=new HashMap<String,BigDecimal>();
            for(String symbol:List.of("TON","USDT","USDC"))totals.put(symbol,balances.find(tenant,"TON",symbol,source.getAccountId()).orElseThrow().getTotalBalance());
            require(totals.get("TON").compareTo(new BigDecimal("25"))==0 && totals.get("USDT").compareTo(new BigDecimal("20"))==0 && totals.get("USDC").compareTo(new BigDecimal("20"))==0,"TON actual native/Jetton deposits did not credit exact owned amounts");
            long deposits=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='TON'",Long.class,tenant);
            scanner.scanAndCredit();require(deposits==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='TON'",Long.class,tenant),"TON deposit replay duplicated tenant projection");
            byte[] destination=new byte[32];new java.security.SecureRandom().nextBytes(destination);String recipient="0:"+HexFormat.of().formatHex(destination);
            long sourceBefore=realRpc.balance(source.getAddress()),fees=0;var hashes=new HashSet<String>();
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for(String symbol:List.of("TON","USDT","USDC")) {
                    String reference="ton-business-"+UUID.randomUUID();var op=new TonSubmissionService.Operation(tenant,reference,TonSubmissionService.Kind.WITHDRAWAL);current[0]=op;
                    createOrder(tx,orders,balances,tenant,source,reference,symbol,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
                    long before=balance(realRpc,symbol,masters,recipient);int count=broadcasts.size();lost.set(true);
                    try{service.send(op,source);throw new AssertionError("TON fixture did not lose an accepted reply");}
                    catch(IllegalStateException expected){require(broadcasts.size()==count+1,"TON failed before deliberate lost reply: "+expected);submissions.unknown(op,"fixture lost reply");}
                    String hash=orders.findTxHash("TON",reference,tenant).orElseThrow();awaitOwner(realRpc,source.getAddress(),hash);
                    require(hashes.add(hash),"TON different business operations selected the same message hash");String raw=transactions.findSigned(hash).orElseThrow().getRawPayload();
                    jdbc.update("update chain_profile set withdraw_enabled=false,collection_enabled=false where chain='TON' and enabled");jdbc.update("update token_config set enabled=false where chain='TON'");
                    require(service.recover(op),"TON original intent did not recover after new-work switches closed");require(broadcasts.size()==count+1,"TON known external message was broadcast again");
                    awaitSettlement(service,tenant,reference,false);require(!service.confirmWithdrawal(tenant,reference),"TON withdrawal settled twice");
                    require(raw.equals(transactions.findSigned(hash).orElseThrow().getRawPayload()),"TON recovery changed selected BOC");
                    long received=balance(realRpc,symbol,masters,recipient)-before;
                    if(symbol.equals("TON")){
                        long recipientFee=TonExecutionReceipt.read(submissions.proof(op).orElseThrow().trace().recipient(),recipient).transaction().getTotalFees().getCoins().longValueExact();
                        require(received==1_000_000_000L-recipientFee,"TON native recipient delta differs from principal and receiver fee");
                    } else require(received==1_000_000,"TON actual recipient Jetton delta differs from exact principal");
                    fees+=transactions.findSigned(hash).orElseThrow().getFeeNano();totals.put(symbol,totals.get(symbol).subtract(new BigDecimal("1.1")));
                    var book=balances.find(tenant,"TON",symbol,source.getAccountId()).orElseThrow();
                    require(book.getTotalBalance().compareTo(totals.get(symbol))==0 && book.getLockedBalance().signum()==0,"TON frozen principal settlement differs");
                    jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true where chain='TON' and enabled");jdbc.update("update token_config set enabled=true where chain='TON' and network=?",profile.getNetwork());
                }
            }
            for(String symbol:List.of("TON","USDT","USDC")) {
                String reference="ton-collection-"+UUID.randomUUID();current[0]=new TonSubmissionService.Operation(tenant,reference,TonSubmissionService.Kind.COLLECTION);
                ChainAddressRecord from=source;
                if(!symbol.equals("TON"))from=addresses.find("TON",symbol,source.getUserId(),source.getBiz(),source.getAddressIndex(),source.getWalletRole()).orElseThrow();
                collections.create(tenant,sourceView.id(),reference,"TON",symbol,from.getAddress(),collector.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null);
                long before=balance(realRpc,symbol,masters,collector.getAddress());String hash=service.collect(tenant,reference,from);
                awaitSettlement(service,tenant,reference,true);require(!service.confirmCollection(tenant,reference),"TON collection settled twice");
                long received=balance(realRpc,symbol,masters,collector.getAddress())-before;
                if(symbol.equals("TON")){
                    long recipientFee=TonExecutionReceipt.read(submissions.proof(current[0]).orElseThrow().trace().recipient(),collector.getAddress()).transaction().getTotalFees().getCoins().longValueExact();
                    require(received==1_000_000_000L-recipientFee,"TON collection native credit differs from actual processing fee");
                }else require(received==1_000_000,"TON collection Jetton delta differs");
                fees+=transactions.findSigned(hash).orElseThrow().getFeeNano();
            }
            for(String symbol:List.of("TON","USDC")) {
                String reference="ton-rejected-"+UUID.randomUUID();current[0]=new TonSubmissionService.Operation(tenant,reference,TonSubmissionService.Kind.WITHDRAWAL);
                BigDecimal amount=new BigDecimal(symbol.equals("TON")?"21.5":"16.5");long before=balance(realRpc,symbol,masters,recipient);
                createOrder(tx,orders,balances,tenant,source,reference,symbol,recipient,amount,BigDecimal.ZERO);
                String hash=service.send(current[0],source);submissions.restoreSent(current[0],hash);awaitSettlement(service,tenant,reference,false);
                require("FAILED".equals(orders.find("TON",reference,tenant).orElseThrow().getStatus()) && !service.confirmWithdrawal(tenant,reference),"TON proven rejection did not refund exactly once");
                require(balance(realRpc,symbol,masters,recipient)==before,"TON rejected transfer credited recipient");
                var book=balances.find(tenant,"TON",symbol,source.getAccountId()).orElseThrow();require(book.getTotalBalance().compareTo(totals.get(symbol))==0 && book.getLockedBalance().signum()==0,"TON rejection refund differs from frozen principal");
                fees+=transactions.findSigned(hash).orElseThrow().getFeeNano();
            }
            require(sourceBefore-realRpc.balance(source.getAddress())-4_000_000_000L==fees,"TON source native balance does not reconcile to actual payments, attachments and bounce/excess fees");
            scanner.scanAndCredit();require(deposits==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='TON'",Long.class,tenant),"TON internal collections, notifications or excesses were credited as external deposits");
        }
    }
    private static long balance(TonCenterClient rpc,String symbol,Map<String,String> masters,String owner){
        if(symbol.equals("TON"))return rpc.balance(owner);
        String wallet=rpc.jettonWallet(owner,masters.get(symbol));if(!"active".equals(rpc.addressInformation(wallet).path("state").asText()))return 0;
        return rpc.jettonData(wallet).balance().longValueExact();
    }
    private static void createOrder(TransactionTemplate tx,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,UUID tenant,ChainAddressRecord source,String reference,String symbol,String recipient,BigDecimal amount,BigDecimal fee){
        tx.executeWithoutResult(status->{orders.createForTenant(tenant,reference,source.getUserId(),"TON",symbol,source.getAddress(),source.getAccountId(),recipient,amount,fee);
            require(balances.freeze("TON",symbol,source.getAccountId(),amount.add(fee),tenant),"TON principal could not freeze");orders.updateStatus(tenant,"TON",reference,"FROZEN",source.getAddress(),null,null);
            require(orders.claimSigningDurable(tenant,"TON",reference,source.getAddress())==1,"TON order could not claim durable signing");});
    }
    private static void awaitOwner(TonCenterClient rpc,String owner,String hash)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(System.nanoTime()<deadline){if(rpc.findExternalMessageTransaction(owner,hash,100).isPresent())return;Thread.sleep(100);}throw new AssertionError("TON original external message was not observed");
    }
    private static void awaitSettlement(TonTransactionService service,UUID tenant,String reference,boolean collection)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(System.nanoTime()<deadline){if(collection?service.confirmCollection(tenant,reference):service.confirmWithdrawal(tenant,reference))return;Thread.sleep(100);}throw new AssertionError("TON exact complete payment or rejection trace did not settle");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
