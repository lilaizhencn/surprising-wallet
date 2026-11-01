package com.surprising.wallet.custody;

import com.surprising.wallet.chain.cardano.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.CardanoSubmissionService.*;
import com.surprising.wallet.testutil.*;
import java.math.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real native consensus, independent MPC, production custody posting/submission/settlement and physical expenses. */
public final class MpcCardanoBusinessNativeProbe {
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();
    private MpcCardanoBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }

    public static void verify(JdbcTemplate jdbc, PlatformTransactionManager manager, MpcSigningService signing,
                              MpcNodes nodes, UUID tenant, JsonNode config) throws Exception {
        Path descriptor = Path.of(config.path("cardanoFixture").asText()).toRealPath();
        var fixture = JSON.readTree(Files.readAllBytes(descriptor)); var endpoint = URI.create(fixture.path("rpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()), "Cardano business acceptance requires isolated native RPC");
        jdbc.update("update chain_profile set deposit_confirmations=3,withdraw_confirmations=3,scan_max_blocks_per_run=500,withdraw_enabled=true,scan_enabled=true,collection_enabled=true,transfer_enabled=true where chain='ADA' and enabled");
        jdbc.update("update chain_asset set active=true,decimals=6 where chain='ADA' and symbol='ADA'");
        var profiles = new ChainProfileRepository(jdbc); var profile = profiles.findAccountByChain("ADA").orElseThrow();
        jdbc.update("update chain_rpc_node set enabled=false where chain='ADA'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('ADA',?,'mpc-test','mpc-cardano-business','rpc',?,0)",profile.getNetwork(),endpoint.toString());
        var real = new CardanoRpcClient(profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2)); var rpc = spy(real);
        require(fixture.path("genesisHash").asText().equals(HEX.formatHex(real.genesis().getGenesis().toByteArray())),"Cardano business native genesis differs");
        var tokenInfo = admin(descriptor,"info");
        for (String symbol : List.of("USDT","USDC")) jdbc.update("update token_config set enabled=true,collect_enabled=true,decimals=6,contract_address=? where chain='ADA' and network=? and symbol=?",tokenInfo.path("units").path(symbol).asText(),profile.getNetwork(),symbol);
        var heights = new ChainScanHeightRepository(jdbc); var blocks = new ChainScanBlockRepository(jdbc);
        var custody = MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager,new CardanoScanAnchorService(real,heights,blocks));
        var gas = CustodyTestSupport.gas(jdbc,custody); var addresses = new ChainAddressRepository(jdbc); var custodyRows = new CustodyAddressRepository(jdbc);
        UUID actor = jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);
        var principal = new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-cardano-probe","TENANT_ADMIN",Set.of("*"));
        var sourceView = custody.create(principal,new CustodyAddressService.CreateAddressCommand("ADA","cardano-business-source",0L,null,Map.of()),"API","127.0.0.1");
        var collectorView = custody.create(principal,new CustodyAddressService.CreateAddressCommand("ADA","cardano-business-collector",0L,null,Map.of()),"API","127.0.0.1");
        var gasView = gas.create(principal,new CustodyGasService.CreateGasAccountCommand("ADA"),"127.0.0.1");
        var source = addresses.findOwned(tenant,custodyRows.require(tenant,sourceView.id()).chainAddressId()).orElseThrow();
        var collector = addresses.findOwned(tenant,custodyRows.require(tenant,collectorView.id()).chainAddressId()).orElseThrow();
        var orders = new WithdrawalOrderRepository(jdbc); var collections = new CollectionRecordRepository(jdbc);
        var transactions = new CardanoTransactionRepository(jdbc); var requests = new WalletMpcSignRequestRepository(jdbc);
        var balances = new LedgerBalanceRepository(jdbc); var tokens = new TokenConfigRepository(jdbc); var assets = new ChainAssetRepository(jdbc);
        var runtime = new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc)); var tx = new TransactionTemplate(manager);
        var quorum = spy(nodes); doNothing().when(quorum).close();
        var loseReply = new AtomicBoolean(); var submitted = new ArrayList<String>(); Operation[] current = {null};
        doAnswer(call -> {
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"Cardano RPC broadcast held a database transaction");
            byte[] bytes = call.getArgument(0); String raw = HEX.formatHex(bytes);
            UUID requestId = jdbc.queryForObject("select request_id from cardano_transaction where tenant_id=? and raw_payload=?",UUID.class,tenant,raw);
            var request = requests.find(tenant,requestId).orElseThrow(); var stored = transactions.find(tenant,requestId).orElseThrow();
            require(request.state()==MpcSignRequest.State.COMPLETED,"Cardano RPC preceded durable MPC result");
            if (request.command().businessType()!=MpcSignCommand.BusinessType.FUNDING) {
                String bound = request.command().businessType()==MpcSignCommand.BusinessType.WITHDRAWAL
                        ? orders.findTxHash("ADA",request.command().businessReference(),tenant).orElseThrow()
                        : collections.findTxHash(tenant,"ADA",request.command().businessReference()).orElseThrow();
                require(stored.txHash().equals(bound),"Cardano RPC preceded durable business hash");
            }
            submitted.add(raw); var answer = call.callRealMethod();
            if (current[0]!=null && request.command().businessReference().equals(current[0].reference()) && loseReply.getAndSet(false))
                throw new IllegalStateException("deliberately lost actual Cardano submission reply");
            return answer;
        }).when(rpc).submit(any(byte[].class));
        try (var context = new AnnotationConfigApplicationContext(); var depositContext = DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),JSON)) {
            context.register(Transactions.class); context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(CardanoRpcClient.class,()->rpc); context.registerBean(WithdrawalOrderRepository.class,()->orders);
            context.registerBean(CollectionRecordRepository.class,()->collections); context.registerBean(CardanoTransactionRepository.class,()->transactions);
            context.registerBean(WalletMpcSignRequestRepository.class,()->requests); context.registerBean(MpcSigningService.class,()->signing);
            context.registerBean(MpcNodes.class,()->quorum); context.registerBean(ChainProfileRepository.class,()->profiles);
            context.registerBean(ChainAddressRepository.class,()->addresses); context.registerBean(ChainAssetRepository.class,()->assets);
            context.registerBean(TokenConfigRepository.class,()->tokens); context.registerBean(ChainScanHeightRepository.class,()->heights);
            context.registerBean(ObjectMapper.class,()->JSON); context.registerBean(LedgerBalanceRepository.class,()->balances);
            context.registerBean(CustodyGasAccountingService.class,()->CustodyTestSupport.gasAccounting(jdbc));
            context.registerBean(WithdrawalSettlementService.class); context.registerBean(CardanoSubmissionService.class);
            context.registerBean(CustodyGasService.class,()->gas); context.registerBean(WalletRuntimeConfigService.class,()->runtime);
            context.registerBean(CardanoTransactionService.class); context.refresh();
            var service = context.getBean(CardanoTransactionService.class); var submissions = context.getBean(CardanoSubmissionService.class);
            var scanner = new CardanoDepositScanner(real,addresses,profiles,tokens,assets,heights,blocks,new CardanoOwnedOutputRepository(jdbc),submissions,
                    depositContext.getBean(DepositService.class),manager,JSON,runtime);
            admin(descriptor,"fund",source.getAddress(),"100000000","20000000");
            awaitNative(real,source.getAddress(),new BigInteger("100000000"));
            admin(descriptor,"fund",gasView.address(),"100000000","0"); awaitNative(real,gasView.address(),new BigInteger("100000000"));
            awaitDeposits(scanner,jdbc,tenant,4);
            require(gas.findGasAccount(tenant,"ADA").orElseThrow().availableBalance().compareTo(new BigDecimal("100"))==0,"Cardano scanner did not automatically credit tenant Gas funding");
            var expected = new HashMap<String,BigDecimal>(Map.of("ADA",new BigDecimal("100"),"USDT",new BigDecimal("20"),"USDC",new BigDecimal("20")));
            BigInteger externalNative=BigInteger.ZERO, additionalNative=BigInteger.ZERO;
            String recipient=MpcCardanoTransactionCodec.address(HEX.parseHex("ee".repeat(32)),false);
            int round=0; var hashes=new HashSet<String>();
            for (var pair : List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for (String symbol : List.of("ADA","USDT","USDC")) {
                    String reference="cardano-business-"+UUID.randomUUID(); var op=new Operation(tenant,reference,Kind.WITHDRAWAL); current[0]=op;
                    BigDecimal amount=symbol.equals("ADA")?new BigDecimal("2.000001"):round==2?new BigDecimal("0.000001"):new BigDecimal("1.000001");
                    createOrder(tx,orders,balances,tenant,source,reference,symbol,recipient,amount,new BigDecimal("0.1"));
                    String unit=symbol.equals("ADA")?"lovelace":tokenInfo.path("units").path(symbol).asText(); BigInteger before=balance(real,recipient,unit);
                    int count=submitted.size(); loseReply.set(true);
                    try { service.send(op,source); throw new AssertionError("Cardano actual submit reply was not lost"); }
                    catch (IllegalStateException error) { require(!loseReply.get() && submitted.size()>count,"Cardano failed before actual reply loss: "+error); submissions.unknown(op,"fixture lost actual accepted reply"); }
                    var request=submissions.intent(op).orElseThrow(); var stored=submissions.stored(op).orElseThrow(); require(hashes.add(stored.txHash()),"Cardano distinct business reused a body hash");
                    String raw=stored.rawPayload();
                    jdbc.update("update chain_profile set withdraw_enabled=false,collection_enabled=false where chain='ADA' and enabled");
                    jdbc.update("update token_config set enabled=false where chain='ADA'");
                    try { require(service.recover(op),"Cardano durable original intent was not recovered"); }
                    catch (RuntimeException replayRejected) { require(submissions.stored(op).orElseThrow().rawPayload().equals(raw),"Cardano retry replaced selected wire"); }
                    awaitOutcome(service,submissions,op);
                    require("CONFIRMED".equals(orders.find("ADA",reference,tenant).orElseThrow().getStatus()) && !service.confirmWithdrawal(tenant,reference),"Cardano principal did not settle exactly once");
                    var payload=submissions.payload(request);
                    require(balance(real,recipient,unit).subtract(before).equals(amount.movePointRight(6).toBigIntegerExact()),"Cardano exact receiver principal differs");
                    externalNative=externalNative.add(MpcCardanoTransactionCodec.recipientLovelace(payload.unsigned()));
                    expected.put(symbol,expected.get(symbol).subtract(amount.add(new BigDecimal("0.1"))));
                    var book=balances.find(tenant,"ADA",symbol,source.getAccountId()).orElseThrow();
                    require(book.getLockedBalance().signum()==0 && book.getTotalBalance().compareTo(expected.get(symbol))==0,"Cardano frozen principal/customer fee differs");
                    require(raw.equals(submissions.stored(op).orElseThrow().rawPayload()),"Cardano recovery changed original wire");
                    try { real.submit(HEX.parseHex(raw)); } catch (RuntimeException spent) { /* Identical already-consumed inputs cannot pay twice. */ }
                    require(balance(real,recipient,unit).subtract(before).equals(amount.movePointRight(6).toBigIntegerExact()),"Cardano same-wire replay paid twice");
                    jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true where chain='ADA' and enabled");
                    jdbc.update("update token_config set enabled=true where chain='ADA' and network=?",profile.getNetwork());
                }
                round++;
            }
            // Full available native principal forces the production budget guard to fund change minimum and actual fee from the owned Gas signer.
            String gasReference="cardano-funded-"+UUID.randomUUID(); var funded=new Operation(tenant,gasReference,Kind.WITHDRAWAL); current[0]=funded;
            BigDecimal principalAmount=new BigDecimal(balance(real,source.getAddress(),"lovelace")).movePointLeft(6);
            createOrder(tx,orders,balances,tenant,source,gasReference,"ADA",recipient,principalAmount,BigDecimal.ZERO);
            var fundedHash=service.send(funded,source); submissions.restoreSent(funded,fundedHash); awaitOutcome(service,submissions,funded);
            externalNative=externalNative.add(principalAmount.movePointRight(6).toBigIntegerExact());
            require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where tenant_id=? and chain='ADA' and business_type='FUNDING'",Integer.class,tenant)>0,"Cardano formal flow never exercised actual MPC Gas supplementation");
            require(gas.findGasAccount(tenant,"ADA").orElseThrow().availableBalance().compareTo(new BigDecimal(balance(real,gasView.address(),"lovelace")).movePointLeft(6))==0,"Cardano Gas reservation did not reconcile to actual source expense");
            // Public test funding is observed normally; do not hand-edit any wallet balance.
            BigInteger sourceBefore=balance(real,source.getAddress(),"lovelace");
            admin(descriptor,"fund",source.getAddress(),"20000000","0"); awaitNative(real,source.getAddress(),sourceBefore.add(new BigInteger("20000000")));
            additionalNative=additionalNative.add(new BigInteger("20000000")); awaitDeposits(scanner,jdbc,tenant,5);
            // Legitimate withdrawal to another managed deposit is a customer credit; its change remains internal.
            String internalRef="cardano-owned-recipient-"+UUID.randomUUID(); var internal=new Operation(tenant,internalRef,Kind.WITHDRAWAL); current[0]=internal;
            createOrder(tx,orders,balances,tenant,source,internalRef,"ADA",collector.getAddress(),new BigDecimal("2"),BigDecimal.ZERO);
            var internalHash=service.send(internal,source); submissions.restoreSent(internal,internalHash); awaitOutcome(service,submissions,internal); awaitDeposits(scanner,jdbc,tenant,6);
            require(balances.find(tenant,"ADA","ADA",collector.getAccountId()).orElseThrow().getTotalBalance().compareTo(new BigDecimal("2"))==0,"Cardano withdrawal to owned deposit was hidden as internal change");
            // Real valid MPC wire is delayed beyond a short accepted TTL; only complete native proof may refund it.
            String expiredRef="cardano-expired-business-"+UUID.randomUUID(); var expired=new Operation(tenant,expiredRef,Kind.WITHDRAWAL); current[0]=expired;
            createOrder(tx,orders,balances,tenant,source,expiredRef,"ADA",recipient,new BigDecimal("2"),BigDecimal.ZERO);
            var origin=real.confirmedBlock(3); var draft=CardanoPublicTransactionBuilder.prepare(real.genesis(),real.parameters(),real.utxos(source.getAddress()),
                    HEX.parseHex(submissions.publicKey(source)),source.getAddress(),recipient,"lovelace",new BigInteger("2000000"),30);
            var request=submissions.reserve(expired,source,new CardanoSigningPayload("ADA",new BigDecimal("2"),BigDecimal.ZERO,6,3,CardanoRpcClient.point(origin.reference()),draft.unsigned()));
            submissions.sign(expired,request.id()); var selected=submissions.commit(expired,request.id());
            BigInteger beforeExpiry=balance(real,source.getAddress(),"lovelace");
            for (int attempt=0; real.tip().getSlot()<MpcCardanoTransactionCodec.expiry(draft.unsigned()); attempt++) { require(attempt<160,"Cardano private node did not close business TTL"); Thread.sleep(250); }
            boolean rejected=false;
            try { real.submit(HEX.parseHex(selected.transactionHex())); }
            catch (RuntimeException error) { require(error.getMessage().matches("(?is).*(Expired|OutsideValidity|ValidityInterval|ttl).*"),"Cardano TTL rejection had unrelated cause: "+error); rejected=true; }
            require(rejected && service.recover(expired),"Cardano expired original wire was not retained"); awaitOutcome(service,submissions,expired);
            require("FAILED".equals(orders.find("ADA",expiredRef,tenant).orElseThrow().getStatus()) && !service.confirmWithdrawal(tenant,expiredRef),"Cardano proven expiry did not refund exactly once");
            require(submissions.stored(expired).orElseThrow().nativeExpenseLovelace().signum()==0 && beforeExpiry.equals(balance(real,source.getAddress(),"lovelace")),"Cardano expired payment changed actual funds/fee");
            var collectionProofs=new ArrayList<Map<String,Object>>();
            for (String symbol : List.of("USDT","USDC","ADA")) {
                String unit=symbol.equals("ADA")?"lovelace":tokenInfo.path("units").path(symbol).asText();
                BigDecimal amount=new BigDecimal(balance(real,source.getAddress(),unit)).movePointLeft(6);
                if(symbol.equals("ADA")) amount=service.collectableNativeAmount(source.getAddress(),amount,BigDecimal.ZERO);
                require(amount.signum()>0,"Cardano physical collection amount is empty");
                String reference="cardano-collection-"+UUID.randomUUID(); var op=new Operation(tenant,reference,Kind.COLLECTION); current[0]=op;
                collections.create(tenant,sourceView.id(),reference,"ADA",symbol,source.getAddress(),collector.getAddress(),amount,BigDecimal.ZERO,null);
                BigInteger before=balance(real,collector.getAddress(),unit); service.collect(tenant,reference,source); awaitOutcome(service,submissions,op);
                require(!service.confirmCollection(tenant,reference) && balance(real,collector.getAddress(),unit).subtract(before).equals(amount.movePointRight(6).toBigIntegerExact()),"Cardano actual collection principal differs/repeats");
                if(!symbol.equals("ADA")) require(balance(real,source.getAddress(),unit).signum()==0,"Cardano full asset collection left tokens behind");
                var intent=submissions.intent(op).orElseThrow(); var row=submissions.stored(op).orElseThrow();
                collectionProofs.add(Map.of("unsigned",submissions.payload(intent).unsigned(),"selected",submissions.commit(op,intent.id()),"proof",JSON.readTree(row.executionReceipt())));
            }
            for(int attempt=0;attempt<8;attempt++){scanner.scanAndCredit();Thread.sleep(250);}
            require(jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='ADA'",Integer.class,tenant)==6,"Cardano internal change/collection/Gas/replay created extra deposits");
            require(balances.find(tenant,"ADA","ADA",collector.getAccountId()).orElseThrow().getTotalBalance().compareTo(new BigDecimal("2"))==0,"Cardano internal collections credited customer twice");
            BigInteger finalControlled=balance(real,source.getAddress(),"lovelace").add(balance(real,collector.getAddress(),"lovelace")).add(balance(real,gasView.address(),"lovelace"));
            BigInteger fees=jdbc.queryForObject("select coalesce(sum(fee_lovelace),0) from cardano_transaction where tenant_id=? and status in ('CONFIRMED','FAILED')",BigDecimal.class,tenant).toBigIntegerExact();
            require(new BigInteger("200000000").add(additionalNative).subtract(finalControlled).subtract(externalNative).equals(fees),"Cardano exact controlled native conservation differs from actual original fees");
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-cardano-business-public.json"),JSON.writeValueAsString(Map.of("feesLovelace",fees,"externalNative",externalNative,"finalControlled",finalControlled,"collections",collectionProofs)));
        }
    }
    private static void createOrder(TransactionTemplate tx, WithdrawalOrderRepository orders, LedgerBalanceRepository balances, UUID tenant,
                                    ChainAddressRecord source,String reference,String symbol,String recipient,BigDecimal amount,BigDecimal fee) {
        tx.executeWithoutResult(status->{
            orders.createForTenant(tenant,reference,source.getUserId(),"ADA",symbol,source.getAddress(),source.getAccountId(),recipient,amount,fee);
            require(balances.freeze("ADA",symbol,source.getAccountId(),amount.add(fee),tenant),"Cardano real customer principal is insufficient");
            orders.updateStatus(tenant,"ADA",reference,"FROZEN",source.getAddress(),null,null);
            require(orders.claimSigningDurable(tenant,"ADA",reference,source.getAddress())==1,"Cardano formal order was not claimed");
        });
    }
    private static void awaitOutcome(CardanoTransactionService service,CardanoSubmissionService submissions,Operation operation)throws Exception {
        for(int attempt=0;attempt<160;attempt++) {
            if(operation.kind()==Kind.COLLECTION) service.confirmCollection(operation.tenantId(),operation.reference());
            else service.confirmWithdrawal(operation.tenantId(),operation.reference());
            if(submissions.terminal(operation)) return; Thread.sleep(250);
        }
        throw new AssertionError("Cardano production outcome remained unresolved: "+operation.reference());
    }
    private static void awaitDeposits(CardanoDepositScanner scanner,JdbcTemplate jdbc,UUID tenant,int expected)throws Exception {
        for(int attempt=0;attempt<160;attempt++) {
            scanner.scanAndCredit();
            if(jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='ADA'",Integer.class,tenant)==expected)return;
            Thread.sleep(250);
        }
        throw new AssertionError("Cardano real deposits did not reach mandatory projection count "+expected);
    }
    private static BigInteger balance(CardanoRpcClient rpc,String address,String unit) {
        BigInteger amount=BigInteger.ZERO;
        for(var item:rpc.utxos(address).getItemsList()) {
            if(unit.equals("lovelace")) amount=amount.add(CardanoRpcClient.integer(item.getCardano().getCoin()));
            else for(var group:item.getCardano().getAssetsList()) for(var asset:group.getAssetsList())
                if((HEX.formatHex(group.getPolicyId().toByteArray())+HEX.formatHex(asset.getName().toByteArray())).equals(unit)) amount=amount.add(CardanoRpcClient.integer(asset.getQuantity()));
        }
        return amount;
    }
    private static void awaitNative(CardanoRpcClient rpc,String address,BigInteger expected)throws Exception {
        for(int attempt=0;attempt<120;attempt++){if(balance(rpc,address,"lovelace").equals(expected))return;Thread.sleep(250);}
        throw new AssertionError("Cardano isolated funding did not appear");
    }
    private static JsonNode admin(Path descriptor,String... arguments)throws Exception {
        var command=new ArrayList<>(List.of("node",Path.of("resources/infra/mpc-signer/tests/cardano_fixture.cjs").toRealPath().toString(),descriptor.toString())); command.addAll(Arrays.asList(arguments));
        Path output=Files.createTempFile("surprising-wallet-mpc-cardano-business-public-",".json"),log=Files.createTempFile("surprising-wallet-mpc-cardano-business-admin-",".log");
        var process=new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(log.toFile()).start();
        if(!process.waitFor(55,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Cardano isolated funding timed out: "+log);}
        require(process.exitValue()==0,"Cardano isolated fixture failed: "+Files.readString(log));return JSON.readTree(Files.readAllBytes(output));
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
