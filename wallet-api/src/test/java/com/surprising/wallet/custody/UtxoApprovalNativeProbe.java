package com.surprising.wallet.custody;

import com.surprising.wallet.chain.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.queue.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.service.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;

/** Actual independent approvals + native MPC + formal PGMQ raw/hash/unknown/retry/settlement against owned Core regtest. */
final class UtxoApprovalNativeProbe {
    private UtxoApprovalNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }
    static Object run(JsonNode input,WithdrawalApprovalClient authority,ObjectMapper json) throws Exception {
        String url=System.getenv("APPROVAL_DB_URL");
        require(url!=null && url.matches("^jdbc:postgresql://127\\.0\\.0\\.1:5432/surprising_wallet_test_[a-z0-9_]+$"),"UTXO acceptance requires its isolated existing-PG18 database");
        var ds=new DriverManagerDataSource(url,System.getenv("APPROVAL_DB_USERNAME"),System.getenv("APPROVAL_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);require(jdbc.queryForObject("select current_setting('server_version_num')::int/10000",Integer.class)==18,"PG18 required");
        String chain=input.path("chain").asText();require(ChainType.valueOf(chain).isBitcoinLike(),"native UTXO chain required");
        var reviews=json.convertValue(input.path("approvalReceipts"),new tools.jackson.core.type.TypeReference<List<ApprovalRequest>>() {});
        require(reviews.size()==2 && reviews.stream().allMatch(r->r.status().equals("APPROVED") && r.intent().chain().equals(chain)),"two actual independent approvals required");
        UUID tenant=reviews.getFirst().intent().tenantId(),keyId=UUID.fromString(input.path("keyId").asText());String publicKey=input.path("publicKeyHex").asText();
        var manager=new DataSourceTransactionManager(ds);var tx=new TransactionTemplate(manager);
        var keys=new WalletMpcKeyRepository(jdbc);var epochs=new WalletMpcKeyEpochRepository(jdbc,json);
        var requests=new WalletMpcSignRequestRepository(jdbc);var attempts=new WalletMpcSignAttemptRepository(jdbc,json);
        var approvals=new WalletWithdrawalApprovalRepository(jdbc,json);var audit=new CustodyAuditRepository(jdbc);
        var authorizations=com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,manager,authority);
        authorizations.registerKey(tenant,keyId); // Cached actual DKG registration must work with holder2 offline.
        var properties=new CustodySecurityProperties();byte[] aes=new byte[32];new java.security.SecureRandom().nextBytes(aes);
        properties.setSecretMasterKey(HexFormat.of().formatHex(aes));Arrays.fill(aes,(byte)0);var crypto=new CustodyCryptoService(properties);crypto.validateConfiguration();
        var signing=new MpcSigningService(new CustodyTenantTableRepository(jdbc),keys,epochs,requests,attempts,crypto,json,manager,authorizations);
        var profiles=new ChainProfileRepository(jdbc);var addresses=new ChainAddressRepository(jdbc);
        var parents=new ChainSigningTransactionRepository(jdbc,json);var orders=new WithdrawalOrderRepository(jdbc);
        var utxos=new UtxoRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);var queue=new PgmqClient(jdbc);var worker=new QueueWorker(queue,manager);
        jdbc.update("update chain_profile set enabled=false where chain=?",chain);jdbc.update("update chain_profile set enabled=true where chain=? and network='regtest'",chain);
        var profile=profiles.findAccountByChain(chain).orElseThrow();String sender=com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec.address(chain,BitcoinLikeNetwork.REGTEST,publicKey);
        var key=keys.find(tenant,keyId).orElseThrow();String account="approval-utxo-"+chain;
        var source=tx.execute(status->addresses.publishMpc(ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(keyId).chain(chain).assetSymbol(chain).userId(key.userId()).biz(key.biz()).addressIndex(key.addressIndex())
                .address(sender).ownerAddress(sender).accountId(account).walletRole("HOT").derivationPath("mpc:"+keyId).enabled(true).build()));
        var inputs=new ArrayList<UtxoSigningRequest.Input>();
        for(var point:input.path("outpoints"))inputs.add(new UtxoSigningRequest.Input(point.path("txId").asText(),point.path("outputIndex").asInt(),sender,BigDecimal.valueOf(point.path("valueAtomic").asLong()).movePointLeft(8),
                new UtxoSigningRequest.Key(source.getUserId(),source.getBiz(),Math.toIntExact(source.getAddressIndex()),null)));
        require(inputs.size()==2,"two actual funded inputs required");
        var request=new UtxoSigningRequest(tenant,UUID.randomUUID(),new UtxoSigningRequest.Asset(chain,BitcoinLikeNetwork.REGTEST,profile.getRuntimeCurrencyId(),8,profile.getBip44CoinType()),UtxoSigningRequest.Operation.WITHDRAWAL,null,inputs,
                reviews.stream().map(r->new UtxoSigningRequest.Payment(r.intent().businessReference(),key.userId(),r.intent().recipient(),r.intent().amount(),BigDecimal.ZERO)).toList(),sender,chain.equals("DOGE")?1000:2,profile.getDustThreshold());
        var nativeProperties=new MpcNodeProperties();var nativeProfile=input.path("nativeProfile");
        nativeProperties.setCaFile(Path.of(nativeProfile.path("caFile").asText()));nativeProperties.setCertificateFile(Path.of(nativeProfile.path("certificateFile").asText()));nativeProperties.setPrivateKeyFile(Path.of(nativeProfile.path("privateKeyFile").asText()));
        nativeProperties.setCallTimeout(Duration.ofSeconds(3));nativeProperties.setOperationTimeout(Duration.ofSeconds(30));
        nativeProperties.setNodes(java.util.stream.StreamSupport.stream(nativeProfile.path("nodes").spliterator(),false).map(n->new MpcNodeProperties.Node(n.path("partyId").asText(),URI.create(n.path("endpoint").asText()))).toList());
        try(var rpc=new CoreRpc(input.path("chainRpc"),json);var context=new AnnotationConfigApplicationContext()) {
            require(rpc.call("getblockchaininfo",List.of()).path("chain").asText().equals("regtest"),"broadcast probe refuses non-regtest networks");
            int[] parentId={0};var observed=new ArrayList<String>();var loseReply=new java.util.concurrent.atomic.AtomicBoolean(true);
            // Only the transport is a fixture bridge. Core, all business services, queue fencing and native signatures are real.
            var runtime=new BlockchainRuntimeService(profiles,new ChainAssetRepository(jdbc),null,null) {
                @Override public String broadcastSignedTransaction(AssetRuntimeMetadata metadata,UtxoSignedTransaction signed) {
                    require(!TransactionSynchronizationManager.isActualTransactionActive(),"Core broadcast held a database transaction");
                    var persisted=parents.findById(chain,parentId[0]).orElseThrow();require(signed.equals(persisted.signed()),"broadcast preceded selected raw/hash receipt");
                    for(var payment:request.payments())require(orders.findTxHash(chain,payment.reference(),tenant).orElseThrow().equals(signed.txId()),"broadcast preceded original business hash binding");
                    observed.add(signed.transactionHex());String hash;
                    try { hash=rpc.call("sendrawtransaction",List.of(signed.transactionHex())).asText(); }
                    catch(IllegalStateException known) { require(!rpc.call("gettxout",List.of(signed.txId(),0,true)).isNull(),"original broadcast output missing");hash=signed.txId(); }
                    require(hash.equals(signed.txId()),"Core returned another transaction hash");return loseReply.getAndSet(false)?"":hash;
                }
            };
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);context.registerBean(JdbcTemplate.class,()->jdbc);context.registerBean(ObjectMapper.class,()->json);
            context.registerBean(ChainSigningTransactionRepository.class,()->parents);context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(UtxoRepository.class,()->utxos);
            context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));context.registerBean(ChainProfileRepository.class,()->profiles);
            context.registerBean(ChainAddressRepository.class,()->addresses);context.registerBean(WalletMpcSignRequestRepository.class,()->requests);context.registerBean(MpcSigningService.class,()->signing);
            context.registerBean(MpcNodes.class,()->new MpcNodes(nativeProperties));context.registerBean(PgmqClient.class,()->queue);context.registerBean(QueueWorker.class,()->worker);
            context.registerBean(BlockchainRuntimeService.class,()->runtime);context.registerBean(WalletTaskLeaseService.class,()->new WalletTaskLeaseService(new WalletTaskLeaseRepository(jdbc)));
            for(Class<?> type:List.of(UtxoMpcSigningService.class,UtxoSigningSubmissionService.class,UtxoBroadcastService.class,WithdrawalSettlementService.class,BitcoinLikeSettlementService.class))context.register(type);
            context.registerBean(WithdrawalQueueService.class,()->new WithdrawalQueueService(worker,null,context.getBean(UtxoBroadcastService.class),json,null));context.refresh();
            tx.executeWithoutResult(status->{
                UUID custody=UUID.randomUUID();jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child) values (?,?,?,?,'regtest',?,'approval-native-batch','API','ACTIVE',1,0)",custody,tenant,source.getId(),chain,sender);
                BigDecimal total=request.inputAmount(),principal=request.payments().stream().map(UtxoSigningRequest.Payment::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
                jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,?,?,?,?,?)",tenant,chain,chain,account,total,total);
                for(var review:reviews) {
                    var intent=review.intent();long order=orders.createForTenant(tenant,intent.businessReference(),key.userId(),chain,chain,sender,account,intent.recipient(),intent.amount(),BigDecimal.ZERO);
                    orders.updateStatus(tenant,chain,intent.businessReference(),"FROZEN",sender,null,null);require(orders.claimSigning(tenant,chain,intent.businessReference(),sender)==1,"batch order could not be claimed");
                    new CustodyWithdrawalRepository(jdbc).insert(intent.id(),tenant,custody,order,intent.businessReference(),intent.externalReference(),"native-"+intent.id(),chain,chain,intent.recipient(),intent.amount(),BigDecimal.ZERO,"FROZEN","API_KEY",intent.initiatorId().toString(),intent.subject(),intent.fundingSource());
                    approvals.insert(intent.id(),order,intent);approvals.record(tenant,intent.id(),"APPROVED",review);
                }
                require(balances.freeze(chain,chain,account,principal,tenant),"batch principal freeze failed");
                for(var point:request.inputs())utxos.upsert(chain,chain,point.txId(),point.vout(),sender,point.amount(),1,"regtest",1,true);
                var parent=parents.create(request,"approval-"+chain);parentId[0]=parent.id();
                for(var point:request.inputs())require(utxos.lock(chain,point.txId(),point.vout(),Integer.toString(parent.id()))==1,"original batch input lock missing");
                context.getBean(UtxoSigningSubmissionService.class).enqueue(tenant,chain,parent.id(),request.requestId());
            });
            context.getBean(UtxoMpcSigningService.class).process();
            require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where tenant_id=? and business_reference=? and state='COMPLETED'",Integer.class,tenant,request.requestId().toString())==2,"actual batch native signatures did not complete");
            require(jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=? and action='MPC.AUTHORIZE' and resource_id in (select id::text from wallet_mpc_sign_request where tenant_id=? and business_reference=?)",Integer.class,tenant,tenant,request.requestId().toString())==2,"input proofs were not committed with authorization audits");
            var done=context.getBean(WithdrawalQueueService.class);done.broadcast();var signed=parents.findById(chain,parentId[0]).orElseThrow().signed();require(signed!=null && observed.size()==1,"formal queue did not persist and broadcast the original batch");
            BigDecimal principal=request.payments().stream().map(UtxoSigningRequest.Payment::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            require(balances.find(tenant,chain,chain,account).orElseThrow().getLockedBalance().compareTo(principal)==0,"lost reply released original frozen principal");
            for(var payment:request.payments())require(orders.find(chain,payment.reference(),tenant).orElseThrow().getStatus().equals("BROADCAST_UNKNOWN"),"lost reply did not retain original unknown state");
            jdbc.execute("update pgmq.q_wallet_sign_done set vt=now()-interval '1 second'");done.broadcast();
            require(observed.size()==2 && observed.getFirst().equals(observed.getLast()),"unknown broadcast retry changed signed bytes");
            rpc.call("generatetoaddress",List.of(1,input.path("minerAddress").asText()));
            var receipt=rpc.call("getrawtransaction",List.of(signed.txId(),true));require(receipt.path("confirmations").asInt()>=1,"batch is not mined");
            var header=rpc.call("getblockheader",List.of(receipt.path("blockhash").asText()));require(rpc.call("getblockhash",List.of(header.path("height").asLong())).asText().equals(receipt.path("blockhash").asText()),"batch block is not canonical");
            for(int index=0;index<request.payments().size();index++) {
                var output=rpc.call("gettxout",List.of(signed.txId(),index,true));var payment=request.payments().get(index);
                require(output.path("value").decimalValue().compareTo(payment.amount())==0 && output.path("scriptPubKey").path("hex").asText().equals(com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec.outputScriptHex(chain,BitcoinLikeNetwork.REGTEST,payment.address())),"mined recipient output differs from independent approval");
            }
            var change=rpc.call("gettxout",List.of(signed.txId(),2,true));require(change.path("value").decimalValue().movePointRight(8).longValueExact()==request.inputAtomic()-request.paymentAtomic()-signed.feeAtomic(),"actual change or batch fee differs");
            for(var point:request.inputs())require(rpc.call("gettxout",List.of(point.txId(),point.vout(),true)).isNull(),"original input was not spent");
            var settlement=context.getBean(BitcoinLikeSettlementService.class);settlement.settleConfirmed(chain,parentId[0],signed.txId());settlement.settleConfirmed(chain,parentId[0],signed.txId());
            var balance=balances.find(tenant,chain,chain,account).orElseThrow();require(balance.getLockedBalance().signum()==0 && balance.getTotalBalance().compareTo(request.inputAmount().subtract(principal))==0,"confirmed principal was not settled exactly once");
            for(var payment:request.payments())require(orders.find(chain,payment.reference(),tenant).orElseThrow().getStatus().equals("CONFIRMED"),"batch order did not confirm");
            return Map.of("status","PASS","chain",chain,"txId",signed.txId(),"transactionId",parentId[0],"inputs",2,"payments",2,"feeAtomic",signed.feeAtomic(),"unknownRetrySameBytes",true,"principalSettledOnce",true,"proofsPersisted",2);
        }
    }
    private static final class CoreRpc implements AutoCloseable {
        private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        private final URI endpoint;private final String authorization;private final ObjectMapper json;
        CoreRpc(JsonNode config,ObjectMapper json) throws Exception {
            this.json=json;endpoint=URI.create(config.path("endpoint").asText());require(endpoint.getScheme().equals("http") && endpoint.getHost().equals("127.0.0.1") && endpoint.getUserInfo()==null && endpoint.getQuery()==null,"Core acceptance requires an isolated loopback endpoint");
            var path=Path.of(config.path("basicAuthFile").asText());require(Files.getPosixFilePermissions(path).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"Core fixture credentials must be 0600");
            authorization="Basic "+Base64.getEncoder().encodeToString(Files.readString(path).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        JsonNode call(String method,List<?> params) {
            try {
                var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("Authorization",authorization)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("jsonrpc","1.0","id",UUID.randomUUID().toString(),"method",method,"params",params)))).build();
                var response=http.send(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),2*1024*1024));
                var body=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.body());
                require(response.statusCode()==200 && body.path("error").isNull(),"Core fixture rejected "+method);return body.path("result");
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException("Core fixture call interrupted");}
            catch(java.io.IOException failure) {throw new IllegalStateException("Core fixture call failed");}
        }
        @Override public void close() {http.close();}
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
}
