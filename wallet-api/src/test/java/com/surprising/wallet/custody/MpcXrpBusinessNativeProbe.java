package com.surprising.wallet.custody;

import com.google.common.primitives.UnsignedInteger;
import com.surprising.wallet.chain.xrp.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.xrpl.xrpl4j.crypto.keys.*;
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Three real cb-mpc nodes and official standalone xrpld. Genesis/issuer keys are fixture-only. */
public final class MpcXrpBusinessNativeProbe {
    private MpcXrpBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,
                              MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var json=new ObjectMapper();var endpoint=URI.create(config.path("xrpRpcUrl").asText());
        require(endpoint.getScheme().equals("http") && endpoint.getHost().equals("127.0.0.1"),"XRP test requires isolated loopback xrpld");
        var profiles=new ChainProfileRepository(jdbc);var profile=profiles.findAccountByChain("XRP").orElseThrow();
        profile.setWithdrawConfirmations(1);jdbc.update("update chain_profile set withdraw_confirmations=1,withdraw_enabled=true,collection_enabled=true,transfer_enabled=true where chain='XRP' and enabled");
        jdbc.update("update chain_rpc_node set enabled=false where chain='XRP'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url) values ('XRP',?,'mpc-test','mpc-xrpld','rpc',?)",profile.getNetwork(),endpoint.toString());
        var rpcNodes=new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2);
        var realRpc=new XrpRpcClient(json,profiles,rpcNodes);var rpc=spy(realRpc);
        long sourceId=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='XRP' and subject='native-customer'",Long.class,tenant);
        var addresses=new ChainAddressRepository(jdbc);var source=addresses.findOwned(tenant,sourceId).orElseThrow();
        var custodyAddresses=MpcSigningNativeProbe.addresses(jdbc,signing,nodes,(DataSourceTransactionManager)manager);
        var gas=CustodyTestSupport.gas(jdbc,custodyAddresses);
        UUID actor=jdbc.queryForObject("select id from custody_tenant_user where tenant_id=? limit 1",UUID.class,tenant);
        var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-mpc-probe","TENANT_ADMIN",Set.of("*"));
        var gasView=gas.create(principal,new CustodyGasService.CreateGasAccountCommand("XRP"),"127.0.0.1");
        var destinationView=custodyAddresses.create(principal,new CustodyAddressService.CreateAddressCommand("XRP","xrp-native-recipient",0L,null,Map.of()),"API","127.0.0.1");
        long destinationId=new CustodyAddressRepository(jdbc).require(tenant,destinationView.id()).chainAddressId();
        var destination=addresses.findOwned(tenant,destinationId).orElseThrow();
        var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);
        var transactions=new XrpTransactionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);
        var loseReply=new AtomicBoolean();var sends=new ArrayList<String>();XrpSubmissionService.Operation[] current={null};
        var quorum=spy(nodes);doNothing().when(quorum).close();
        try(var web=HttpClient.newHttpClient();var context=new AnnotationConfigApplicationContext()) {
            // Official standalone genesis passphrase is a public development fixture, never a wallet signing key.
            var genesis=Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(call(web,json,endpoint,"wallet_propose",Map.of("passphrase","masterpassphrase","key_type","secp256k1")).path("master_seed").asText())).deriveKeyPair();
            var signatures=new BcSignatureService();
            var issuer=genesis.publicKey().deriveAddress();
            var defaultRipple=AccountSet.builder().account(issuer).signingPublicKey(genesis.publicKey()).setFlag(AccountSet.AccountSetFlag.DEFAULT_RIPPLE)
                    .fee(XrpCurrencyAmount.ofDrops(12)).sequence(UnsignedInteger.valueOf(realRpc.accountSequence(issuer.value())))
                    .lastLedgerSequence(UnsignedInteger.valueOf(realRpc.latestLedgerIndex()+20)).build();
            // Repeated native probes use a fresh ledger process or a monotonically advancing genesis sequence.
            fixtureSubmit(realRpc,signatures.sign(genesis.privateKey(),defaultRipple).signedTransactionBytes().hexValue(),web,json,endpoint);
            fixturePayment(realRpc,genesis,gasView.address(),XrpCurrencyAmount.ofDrops(500_000_000),web,json,endpoint);
            var gasAccount=gas.findGasAccount(tenant,"XRP").orElseThrow();
            jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'XRP','XRP',?,500,500) on conflict(chain,asset_symbol,account_id) do update set available_balance=ledger_balance.available_balance+500,total_balance=ledger_balance.total_balance+500",tenant,gasAccount.accountId());
            for(String symbol:List.of("USDT","USDC"))jdbc.update("""
                    insert into token_config(chain,network,symbol,standard,contract_address,decimals,enabled)
                    values ('XRP',?,?,'IOU',?,6,true)
                    on conflict(chain,network,symbol) do update set contract_address=excluded.contract_address,decimals=6,enabled=true
                    """,profile.getNetwork(),symbol,issuer.value()+":"+(symbol.equals("USDT")?"5553445400000000000000000000000000000000":"5553444300000000000000000000000000000000"));
            doAnswer(invocation->{
                require(!TransactionSynchronizationManager.isActualTransactionActive(),"XRPL network submit held a business transaction");
                String raw=invocation.getArgument(0);var operation=current[0];
                if(operation!=null) {
                    var request=requests.findBusiness(tenant,ChainType.XRP,MpcSignCommand.BusinessType.valueOf(operation.kind().name()),operation.reference(),"transaction").orElseThrow();
                    require(request.state()==MpcSignRequest.State.COMPLETED,"XRPL RPC preceded durable MPC result");
                    String hash=operation.kind()==XrpSubmissionService.Kind.WITHDRAWAL?orders.find("XRP",operation.reference(),tenant).orElseThrow().getTxHash()
                            :collections.findTxHash(tenant,"XRP",operation.reference()).orElseThrow();
                    require(raw.equals(transactions.findSigned(hash).orElseThrow().getRawPayload()),"XRPL RPC preceded raw/hash commit");
                }
                sends.add(raw);String result;
                try {result=(String)invocation.callRealMethod();}
                finally {call(web,json,endpoint,"ledger_accept",Map.of());}
                if(loseReply.getAndSet(false))throw new IllegalStateException("deliberately lost xrpld reply");return result;
            }).when(rpc).submit(anyString());
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);
            context.registerBean(XrpTransactionRepository.class,()->transactions);context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
            context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(ChainProfileRepository.class,()->profiles);
            context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));
            context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);context.registerBean(ObjectMapper.class,()->json);
            context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(WithdrawalSettlementService.class);context.registerBean(CustodyGasAccountingService.class,()->com.surprising.wallet.testutil.CustodyTestSupport.gasAccounting(jdbc));context.registerBean(XrpSubmissionService.class);
            context.registerBean(XrpRpcClient.class,()->rpc);context.registerBean(CustodyGasService.class,()->gas);
            context.registerBean(WalletTaskLeaseRepository.class,()->new WalletTaskLeaseRepository(jdbc));context.registerBean(WalletTaskLeaseService.class);
            context.registerBean(WalletRuntimeConfigService.class,()->RuntimeTestSupport.database(jdbc));context.registerBean(XrpTransactionService.class);context.refresh();
            var service=context.getBean(XrpTransactionService.class);var submissions=context.getBean(XrpSubmissionService.class);
            var tokens=new TokenConfigRepository(jdbc);
            for(var target:List.of(source,destination))for(String symbol:List.of("USDT","USDC")) {
                var preparation=service.ensureIssuedCurrencyDepositReady(target,symbol);
                require(preparation.activated() && preparation.trustLineReady(),"actual MPC XRP activation/TrustSet failed");
                require(service.ensureIssuedCurrencyDepositReady(target,symbol).trustLineReady(),"XRP preparation retry lost its durable step");
                fixturePayment(realRpc,genesis,target.getAddress(),XrpIssuedCurrency.fromToken(tokens.findDefinition("XRP",profile.getNetwork(),symbol).orElseThrow()).amount(new BigDecimal("100")),web,json,endpoint);
            }
            fixturePayment(realRpc,genesis,source.getAddress(),XrpCurrencyAmount.ofDrops(1_000_000),web,json,endpoint);
            var transaction=new TransactionTemplate(manager);
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                for(String symbol:List.of("XRP","USDT","USDC")) {
                    String reference="native-xrp-"+UUID.randomUUID(),account=reference+"-principal";
                    current[0]=new XrpSubmissionService.Operation(tenant,reference,XrpSubmissionService.Kind.WITHDRAWAL);
                    transaction.executeWithoutResult(status->{
                        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'XRP',?,?,5,5)",tenant,symbol,account);
                        orders.createForTenant(tenant,reference,42,"XRP",symbol,source.getAddress(),account,destination.getAddress(),new BigDecimal("0.01"),new BigDecimal("0.001"));
                        require(balances.freeze("XRP",symbol,account,new BigDecimal("0.011"),tenant),"XRP principal freeze failed");
                        orders.updateStatus(tenant,"XRP",reference,"FROZEN",source.getAddress(),null,null);
                        require(orders.claimSigningDurable(tenant,"XRP",reference,source.getAddress())==1,"XRP order could not be claimed");
                    });
                    BigDecimal before=balance(realRpc,destination.getAddress(),symbol,tokens,profile);int count=sends.size();loseReply.set(true);
                    try {
                        if(symbol.equals("XRP"))service.sendNative(current[0],source,destination.getAddress(),new BigDecimal("0.01"));
                        else service.sendIssuedCurrency(current[0],source,tokens.findDefinition("XRP",profile.getNetwork(),symbol).orElseThrow(),destination.getAddress(),new BigDecimal("0.01"));
                        throw new AssertionError("XRP probe did not lose its submitted reply");
                    } catch(IllegalStateException lost) {require(sends.size()>count,"XRP failed before deliberate lost reply: "+lost);}
                    orders.markBroadcastUnknown(tenant,"XRP",reference,source.getAddress(),"lost xrpld reply");require(service.recover(current[0]),"XRP accepted transaction did not recover");
                    String hash=orders.find("XRP",reference,tenant).orElseThrow().getTxHash();String raw=transactions.findSigned(hash).orElseThrow().getRawPayload();
                    for(int i=count;i<sends.size();i++)require(raw.equals(sends.get(i)),"XRP recovery changed selected raw");
                    require(service.confirmWithdrawal(tenant,profile,reference),"XRP actual validated payment did not settle");service.confirmWithdrawal(tenant,profile,reference);
                    require(balance(realRpc,destination.getAddress(),symbol,tokens,profile).subtract(before).compareTo(new BigDecimal("0.01"))==0,"XRP recipient credited wrong amount or twice");
                    var ledger=balances.find(tenant,"XRP",symbol,account).orElseThrow();
                    require(ledger.getLockedBalance().signum()==0 && ledger.getTotalBalance().compareTo(new BigDecimal("4.989"))==0,"XRP principal settlement was not exact/once");
                }
            }
            current[0]=null;
            var gasSource=addresses.findEnabledByTenantAndAddress(tenant,"XRP",gasView.address()).orElseThrow();
            require(service.ensureIssuedCurrencyDepositReady(gasSource,"USDT").trustLineReady(),"XRP collector TrustSet failed");
            String collectionReference="native-xrp-collection-"+UUID.randomUUID();
            current[0]=new XrpSubmissionService.Operation(tenant,collectionReference,XrpSubmissionService.Kind.COLLECTION);
            UUID custody=jdbc.queryForObject("select id from custody_address where tenant_id=? and chain_address_id=?",UUID.class,tenant,source.getId());
            require(collections.create(tenant,custody,collectionReference,"XRP","USDT",source.getAddress(),gasView.address(),new BigDecimal("0.01"),BigDecimal.ZERO,null)==1,"XRP collection was not reserved");
            BigDecimal collectedBefore=balance(realRpc,gasView.address(),"USDT",tokens,profile);
            service.collectIssuedCurrency(tenant,collectionReference,source,tokens.findDefinition("XRP",profile.getNetwork(),"USDT").orElseThrow(),gasView.address(),new BigDecimal("0.01"));
            require(service.confirmCollection(tenant,profile,collectionReference),"actual MPC XRP collection did not settle");service.confirmCollection(tenant,profile,collectionReference);
            require(balance(realRpc,gasView.address(),"USDT",tokens,profile).subtract(collectedBefore).compareTo(new BigDecimal("0.01"))==0,"XRP collection delivered a different amount");
            // A valid Payment below the activation reserve to a fresh recipient produces a real validated tec failure.
            String failedReference="native-xrp-failed-"+UUID.randomUUID(),failedAccount=failedReference+"-principal";
            String unavailable=PublicKey.fromBase16EncodedPublicKey(HexFormat.of().formatHex(new org.bitcoinj.crypto.ECKey().getPubKey())).deriveAddress().value();
            current[0]=new XrpSubmissionService.Operation(tenant,failedReference,XrpSubmissionService.Kind.WITHDRAWAL);
            transaction.executeWithoutResult(status->{
                jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'XRP','XRP',?,5,5)",tenant,failedAccount);
                orders.createForTenant(tenant,failedReference,42,"XRP","XRP",source.getAddress(),failedAccount,unavailable,new BigDecimal("0.01"),new BigDecimal("0.001"));
                require(balances.freeze("XRP","XRP",failedAccount,new BigDecimal("0.011"),tenant),"XRP failed fixture freeze failed");
                orders.updateStatus(tenant,"XRP",failedReference,"FROZEN",source.getAddress(),null,null);
                require(orders.claimSigningDurable(tenant,"XRP",failedReference,source.getAddress())==1,"XRP failed fixture claim failed");
            });
            try {service.sendNative(current[0],source,unavailable,new BigDecimal("0.01"));throw new AssertionError("xrpld did not produce expected tec execution failure");}
            catch(IllegalStateException expected) { }
            orders.markBroadcastUnknown(tenant,"XRP",failedReference,source.getAddress(),"observed provisional tec result");
            require(service.recover(current[0]),"XRP failed receipt was not recovered by selected hash");
            require(service.confirmWithdrawal(tenant,profile,failedReference),"XRP validated failure was not settled");service.confirmWithdrawal(tenant,profile,failedReference);
            require("FAILED".equals(orders.find("XRP",failedReference,tenant).orElseThrow().getStatus()),"XRP actual failed payment was marked paid");
            require(balances.find(tenant,"XRP","XRP",failedAccount).orElseThrow().getTotalBalance().compareTo(new BigDecimal("5"))==0,"XRP failed principal was not refunded once");
            require(realRpc.accountInfo(unavailable).isEmpty(),"failed XRP payment activated its recipient");
            String failedHash=orders.find("XRP",failedReference,tenant).orElseThrow().getTxHash();
            require(transactions.findConfirmedFeeAtomic("XRP",failedHash).orElseThrow().signum()>0,"XRP failed transaction lost actual fee");
            require(gas.findGasAccount(tenant,"XRP").orElseThrow().availableBalance().compareTo(realRpc.accountBalanceDrops(gasView.address()).movePointLeft(6))==0,
                    "XRP Gas ledger and actual activation/TrustSet spending diverged");
            current[0]=null;
            genesis.privateKey().destroy();
        }
    }
    private static BigDecimal balance(XrpRpcClient rpc,String address,String symbol,TokenConfigRepository tokens,AccountChainProfile profile) {
        if(symbol.equals("XRP"))return rpc.accountBalanceDrops(address).movePointLeft(6);
        var issued=XrpIssuedCurrency.fromToken(tokens.findDefinition("XRP",profile.getNetwork(),symbol).orElseThrow());
        for(var line:rpc.accountLines(address,issued.issuer()))if(issued.currencyCode().equalsIgnoreCase(line.path("currency").asText()))return new BigDecimal(line.path("balance").asText());
        throw new AssertionError("XRP fixture has no expected trust line");
    }
    private static void fixturePayment(XrpRpcClient rpc,KeyPair genesis,String destination,CurrencyAmount amount,HttpClient web,ObjectMapper json,URI endpoint) throws Exception {
        var payment=Payment.builder().account(genesis.publicKey().deriveAddress()).destination(Address.of(destination)).amount(amount).signingPublicKey(genesis.publicKey())
                .fee(XrpCurrencyAmount.ofDrops(12)).sequence(UnsignedInteger.valueOf(rpc.accountSequence(genesis.publicKey().deriveAddress().value())))
                .lastLedgerSequence(UnsignedInteger.valueOf(rpc.latestLedgerIndex()+20)).build();
        fixtureSubmit(rpc,new BcSignatureService().sign(genesis.privateKey(),payment).signedTransactionBytes().hexValue(),web,json,endpoint);
    }
    private static void fixtureSubmit(XrpRpcClient rpc,String raw,HttpClient web,ObjectMapper json,URI endpoint) throws Exception {
        rpc.submit(raw);call(web,json,endpoint,"ledger_accept",Map.of());
    }
    private static JsonNode call(HttpClient web,ObjectMapper json,URI endpoint,String method,Map<String,?> parameters) throws Exception {
        var body=json.writeValueAsBytes(Map.of("method",method,"params",List.of(parameters)));
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response=web.send(request,HttpResponse.BodyHandlers.ofByteArray());
        var result=json.readTree(response.body()).path("result");
        require(response.statusCode()==200 && !result.hasNonNull("error"),"XRP fixture RPC failed: "+method);return result;
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
