package com.surprising.wallet.service;

import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import com.surprising.wallet.sdk.signing.tron.TronAddressCodec;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.model.ChainCollectionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.chain.tron.*;
import com.surprising.wallet.chain.xrp.XrpTransactionService;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.tron.trident.abi.datatypes.*;
import org.tron.trident.abi.datatypes.generated.*;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;
import tools.jackson.databind.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual wallet MPC signatures and TRE execution. Local development funder keys are fixture-only. */
public final class MpcTronBusinessNativeProbe {
    private MpcTronBusinessNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }

    public static void verify(JdbcTemplate jdbc,PlatformTransactionManager manager,MpcSigningService signing,
                              MpcNodes nodes,UUID tenant,JsonNode config) throws Exception {
        var json=new ObjectMapper();
        URI http=URI.create(config.path("tronHttpUrl").asText());
        require(http.getScheme().equals("http") && http.getHost().equals("127.0.0.1") && http.getUserInfo()==null,"TRON fixture requires isolated loopback TRE");
        String full=config.path("tronFullNode").asText(),solid=config.path("tronSolidityNode").asText();
        require(full.matches("127\\.0\\.0\\.1:[0-9]+") && solid.matches("127\\.0\\.0\\.1:[0-9]+"),"TRON fixture requires isolated loopback gRPC");
        var transaction=new TransactionTemplate(manager);
        var profiles=new ChainProfileRepository(jdbc);
        var profile=profiles.findAccountByChain("TRON").orElseThrow();
        long id=jdbc.queryForObject("select chain_address_id from custody_address where tenant_id=? and chain='TRON' and subject='native-customer'",Long.class,tenant);
        var source=new ChainAddressRepository(jdbc).findOwned(tenant,id).orElseThrow();
        var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);
        var transactions=new TronTransactionRepository(jdbc);var requests=new WalletMpcSignRequestRepository(jdbc);
        var balances=new LedgerBalanceRepository(jdbc);var tokens=new TokenConfigRepository(jdbc);
        jdbc.update("update chain_profile set withdraw_confirmations=1 where chain='TRON' and enabled");
        profile.setWithdrawConfirmations(1);
        jdbc.update("update chain_rpc_node set enabled=false where chain='TRON'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url) values ('TRON',?,'mpc-test','mpc-full','rpc',?),('TRON',?,'mpc-test','mpc-solid','solidity',?)",
                profile.getNetwork(),full,profile.getNetwork(),solid);
        var rpcNodes=new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2);
        var actualFactory=new TronClientFactory(profiles,rpcNodes);
        var loseReply=new AtomicBoolean();var sends=new ArrayList<String>();
        TronSubmissionService.Operation[] current={null};
        var clients=spy(actualFactory);
        doAnswer(call->{
            var client=spy(actualFactory.create());
            doAnswer(broadcast->{
                require(!TransactionSynchronizationManager.isActualTransactionActive(),"TRON RPC held a business transaction");
                Chain.Transaction raw=broadcast.getArgument(0);String hash=TronTransactionService.txId(raw);
                var intent=requests.findBusiness(tenant,ChainType.TRON,
                        com.surprising.wallet.common.signing.MpcSignCommand.BusinessType.valueOf(current[0].kind().name()),current[0].reference(),"transaction").orElseThrow();
                require(intent.state()==MpcSignRequest.State.COMPLETED,"TRON RPC preceded durable MPC result");
                String ownerHash=current[0].kind()==TronSubmissionService.Kind.WITHDRAWAL
                        ? orders.find("TRON",current[0].reference(),tenant).orElseThrow().getTxHash()
                        : collections.findTxHash(tenant,"TRON",current[0].reference()).orElseThrow();
                require(hash.equals(ownerHash) && HexFormat.of().formatHex(raw.toByteArray()).equals(transactions.findSigned(hash).orElseThrow().getRawPayload()),
                        "TRON RPC preceded exact raw/business hash commit");
                sends.add(HexFormat.of().formatHex(raw.toByteArray()));
                String accepted=(String)broadcast.callRealMethod();
                if(loseReply.getAndSet(false)) throw new IllegalStateException("deliberately lost TRE broadcast reply");
                return accepted;
            }).when(client).broadcast(any());
            return client;
        }).when(clients).create();
        var quorum=spy(nodes);doNothing().when(quorum).close();
        try(var context=new AnnotationConfigApplicationContext();var web=HttpClient.newHttpClient()) {
            // Only a development funder fixture signs locally; custody source and collector use actual MPC keys.
            var account=request(web,json,http.resolve("/admin/accounts-json"),null);
            var funder=new KeyPair(account.path("privateKeys").get(0).asText());
            try(var fixture=actualFactory.create()) {
                var funding=fixture.createTrxTransfer(funder.toBase58CheckAddress(),source.getAddress(),500_000_000L);
                String hash=fixture.broadcast(fixture.signTransaction(funding,funder));awaitReceipt(fixture,hash);
            }
            var artifact=json.readTree(Files.readAllBytes(Path.of(config.path("tronTokenArtifact").asText())));
            var deployed=new LinkedHashMap<String,String>();
            try(var fixture=actualFactory.create()) {
                for(String symbol:List.of("USDT","USDC")) {
                    // Trident deployContract's per-value encoder does not supply dynamic constructor offsets.
                    String constructor=org.tron.trident.abi.FunctionEncoder.encodeConstructor(List.of(
                            new Utf8String("MPC "+symbol),new Utf8String(symbol),new Uint8(6)));
                    var creation=fixture.deployContract(funder,"MpcMock"+symbol,json.writeValueAsString(artifact.get("abi")),
                            artifact.path("bytecode").asText()+constructor,List.of(),1_000_000_000L,100,10_000_000,0);
                    String hash=fixture.broadcast(fixture.signTransaction(creation.getTransaction(),funder));
                    var receipt=awaitReceipt(fixture,hash);
                    require(receipt.getResultValue()==0 && receipt.getContractAddress().size()==21,"TRE fixture deployment failed: code="+receipt.getResultValue()+" vm="+receipt.getReceipt().getResult()+" addressBytes="+receipt.getContractAddress().size());
                    String contract=TronAddressCodec.hexToBase58(HexFormat.of().formatHex(receipt.getContractAddress().toByteArray()));
                    var mint=new Function("mint",List.of(new Address(TronAddressCodec.toAbiAddress(source.getAddress())),new Uint256(BigInteger.valueOf(10_000_000_000L))),List.of());
                    var unsigned=fixture.createContractCall(funder.toBase58CheckAddress(),contract,mint,1_000_000_000L);
                    awaitReceipt(fixture,fixture.broadcast(fixture.signTransaction(unsigned,funder)));
                    jdbc.update("""
                            insert into token_config(chain,network,symbol,standard,contract_address,decimals,enabled)
                            values ('TRON',?,?,'TRC20',?,6,true)
                            on conflict(chain,network,symbol) do update set contract_address=excluded.contract_address,decimals=6,enabled=true
                            """,profile.getNetwork(),symbol,contract);
                    deployed.put(symbol,contract);
                }
            }
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->manager);
            context.registerBean(WithdrawalOrderRepository.class,()->orders);context.registerBean(CollectionRecordRepository.class,()->collections);
            context.registerBean(TronTransactionRepository.class,()->transactions);context.registerBean(WalletMpcSignRequestRepository.class,()->requests);
            context.registerBean(ChainProfileRepository.class,()->profiles);context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(TokenConfigRepository.class,()->tokens);
            context.registerBean(LedgerBalanceRepository.class,()->balances);context.registerBean(ObjectMapper.class,()->json);
            context.registerBean(MpcSigningService.class,()->signing);context.registerBean(MpcNodes.class,()->quorum);
            context.registerBean(TronClientFactory.class,()->clients);
            context.registerBean(AccountChainAssetService.class,()->new AccountChainAssetService(new Evm7702ConfigRepository(jdbc),new ChainAddressRepository(jdbc),
                    tokens,new ChainAssetRepository(jdbc),mock(XrpTransactionService.class),mock(com.surprising.wallet.chain.polkadot.PolkadotTransactionService.class),mock(com.surprising.wallet.chain.cardano.CardanoTransactionService.class)));
            context.registerBean(TronTransactionService.class);context.registerBean(TronTrc20Service.class);
            context.registerBean(WithdrawalSettlementService.class);context.registerBean(TronSubmissionService.class);context.registerBean(TronAccountChainService.class);
            context.refresh();
            var service=context.getBean(TronAccountChainService.class);
            var submissions=context.getBean(TronSubmissionService.class);
            try(var observer=actualFactory.create()) {
                for(var pair:List.of(List.of(0,1),List.of(0,2),List.of(1,2))) {
                    doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                    for(String symbol:List.of("TRX","USDT","USDC")) {
                        String recipient=new KeyPair(account.path("privateKeys").get(1).asText()).toBase58CheckAddress();
                        String reference="native-tron-"+UUID.randomUUID(),principal=reference+"-principal";
                        String destination=recipient;current[0]=new TronSubmissionService.Operation(tenant,reference,TronSubmissionService.Kind.WITHDRAWAL);
                        transaction.executeWithoutResult(status->{
                            jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'TRON',?,?,5,5)",tenant,symbol,principal);
                            orders.createForTenant(tenant,reference,42,"TRON",symbol,source.getAddress(),principal,destination,BigDecimal.ONE,new BigDecimal("0.1"));
                            require(balances.freeze("TRON",symbol,principal,new BigDecimal("1.1"),tenant),"TRON principal freeze failed");
                            orders.updateStatus(tenant,"TRON",reference,"FROZEN",source.getAddress(),null,null);
                            require(orders.claimSigningDurable(tenant,"TRON",reference,source.getAddress())==1,"TRON business not claimed");
                        });
                        var order=orders.find("TRON",reference,tenant).orElseThrow();
                        BigInteger before=balance(observer,symbol.equals("TRX") ? null:deployed.get(symbol),destination);
                        loseReply.set(true);int count=sends.size();
                        boolean lost=false;
                        try {service.broadcast(profile,order,source);} catch(IllegalStateException expected) {
                            require(sends.size()>count,"TRON failed before the deliberate lost reply: "+expected);lost=true;
                            submissions.unknown(current[0],"fixture lost response");
                        }
                        require(lost,"TRON fixture did not lose its accepted reply");
                        String hash=orders.find("TRON",reference,tenant).orElseThrow().getTxHash();require(hash!=null,"lost TRON reply lost its local hash");
                        awaitReceipt(observer,hash);
                        require(service.recover(current[0]),"TRON unknown payment was not recovered");
                        String raw=transactions.findSigned(hash).orElseThrow().getRawPayload();
                        for(int i=count;i<sends.size();i++) require(raw.equals(sends.get(i)),"TRON retry changed accepted bytes");
                        order=orders.find("TRON",reference,tenant).orElseThrow();service.confirmWithdrawal(profile,order);service.confirmWithdrawal(profile,order);
                        require("CONFIRMED".equals(orders.find("TRON",reference,tenant).orElseThrow().getStatus()),"TRON actual execution did not settle payment");
                        require(balance(observer,symbol.equals("TRX") ? null:deployed.get(symbol),destination).subtract(before).equals(BigInteger.valueOf(1_000_000)),
                                "TRON recipient received a different amount or was paid twice");
                        var ledger=balances.find(tenant,"TRON",symbol,principal).orElseThrow();
                        require(ledger.getLockedBalance().signum()==0 && ledger.getTotalBalance().compareTo(new BigDecimal("3.9"))==0,"TRON principal settled more than once");
                    }
                }
                collection(jdbc,transaction,signing,nodes,tenant,source,profile,context,observer,current,deployed.get("USDT"));
                failedExecution(jdbc,transaction,tenant,source,profile,context,observer,current,deployed.get("USDT"),
                        new KeyPair(account.path("privateKeys").get(1).asText()).toBase58CheckAddress(),web,json,http);
            }
        }
    }
    private static void collection(JdbcTemplate jdbc,TransactionTemplate transaction,MpcSigningService signing,MpcNodes nodes,
                                   UUID tenant,ChainAddressRecord source,AccountChainProfile profile,AnnotationConfigApplicationContext context,
                                   TronTridentClient observer,TronSubmissionService.Operation[] current,String contract) throws Exception {
        var key=signing.awaitKey(tenant,ChainType.TRON,0,source.getBiz(),9198,nodes);
        String target=MpcPublicAddressCodec.encode(profile,HexFormat.of().parseHex(key.publicKeyHex()));
        transaction.executeWithoutResult(status->{
            signing.lockActiveKey(tenant,key.id(),ChainType.TRON);
            new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(key.id())
                    .chain("TRON").assetSymbol("TRX").accountId(target).userId(0L).biz(source.getBiz()).addressIndex(9198L)
                    .address(target).ownerAddress(target).walletRole("HOT").enabled(true).derivationPath("mpc:"+key.id()).build());
        });
        UUID custody=jdbc.queryForObject("select id from custody_address where tenant_id=? and chain='TRON' and chain_address_id=?",UUID.class,tenant,source.getId());
        String reference="native-tron-collection-"+UUID.randomUUID();
        current[0]=new TronSubmissionService.Operation(tenant,reference,TronSubmissionService.Kind.COLLECTION);
        var collections=context.getBean(CollectionRecordRepository.class);
        require(collections.create(tenant,custody,reference,"TRON","USDT",source.getAddress(),target,BigDecimal.ONE,BigDecimal.ZERO,null)==1,"TRE collection was not reserved");
        var service=context.getBean(TronAccountChainService.class);
        var record=collections.lockForSubmission(tenant,"TRON",reference).orElseThrow();
        BigInteger before=balance(observer,contract,target);
        service.processCollection(profile,record,source);
        String hash=collections.findTxHash(tenant,"TRON",reference).orElseThrow();awaitReceipt(observer,hash);
        record=collections.lockForSubmission(tenant,"TRON",reference).orElseThrow();
        service.confirmCollection(profile,record);service.confirmCollection(profile,record);
        require("CONFIRMED".equals(collections.findStatus(tenant,"TRON",reference).orElseThrow()),"TRE MPC collection was not confirmed");
        require(balance(observer,contract,target).subtract(before).equals(BigInteger.valueOf(1_000_000)),"TRE collection paid a different token amount");
        var row=collections.lockForSubmission(tenant,"TRON",reference).orElseThrow();
        require(row.getFee().signum()>=0,"TRE actual collection fee was not recorded");
    }

    /** A fixture storage change makes an already accepted, valid transfer revert during actual TVM execution. */
    private static void failedExecution(JdbcTemplate jdbc,TransactionTemplate transaction,UUID tenant,ChainAddressRecord source,
                                       AccountChainProfile profile,AnnotationConfigApplicationContext context,TronTridentClient observer,
                                       TronSubmissionService.Operation[] current,String contract,String recipient,HttpClient web,ObjectMapper json,URI endpoint) throws Exception {
        String reference="native-tron-failed-"+UUID.randomUUID(),principal=reference+"-principal";
        current[0]=new TronSubmissionService.Operation(tenant,reference,TronSubmissionService.Kind.WITHDRAWAL);
        var orders=context.getBean(WithdrawalOrderRepository.class);var balances=context.getBean(LedgerBalanceRepository.class);
        var submissions=context.getBean(TronSubmissionService.class);var service=context.getBean(TronAccountChainService.class);
        transaction.executeWithoutResult(status->{
            jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,total_balance) values (?,'TRON','USDT',?,5,5)",tenant,principal);
            orders.createForTenant(tenant,reference,42,"TRON","USDT",source.getAddress(),principal,recipient,BigDecimal.ONE,new BigDecimal("0.1"));
            require(balances.freeze("TRON","USDT",principal,new BigDecimal("1.1"),tenant),"TRE failure fixture could not freeze principal");
            orders.updateStatus(tenant,"TRON",reference,"FROZEN",source.getAddress(),null,null);
            require(orders.claimSigningDurable(tenant,"TRON",reference,source.getAddress())==1,"TRE failure fixture could not claim order");
        });
        var unsigned=new TronTrc20Service().prepareTransfer(observer,source.getAddress(),contract,recipient,BigDecimal.ONE,6,30_000_000L);
        unsigned=unsigned.toBuilder().setRawData(unsigned.getRawData().toBuilder().setData(com.google.protobuf.ByteString.copyFrom(TronSubmissionService.memo(current[0])))).build();
        var payload=new com.surprising.wallet.common.signing.TronSigningPayload("USDT",BigDecimal.ONE,new BigDecimal("0.1"),6,
                MpcTronTransactionCodec.prepare(unsigned,source.getAddress(),recipient,BigInteger.valueOf(1_000_000),contract,30_000_000L));
        var intent=submissions.reserve(current[0],source,payload);submissions.sign(current[0],intent.id());var signed=submissions.commit(current[0],intent.id());
        BigInteger before=balance(observer,contract,recipient),original=balance(observer,contract,source.getAddress());
        byte[] mapKey=new byte[64];System.arraycopy(HexFormat.of().parseHex(TronAddressCodec.base58ToHex(source.getAddress()).substring(2)),0,mapKey,12,20);mapKey[63]=3;
        String slot=org.web3j.crypto.Hash.sha3(org.web3j.utils.Numeric.toHexString(mapKey));
        try {
            var response=request(web,json,endpoint.resolve("/tre"),Map.of("jsonrpc","2.0","id",1,"method","tre_setAccountStorageAt",
                    "params",List.of(contract,slot,"0x"+"00".repeat(32))));
            require(!response.hasNonNull("error") && balance(observer,contract,source.getAddress()).signum()==0,"TRE could not set up an actual reverted transfer");
            require(service.recover(current[0]),"TRE reverted candidate was not broadcast from its fixed intent");
            var receipt=awaitReceipt(observer,signed.txId());require(receipt.getResultValue()==1,"TRE did not produce the deliberate failed execution receipt");
            var order=orders.find("TRON",reference,tenant).orElseThrow();service.confirmWithdrawal(profile,order);service.confirmWithdrawal(profile,order);
            require("FAILED".equals(orders.find("TRON",reference,tenant).orElseThrow().getStatus()),"TRE failed execution settled as a payment");
            var ledger=balances.find(tenant,"TRON","USDT",principal).orElseThrow();
            require(ledger.getLockedBalance().signum()==0 && ledger.getTotalBalance().compareTo(new BigDecimal("5"))==0,"TRE failed execution did not refund frozen principal exactly once");
            require(balance(observer,contract,recipient).equals(before),"TRE reverted transfer changed recipient balance");
            require(context.getBean(TronTransactionRepository.class).findConfirmedFee("TRON",signed.txId()).orElseThrow()
                    .compareTo(BigDecimal.valueOf(receipt.getFee()).movePointLeft(6))==0,"TRE failed execution lost its actual gas fee");
        } finally {
            request(web,json,endpoint.resolve("/tre"),Map.of("jsonrpc","2.0","id",1,"method","tre_setAccountStorageAt",
                    "params",List.of(contract,slot,"0x"+org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(original,64))));
        }
    }

    private static BigInteger balance(TronTridentClient client,String contract,String owner) {
        if(contract==null) return BigInteger.valueOf(client.getBalanceSun(owner));
        var result=client.constantCall(contract,contract,new Function("balanceOf",List.of(new Address(TronAddressCodec.toAbiAddress(owner))),List.of()));
        require(result.getConstantResultCount()==1,"TRE token balance call failed");return new BigInteger(1,result.getConstantResult(0).toByteArray());
    }
    private static Response.TransactionInfo awaitReceipt(TronTridentClient client,String hash) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline) {
            try {var receipt=client.getTransactionInfo(hash,NodeType.FULL_NODE);if(receipt.getBlockNumber()>0) return receipt;}
            catch(Exception notYet) { }
            Thread.sleep(100);
        }
        throw new AssertionError("TRE did not mine the accepted fixture transaction");
    }
    private static JsonNode request(HttpClient http,ObjectMapper json,URI uri,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
        var request=body==null ? builder.GET().build():builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());require(response.statusCode()==200,"TRE fixture HTTP request failed");return json.readTree(response.body());
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
