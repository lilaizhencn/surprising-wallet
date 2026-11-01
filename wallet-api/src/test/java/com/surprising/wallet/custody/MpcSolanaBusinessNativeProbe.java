package com.surprising.wallet.custody;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.solana.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.p2p.solanaj.core.*;
import org.p2p.solanaj.programs.*;
import org.p2p.solanaj.utils.Base58;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real three-node MPC and Agave. Local Account keys exist only for mint/funder fixtures. */
public final class MpcSolanaBusinessNativeProbe {
    private MpcSolanaBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var endpoint=URI.create(config.path("solanaRpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()),"Solana business test requires isolated loopback Agave");
        var json=new ObjectMapper();var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("SOLANA").orElseThrow();
        jdbc.update("update chain_profile set withdraw_confirmations=1,deposit_confirmations=1,withdraw_enabled=true,scan_enabled=true,collection_enabled=true,transfer_enabled=true where chain='SOLANA' and enabled");
        jdbc.update("update chain_rpc_node set enabled=false where chain='SOLANA'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url) values ('SOLANA',?,'mpc-test','mpc-agave','rpc',?)",profile.getNetwork(),endpoint.toString());
        var realRpc=new SolanaRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));var rpc=spy(realRpc);
        var addresses=new ChainAddressRepository(jdbc);long sourceId=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='SOLANA' and subject='native-customer'",Long.class,tenant);
        var source=addresses.findOwned(tenant,sourceId).orElseThrow();
        var custody=MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager);var gas=CustodyTestSupport.gas(jdbc,custody);
        UUID actor=jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);
        var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-mpc-probe","TENANT_ADMIN",Set.of("*"));
        var gasView=gas.create(principal,new CustodyGasService.CreateGasAccountCommand("SOLANA"),"127.0.0.1");
        var collectorView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("SOLANA","solana-native-collector",0L,null,Map.of()),"API","127.0.0.1");
        var collector=addresses.findOwned(tenant,new CustodyAddressRepository(jdbc).require(tenant,collectorView.id()).chainAddressId()).orElseThrow();
        var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);var transactions=new SolanaTransactionRepository(jdbc);
        var requests=new WalletMpcSignRequestRepository(jdbc);var tokens=new TokenConfigRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);var transaction=new TransactionTemplate(manager);
        var runtime=new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc));
        var quorum=spy(nodes);doNothing().when(quorum).close();
        var lost=new AtomicBoolean();var broadcasts=new ArrayList<String>();SolanaSubmissionService.Operation[] current={null};
        doAnswer(call->{
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"Solana broadcast ran inside business transaction");
            byte[] wire=call.getArgument(0);String signature=Base58.encode(Arrays.copyOfRange(wire,1,65));var op=Objects.requireNonNull(current[0]);
            String owned=op.kind()==SolanaSubmissionService.Kind.WITHDRAWAL?orders.findTxHash("SOLANA",op.reference(),tenant).orElseThrow():collections.findTxHash(tenant,"SOLANA",op.reference()).orElseThrow();
            require(signature.equals(owned) && Base64.getEncoder().encodeToString(wire).equals(transactions.findSigned(signature).orElseThrow().getRawPayload()),"Solana usable raw/signature reached RPC before durable business commit");
            broadcasts.add(signature);String result=(String)call.callRealMethod();if(lost.getAndSet(false))throw new IllegalStateException("deliberately lost Agave broadcast reply");return result;
        }).when(rpc).sendTransaction(any());
        try(var context=new AnnotationConfigApplicationContext();var deposits=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper())) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);context.registerBean(SolanaTransactionRepository.class,()->transactions);
            context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);
            context.registerBean(ChainProfileRepository.class,()->profiles);context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(TokenConfigRepository.class,()->tokens);
            context.registerBean(CustodyGasAccountRepository.class,()->new CustodyGasAccountRepository(jdbc));context.registerBean(CustodyAddressRepository.class,()->new CustodyAddressRepository(jdbc));
            context.registerBean(ObjectMapper.class,()->json);context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(WithdrawalSettlementService.class);
            context.registerBean(SolanaSubmissionService.class);context.registerBean(SolanaRpcClient.class,()->rpc);context.registerBean(CustodyGasService.class,()->gas);context.registerBean(WalletRuntimeConfigService.class,()->runtime);context.registerBean(SolanaTransactionService.class);context.refresh();
            var service=context.getBean(SolanaTransactionService.class);var submissions=context.getBean(SolanaSubmissionService.class);
            var scanner=new SolanaDepositScanner(realRpc,addresses,profiles,tokens,new ChainScanHeightRepository(jdbc),transactions,deposits.getBean(DepositService.class),new DepositRecordRepository(jdbc),runtime,new SolanaAddressService());
            var funder=new Account();await(realRpc,realRpc.requestAirdrop(funder.getPublicKeyBase58(),100_000_000_000L),false);
            fixtureSend(realRpc,new Transaction().addInstruction(SystemProgram.transfer(funder.getPublicKey(),new PublicKey(source.getAddress()),20_000_000_000L))
                    .addInstruction(SystemProgram.transfer(funder.getPublicKey(),new PublicKey(gasView.address()),2_000_000_000L)),List.of(funder));
            var mints=new LinkedHashMap<String,String>();
            for(String symbol:List.of("USDT","USDC")) {
                var mint=new Account();mints.put(symbol,mint.getPublicKeyBase58());
                fixtureSend(realRpc,new Transaction().addInstruction(SystemProgram.createAccount(funder.getPublicKey(),mint.getPublicKey(),realRpc.minimumBalanceForRentExemption(82),82,TokenProgram.PROGRAM_ID))
                        .addInstruction(TokenProgram.initializeMint(mint.getPublicKey(),6,funder.getPublicKey(),funder.getPublicKey())),List.of(funder,mint));
                String from=MpcSolanaTransactionCodec.associatedTokenAddress(funder.getPublicKeyBase58(),mint.getPublicKeyBase58());
                String to=MpcSolanaTransactionCodec.associatedTokenAddress(source.getAddress(),mint.getPublicKeyBase58());
                fixtureSend(realRpc,new Transaction().addInstruction(AssociatedTokenProgram.createIdempotent(funder.getPublicKey(),funder.getPublicKey(),mint.getPublicKey()))
                        .addInstruction(TokenProgram.mintTo(mint.getPublicKey(),new PublicKey(from),funder.getPublicKey(),50_000_000L))
                        .addInstruction(AssociatedTokenProgram.createIdempotent(funder.getPublicKey(),new PublicKey(source.getAddress()),mint.getPublicKey()))
                        .addInstruction(TokenProgram.transferChecked(new PublicKey(from),new PublicKey(to),20_000_000L,(byte)6,funder.getPublicKey(),mint.getPublicKey())),List.of(funder));
                jdbc.update("update token_config set contract_address=?,contract_address_base58=?,decimals=6,enabled=true where chain='SOLANA' and network=? and symbol=?",mint.getPublicKeyBase58(),mint.getPublicKeyBase58(),profile.getNetwork(),symbol);
                jdbc.update("update chain_asset set contract_address=?,decimals=6,active=true where chain='SOLANA' and symbol=?",mint.getPublicKeyBase58(),symbol);
            }
            scanner.scanAndCredit();var totals=new HashMap<String,BigDecimal>();
            for(String symbol:List.of("SOL","USDT","USDC"))totals.put(symbol,balances.find(tenant,"SOLANA",symbol,source.getAccountId()).orElseThrow().getTotalBalance());
            long credited=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SOLANA'",Long.class,tenant);scanner.scanAndCredit();
            require(credited==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SOLANA'",Long.class,tenant),"Solana deposit replay produced duplicate tenant projection");
            require(totals.get("USDT").compareTo(new BigDecimal("20"))==0 && totals.get("USDC").compareTo(new BigDecimal("20"))==0,"Actual SPL transfers did not credit source tenant");
            long gasBefore=realRpc.getBalance(gasView.address()),gasCost=0;var signatures=new HashSet<String>();String recipient=new Account().getPublicKeyBase58();
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for(String symbol:List.of("SOL","USDT","USDC")) {
                    BigDecimal amount=symbol.equals("SOL")?new BigDecimal("0.01"):BigDecimal.ONE;
                    String reference="solana-business-"+UUID.randomUUID();var op=new SolanaSubmissionService.Operation(tenant,reference,SolanaSubmissionService.Kind.WITHDRAWAL);current[0]=op;
                    createOrder(transaction,orders,balances,tenant,source,reference,symbol,recipient,amount,new BigDecimal("0.1"));
                    long before=balance(realRpc,symbol,mints.get(symbol),recipient),count=broadcasts.size();lost.set(true);
                    try{service.send(op,source);throw new AssertionError("Agave lost-response fixture did not interrupt the business call");}
                    catch(IllegalStateException expected){require(broadcasts.size()==count+1,"Solana failed before deliberate lost reply: "+expected);submissions.unknown(op,"fixture lost response");}
                    String signature=orders.findTxHash("SOLANA",reference,tenant).orElseThrow();await(realRpc,signature,false);
                    require(signatures.add(signature),"Different Solana operations selected the same signature");String raw=transactions.findSigned(signature).orElseThrow().getRawPayload();
                    jdbc.update("update chain_profile set withdraw_enabled=false,collection_enabled=false where chain='SOLANA' and enabled");
                    jdbc.update("update token_config set enabled=false where chain='SOLANA'");
                    require(service.recover(op),"Solana did not recover selected message after disabled new-work switches");
                    require(broadcasts.size()==count+1,"Solana recovery broadcast a known signature again");
                    require(service.confirmWithdrawal(tenant,reference),"Solana confirmed receipt did not settle");require(!service.confirmWithdrawal(tenant,reference),"Solana settled principal twice");
                    require(raw.equals(transactions.findSigned(signature).orElseThrow().getRawPayload()) && raw.equals(realRpc.getRawTransaction(signature).path("transaction").path(0).asText()),"Solana recovery changed selected raw");
                    long expected=amount.movePointRight(symbol.equals("SOL")?9:6).longValueExact();require(balance(realRpc,symbol,mints.get(symbol),recipient)-before==expected,"Solana recipient delta differs from payment");
                    long cost=transactions.findSigned(signature).orElseThrow().getFeeLamports();if(!symbol.equals("SOL"))gasCost+=cost;
                    totals.put(symbol,totals.get(symbol).subtract(amount).subtract(new BigDecimal("0.1")));
                    require(balances.find(tenant,"SOLANA",symbol,source.getAccountId()).orElseThrow().getTotalBalance().compareTo(totals.get(symbol))==0,"Solana principal ledger debit differs");
                    require(balances.find(tenant,"SOLANA",symbol,source.getAccountId()).orElseThrow().getLockedBalance().signum()==0,"Solana confirmed principal remains frozen");
                    jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true where chain='SOLANA' and enabled");jdbc.update("update token_config set enabled=true where chain='SOLANA' and network=?",profile.getNetwork());
                }
            }
            UUID custodyId=jdbc.queryForObject("select id from custody_address where tenant_id=? and chain_address_id=?",UUID.class,tenant,source.getId());
            for(String symbol:List.of("SOL","USDT","USDC")) {
                BigDecimal amount=symbol.equals("SOL")?new BigDecimal("0.01"):BigDecimal.ONE;String reference="solana-collection-"+UUID.randomUUID();
                collections.create(tenant,custodyId,reference,"SOLANA",symbol,source.getAddress(),collector.getAddress(),amount,BigDecimal.ZERO,null);
                current[0]=new SolanaSubmissionService.Operation(tenant,reference,SolanaSubmissionService.Kind.COLLECTION);long before=balance(realRpc,symbol,mints.get(symbol),collector.getAddress());
                String signature=service.collect(tenant,reference,source);await(realRpc,signature,false);require(service.confirmCollection(tenant,reference),"Solana collection did not confirm");
                require(!service.confirmCollection(tenant,reference),"Solana collection settled twice");require(balance(realRpc,symbol,mints.get(symbol),collector.getAddress())-before==amount.movePointRight(symbol.equals("SOL")?9:6).longValueExact(),"Solana collector delta differs");
                if(!symbol.equals("SOL"))gasCost+=transactions.findSigned(signature).orElseThrow().getFeeLamports();
            }
            require(gasBefore-realRpc.getBalance(gasView.address())==gasCost,"Solana committed Gas costs do not reconcile with actual payer spending including ATA rent");
            confirmedFailure(jdbc,manager,tenant,custody,principal,realRpc,scanner,submissions,service,orders,balances,current);
            long depositsBefore=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SOLANA'",Long.class,tenant);scanner.scanAndCredit();
            require(depositsBefore==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='SOLANA'",Long.class,tenant),"Solana platform payments were credited as external deposits");
        }
    }
    private static void confirmedFailure(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID tenant,CustodyAddressService custody,CustodyPrincipal principal,
                                         SolanaRpcClient rpc,SolanaDepositScanner scanner,SolanaSubmissionService submissions,SolanaTransactionService service,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,SolanaSubmissionService.Operation[] current) throws Exception {
        var view=custody.create(principal,new CustodyAddressService.CreateAddressCommand("SOLANA","solana-native-failure",0L,null,Map.of()),"API","127.0.0.1");
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,new CustodyAddressRepository(jdbc).require(tenant,view.id()).chainAddressId()).orElseThrow();
        // Exactly the principal is funded. The network fee leaves insufficient SOL for the fixed transfer.
        await(rpc,rpc.requestAirdrop(source.getAddress(),1_000_000L),false);
        scanner.scanAndCredit();
        require(balances.find(tenant,"SOLANA","SOL",source.getAccountId()).orElseThrow().getTotalBalance().compareTo(new BigDecimal("0.001"))==0,"Solana failure fixture deposit did not credit exact principal");
        String reference="solana-confirmed-failure-"+UUID.randomUUID(),recipient=new Account().getPublicKeyBase58();
        var op=new SolanaSubmissionService.Operation(tenant,reference,SolanaSubmissionService.Kind.WITHDRAWAL);current[0]=op;
        createOrder(new TransactionTemplate(manager),orders,balances,tenant,source,reference,"SOL",recipient,new BigDecimal("0.001"),BigDecimal.ZERO);
        var latest=rpc.call("getLatestBlockhash",List.of(Map.of("commitment","confirmed"))).path("value");
        var unsigned=MpcSolanaTransactionCodec.payment(source.getAddress(),source.getAddress(),recipient,null,1_000_000,9,latest.path("blockhash").asText(),SolanaSubmissionService.memo(op));
        var payload=new SolanaSigningPayload("SOL",new BigDecimal("0.001"),BigDecimal.ZERO,9,null,source.getAddress(),recipient,source.getAddress(),latest.path("lastValidBlockHeight").asLong(),unsigned);
        var request=submissions.reserve(op,source,payload);submissions.sign(op,request.id());var signed=submissions.commit(op,request.id());
        // Test-only bypass admits an authentic failed execution; production keeps preflight enabled.
        require(signed.signature().equals(rpc.call("sendTransaction",List.of(signed.transactionBase64(),Map.of("encoding","base64","skipPreflight",true,"maxRetries",5))).asText()),"Agave failure fixture returned another selected signature");
        await(rpc,signed.signature(),true);submissions.unknown(op,"fixture lost failed-execution response");require(service.recover(op),"Solana failed receipt did not recover original raw");
        require(service.confirmWithdrawal(tenant,reference) && !service.confirmWithdrawal(tenant,reference),"Solana confirmed failure refund did not happen exactly once");
        require("FAILED".equals(orders.find("SOLANA",reference,tenant).orElseThrow().getStatus()),"Solana failed transaction confirmed as payment");
        var balance=balances.find(tenant,"SOLANA","SOL",source.getAccountId()).orElseThrow();require(balance.getTotalBalance().compareTo(new BigDecimal("0.001"))==0 && balance.getLockedBalance().signum()==0,"Solana confirmed failure did not release exact frozen principal");
        require(transactions(jdbc).findConfirmedFeeAtomic("SOLANA",signed.signature()).orElseThrow().longValueExact()==5000 && rpc.getBalance(recipient)==0,"Solana failed execution fee/payment differs");
    }
    private static SolanaTransactionRepository transactions(JdbcTemplate jdbc){return new SolanaTransactionRepository(jdbc);}
    private static void createOrder(TransactionTemplate tx,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,UUID tenant,ChainAddressRecord source,String reference,String symbol,String recipient,BigDecimal amount,BigDecimal fee){
        tx.executeWithoutResult(status->{orders.createForTenant(tenant,reference,source.getUserId(),"SOLANA",symbol,source.getAddress(),source.getAccountId(),recipient,amount,fee);
            require(balances.freeze("SOLANA",symbol,source.getAccountId(),amount.add(fee),tenant),"Solana principal could not freeze");orders.updateStatus(tenant,"SOLANA",reference,"FROZEN",source.getAddress(),null,null);
            require(orders.claimSigningDurable(tenant,"SOLANA",reference,source.getAddress())==1,"Solana order could not claim durable signing");});
    }
    private static String fixtureSend(SolanaRpcClient rpc,Transaction tx,List<Account> signers) throws Exception {tx.setRecentBlockHash(rpc.getLatestBlockhash());tx.sign(signers);String signature=rpc.sendTransaction(tx.serialize());await(rpc,signature,false);return signature;}
    private static long balance(SolanaRpcClient rpc,String symbol,String mint,String owner){
        if(symbol.equals("SOL"))return rpc.getBalance(owner);String ata=MpcSolanaTransactionCodec.associatedTokenAddress(owner,mint);
        return rpc.getAccountInfo(ata).isNull()?0:rpc.getTokenAccountBalance(ata);
    }
    private static void await(SolanaRpcClient rpc,String signature,boolean failure) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(System.nanoTime()<end){var status=rpc.getSignatureStatus(signature);
            if(status!=null && !status.isNull() && Set.of("confirmed","finalized").contains(status.path("confirmationStatus").asText())){require(status.path("err").isNull()!=failure,"Agave execution outcome differs: "+status.path("err"));return;}Thread.sleep(100);}
        throw new AssertionError("Agave did not confirm selected signature");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
