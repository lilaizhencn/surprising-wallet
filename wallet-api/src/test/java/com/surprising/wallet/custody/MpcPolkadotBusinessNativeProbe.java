package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.polkadot.*;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.PolkadotSubmissionService.*;
import com.surprising.wallet.testutil.*;
import java.math.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real independent MPC, official archived relay/Asset Hub, tenant deposits, business payments and fees. */
public final class MpcPolkadotBusinessNativeProbe {
    private static final BigInteger UNIT=BigInteger.TEN.pow(12);
    private MpcPolkadotBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config)throws Exception {
        var json=new ObjectMapper();var path=Path.of(config.path("dotFixture").asText()).toRealPath();var fixture=json.readTree(Files.readAllBytes(path));
        var endpoint=URI.create(fixture.path("runtimeUrl").asText());require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()),"DOT formal acceptance requires isolated loopback public gateway");
        for(var role:Role.values()){var ws=URI.create(fixture.path(role.name()).path("endpoint").asText());require("ws".equals(ws.getScheme()) && "127.0.0.1".equals(ws.getHost()),"DOT administrator fixture must use isolated loopback consensus");}
        jdbc.update("update chain_profile set network='localnet',chain_id=42,withdraw_confirmations=1,deposit_confirmations=1,scan_start_height=1,scan_batch_size=100,scan_max_blocks_per_run=100,withdraw_enabled=true,scan_enabled=true,collection_enabled=true,transfer_enabled=true where chain='DOT' and enabled");
        jdbc.update("update chain_asset set decimals=12,active=true where chain='DOT' and symbol='DOT'");
        jdbc.update("update chain_rpc_node set enabled=false where chain='DOT'");
        for(var role:Role.values())jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('DOT','localnet','mpc-test',?,?,?,0)","mpc-dot-"+role,role==Role.RELAY?"relay_rpc":"rpc",fixture.path("publicRpcUrls").path(role.name()).asText());
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,auth_type,api_key,min_request_interval_ms) values ('DOT','localnet','mpc-test','mpc-dot-public-runtime','runtime',?,'BEARER',?,0)",endpoint.toString(),fixture.path("runtimeApiKey").asText());
        var profiles=new ChainProfileRepository(jdbc);var realRpc=new PolkadotRuntimeClient(profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));var rpc=spy(realRpc);
        for(var role:Role.values())require(fixture.path(role.name()).path("genesisHash").asText().equals(realRpc.finalized(role).path("genesisHash").asText()),"DOT formal gateway points at another role genesis");
        var addresses=new ChainAddressRepository(jdbc);var custody=MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager);var gas=CustodyTestSupport.gas(jdbc,custody);
        UUID actor=jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-mpc-probe","TENANT_ADMIN",Set.of("*"));
        var sourceView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("DOT","dot-business-source",0L,null,Map.of()),"API","127.0.0.1");
        var collectorView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("DOT","dot-business-collector",0L,null,Map.of()),"API","127.0.0.1");
        var gasView=gas.create(principal,new CustodyGasService.CreateGasAccountCommand("DOT"),"127.0.0.1");var custodyRows=new CustodyAddressRepository(jdbc);
        var source=addresses.findOwned(tenant,custodyRows.require(tenant,sourceView.id()).chainAddressId()).orElseThrow();var collector=addresses.findOwned(tenant,custodyRows.require(tenant,collectorView.id()).chainAddressId()).orElseThrow();
        var tokens=new TokenConfigRepository(jdbc);var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);var transactions=new PolkadotTransactionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);var tx=new TransactionTemplate(manager);var assets=new ChainAssetRepository(jdbc);
        var runtime=new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc));var quorum=spy(nodes);doNothing().when(quorum).close();var loseReply=new AtomicBoolean();var broadcasts=new ArrayList<String>();Operation[] current={null};
        doAnswer(call->{
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"DOT RPC broadcast held a database transaction");String raw=call.getArgument(1);String hash="0x"+HexFormat.of().formatHex(new Blake2b.Blake2b256().digest(HexFormat.of().parseHex(raw.substring(2))));
            UUID requestId=jdbc.queryForObject("select request_id from polkadot_transaction where tenant_id=? and tx_hash=? and raw_payload=?",UUID.class,tenant,hash,raw);var request=requests.find(tenant,requestId).orElseThrow();
            require(request.state()==MpcSignRequest.State.COMPLETED,"DOT RPC preceded durable signature result");
            if(request.command().businessType()!=MpcSignCommand.BusinessType.FUNDING) {
                String bound=request.command().businessType()==MpcSignCommand.BusinessType.WITHDRAWAL?orders.findTxHash("DOT",request.command().businessReference(),tenant).orElseThrow():collections.findTxHash(tenant,"DOT",request.command().businessReference()).orElseThrow();
                require(hash.equals(bound),"DOT wire reached RPC before owned business hash");
            }
            broadcasts.add(hash);var answer=call.callRealMethod();
            if(current[0]!=null && request.command().businessReference().equals(current[0].reference()) && loseReply.getAndSet(false))throw new IllegalStateException("deliberately lost actual DOT broadcast response");return answer;
        }).when(rpc).broadcast(any(),anyString());
        try(var context=new AnnotationConfigApplicationContext();var depositContext=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper())) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);context.registerBean(PolkadotTransactionRepository.class,()->transactions);
            context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);context.registerBean(ChainProfileRepository.class,()->profiles);
            context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(TokenConfigRepository.class,()->tokens);context.registerBean(ChainAssetRepository.class,()->assets);context.registerBean(ObjectMapper.class,()->json);context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(WithdrawalSettlementService.class);
            context.registerBean(CustodyGasAccountingService.class,()->CustodyTestSupport.gasAccounting(jdbc));context.registerBean(PolkadotSubmissionService.class);context.registerBean(PolkadotRuntimeClient.class,()->rpc);context.registerBean(CustodyGasService.class,()->gas);context.registerBean(WalletRuntimeConfigService.class,()->runtime);context.registerBean(PolkadotTransactionService.class);context.refresh();
            var service=context.getBean(PolkadotTransactionService.class);var submissions=context.getBean(PolkadotSubmissionService.class);var heights=new ChainScanHeightRepository(jdbc);var scanner=new PolkadotDepositScanner(realRpc,heights,depositContext.getBean(DepositService.class),addresses,profiles,tokens,assets,new DepositRecordRepository(jdbc),runtime);
            String recipient=MpcPolkadotTransactionCodec.address(HexFormat.of().parseHex("cd".repeat(32)),42);long relayStart=realRpc.finalized(Role.RELAY).path("height").asLong(),hubStart=realRpc.finalized(Role.ASSET_HUB).path("height").asLong();
            admin(path,fixture,"fund","ASSET_HUB",source.getAddress(),UNIT.multiply(BigInteger.valueOf(100)).toString());admin(path,fixture,"fund","ASSET_HUB",source.getAddress(),"1");
            BigInteger hubEd=new BigInteger(realRpc.networkInfo(Role.ASSET_HUB,source.getAddress(),null).path("existentialDeposit").asText());
            admin(path,fixture,"fund","ASSET_HUB",collector.getAddress(),hubEd.toString());admin(path,fixture,"fund","ASSET_HUB",recipient,UNIT.toString());admin(path,fixture,"fund","ASSET_HUB",gasView.address(),UNIT.multiply(BigInteger.valueOf(100)).toString());
            for(String symbol:List.of("USDT","USDC")) {
                String id=fixture.path(symbol).asText();jdbc.update("insert into token_config(chain,network,symbol,standard,contract_address,decimals,enabled,collect_enabled,gas_strategy) values ('DOT','localnet',?,'ASSET_HUB_ASSET',?,6,true,true,'ASSET_HUB_NATIVE_GAS') on conflict(chain,network,symbol) do update set contract_address=excluded.contract_address,decimals=6,enabled=true,collect_enabled=true",symbol,id);assets.upsertNonNative("DOT",symbol,"ASSET_HUB_ASSET",id,6,true,BigDecimal.ZERO,BigDecimal.ZERO);
                admin(path,fixture,"asset-init","ASSET_HUB",symbol,source.getAddress(),"20000000");
            }
            heights.upsert("DOT","polkadot-assethub-scanner",hubStart-1,hubStart-1);scanner.scanAndCredit();
            var totals=new HashMap<String,BigDecimal>();for(String symbol:List.of("DOT","USDT","USDC"))totals.put(symbol,balances.find(tenant,"DOT",symbol,source.getAccountId()).orElseThrow().getTotalBalance());
            require(totals.get("DOT").compareTo(new BigDecimal("100.000000000001"))==0 && totals.get("USDT").compareTo(new BigDecimal("20"))==0 && totals.get("USDC").compareTo(new BigDecimal("20"))==0,"DOT actual native/token deposits differ from exact funding");
            long deposits=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='DOT'",Long.class,tenant);require(deposits==6,"DOT mandatory tenant funding projection count differs: "+deposits);
            require(gas.findGasAccount(tenant,"DOT").orElseThrow().availableBalance().compareTo(new BigDecimal("100"))==0,"DOT scanner did not automatically credit exactly one tenant Gas deposit");
            heights.upsert("DOT","polkadot-assethub-scanner",hubStart-1,hubStart-1);scanner.scanAndCredit();require(deposits==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='DOT'",Long.class,tenant),"DOT canonical replay duplicated deposits");
            BigInteger controlledBefore=controlled(realRpc,source.getAddress(),collector.getAddress(),gasView.address()),externalNative=BigInteger.ZERO;int round=0;var hashes=new HashSet<String>();
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for(String symbol:List.of("DOT","USDT","USDC")) {
                    BigDecimal amount=symbol.equals("DOT") && round==2?new BigDecimal("0.000000000001"):BigDecimal.ONE;String reference="dot-business-"+UUID.randomUUID();var op=new Operation(tenant,reference,Kind.WITHDRAWAL);current[0]=op;
                    createOrder(tx,orders,balances,tenant,source,reference,symbol,recipient,amount,new BigDecimal("0.1"));var before=balance(realRpc,symbol,fixture,recipient);int count=broadcasts.size();loseReply.set(true);
                    try{service.send(op,source);throw new AssertionError("DOT actual reply was not lost");}catch(IllegalStateException expected){require(!loseReply.get() && broadcasts.size()>count,"DOT failed before actual accepted response was lost: "+expected);submissions.unknown(op,"fixture lost actual response");}
                    var request=submissions.intent(op).orElseThrow();String hash=orders.findTxHash("DOT",reference,tenant).orElseThrow();require(hashes.add(hash),"DOT distinct payments reused selected hash");String raw=transactions.find(tenant,request.id()).orElseThrow().rawPayload();int submitted=broadcasts.size();
                    jdbc.update("update chain_profile set withdraw_enabled=false,collection_enabled=false where chain='DOT' and enabled");jdbc.update("update token_config set enabled=false where chain='DOT'");
                    require(service.recover(op) && broadcasts.size()==submitted,"DOT recovery replaced or rebroadcast known original wire");await(service,tenant,reference,false);require(!service.confirmWithdrawal(tenant,reference),"DOT principal settled twice");
                    require(raw.equals(transactions.find(tenant,request.id()).orElseThrow().rawPayload()),"DOT recovery changed durable wire");
                    BigInteger atomic=amount.movePointRight(symbol.equals("DOT")?12:6).toBigIntegerExact();require(balance(realRpc,symbol,fixture,recipient).subtract(before).equals(atomic),"DOT receiver delta differs from exact principal");if(symbol.equals("DOT"))externalNative=externalNative.add(atomic);
                    totals.put(symbol,totals.get(symbol).subtract(amount.add(new BigDecimal("0.1"))));var book=balances.find(tenant,"DOT",symbol,source.getAccountId()).orElseThrow();require(book.getLockedBalance().signum()==0 && book.getTotalBalance().compareTo(totals.get(symbol))==0,"DOT principal/customer fee settlement differs");
                    try{realRpc.broadcast(submissions.payload(request).unsigned().role(),raw);}catch(RuntimeException alreadySpent){/* The original nonce has already finalized. */}
                    require(balance(realRpc,symbol,fixture,recipient).subtract(before).equals(atomic),"DOT original raw replay paid twice");
                    jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true where chain='DOT' and enabled");jdbc.update("update token_config set enabled=true where chain='DOT' and network='localnet'");
                }
                round++;
            }
            var collectionProofs=new ArrayList<Map<String,Object>>();var failedProofs=new ArrayList<Map<String,Object>>();
            for(boolean token:List.of(false,true)) {
                String symbol=token?"USDC":"DOT",reference="dot-failed-"+UUID.randomUUID(),receiver=token?recipient:MpcPolkadotTransactionCodec.address(HexFormat.of().parseHex("ee".repeat(32)),42);BigDecimal amount=token?BigDecimal.ONE:new BigDecimal("0.000000000001");var op=new Operation(tenant,reference,Kind.WITHDRAWAL);current[0]=op;
                if(token)admin(path,fixture,"asset-freeze","ASSET_HUB",symbol,source.getAddress());
                try {
                    createOrder(tx,orders,balances,tenant,source,reference,symbol,receiver,amount,BigDecimal.ZERO);var before=balance(realRpc,symbol,fixture,receiver);String hash=service.send(op,source);submissions.restoreSent(op,hash);await(service,tenant,reference,false);
                    require("FAILED".equals(orders.find("DOT",reference,tenant).orElseThrow().getStatus()) && !service.confirmWithdrawal(tenant,reference),"DOT actual dispatch failure did not refund exactly once");require(before.equals(balance(realRpc,symbol,fixture,receiver)),"DOT failed dispatch paid recipient");var book=balances.find(tenant,"DOT",symbol,source.getAccountId()).orElseThrow();require(book.getLockedBalance().signum()==0 && book.getTotalBalance().compareTo(totals.get(symbol))==0,"DOT failed dispatch changed exact customer principal");
                    var request=submissions.intent(op).orElseThrow();failedProofs.add(Map.of("unsigned",submissions.payload(request).unsigned(),"selected",submissions.commit(op,request.id()),"proof",submissions.proof(op).orElseThrow()));
                } finally {if(token)admin(path,fixture,"asset-thaw","ASSET_HUB",symbol,source.getAddress());}
            }
            for(String symbol:List.of("DOT","USDT","USDC")) {
                String reference="dot-collection-"+UUID.randomUUID();BigDecimal amount=symbol.equals("DOT")?service.collectableNativeAmount(source.getAddress(),new BigDecimal(realRpc.nativeBalance(source.getAddress())).movePointLeft(12),BigDecimal.ZERO):new BigDecimal(realRpc.assetBalance(fixture.path(symbol).asText(),source.getAddress())).movePointLeft(6);
                collections.create(tenant,sourceView.id(),reference,"DOT",symbol,source.getAddress(),collector.getAddress(),amount,BigDecimal.ZERO,null);current[0]=new Operation(tenant,reference,Kind.COLLECTION);
                var before=balance(realRpc,symbol,fixture,collector.getAddress());service.collect(tenant,reference,source);await(service,tenant,reference,true);require(!service.confirmCollection(tenant,reference),"DOT collection settled twice");require(balance(realRpc,symbol,fixture,collector.getAddress()).subtract(before).equals(amount.movePointRight(symbol.equals("DOT")?12:6).toBigIntegerExact()),"DOT actual collection principal differs");
                var request=submissions.intent(current[0]).orElseThrow();var payload=submissions.payload(request);require(payload.unsigned().keepAlive()==symbol.equals("DOT"),"DOT collection native/asset account policy differs");
                var system=realRpc.networkInfo(payload.unsigned().role(),source.getAddress(),null);require(system.path("nonce").asLong(-1)==payload.unsigned().nonce()+1 && new BigInteger(system.path("nativeBalance").asText()).compareTo(payload.existentialDeposit())>=0,"DOT whole asset/native collection reaped or reset its system account");
                if(!symbol.equals("DOT"))require(realRpc.assetBalance(fixture.path(symbol).asText(),source.getAddress()).signum()==0,"DOT asset collection left a minimum token balance behind");
                collectionProofs.add(Map.of("unsigned",payload.unsigned(),"selected",submissions.commit(current[0],request.id()),"proof",submissions.proof(current[0]).orElseThrow()));
            }
            BigInteger fees=jdbc.queryForObject("select sum(fee_planck) from polkadot_transaction where tenant_id=? and status in ('CONFIRMED','FAILED')",BigDecimal.class,tenant).toBigIntegerExact();
            require(controlledBefore.subtract(controlled(realRpc,source.getAddress(),collector.getAddress(),gasView.address())).subtract(externalNative).equals(fees),"DOT controlled native balances do not reconcile to external principal and all actual fees across both roles");
            var info=realRpc.networkInfo(Role.ASSET_HUB,source.getAddress(),null);require(realRpc.nativeBalance(source.getAddress()).compareTo(new BigInteger(info.path("existentialDeposit").asText()))>=0,"DOT native collection reaped a published account");
            require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where tenant_id=? and chain='DOT' and business_type='FUNDING'",Integer.class,tenant)>0,"DOT real full collection never exercised tenant MPC Gas funding");
            require(gas.findGasAccount(tenant,"DOT").orElseThrow().availableBalance().compareTo(new BigDecimal(realRpc.nativeBalance(gasView.address())).movePointLeft(12))==0,"DOT owned Gas funding reservation/actual source cost did not reconcile");
            jdbc.update("update chain_address set enabled=false where tenant_id=? and id=?",tenant,source.getId());scanner.scanAndCredit();require(deposits==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='DOT'",Long.class,tenant),"DOT owned payments including disabled senders were credited as external deposits");
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-dot-actual-collection-proofs.json"),json.writeValueAsString(collectionProofs));
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-dot-actual-failed-proofs.json"),json.writeValueAsString(failedProofs));
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-dot-business-public-summary.json"),json.writeValueAsString(Map.of("source",source.getAddress(),"collector",collector.getAddress(),"gas",gasView.address(),"actualBusinessPayouts",9,"collections",3,"failedRefunds",2,"depositProjections",deposits,"totalNativeFeesPlanck",fees)));
        }
    }
    private static void await(PolkadotTransactionService service,UUID tenant,String reference,boolean collection)throws Exception{for(int attempt=0;attempt<45;attempt++){if(collection?service.confirmCollection(tenant,reference):service.confirmWithdrawal(tenant,reference))return;Thread.sleep(1000);}throw new AssertionError("DOT final business receipt timed out: "+reference);}
    private static BigInteger controlled(PolkadotRuntimeClient rpc,String... owners){BigInteger total=BigInteger.ZERO;for(String owner:owners)total=total.add(rpc.nativeBalance(Role.RELAY,owner)).add(rpc.nativeBalance(Role.ASSET_HUB,owner));return total;}
    private static BigInteger balance(PolkadotRuntimeClient rpc,String symbol,JsonNode fixture,String owner){return symbol.equals("DOT")?rpc.nativeBalance(owner):rpc.assetBalance(fixture.path(symbol).asText(),owner);}
    private static void createOrder(TransactionTemplate tx,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,UUID tenant,ChainAddressRecord source,String reference,String symbol,String recipient,BigDecimal amount,BigDecimal fee){tx.executeWithoutResult(status->{orders.createForTenant(tenant,reference,source.getUserId(),"DOT",symbol,source.getAddress(),source.getAccountId(),recipient,amount,fee);require(balances.freeze("DOT",symbol,source.getAccountId(),amount.add(fee),tenant),"DOT principal freeze failed");orders.updateStatus(tenant,"DOT",reference,"FROZEN",source.getAddress(),null,null);require(orders.claimSigningDurable(tenant,"DOT",reference,source.getAddress())==1,"DOT durable claim failed");});}
    private static void admin(Path path,JsonNode fixture,String... args)throws Exception {
        var command=new ArrayList<String>(List.of("node",Path.of("resources/infra/mpc-signer/tests/polkadot_fixture.cjs").toRealPath().toString(),path.toString()));command.addAll(Arrays.asList(args));var output=Files.createTempFile("surprising-wallet-mpc-dot-business-admin-",".json");var log=Files.createTempFile("surprising-wallet-mpc-dot-business-admin-",".log");var builder=new ProcessBuilder(command);builder.environment().put("NODE_PATH",fixture.path("nodeModules").asText());var process=builder.redirectOutput(output.toFile()).redirectError(log.toFile()).start();if(!process.waitFor(55,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("DOT fixture timeout; see "+log);}require(process.exitValue()==0,"DOT fixture action failed; see "+log);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
