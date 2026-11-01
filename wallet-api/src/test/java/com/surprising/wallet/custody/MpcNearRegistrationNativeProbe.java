package com.surprising.wallet.custody;

import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.NearSubmissionService.*;
import com.surprising.wallet.testutil.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bitcoinj.base.Base58;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual MPC/NEAR consensus and owned Gas accounting; RPC fault injection never fabricates execution receipts. */
public final class MpcNearRegistrationNativeProbe {
    private static final BigInteger YOCTO=BigInteger.TEN.pow(24);
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;
    private Path root,fixturePath;
    private JsonNode fixture;
    private MpcNativeTestSupport mpc;
    private MpcNodes quorum;
    private List<Integer> pair;
    private ChainAddressRecord source,gas;
    private UUID sourceCustody;
    private long profileId;
    private NearRpcClient rpc,realRpc;
    private NearTransactionService service;
    private NearSubmissionService payments;
    private NearRegistrationService registrations;
    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    private Operation current;
    private boolean loseReply,registrationRace,staleQuote;
    private final Map<String,String> broadcasts=new LinkedHashMap<>();
    private final List<Operation> operations=new ArrayList<>();
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Transactions { }

    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("native NEAR registration requires a private fixture directory");
        new MpcNearRegistrationNativeProbe().run(Path.of(args[0]));
    }
    private void run(Path directory)throws Exception {
        root=directory.toRealPath();fixturePath=Path.of(System.getenv("MPC_NEAR_TEST_FIXTURE")).toRealPath();fixture=json.readTree(Files.readAllBytes(fixturePath));
        var endpoint=java.net.URI.create(fixture.path("endpoint").asText());
        assertEquals("http",endpoint.getScheme());assertEquals("127.0.0.1",endpoint.getHost());
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='NEAR'");
        assertEquals(1,jdbc.update("update chain_profile set network='localnet',enabled=true,withdraw_confirmations=1,deposit_confirmations=1,scan_batch_size=500,scan_max_blocks_per_run=500,scan_enabled=true,withdraw_enabled=true,collection_enabled=true,transfer_enabled=true where chain='NEAR' and network='testnet'"));
        var profiles=new ChainProfileRepository(jdbc);profileId=profiles.findAccountByChain("NEAR").orElseThrow().getId();
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('NEAR','localnet','near-registration-test','native-storage','rpc',?,0)",endpoint.toString());
        realRpc=new NearRpcClient(json,profiles,new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"near-registration-test",2));rpc=spy(realRpc.forNetwork("localnet"));doReturn(rpc).when(rpc).forNetwork("localnet");
        assertEquals("mpc-localnet-2134",realRpc.status().path("chain_id").asText());assertEquals(fixture.path("genesisHash").asText(),realRpc.status().path("genesis_hash").asText());
        mpc=new MpcNativeTestSupport(jdbc);
        try {
            pair=new ArrayList<>();for(var item:json.readTree(Files.readString(root.resolve("java-probe.json"))).path("pair"))pair.add(item.asInt());
            assertEquals(2,pair.size());quorum=spy(mpc.nodes);doNothing().when(quorum).close();
            doReturn(pair.stream().map(mpc.nodes.parties()::get).toList()).when(quorum).online();
            // CustodyGasService publishes its Gas address through the normal DEPOSIT address flow.
            source=mpc.address(ChainType.NEAR,42100,0,"DEPOSIT");gas=mpc.address(ChainType.NEAR,42101,0,"DEPOSIT");
            sourceCustody=custodyAddress(source,"storage-source",42100);UUID gasCustody=custodyAddress(gas,"storage-gas",42101);
            jdbc.update("insert into custody_gas_account(id,tenant_id,custody_address_id,chain,network,native_symbol,status) values (?,?,?,'NEAR','localnet','NEAR','ACTIVE')",UUID.randomUUID(),mpc.tenant,gasCustody);
            for(String symbol:List.of("USDT","USDC")) {
                String contract=fixture.path(symbol).asText();
                jdbc.update("insert into token_config(chain,network,symbol,standard,contract_address,decimals,enabled,collect_enabled,gas_strategy) values ('NEAR','localnet',?,'NEP141',?,6,true,true,'NEAR_STORAGE_DEPOSIT') on conflict(chain,network,symbol) do update set contract_address=excluded.contract_address,decimals=6,enabled=true,collect_enabled=true,gas_strategy=excluded.gas_strategy",symbol,contract);
                new ChainAssetRepository(jdbc).upsertNonNative("NEAR",symbol,"NEP141",contract,6,true,BigDecimal.ZERO,BigDecimal.ZERO);
            }
            Files.writeString(root.resolve("near-storage-keys-ready"),"ready");await("near-storage-third-stopped");
            // Operating fuel precedes the customer scan cursor; only Gas funding and tokens become ledger claims.
            fixtureCall("fund",source.getAddress(),YOCTO.multiply(BigInteger.TEN).toString());
            long start=realRpc.latestFinalBlockHeight();
            fixtureCall("fund",gas.getAddress(),YOCTO.multiply(BigInteger.TEN).toString());
            for(String symbol:List.of("USDT","USDC")) {
                fixtureCall("register",fixture.path(symbol).asText(),source.getAddress());
                fixtureCall("token-fund",fixture.path(symbol).asText(),source.getAddress(),"10000000");
            }
            try(var deposits=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper())) {
                var heights=new ChainScanHeightRepository(jdbc);heights.upsert("NEAR","near-block-scanner",start,start);
                var scanner=new NearDepositScanner(realRpc,heights,new NearTransactionRepository(jdbc),deposits.getBean(DepositService.class),new ChainAddressRepository(jdbc),profiles,
                        new TokenConfigRepository(jdbc),new DepositRecordRepository(jdbc),new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc)));
                scanner.scanAndCredit();
                assertAmount("10",balance("NEAR",gas).getTotalBalance());assertAmount("10",balance("USDT",source).getTotalBalance());assertAmount("10",balance("USDC",source).getTotalBalance());
                assertEquals(3,jdbc.queryForObject("select count(*) from custody_deposit",Integer.class));
                configureRpcGate();openContext();

                var lost=withdrawal("USDT",recipient());current=lost;loseReply=true;
                assertThrows(RuntimeException.class,()->service.send(lost,source));assertFalse(loseReply);
                payments.unknown(lost,"fixture lost actual accepted registration response");
                assertTrue(payments.intent(lost).isEmpty());assertEquals(0,payments.releaseUnsigned(lost));
                var original=registrations.intent(lost).orElseThrow();String originalRaw=new NearTransactionRepository(jdbc).find(MpcNearTransactionCodec.transactionHash(registrations.payload(lost).unsigned())).orElseThrow().getRawPayload();
                String originalAttempt=original.currentAttemptId().toString();
                Files.writeString(root.resolve("near-storage-restart-ready"),"ready");await("near-storage-restarted");openContext();
                disable(true);
                try {assertTrue(service.recover(lost));assertTrue(service.confirmWithdrawal(mpc.tenant,lost.reference()));}
                finally {disable(false);}
                assertEquals(original.id(),registrations.intent(lost).orElseThrow().id());assertEquals(originalAttempt,registrations.intent(lost).orElseThrow().currentAttemptId().toString());
                assertEquals(originalRaw,new NearTransactionRepository(jdbc).find(MpcNearTransactionCodec.transactionHash(registrations.payload(lost).unsigned())).orElseThrow().getRawPayload());
                assertEquals(BigInteger.valueOf(1_000_000),tokens("USDT",payments.payment(lost).recipient()));

                var collection=new Operation(mpc.tenant,"near-storage-collection-"+UUID.randomUUID(),Kind.COLLECTION);operations.add(collection);current=collection;
                assertEquals(1,new CollectionRecordRepository(jdbc).create(mpc.tenant,sourceCustody,collection.reference(),"NEAR","USDT",source.getAddress(),gas.getAddress(),BigDecimal.ONE,BigDecimal.ZERO,null));
                assertNotNull(service.collect(mpc.tenant,collection.reference(),source));assertTrue(service.confirmCollection(mpc.tenant,collection.reference()));
                assertEquals(gas.getAddress(),registrations.payload(collection).unsigned().beneficiary());

                var duplicate=withdrawal("USDC",recipient());current=duplicate;registrationRace=true;
                String duplicateHash=service.send(duplicate,source);payments.restoreSent(duplicate,duplicateHash);assertTrue(service.confirmWithdrawal(mpc.tenant,duplicate.reference()));assertFalse(registrationRace);
                var duplicateOutcome=registrationOutcome(duplicate);assertEquals(registrations.payload(duplicate).unsigned().depositAtomic(),duplicateOutcome.returnedDepositYocto());
                assertEquals(duplicateOutcome.feeYocto(),duplicateOutcome.expenseYocto());

                var failed=withdrawal("USDC",recipient());current=failed;staleQuote=true;
                assertThrows(RuntimeException.class,()->service.send(failed,source));staleQuote=false;
                assertEquals("FAILED",new WithdrawalOrderRepository(jdbc).find("NEAR",failed.reference(),mpc.tenant).orElseThrow().getStatus());assertTrue(payments.intent(failed).isEmpty());
                var failureOutcome=registrationOutcome(failed);assertFalse(failureOutcome.registered());
                assertEquals(registrations.payload(failed).unsigned().depositAtomic(),failureOutcome.returnedDepositYocto());assertEquals(failureOutcome.feeYocto(),failureOutcome.expenseYocto());
                assertFalse(registrations.settle(failed,registrations.proof(failed).orElseThrow(),realRpc.latestFinalBlockHeight(),1));
                assertEquals(BigInteger.ZERO,tokens("USDC",payments.payment(failed).recipient()));

                long depositsBefore=jdbc.queryForObject("select count(*) from custody_deposit",Long.class);
                long requestsBefore=jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Long.class);
                heights.upsert("NEAR","near-block-scanner",start,start);scanner.scanAndCredit();heights.upsert("NEAR","near-block-scanner",start,start);scanner.scanAndCredit();
                assertEquals(depositsBefore,jdbc.queryForObject("select count(*) from custody_deposit",Long.class));
                current=lost;assertTrue(service.recover(lost));assertFalse(service.confirmWithdrawal(mpc.tenant,lost.reference()));
                assertEquals(requestsBefore,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Long.class));
                writeProof();
            }
        } catch(Exception | AssertionError failure) {
            try {captureCurrentRegistration();}catch(Exception ignored) { }
            throw failure;
        } finally {if(context!=null)context.close();mpc.close();}
    }
    private void openContext() {
        if(context!=null)context.close();context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class,()->mpc.manager);
        context.registerBean(WithdrawalOrderRepository.class,()->new WithdrawalOrderRepository(jdbc));context.registerBean(CollectionRecordRepository.class,()->new CollectionRecordRepository(jdbc));
        context.registerBean(NearTransactionRepository.class,()->new NearTransactionRepository(jdbc));context.registerBean(AccountSequenceRepository.class,()->new AccountSequenceRepository(jdbc));
        context.registerBean(WalletMpcSignRequestRepository.class,()->mpc.requests);context.registerBean(ChainAddressRepository.class,()->new ChainAddressRepository(jdbc));context.registerBean(ChainProfileRepository.class,()->new ChainProfileRepository(jdbc));
        context.registerBean(CustodyGasUsageRepository.class,()->new CustodyGasUsageRepository(jdbc));context.registerBean(CustodyGasAccountRepository.class,()->new CustodyGasAccountRepository(jdbc));context.registerBean(CustodyAddressRepository.class,()->new CustodyAddressRepository(jdbc));
        context.registerBean(TokenConfigRepository.class,()->new TokenConfigRepository(jdbc));context.registerBean(LedgerBalanceRepository.class,()->new LedgerBalanceRepository(jdbc));
        context.registerBean(CustodyGasAccountingService.class,()->CustodyTestSupport.gasAccounting(jdbc));context.registerBean(ObjectMapper.class,()->json);
        context.registerBean(MpcSigningService.class,()->new MpcSigningService(new CustodyTenantTableRepository(jdbc),new WalletMpcKeyRepository(jdbc),new WalletMpcKeyEpochRepository(jdbc,json),mpc.requests,new WalletMpcSignAttemptRepository(jdbc,json),mpc.crypto,json,mpc.manager));
        context.registerBean(MpcNodes.class,()->quorum);context.registerBean(NearRpcClient.class,()->rpc);context.registerBean(WalletRuntimeConfigService.class,()->new WalletRuntimeConfigService(new ChainProfileRepository(jdbc),new WalletSystemConfigRepository(jdbc)));
        context.registerBean(WithdrawalSettlementService.class);context.registerBean(NearSubmissionService.class);context.registerBean(NearRegistrationService.class);context.registerBean(NearTransactionService.class);context.refresh();
        payments=context.getBean(NearSubmissionService.class);registrations=context.getBean(NearRegistrationService.class);service=context.getBean(NearTransactionService.class);
    }
    private void configureRpcGate() {
        doAnswer(call->{
            var answer=(JsonNode)call.callRealMethod();
            if(staleQuote && "storage_balance_bounds".equals(call.getArgument(1,String.class))) {
                var changed=(ObjectNode)answer.deepCopy();changed.put("min",new BigInteger(changed.path("min").asText()).subtract(BigInteger.ONE).toString());return changed;
            }
            return answer;
        }).when(rpc).viewFunctionJson(anyString(),anyString(),any(byte[].class));
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());String wire=call.getArgument(0,String.class);
            byte[] bytes=Base64.getDecoder().decode(wire);String hash=Base58.encode(java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes,bytes.length-65)));
            var row=new NearTransactionRepository(jdbc).find(hash).orElseThrow();assertEquals(wire,json.readValue(row.getRawPayload(),MpcNearTransactionCodec.Signed.class).signedTransactionBase64());
            var child=registrations.intent(current);boolean registration=child.isPresent() && MpcNearTransactionCodec.transactionHash(registrations.payload(current).unsigned()).equals(hash);
            var request=registration?child.orElseThrow():payments.intent(current).orElseThrow();assertEquals(MpcSignRequest.State.COMPLETED,request.state());
            assertEquals("PREPARED",row.getStatus());
            if(registration) {
                assertTrue(payments.intent(current).isEmpty());assertEquals("RESERVED",new CustodyGasUsageRepository(jdbc).find(mpc.tenant,"ADDRESS_PREPARATION",request.id()).orElseThrow().status());
                if(registrationRace) {registrationRace=false;var p=registrations.payload(current);fixtureCall("register",p.unsigned().contract(),p.unsigned().beneficiary());}
            } else assertEquals(hash,current.kind()==Kind.WITHDRAWAL?new WithdrawalOrderRepository(jdbc).findTxHash("NEAR",current.reference(),mpc.tenant).orElseThrow():new CollectionRecordRepository(jdbc).findTxHash(mpc.tenant,"NEAR",current.reference()).orElseThrow());
            var answer=(JsonNode)call.callRealMethod();assertEquals(hash,answer.path("transaction").path("hash").asText());broadcasts.putIfAbsent(hash,registration?"REGISTRATION":"PAYMENT");
            if(registration && loseReply) {loseReply=false;throw new IllegalStateException("deliberately lost actual accepted registration response");}return answer;
        }).when(rpc).sendTransaction(anyString());
    }
    private Operation withdrawal(String symbol,String recipient) {
        var op=new Operation(mpc.tenant,"near-storage-withdrawal-"+UUID.randomUUID(),Kind.WITHDRAWAL);operations.add(op);
        var orders=new WithdrawalOrderRepository(jdbc);new TransactionTemplate(mpc.manager).executeWithoutResult(status->{
            orders.createForTenant(mpc.tenant,op.reference(),source.getUserId(),"NEAR",symbol,source.getAddress(),source.getAccountId(),recipient,BigDecimal.ONE,BigDecimal.ZERO);
            assertTrue(new LedgerBalanceRepository(jdbc).freeze("NEAR",symbol,source.getAccountId(),BigDecimal.ONE,mpc.tenant));orders.updateStatus(mpc.tenant,"NEAR",op.reference(),"FROZEN",source.getAddress(),null,null);
            assertEquals(1,orders.claimSigningDurable(mpc.tenant,"NEAR",op.reference(),source.getAddress()));
        });return op;
    }
    private void disable(boolean disabled) {
        jdbc.update("update chain_profile set enabled=?,withdraw_enabled=?,collection_enabled=? where id=?",!disabled,!disabled,!disabled,profileId);
        jdbc.update("update token_config set enabled=? where chain='NEAR' and network='localnet'",!disabled);
        jdbc.update("update chain_address set enabled=? where id in (?,?)",!disabled,source.getId(),gas.getId());
    }
    private UUID custodyAddress(ChainAddressRecord address,String subject,int coordinate) {
        UUID id=UUID.randomUUID();jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,derivation_subject,derivation_child) values (?,?,?,'NEAR','localnet',?,?,'API',?,0)",id,mpc.tenant,address.getId(),address.getAddress(),subject,coordinate);return id;
    }
    private NearStorageReceipt.Outcome registrationOutcome(Operation op) {
        var p=registrations.payload(op);var tx=new NearTransactionRepository(jdbc).find(MpcNearTransactionCodec.transactionHash(p.unsigned())).orElseThrow();
        return NearStorageReceipt.verify(registrations.proof(op).orElseThrow(),p.unsigned(),json.readValue(tx.getRawPayload(),MpcNearTransactionCodec.Signed.class));
    }
    private void writeProof()throws Exception {
        assertEquals(7,broadcasts.size());assertEquals(7,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where state<>'COMPLETED'",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where locked_balance<>0",Integer.class));
        assertEquals(4,jdbc.queryForObject("select count(*) from custody_gas_usage where operation_type='ADDRESS_PREPARATION' and status='SETTLED'",Integer.class));
        assertEquals(4,jdbc.queryForObject("select count(*) from custody_ledger_entry where entry_type='ADDRESS_PREPARATION'",Integer.class));
        BigInteger registrationExpense=BigInteger.ZERO,paymentExpense=BigInteger.ZERO;var proofTransactions=new ArrayList<Map<String,Object>>();
        for(var entry:broadcasts.entrySet()) {
            var tx=new NearTransactionRepository(jdbc).find(entry.getKey()).orElseThrow();assertNotNull(tx.getExecutionReceipt());assertTrue(Set.of("CONFIRMED","FAILED").contains(tx.getStatus()));
            if("REGISTRATION".equals(entry.getValue()))registrationExpense=registrationExpense.add(tx.getFeeYocto());else paymentExpense=paymentExpense.add(tx.getFeeYocto());
            proofTransactions.add(Map.of("hash",entry.getKey(),"kind",entry.getValue(),"status",tx.getStatus(),"expenseYocto",tx.getFeeYocto().toString()));
        }
        assertEquals(YOCTO.multiply(BigInteger.TEN).subtract(registrationExpense),realRpc.accountBalanceYocto(gas.getAddress()));
        assertEquals(realRpc.accountBalanceYocto(gas.getAddress()),balance("NEAR",gas).getTotalBalance().movePointRight(24).toBigIntegerExact());
        assertEquals(YOCTO.multiply(BigInteger.valueOf(20)).subtract(registrationExpense).subtract(paymentExpense),realRpc.accountBalanceYocto(source.getAddress()).add(realRpc.accountBalanceYocto(gas.getAddress())));
        for(String symbol:List.of("USDT","USDC")) {assertAmount("9",balance(symbol,source).getTotalBalance());assertEquals(BigInteger.valueOf(9_000_000),tokens(symbol,source.getAddress()).add(tokens(symbol,gas.getAddress())));}
        var wanted=pair.stream().map(mpc.nodes.parties()::get).toList();
        for(String request:jdbc.queryForList("select request::text from wallet_mpc_sign_attempt",String.class)) {
            var online=new ArrayList<String>();for(var id:json.readTree(request).path("online"))online.add(id.asText());assertEquals(wanted,online);
        }
        var proofs=new ArrayList<Map<String,Object>>();for(var op:operations)proofs.add(Map.of("payload",registrations.payload(op),"proof",registrations.proof(op).orElseThrow()));
        var proof=new LinkedHashMap<String,Object>();proof.put("passed",true);proof.put("pair",pair);proof.put("transactions",proofTransactions);proof.put("registrationExpenseYocto",registrationExpense.toString());proof.put("paymentExpenseYocto",paymentExpense.toString());
        proof.put("registrationProofs",proofs);proof.put("lostAcceptedReplyRecovered",true);proof.put("disabledRecovery",true);proof.put("principalRefundOnce",true);proof.put("gasLedgerEqualsPhysical",true);proof.put("internalRescanNoCredit",true);
        Files.writeString(root.resolve("near-storage-public.json"),json.writeValueAsString(proof));
    }
    private com.surprising.wallet.chain.model.LedgerBalanceRecord balance(String symbol,ChainAddressRecord address) {
        return new LedgerBalanceRepository(jdbc).find(mpc.tenant,"NEAR",symbol,address.getAccountId())
                .orElseThrow(()->new AssertionError("missing actual funding ledger for "+symbol+" / "+address.getAddress()));
    }
    private void captureCurrentRegistration()throws Exception {
        if(current==null || registrations==null || registrations.intent(current).isEmpty())return;
        var p=registrations.payload(current);String hash=MpcNearTransactionCodec.transactionHash(p.unsigned());
        var row=new NearTransactionRepository(jdbc).find(hash).orElseThrow();if(row.getRawPayload()==null)return;
        var execution=realRpc.finalTransaction(hash,p.unsigned().sender());var headers=new HashMap<String,JsonNode>();var receipts=new HashMap<String,JsonNode>();
        String block=execution.path("transaction_outcome").path("block_hash").asText();headers.put(block,realRpc.block(block).path("header"));
        for(var item:execution.path("receipts_outcome")) {String id=item.path("id").asText();receipts.put(id,realRpc.receipt(id));block=item.path("block_hash").asText();headers.put(block,realRpc.block(block).path("header"));}
        Files.writeString(Path.of("/tmp/surprising-wallet-mpc-near-storage-latest-final-public.json"),json.writeValueAsString(Map.of("payload",p,
                "selected",json.readValue(row.getRawPayload(),MpcNearTransactionCodec.Signed.class),"proof",new NearStorageReceipt.Proof(execution,headers,receipts))));
    }
    private BigInteger tokens(String symbol,String owner) {
        return new BigInteger(realRpc.viewFunctionJson(fixture.path(symbol).asText(),"ft_balance_of",json.writeValueAsBytes(json.createObjectNode().put("account_id",owner))).asText());
    }
    private void fixtureCall(String command,String...args)throws Exception {
        var argv=new ArrayList<String>(List.of("node",projectRoot().resolve("resources/infra/mpc-signer/tests/near_fixture.cjs").toString(),fixturePath.toString(),command));argv.addAll(List.of(args));
        var output=Files.createTempFile("surprising-wallet-mpc-near-storage-admin-",".json");var builder=new ProcessBuilder(argv);
        builder.environment().put("NODE_PATH",fixture.path("nodeModules").asText());
        var process=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("isolated NEAR fixture timed out; public log: "+output);}
        assertEquals(0,process.exitValue(),"isolated NEAR fixture command failed; public log: "+output);
        assertTrue(json.readTree(Files.readAllBytes(output)).path("status").has("SuccessValue"));
    }
    private void await(String name)throws Exception {
        Instant deadline=Instant.now().plusSeconds(90);while(!Files.isRegularFile(root.resolve(name)) && Instant.now().isBefore(deadline))Thread.sleep(50);
        assertTrue(Files.isRegularFile(root.resolve(name)),"native quorum driver did not acknowledge "+name);
        var ack=json.readTree(Files.readString(root.resolve(name)));var actual=new ArrayList<Integer>();for(var item:ack.path("pair"))actual.add(item.asInt());assertEquals(pair,actual);assertFalse(pair.contains(ack.path("absent").asInt()));
    }
    private static Path projectRoot(){var p=Path.of("").toAbsolutePath();while(p!=null && !Files.isDirectory(p.resolve("resources/infra/mpc-signer")))p=p.getParent();return Objects.requireNonNull(p);}
    private static String recipient(){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void assertAmount(String value,BigDecimal actual){assertEquals(0,new BigDecimal(value).compareTo(actual));}
}
