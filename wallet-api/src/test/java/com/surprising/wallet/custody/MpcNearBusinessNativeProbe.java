package com.surprising.wallet.custody;

import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bitcoinj.base.Base58;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real independent MPC, NEAR consensus, owned business transactions and exact 24-place money. */
public final class MpcNearBusinessNativeProbe {
    private static final BigInteger YOCTO=BigInteger.TEN.pow(24);
    private MpcNearBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,MpcNodes nodes,UUID tenant,JsonNode config)throws Exception {
        var json=new ObjectMapper();var fixturePath=Path.of(config.path("nearFixture").asText()).toRealPath();var fixture=json.readTree(Files.readAllBytes(fixturePath));var endpoint=URI.create(config.path("nearRpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()) && endpoint.toString().equals(fixture.path("endpoint").asText()),"NEAR business requires its isolated loopback fixture ledger");
        var profiles=new ChainProfileRepository(jdbc);
        jdbc.update("update chain_profile set network='localnet',withdraw_confirmations=1,deposit_confirmations=1,scan_batch_size=500,scan_max_blocks_per_run=500,withdraw_enabled=true,scan_enabled=true,collection_enabled=true,transfer_enabled=true where chain='NEAR' and enabled");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('NEAR','localnet','mpc-test','mpc-near-business','rpc',?,0)",endpoint.toString());
        var realRpc=new NearRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2));var rpc=spy(realRpc.forNetwork("localnet"));doReturn(rpc).when(rpc).forNetwork("localnet");
        require("mpc-localnet-2134".equals(realRpc.status().path("chain_id").asText()) && fixture.path("genesisHash").asText().equals(realRpc.status().path("genesis_hash").asText()),"NEAR business fixture genesis differs");
        var addresses=new ChainAddressRepository(jdbc);var custody=MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager);
        UUID actor=jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);
        var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-mpc-probe","TENANT_ADMIN",Set.of("*"));
        var sourceView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("NEAR","near-business-source",0L,null,Map.of()),"API","127.0.0.1");
        var collectorView=custody.create(principal,new CustodyAddressService.CreateAddressCommand("NEAR","near-business-collector",0L,null,Map.of()),"API","127.0.0.1");
        var custodyRows=new CustodyAddressRepository(jdbc);var source=addresses.findOwned(tenant,custodyRows.require(tenant,sourceView.id()).chainAddressId()).orElseThrow();var collector=addresses.findOwned(tenant,custodyRows.require(tenant,collectorView.id()).chainAddressId()).orElseThrow();
        var tokens=new TokenConfigRepository(jdbc);var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);var transactions=new NearTransactionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);var transaction=new TransactionTemplate(manager);
        var runtime=new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc));var quorum=spy(nodes);doNothing().when(quorum).close();
        var loseReply=new AtomicBoolean();var broadcasts=new ArrayList<String>();NearSubmissionService.Operation[] current={null};
        doAnswer(call->{
            require(!TransactionSynchronizationManager.isActualTransactionActive(),"NEAR RPC broadcast held a business transaction");var op=Objects.requireNonNull(current[0]);
            String hash=op.kind()==NearSubmissionService.Kind.WITHDRAWAL?orders.findTxHash("NEAR",op.reference(),tenant).orElseThrow():collections.findTxHash(tenant,"NEAR",op.reference()).orElseThrow();
            var selected=json.readValue(transactions.find(hash).orElseThrow().getRawPayload(),MpcNearTransactionCodec.Signed.class);
            require(hash.equals(selected.txHash()) && selected.signedTransactionBase64().equals(call.getArgument(0)),"NEAR selected wire reached RPC before durable business hash/raw");
            require(requests.findBusiness(tenant,ChainType.NEAR,MpcSignCommand.BusinessType.valueOf(op.kind().name()),op.reference(),"transaction").orElseThrow().state()==MpcSignRequest.State.COMPLETED,"NEAR RPC preceded durable MPC result");
            broadcasts.add(hash);var answer=call.callRealMethod();Files.writeString(Path.of("/tmp/surprising-wallet-mpc-near-latest-submission-reply-public.json"),json.writeValueAsString(answer));if(loseReply.getAndSet(false))throw new IllegalStateException("deliberately lost NEAR broadcast reply");return answer;
        }).when(rpc).sendTransaction(anyString());
        try(var context=new AnnotationConfigApplicationContext();var depositContext=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper())) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);context.registerBean(NearTransactionRepository.class,()->transactions);context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
            context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);context.registerBean(ChainProfileRepository.class,()->profiles);context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(TokenConfigRepository.class,()->tokens);
            context.registerBean(ObjectMapper.class,()->json);context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(WithdrawalSettlementService.class);context.registerBean(NearSubmissionService.class);context.registerBean(NearRpcClient.class,()->rpc);context.registerBean(WalletRuntimeConfigService.class,()->runtime);context.registerBean(CustodyGasUsageRepository.class,()->new CustodyGasUsageRepository(jdbc));context.registerBean(CustodyGasAccountRepository.class,()->new CustodyGasAccountRepository(jdbc));context.registerBean(CustodyAddressRepository.class,()->custodyRows);context.registerBean(CustodyGasAccountingService.class,()->CustodyTestSupport.gasAccounting(jdbc));context.registerBean(NearRegistrationService.class);context.registerBean(NearTransactionService.class);context.refresh();
            var service=context.getBean(NearTransactionService.class);var submissions=context.getBean(NearSubmissionService.class);var heights=new ChainScanHeightRepository(jdbc);
            var scanner=new NearDepositScanner(realRpc,heights,transactions,depositContext.getBean(DepositService.class),addresses,profiles,tokens,new DepositRecordRepository(jdbc),runtime);
            String recipient="cd".repeat(32);long fundingStart=realRpc.latestFinalBlockHeight();
            fixtureCall(fixturePath,fixture,"fund",source.getAddress(),YOCTO.multiply(BigInteger.valueOf(100)).toString());fixtureCall(fixturePath,fixture,"fund",source.getAddress(),"1");fixtureCall(fixturePath,fixture,"fund",recipient,YOCTO.toString());
            for(String symbol:List.of("USDT","USDC")) {
                String contract=fixture.path(symbol).asText();
                jdbc.update("insert into token_config(chain,network,symbol,standard,contract_address,decimals,enabled,collect_enabled,gas_strategy) values ('NEAR','localnet',?,'NEP141',?,6,true,true,'NEAR_STORAGE_DEPOSIT') on conflict(chain,network,symbol) do update set contract_address=excluded.contract_address,decimals=6,enabled=true,collect_enabled=true",symbol,contract);
                new ChainAssetRepository(jdbc).upsertNonNative("NEAR",symbol,"NEP141",contract,6,true,BigDecimal.ZERO,BigDecimal.ZERO);
                for(String owner:List.of(source.getAddress(),collector.getAddress(),recipient))fixtureCall(fixturePath,fixture,"register",contract,owner);
                fixtureCall(fixturePath,fixture,"token-fund",contract,source.getAddress(),"20000000");
            }
            heights.upsert("NEAR","near-block-scanner",fundingStart-1,fundingStart-1);var depositEvents=scanner.scanAndCredit();
            var totals=new HashMap<String,BigDecimal>();for(String symbol:List.of("NEAR","USDT","USDC"))totals.put(symbol,balances.find(tenant,"NEAR",symbol,source.getAccountId()).orElseThrow(()->new AssertionError("NEAR actual funding deposit absent: "+symbol)).getTotalBalance());
            require(totals.get("NEAR").compareTo(new BigDecimal("100.000000000000000000000001"))==0 && totals.get("USDT").compareTo(new BigDecimal("20"))==0 && totals.get("USDC").compareTo(new BigDecimal("20"))==0,"NEAR actual funding did not credit exact native/token deposits including 1 yocto");
            long deposits=jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='NEAR'",Long.class,tenant);require(deposits==4,"NEAR mandatory tenant funding projection count differs");
            heights.upsert("NEAR","near-block-scanner",fundingStart-1,fundingStart-1);scanner.scanAndCredit();require(deposits==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='NEAR'",Long.class,tenant),"NEAR canonical replay duplicated tenant deposit projection");
            // A genuine transfer_call into an implicit account cannot run ft_on_transfer and fully refunds.
            var beforeCall=balance(realRpc,"USDC",fixture,source.getAddress(),json);var call=fixtureCall(fixturePath,fixture,"token-call",fixture.path("USDC").asText(),source.getAddress(),"1000000");
            String callHash=call.path("transaction").path("hash").asText();require(!callHash.isBlank(),"NEAR fixture transfer_call hash is absent");var refundedCall=realRpc.finalTransaction(callHash,"admin.near");
            require(beforeCall.equals(balance(realRpc,"USDC",fixture,source.getAddress(),json)),"NEAR transfer_call did not actually refund the implicit recipient");scanner.scanAndCredit();
            require(deposits==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='NEAR'",Long.class,tenant),"NEAR transfer_call arguments were credited despite the completed refund");
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-near-actual-transfer-call-refund.json"),json.writeValueAsString(refundedCall));
            Files.writeString(Path.of("/tmp/surprising-wallet-mpc-near-business-deposits.json"),json.writeValueAsString(Map.of("source",source.getAddress(),"events",depositEvents,"transferCallRefundHash",callHash)));
            BigInteger beforeControlled=nativeBalance(realRpc,source.getAddress()).add(nativeBalance(realRpc,collector.getAddress())),fees=BigInteger.ZERO,externalNative=BigInteger.ZERO;int round=0;var hashes=new HashSet<String>();
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for(String symbol:List.of("NEAR","USDT","USDC")) {
                    BigDecimal amount="NEAR".equals(symbol) && round==2?new BigDecimal("0.000000000000000000000001"):BigDecimal.ONE;
                    String reference="near-business-"+UUID.randomUUID();var op=new NearSubmissionService.Operation(tenant,reference,NearSubmissionService.Kind.WITHDRAWAL);current[0]=op;
                    createOrder(transaction,orders,balances,tenant,source,reference,symbol,recipient,amount,new BigDecimal("0.1"));var before=balance(realRpc,symbol,fixture,recipient,json);int count=broadcasts.size();loseReply.set(true);
                    try{service.send(op,source);throw new AssertionError("NEAR fixture did not lose an actual accepted response");}catch(IllegalStateException expected){require(broadcasts.size()==count+1 && !loseReply.get(),"NEAR failed before deliberately losing a real accepted response: "+expected);submissions.unknown(op,"fixture lost response");}
                    String hash=orders.findTxHash("NEAR",reference,tenant).orElseThrow();require(hashes.add(hash),"NEAR owned operations reused a selected transaction");require(realRpc.finalTransaction(hash,source.getAddress()).path("status").has("SuccessValue"),"NEAR formal MPC payout failed");String raw=transactions.find(hash).orElseThrow().getRawPayload();
                    jdbc.update("update chain_profile set withdraw_enabled=false,collection_enabled=false where chain='NEAR' and enabled");jdbc.update("update token_config set enabled=false where chain='NEAR'");
                    require(service.recover(op) && broadcasts.size()==count+1,"NEAR known wire was replaced or rebroadcast during disabled-gate recovery");
                    require(service.confirmWithdrawal(tenant,reference) && !service.confirmWithdrawal(tenant,reference),"NEAR frozen principal did not settle exactly once");require(raw.equals(transactions.find(hash).orElseThrow().getRawPayload()),"NEAR recovery replaced durable raw");
                    BigInteger principalAtomic=amount.movePointRight(symbol.equals("NEAR")?24:6).toBigIntegerExact();require(balance(realRpc,symbol,fixture,recipient,json).subtract(before).equals(principalAtomic),"NEAR receiver delta differs from exact selected principal");
                    fees=fees.add(transactions.find(hash).orElseThrow().getFeeYocto());if(symbol.equals("NEAR"))externalNative=externalNative.add(principalAtomic);
                    totals.put(symbol,totals.get(symbol).subtract(amount.add(new BigDecimal("0.1"))));var book=balances.find(tenant,"NEAR",symbol,source.getAccountId()).orElseThrow();require(book.getTotalBalance().compareTo(totals.get(symbol))==0 && book.getLockedBalance().signum()==0,"NEAR frozen principal or customer fee differs");
                    jdbc.update("update chain_profile set withdraw_enabled=true,collection_enabled=true where chain='NEAR' and enabled");jdbc.update("update token_config set enabled=true where chain='NEAR' and network='localnet'");
                }
                round++;
            }
            for(String symbol:List.of("NEAR","USDT","USDC")) {
                String reference="near-collection-"+UUID.randomUUID();collections.create(tenant,sourceView.id(),reference,"NEAR",symbol,source.getAddress(),collector.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null);current[0]=new NearSubmissionService.Operation(tenant,reference,NearSubmissionService.Kind.COLLECTION);
                var before=balance(realRpc,symbol,fixture,collector.getAddress(),json);String hash=service.collect(tenant,reference,source);realRpc.finalTransaction(hash,source.getAddress());
                require(service.confirmCollection(tenant,reference) && !service.confirmCollection(tenant,reference),"NEAR collection did not settle once");require(balance(realRpc,symbol,fixture,collector.getAddress(),json).subtract(before).equals(BigInteger.TEN.pow(symbol.equals("NEAR")?24:6)),"NEAR actual collection principal differs");fees=fees.add(transactions.find(hash).orElseThrow().getFeeYocto());
            }
            for(boolean token:List.of(false,true)) {
                String symbol=token?"USDC":"NEAR",reference="near-failed-"+UUID.randomUUID(),receiver=token?recipient:"missing-"+UUID.randomUUID().toString().replace("-","")+".near";var op=new NearSubmissionService.Operation(tenant,reference,NearSubmissionService.Kind.WITHDRAWAL);current[0]=op;
                createOrder(transaction,orders,balances,tenant,source,reference,symbol,receiver,BigDecimal.ONE,BigDecimal.ZERO);var before=token?balance(realRpc,symbol,fixture,receiver,json):BigInteger.ZERO;
                if(token) {
                    String publicKey=submissions.publicKey(source);var access=realRpc.accessKey(source.getAddress(),Base58.encode(HexFormat.of().parseHex(publicKey)));long height=access.path("block_height").asLong(),validity=realRpc.genesisConfig().path("transaction_validity_period").asLong();
                    var u=new MpcNearTransactionCodec.Unsigned("localnet",fixture.path("genesisHash").asText(),source.getAddress(),access.path("nonce").asLong()+1,receiver,BigInteger.valueOf(1_000_000),fixture.path("USDC").asText(),1_000_000_000_000L,reference,height,height+validity,access.path("block_hash").asText(),publicKey);
                    submissions.reserve(op,source,new NearSigningPayload(symbol,BigDecimal.ONE,BigDecimal.ZERO,6,u));require(service.recover(op),"NEAR low-gas original intent did not recover");
                } else {String hash=service.send(op,source);submissions.restoreSent(op,hash);}
                String hash=orders.findTxHash("NEAR",reference,tenant).orElseThrow();var receipt=realRpc.finalTransaction(hash,source.getAddress());require(receipt.path("status").has("Failure"),"NEAR failure fixture paid principal");
                require(service.confirmWithdrawal(tenant,reference) && !service.confirmWithdrawal(tenant,reference) && "FAILED".equals(orders.find("NEAR",reference,tenant).orElseThrow().getStatus()),"NEAR committed failure did not refund exactly once");
                require(token?before.equals(balance(realRpc,symbol,fixture,receiver,json)):!realRpc.accountExists(receiver),"NEAR failed execution changed recipient principal");
                var book=balances.find(tenant,"NEAR",symbol,source.getAccountId()).orElseThrow();require(book.getTotalBalance().compareTo(totals.get(symbol))==0 && book.getLockedBalance().signum()==0,"NEAR failed execution refund changed exact customer principal");fees=fees.add(transactions.find(hash).orElseThrow().getFeeYocto());
            }
            require(beforeControlled.subtract(nativeBalance(realRpc,source.getAddress())).subtract(nativeBalance(realRpc,collector.getAddress())).subtract(externalNative).equals(fees),"NEAR controlled native balances do not reconcile to external principal and exact actual fees including retained one yocto");
            scanner.scanAndCredit();require(deposits==jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='NEAR'",Long.class,tenant),"NEAR internal withdrawals or collections were credited as external deposits");
        }
    }
    private static BigInteger nativeBalance(NearRpcClient rpc,String owner){return rpc.accountExists(owner)?rpc.accountBalanceYocto(owner):BigInteger.ZERO;}
    private static BigInteger balance(NearRpcClient rpc,String symbol,JsonNode fixture,String owner,ObjectMapper json){return symbol.equals("NEAR")?nativeBalance(rpc,owner):new BigInteger(rpc.viewFunctionJson(fixture.path(symbol).asText(),"ft_balance_of",json.writeValueAsBytes(Map.of("account_id",owner))).asText());}
    private static JsonNode fixtureCall(Path path,JsonNode fixture,String... arguments)throws Exception {
        var command=new ArrayList<String>(List.of("node",Path.of("resources/infra/mpc-signer/tests/near_fixture.cjs").toRealPath().toString(),path.toString()));command.addAll(Arrays.asList(arguments));
        var log=Files.createTempFile("surprising-wallet-mpc-near-business-admin-",".log");var builder=new ProcessBuilder(command);builder.environment().put("NODE_PATH",fixture.path("nodeModules").asText());var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("NEAR fixture action timed out; see "+log);}require(process.exitValue()==0,"NEAR fixture action failed; see "+log);return new ObjectMapper().readTree(Files.readAllBytes(log));
    }
    private static void createOrder(TransactionTemplate tx,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,UUID tenant,ChainAddressRecord source,String reference,String symbol,String recipient,BigDecimal amount,BigDecimal fee){tx.executeWithoutResult(status->{orders.createForTenant(tenant,reference,source.getUserId(),"NEAR",symbol,source.getAddress(),source.getAccountId(),recipient,amount,fee);require(balances.freeze("NEAR",symbol,source.getAccountId(),amount.add(fee),tenant),"NEAR principal could not freeze");orders.updateStatus(tenant,"NEAR",reference,"FROZEN",source.getAddress(),null,null);require(orders.claimSigningDurable(tenant,"NEAR",reference,source.getAddress())==1,"NEAR order could not claim signing");});}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
