package com.surprising.wallet.custody;

import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.repository.UtxoRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.service.UtxoMpcSigningService;
import com.surprising.wallet.service.UtxoSigningSubmissionService;
import com.surprising.wallet.service.UtxoBroadcastService;
import com.surprising.wallet.service.WithdrawalQueueService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.service.WalletTaskLeaseService;
import com.surprising.wallet.service.QueuedUtxoWithdrawalService;
import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec;
import com.surprising.wallet.service.MpcSigningService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual native MPC and Bitcoin Core consensus, followed by the formal signing queue and principal settlement. */
final class MpcUtxoNativeProbe {
    private final ObjectMapper json=new ObjectMapper();
    private final String chain;
    private final BigDecimal funding;
    private final BigDecimal payment;
    private final BigDecimal customerFee;
    private final long rate;
    private long dust;
    private final URI endpoint;
    private final String authentication;
    private final HttpClient http;

    private MpcUtxoNativeProbe(JsonNode config,HttpClient http,String chain) {
        this.chain=chain;
        funding=new BigDecimal(chain.equals("DOGE") ? "1000" : "0.02");
        payment=new BigDecimal(chain.equals("DOGE") ? "100" : "0.005");
        customerFee=new BigDecimal(chain.equals("DOGE") ? "1" : "0.001");
        rate=chain.equals("DOGE") ? 1000 : 2;
        String prefix=chain.toLowerCase(java.util.Locale.ROOT);
        URI root=URI.create(config.path(prefix+"RpcUrl").asText());
        require("http".equals(root.getScheme()) && "127.0.0.1".equals(root.getHost()) && root.getUserInfo()==null
                && root.getQuery()==null && (root.getPath().isEmpty() || root.getPath().equals("/")),chain+" probe requires its isolated loopback RPC");
        String wallet=config.path(prefix+"Wallet").asText();
        require(wallet.matches("[A-Za-z0-9_-]+"),"isolated UTXO funder wallet is required");
        endpoint=chain.equals("DOGE") ? root : root.resolve("/wallet/"+wallet);
        authentication="Basic "+Base64.getEncoder().encodeToString((config.path(prefix+"RpcUser").asText()+":"
                +config.path(prefix+"RpcPassword").asText()).getBytes(StandardCharsets.UTF_8));
        this.http=http;
    }
    static void verify(JdbcTemplate jdbc,TransactionTemplate transaction,MpcSigningService signing,MpcNodes nodes,
                       UUID tenant,JsonNode config) throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            for(String chain:List.of("BTC","LTC","DOGE","BCH")) if(config.hasNonNull(chain.toLowerCase(java.util.Locale.ROOT)+"RpcUrl")) {
                new MpcUtxoNativeProbe(config,http,chain).execute(jdbc,transaction,signing,nodes,tenant);
            }
        }
    }
    private void execute(JdbcTemplate jdbc,TransactionTemplate transaction,MpcSigningService signing,MpcNodes nodes,UUID tenant) throws Exception {
        require("regtest".equals(rpc("getblockchaininfo",List.of()).path("chain").asText()),chain+" native probe refuses non-regtest funds");
        dust=new ChainProfileRepository(jdbc).findAccountByChain(chain).orElseThrow().getDustThreshold();
        long id=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain=? and subject='native-customer'",Long.class,tenant,chain);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,id).orElseThrow();
        var key=new WalletMpcKeyRepository(jdbc).find(tenant,source.getMpcKeyId()).orElseThrow();
        var parties=nodes.parties();
        for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
            String funded=rpc("sendtoaddress",List.of(source.getAddress(),funding)).asText();
            var original=rpc("getrawtransaction",List.of(funded,true));
            JsonNode output=null;
            for(var candidate:original.path("vout")) if(source.getAddress().equals(address(candidate))) output=candidate;
            require(output!=null,"funder did not create the joint-key UTXO");
            String destination=rpc("getnewaddress",List.of()).asText();
            rpc("generatetoaddress",List.of(1,destination));
            long value=output.path("value").decimalValue().movePointRight(8).longValueExact();
            var unsigned=MpcUtxoTransactionCodec.prepare(chain,BitcoinLikeNetwork.REGTEST,
                    List.of(new MpcUtxoTransactionCodec.Input(funded,output.path("n").asInt(),value,key.publicKeyHex())),
                    List.of(new MpcUtxoTransactionCodec.Payment(destination,payment.movePointRight(8).longValueExact())),source.getAddress(),rate,dust);
            String payload=HexFormat.of().formatHex(json.writeValueAsBytes(unsigned));
            var command=new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.valueOf(chain),MpcSignCommand.BusinessType.WITHDRAWAL,
                    "codec-native-"+chain+"-"+UUID.randomUUID(),"input/0",MpcSignCommand.Purpose.TRANSACTION,"UTXO_UNSIGNED_JSON",payload,
                    HexFormat.of().formatHex(MpcUtxoTransactionCodec.digest(unsigned,0)));
            var online=pair.stream().map(parties::get).toList();
            var request=transaction.execute(status->signing.reserveSignature(command,online));
            byte[] signature=signing.awaitSignature(tenant,request.id(),nodes);
            var restored=json.readValue(HexFormat.of().parseHex(request.command().payloadHex()),MpcUtxoTransactionCodec.Unsigned.class);
            var signed=MpcUtxoTransactionCodec.assemble(restored,List.of(signature));
            require(new WalletMpcSignRequestRepository(jdbc).find(tenant,request.id()).orElseThrow().state()
                    ==MpcSignRequest.State.COMPLETED,"usable BTC signature was not durably committed");
            require(chain.equals("DOGE") || rpc("testmempoolaccept",List.of(List.of(signed.transactionHex()))).get(0).path("allowed").asBoolean(),
                    "BTC Core rejected MPC witness, script ownership or fee");
            require(signed.txId().equals(rpc("sendrawtransaction",List.of(signed.transactionHex())).asText()),chain+" broadcast hash differs from signed bytes");
            rpc("generatetoaddress",List.of(1,destination));
            var received=rpc("gettxout",List.of(signed.txId(),0,true));
            require(received.path("confirmations").asInt()>=1 && destination.equals(address(received))
                    && received.path("value").decimalValue().compareTo(payment)==0,chain+" recipient or amount differs from accepted intent");
            var change=rpc("gettxout",List.of(signed.txId(),1,true));
            require(source.getAddress().equals(address(change))
                    && change.path("value").decimalValue().movePointRight(8).longValueExact()==value-payment.movePointRight(8).longValueExact()-signed.feeAtomic(),chain+" change or fee differs");
            require(rpc("gettxout",List.of(funded,output.path("n").asInt(),true)).isNull(),"funding input was not spent");
            try {rpc("sendrawtransaction",List.of(signed.transactionHex()));}
            catch(IllegalStateException alreadyKnown) {
                require(!rpc("gettxout",List.of(signed.txId(),0,true)).isNull(),"duplicate broadcast lost its original output");
            }
            require(request.id().equals(transaction.execute(status->signing.reserveSignature(command,online)).id()),chain+" retry changed the fixed signing intent");
            require(signed.equals(MpcUtxoTransactionCodec.assemble(restored,List.of(signing.signature(tenant,request.id()).orElseThrow()))),
                    "BTC replay produced a different transaction");
        }
        business(jdbc, transaction, signing, nodes, tenant, source);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions { }

    private void business(JdbcTemplate jdbc, TransactionTemplate transaction, MpcSigningService signing,
                          MpcNodes nodes, UUID tenant, ChainAddressRecord source) throws Exception {
        var manager=transaction.getTransactionManager();
        var parents=new ChainSigningTransactionRepository(jdbc,json);
        var orders=new WithdrawalOrderRepository(jdbc);
        var utxos=new UtxoRepository(jdbc);
        var balances=new LedgerBalanceRepository(jdbc);
        var queue=new PgmqClient(jdbc);
        var worker=new QueueWorker(queue,manager);
        // Only quorum selection and the chain transport bridge are test collaborators. Every signature is native MPC.
        var quorum=org.mockito.Mockito.spy(nodes);
        org.mockito.Mockito.doNothing().when(quorum).close();
        var runtime=org.mockito.Mockito.mock(BlockchainRuntimeService.class);
        var leases=org.mockito.Mockito.mock(WalletTaskLeaseService.class);
        org.mockito.Mockito.when(leases.ownerId()).thenReturn("native-utxo-business");
        var observed=new java.util.ArrayList<String>();
        var loseReply=new java.util.concurrent.atomic.AtomicBoolean();
        int[] currentId={0};
        org.mockito.Mockito.when(runtime.broadcastSignedTransaction(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call->{
                    require(!TransactionSynchronizationManager.isActualTransactionActive(),chain+" RPC held a business transaction");
                    UtxoSignedTransaction signed=call.getArgument(1);
                    var persisted=parents.findById(chain,currentId[0]).orElseThrow();
                    require(signed.equals(persisted.signed()),chain+" RPC preceded durable selected raw");
                    var payment=persisted.request().payments().getFirst();
                    String bound=persisted.request().operation()==UtxoSigningRequest.Operation.COLLECTION
                            ? new CollectionRecordRepository(jdbc).findTxHash(tenant,chain,persisted.request().collectionNo()).orElseThrow()
                            : orders.find(chain,payment.reference(),tenant).orElseThrow().getTxHash();
                    require(signed.txId().equals(bound),chain+" RPC preceded business hash commit");
                    require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where tenant_id=? and business_reference=? and state='COMPLETED'",Long.class,
                            tenant,persisted.request().requestId().toString())==persisted.request().inputs().size(),chain+" RPC preceded durable MPC results");
                    observed.add(signed.transactionHex());
                    try {require(signed.txId().equals(rpc("sendrawtransaction",List.of(signed.transactionHex())).asText()),chain+" node returned another hash");}
                    catch(IllegalStateException known) {require(!rpc("gettxout",List.of(signed.txId(),0,true)).isNull(),chain+" replay did not find original output");}
                    return loseReply.getAndSet(false) ? "" : signed.txId();
                });
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(ChainSigningTransactionRepository.class,()->parents);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);
            context.registerBean(UtxoRepository.class,()->utxos);
            context.registerBean(LedgerBalanceRepository.class,()->balances);
            context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
            context.registerBean(com.surprising.wallet.repository.ChainAddressRepository.class,()->new com.surprising.wallet.repository.ChainAddressRepository(jdbc));
            context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
            context.registerBean(WalletMpcSignRequestRepository.class,()->new WalletMpcSignRequestRepository(jdbc));
            context.registerBean(MpcSigningService.class,()->signing);
            context.registerBean(MpcNodes.class,()->quorum,definition->definition.setDestroyMethodName(""));
            context.registerBean(PgmqClient.class,()->queue);
            context.registerBean(QueueWorker.class,()->worker);
            context.registerBean(ObjectMapper.class,()->json);
            context.registerBean(BlockchainRuntimeService.class,()->runtime);
            context.registerBean(WalletTaskLeaseService.class,()->leases);
            context.registerBean(WalletRuntimeConfigService.class,()->org.mockito.Mockito.mock(WalletRuntimeConfigService.class));
            context.registerBean(QueuedUtxoWithdrawalService.class,()->org.mockito.Mockito.mock(QueuedUtxoWithdrawalService.class));
            context.registerBean(UtxoMpcSigningService.class);
            context.registerBean(UtxoSigningSubmissionService.class);
            context.registerBean(UtxoBroadcastService.class);
            context.registerBean(WithdrawalQueueService.class);
            var feeRates=org.mockito.Mockito.mock(com.surprising.wallet.repository.ChainFeeRateRepository.class);
            org.mockito.Mockito.when(feeRates.find("btc")).thenReturn("10");
            context.registerBean(com.surprising.wallet.repository.ChainFeeRateRepository.class,()->feeRates);
            context.registerBean(com.surprising.wallet.service.RbfBumpService.class);
            context.registerBean(WithdrawalSettlementService.class);
            context.registerBean(BitcoinLikeSettlementService.class);
            context.refresh();
            var submit=context.getBean(UtxoSigningSubmissionService.class);
            var mpc=context.getBean(UtxoMpcSigningService.class);
            var done=context.getBean(WithdrawalQueueService.class);
            var settle=context.getBean(BitcoinLikeSettlementService.class);
            for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                var online=pair.stream().map(nodes.parties()::get).toList();
                org.mockito.Mockito.doReturn(online).when(quorum).online();
                String funded=rpc("sendtoaddress",List.of(source.getAddress(),funding)).asText();
                var original=rpc("getrawtransaction",List.of(funded,true));
                JsonNode output=null;
                for(var candidate:original.path("vout")) if(source.getAddress().equals(address(candidate))) output=candidate;
                require(output!=null,chain+" business input missing");
                String destination=rpc("getnewaddress",List.of()).asText();
                rpc("generatetoaddress",List.of(1,destination));
                String order="native-utxo-"+UUID.randomUUID(),account=order+"-principal";
                int vout=output.path("n").asInt();
                var request=new UtxoSigningRequest(tenant,UUID.randomUUID(),
                        new UtxoSigningRequest.Asset(chain,BitcoinLikeNetwork.REGTEST,
                                new ChainProfileRepository(jdbc).findAccountByChain(chain).orElseThrow().getRuntimeCurrencyId(),8,
                                new ChainProfileRepository(jdbc).findAccountByChain(chain).orElseThrow().getBip44CoinType()),
                        UtxoSigningRequest.Operation.WITHDRAWAL,null,
                        List.of(new UtxoSigningRequest.Input(funded,vout,source.getAddress(),funding,
                                new UtxoSigningRequest.Key(source.getUserId(),source.getBiz(),Math.toIntExact(source.getAddressIndex()),null))),
                        List.of(new UtxoSigningRequest.Payment(order,42,destination,payment,customerFee)),
                        source.getAddress(),rate,dust);
                transaction.executeWithoutResult(status->{
                    jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,?,?,?,?,?)",tenant,chain,chain,account,funding,funding);
                    orders.createForTenant(tenant,order,42,chain,chain,source.getAddress(),account,destination,payment,customerFee);
                    require(balances.freeze(chain,chain,account,payment.add(customerFee),tenant),chain+" principal freeze failed");
                    orders.updateStatus(tenant,chain,order,"FROZEN",source.getAddress(),null,null);
                    require(orders.claimSigning(tenant,chain,order,source.getAddress())==1,chain+" order not claimed");
                    utxos.upsert(chain,chain,funded,vout,source.getAddress(),funding,1,"regtest",1,true);
                    var parent=parents.create(request,order);
                    currentId[0]=parent.id();
                    require(utxos.lock(chain,funded,vout,Integer.toString(parent.id()))==1,chain+" input not locked");
                    submit.enqueue(tenant,chain,parent.id(),request.requestId());
                });
                require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where tenant_id=? and business_reference=?",Long.class,tenant,request.requestId().toString())==1,
                        "BTC queue lacks fixed input intent");
                mpc.process();
                int count=observed.size();loseReply.set(true);
                done.broadcast();
                var selected=parents.findById(chain,currentId[0]).orElseThrow();
                require(selected.signed()!=null && observed.size()==count+1,chain+" formal queue failed to sign and broadcast");
                require("BROADCAST_UNKNOWN".equals(orders.find(chain,order,tenant).orElseThrow().getStatus()),"lost BTC reply did not retain unknown state");
                require(balances.find(tenant,chain,chain,account).orElseThrow().getLockedBalance().compareTo(payment.add(customerFee))==0,"unknown BTC released frozen principal");
                jdbc.execute("update pgmq.q_wallet_sign_done set vt=now()-interval '1 second'");
                done.broadcast();
                require(observed.size()==count+2 && observed.get(count).equals(observed.get(count+1)),chain+" unknown replay changed bytes");
                if (chain.equals("BTC")) {
                    queue.send(com.surprising.wallet.common.queue.WalletQueue.RBF,
                            json.writeValueAsString(Map.of("transactionId",currentId[0],"expectedTxId",selected.txId())),
                            com.surprising.wallet.common.queue.QueueTenant.headers(tenant));
                    context.getBean(com.surprising.wallet.service.RbfBumpService.class).process();
                    mpc.process();
                    done.broadcast();
                    var replacement=parents.findById(chain,currentId[0]).orElseThrow();
                    require(replacement.signed()!=null && !selected.txId().equals(replacement.txId())
                                    && replacement.attempts().size()==1 && replacement.request().inputs().equals(selected.request().inputs())
                                    && replacement.request().payments().equals(selected.request().payments()),"BTC RBF changed original inputs or payment");
                    require(rpc("gettxout",List.of(selected.txId(),0,true)).isNull(),"BTC node retained the replaced transaction");
                    selected=replacement;
                }
                rpc("generatetoaddress",List.of(1,destination));
                var received=rpc("gettxout",List.of(selected.txId(),0,true));
                require(received.path("confirmations").asInt()>=1 && destination.equals(address(received))
                        && received.path("value").decimalValue().compareTo(payment)==0,chain+" business payment differs or was not mined");
                settle.settleConfirmed(chain,currentId[0],selected.txId());
                settle.settleConfirmed(chain,currentId[0],selected.txId());
                submit.enqueue(tenant,chain,currentId[0],selected.request().requestId());
                require("CONFIRMED".equals(orders.find(chain,order,tenant).orElseThrow().getStatus()),chain+" principal not settled");
                var balance=balances.find(tenant,chain,chain,account).orElseThrow();
                require(balance.getLockedBalance().signum()==0 && balance.getTotalBalance().compareTo(funding.subtract(payment).subtract(customerFee))==0,chain+" principal settled more than once");
                require(rpc("gettxout",List.of(funded,vout,true)).isNull(),chain+" original input was not spent");
            }
            if(chain.equals("BTC")) collection(jdbc,transaction,signing,nodes,tenant,source,context,currentId);
        }
    }

    /** A two-input sweep uses two durable signature slots and one exact input-minus-fee output. */
    private void collection(JdbcTemplate jdbc,TransactionTemplate transaction,MpcSigningService signing,MpcNodes nodes,
                            UUID tenant,ChainAddressRecord source,AnnotationConfigApplicationContext context,int[] currentId) throws Exception {
        var profile=new ChainProfileRepository(jdbc).findAccountByChain(chain).orElseThrow();
        var key=signing.awaitKey(tenant,ChainType.BTC,0,source.getBiz(),9199,nodes);
        String target=com.surprising.wallet.chain.MpcPublicAddressCodec.encode(profile,HexFormat.of().parseHex(key.publicKeyHex()));
        transaction.executeWithoutResult(status->{
            signing.lockActiveKey(tenant,key.id(),ChainType.BTC);
            new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(key.id())
                    .chain(chain).assetSymbol(chain).accountId(target).userId(0L).biz(source.getBiz()).addressIndex(9199L)
                    .address(target).ownerAddress(target).walletRole("HOT").enabled(true).derivationPath("mpc:"+key.id()).build());
        });
        var inputs=new java.util.ArrayList<UtxoSigningRequest.Input>();
        for(var value:List.of(new BigDecimal("0.003"),new BigDecimal("0.004"))) {
            String hash=rpc("sendtoaddress",List.of(source.getAddress(),value)).asText();
            var raw=rpc("getrawtransaction",List.of(hash,true));
            JsonNode output=null;
            for(var candidate:raw.path("vout")) if(source.getAddress().equals(address(candidate))) output=candidate;
            require(output!=null,"BTC collection funding output missing");
            inputs.add(new UtxoSigningRequest.Input(hash,output.path("n").asInt(),source.getAddress(),value,
                    new UtxoSigningRequest.Key(source.getUserId(),source.getBiz(),Math.toIntExact(source.getAddressIndex()),null)));
        }
        String miner=rpc("getnewaddress",List.of()).asText();
        rpc("generatetoaddress",List.of(1,miner));
        UUID custody=jdbc.queryForObject("select id from custody_address where tenant_id=? and chain=? and chain_address_id=?",UUID.class,tenant,chain,source.getId());
        String reference="native-mpc-collection-"+UUID.randomUUID();
        var asset=new UtxoSigningRequest.Asset(chain,BitcoinLikeNetwork.REGTEST,profile.getRuntimeCurrencyId(),8,profile.getBip44CoinType());
        var draft=new UtxoSigningRequest(tenant,UUID.randomUUID(),asset,UtxoSigningRequest.Operation.COLLECTION,reference,inputs,
                List.of(new UtxoSigningRequest.Payment(reference,source.getUserId(),target,BigDecimal.ONE,BigDecimal.ZERO)),target,rate,dust);
        var collections=context.getBean(CollectionRecordRepository.class);
        var parents=context.getBean(ChainSigningTransactionRepository.class);
        var utxos=context.getBean(UtxoRepository.class);
        var mpc=context.getBean(UtxoMpcSigningService.class);
        long fee=transaction.execute(status->mpc.estimateFee(draft,false,rate));
        BigDecimal amount=BigDecimal.valueOf(draft.inputAtomic()-fee).movePointLeft(8);
        var request=draft.replacement(draft.requestId(),rate,List.of(new UtxoSigningRequest.Payment(reference,source.getUserId(),target,amount,BigDecimal.ZERO)));
        transaction.executeWithoutResult(status->{
            require(collections.create(tenant,custody,reference,chain,chain,source.getAddress(),target,amount,BigDecimal.valueOf(fee).movePointLeft(8),null)==1,"BTC collection not reserved");
            require(collections.claimSigning(tenant,chain,reference,null)==1,"BTC collection not claimed");
            var parent=parents.create(request,reference);currentId[0]=parent.id();
            for(var input:inputs) {
                utxos.upsert(chain,chain,input.txId(),input.vout(),input.address(),input.amount(),1,"regtest",1,true);
                require(utxos.lock(chain,input.txId(),input.vout(),Integer.toString(parent.id()))==1,"BTC collection input not locked");
            }
            context.getBean(UtxoSigningSubmissionService.class).enqueue(tenant,chain,parent.id(),request.requestId());
        });
        mpc.process();
        context.getBean(WithdrawalQueueService.class).broadcast();
        var selected=parents.findById(chain,currentId[0]).orElseThrow();
        require(selected.signed()!=null && selected.signed().feeAtomic()==fee,"BTC sweep fee changed after MPC signing");
        require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where tenant_id=? and business_reference=? and state='COMPLETED'",Long.class,tenant,request.requestId().toString())==2,
                "BTC sweep did not persist both input signature slots");
        rpc("generatetoaddress",List.of(1,miner));
        var received=rpc("gettxout",List.of(selected.txId(),0,true));
        require(received.path("confirmations").asInt()>=1 && target.equals(address(received)) && received.path("value").decimalValue().compareTo(amount)==0,
                "BTC sweep target or input-minus-fee amount differs");
        require(rpc("gettxout",List.of(selected.txId(),1,true)).isNull(),"BTC sweep created an unexpected change output");
        var settle=context.getBean(BitcoinLikeSettlementService.class);
        settle.settleConfirmed(chain,currentId[0],selected.txId());settle.settleConfirmed(chain,currentId[0],selected.txId());
        require("CONFIRMED".equals(collections.findStatus(tenant,chain,reference).orElseThrow()),"BTC sweep not settled");
        for(var input:inputs) require(rpc("gettxout",List.of(input.txId(),input.vout(),true)).isNull(),"BTC sweep left an original input unspent");
    }
    private static String address(JsonNode output) {
        var script=output.path("scriptPubKey");
        return script.hasNonNull("address") ? script.path("address").asText() : script.path("addresses").get(0).asText();
    }
    private JsonNode rpc(String method,List<?> parameters) throws Exception {
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .header("Authorization",authentication).POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(
                        Map.of("jsonrpc","2.0","id",1,"method",method,"params",parameters)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
        var result=json.readTree(response.body());
        if(response.statusCode()!=200 || (result.hasNonNull("error"))) throw new IllegalStateException("isolated UTXO RPC rejected "+method);
        return result.get("result");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
