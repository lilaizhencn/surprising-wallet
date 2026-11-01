package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.testutil.RuntimeTestSupport;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyWithdrawalCreationIntegrationTest {
    private CountingJdbc jdbc;
    private AnnotationConfigApplicationContext context;
    private CustodyWithdrawalService service;
    private CustodyTenantChainService tenantChains;
    private CustodyGasService gas;
    private com.surprising.wallet.gateway.WithdrawalApprovalClient authority;
    private com.surprising.wallet.service.CustodyGasReconciliationService gasProjection;
    private boolean rejectGas;
    private final UUID tenant = UUID.randomUUID();
    private final UUID custodyId = UUID.randomUUID();
    private final String address = "0x" + "12".repeat(20);

    @BeforeEach void setup() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new CountingJdbc(dataSource);
        jdbc.update("update chain_profile set enabled=true, withdraw_enabled=true where chain='ETH' and network='sepolia'");
        jdbc.update("insert into custody_tenant(id,slug,name,derivation_namespace) values (?,?,'Withdrawal fixture',nextval('custody_derivation_namespace_seq'))",
                tenant, "withdraw-test-" + tenant);
        long chainAddressId = jdbc.queryForObject("""
                insert into chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,
                    address,owner_address,derivation_path,wallet_role,enabled)
                values (?,'ETH','ETH','withdraw-account',1,1,0,?,?,'m/44/60/1/1/0','DEPOSIT',true) returning id
                """, Long.class, tenant, address, address);
        jdbc.update("""
                insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,
                    status,derivation_subject,derivation_child)
                values (?,?,?,'ETH','sepolia',?,'withdraw-test','API','ACTIVE',1,0)
                """, custodyId, tenant, chainAddressId, address);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','withdraw-account',5,0,5)", tenant);
        var runtime = mock(BlockchainRuntimeService.class);
        when(runtime.checkAddress(eq("ETH"), anyString())).thenReturn(true);
        var planning = mock(CollectionPlanningService.class);
        when(planning.destination(tenant, "ETH")).thenReturn(Optional.empty());
        var properties = new CustodySecurityProperties(); properties.setSecretMasterKey("33".repeat(32));
        var mapper = new CustodyJacksonConfiguration().custodyObjectMapper();
        var execution = new CustodyWithdrawalExecutionService(new WithdrawalOrderRepository(jdbc), planning,
                RuntimeTestSupport.database(jdbc), runtime, new ChainAssetRepository(jdbc),
                new ChainProfileRepository(jdbc), new TokenConfigRepository(jdbc), new LedgerBalanceRepository(jdbc), CustodyTestSupport.poolFunding(jdbc));
        tenantChains = mock(CustodyTenantChainService.class);
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        gas = mock(CustodyGasService.class, invocation -> {
            if (rejectGas && invocation.getMethod().getName().equals("reserveWithdrawal")) throw new IllegalStateException("synthetic Gas reservation failure");
            if(invocation.getMethod().getName().equals("reserveWithdrawal")) return new com.surprising.wallet.model.custody.GasUsageRecord(
                    UUID.randomUUID(),tenant,UUID.randomUUID(),"WITHDRAWAL",invocation.getArgument(1),"fixture-order","ETH","ETH",new BigDecimal("0.01"),null,"RESERVED","TEST",null,null,java.time.Instant.now(),java.time.Instant.now(),null);
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        context.registerBean(WithdrawalSettlementService.class, () -> new WithdrawalSettlementService(new WithdrawalOrderRepository(jdbc), new LedgerBalanceRepository(jdbc)));
        authority=mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class);
        gasProjection=mock(CustodyGasReconciliationService.class);
        context.registerBean(CustodyWithdrawalReconciliationService.class,()->new CustodyWithdrawalReconciliationService(new CustodyWithdrawalRepository(jdbc),new WithdrawalOrderRepository(jdbc),
                new CustodyAddressRepository(jdbc),new CustodyLedgerEntryRepository(jdbc),gasProjection,CustodyTestSupport.events(jdbc),mapper,context.getBean(PlatformTransactionManager.class)));
        context.registerBean(WithdrawalApprovalService.class,()->new WithdrawalApprovalService(new WalletWithdrawalApprovalRepository(jdbc,mapper),new WithdrawalOrderRepository(jdbc),
                new LedgerBalanceRepository(jdbc),new CustodyAuditRepository(jdbc),context.getBean(CustodyWithdrawalReconciliationService.class),authority,mapper,context.getBean(PlatformTransactionManager.class),
                new UnsignedWithdrawalCancellationService(new WalletMpcSignRequestRepository(jdbc),new WalletMpcSignAttemptRepository(jdbc,mapper),
                    new ChainSigningTransactionRepository(jdbc,mapper),new UtxoRepository(jdbc),new EvmWithdrawalBatchRepository(jdbc),
                    new EvmWithdrawalBatchItemRepository(jdbc),new EvmWithdrawalBatchAttemptRepository(jdbc),new WithdrawalOrderRepository(jdbc),
                    new AccountSequenceRepository(jdbc),new EvmNonceRepository(jdbc),new CustodyGasUsageRepository(jdbc),CustodyTestSupport.gasAccounting(jdbc),new CustodyAuditRepository(jdbc),mapper)));

        context.registerBean(MpcAuthorizationService.class,()->com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,mapper,context.getBean(PlatformTransactionManager.class),authority));
        context.registerBean(MpcSigningService.class,()->new MpcSigningService(new CustodyTenantTableRepository(jdbc),new WalletMpcKeyRepository(jdbc),new WalletMpcKeyEpochRepository(jdbc,mapper),
                new WalletMpcSignRequestRepository(jdbc),new WalletMpcSignAttemptRepository(jdbc,mapper),new CustodyCryptoService(properties),mapper,
                context.getBean(PlatformTransactionManager.class),context.getBean(MpcAuthorizationService.class)));
        context.registerBean(CustodyWithdrawalService.class, () -> new CustodyWithdrawalService(execution,
                gas, tenantChains, new CustodyCryptoService(properties),
                mapper, CustodyTestSupport.events(jdbc), new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new CustodyTenantTableRepository(jdbc), new ChainAddressRepository(jdbc), new CustodyReorgDeficitRepository(jdbc),
                new CustodyIdempotencyRepository(jdbc), new CustodyWithdrawalRepository(jdbc), new CustodyAuditRepository(jdbc), CustodyTestSupport.poolFunding(jdbc), new WalletWithdrawalApprovalRepository(jdbc,mapper)));
        context.refresh(); service = context.getBean(CustodyWithdrawalService.class);
        jdbc.selects.clear();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void missingReceiptCanNeverTurnAnAcceptedBusinessOrderIntoARefusal() {
        var original=create("127.0.0.1");
        jdbc.update("delete from custody_idempotency_key where tenant_id=? and idempotency_key='withdrawal-test-request'",tenant);
        assertThrows(IllegalStateException.class,()->service.resolve(CustodyTestSupport.tenantAdmin(tenant),command(),"withdrawal-test-request","127.0.0.1"));
        assertEquals(0,count("custody_idempotency_key"));
        assertEquals(1,count("custody_withdrawal"));
        assertNotNull(new WithdrawalOrderRepository(jdbc).find("ETH",original.orderNo(),tenant).orElseThrow());
    }
    private WalletWithdrawalApprovalRepository approvalRows() {
        return new WalletWithdrawalApprovalRepository(jdbc,new CustodyJacksonConfiguration().custodyObjectMapper());
    }
    private com.surprising.wallet.common.approval.ApprovalRequest independent(UUID withdrawal,String state,boolean started) {
        var i=approvalRows().find(tenant,withdrawal).orElseThrow().intent();
        var asset=new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("ETH","sepolia","ETH","ETH","eip155:11155111",null,18,18,new BigDecimal("0.02"));
        return new com.surprising.wallet.common.approval.ApprovalRequest(i,asset.bind(i,new CustodyJacksonConfiguration().custodyObjectMapper()),asset,UUID.randomUUID(),1,
                List.of("ALL_WITHDRAWALS"),1,state,started,java.time.Instant.now(),java.time.Instant.now().plusSeconds(3600));
    }
    @Test void durableAdmissionIncludesBothSourcesAndCannotBeRewrittenOrDeleted() {
        var principal=CustodyTestSupport.tenantAdmin(tenant);
        for(String source:List.of("API","CONSOLE")) {
            var command=command();
            command=new CustodyWithdrawalService.CreateWithdrawalCommand(command.custodyAddressId(),command.chain(),command.assetSymbol(),command.toAddress(),command.amount(),"trace-"+source,true,command.subject(),command.fundingSource());
            var accepted=service.create(principal,command,source,"admission-"+source,"127.0.0.1");
            var row=approvalRows().find(tenant,accepted.id()).orElseThrow();
            assertEquals("QUEUED",row.state());assertEquals(source,row.intent().source());assertEquals(principal.actorId(),row.intent().initiatorId());
            assertEquals(accepted.externalReference(),row.intent().externalReference());assertEquals("ADDRESS",row.intent().fundingSource());
            assertEquals(accepted.orderNo(),row.intent().businessReference());assertEquals(accepted.amount(),row.intent().amount());
            assertEquals(0,new BigDecimal("0.01").compareTo(row.intent().maximumNetworkFee()));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("update wallet_withdrawal_approval set intent='{}' where withdrawal_id=?",accepted.id()));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("delete from wallet_withdrawal_approval where withdrawal_id=?",accepted.id()));
        }
    }
    @Test void independentAdmissionOccursAfterCommitAndApprovalReleasesOnlyPreparationHold() {
        var accepted=create("127.0.0.1");var pending=independent(accepted.id(),"PENDING",false);
        when(authority.submit(any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));assertEquals(1,count("wallet_withdrawal_approval"));return pending;
        });
        var approvals=context.getBean(WithdrawalApprovalService.class);
        assertEquals("PENDING",approvals.reconcile(tenant,accepted.id()).state());
        assertEquals("PENDING_REVIEW",new WithdrawalOrderRepository(jdbc).find("ETH",accepted.orderNo(),tenant).orElseThrow().getStatus());
        when(authority.status(tenant,accepted.id())).thenReturn(independent(accepted.id(),"APPROVED",false));
        assertEquals("APPROVED",approvals.reconcile(tenant,accepted.id()).state());
        assertEquals("FROZEN",new WithdrawalOrderRepository(jdbc).find("ETH",accepted.orderNo(),tenant).orElseThrow().getStatus());
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));assertEquals("FROZEN",create("127.0.0.1").status());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_event where event_type='WITHDRAWAL.CREATED'",Integer.class));
    }
    @Test void independentRejectionReleasesPrincipalAndProjectsNormalFailureOnce() {
        var accepted=create("127.0.0.1");when(authority.submit(any())).thenReturn(independent(accepted.id(),"REJECTED",false));
        var approvals=context.getBean(WithdrawalApprovalService.class);
        assertEquals("REJECTED",approvals.reconcile(tenant,accepted.id()).state());
        assertEquals("REJECTED",approvals.reconcile(tenant,accepted.id()).state());
        assertEquals(0,new BigDecimal("5").compareTo(balance("available_balance")));assertEquals(0,BigDecimal.ZERO.compareTo(balance("locked_balance")));
        assertEquals("FAILED",jdbc.queryForObject("select status from custody_withdrawal where id=?",String.class,accepted.id()));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_event where event_type='WITHDRAWAL.FAILED'",Integer.class));
        verify(gasProjection,times(1)).reconcileWithdrawal(tenant,accepted.id());verify(authority,times(1)).submit(any());
        for(String payload:jdbc.queryForList("select payload::text from custody_event",String.class))assertFalse(payload.contains("REJECTED") || payload.contains("review"));
    }
    @Test void terminalNoGrantReceiptCancelsPreparedNativeRequestAndReturnsOnlyItsNonce() {
        var request=preparedMpcWithdrawal();var approval=approvalRows().findByBusiness(tenant,"ETH",request.command().businessReference()).orElseThrow();
        when(authority.status(tenant,approval.withdrawalId())).thenReturn(independent(approval.withdrawalId(),"EXPIRED",false));
        context.getBean(WithdrawalApprovalService.class).reconcile(tenant,approval.withdrawalId());
        assertEquals(com.surprising.wallet.model.mpc.MpcSignRequest.State.CANCELLED,new WalletMpcSignRequestRepository(jdbc).find(tenant,request.id()).orElseThrow().state());
        assertNull(jdbc.queryForObject("select pending_owner from evm_nonce where pending_owner is null",String.class));
        assertEquals(java.math.BigInteger.ONE,jdbc.queryForObject("select reserved_nonce from evm_nonce",BigDecimal.class).toBigIntegerExact());
        assertEquals(0,new BigDecimal("5").compareTo(balance("available_balance")));assertEquals(0,BigDecimal.ZERO.compareTo(balance("locked_balance")));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update wallet_mpc_sign_request set state='PENDING',cancellation_receipt=null,cancelled_at=null where id=?",request.id()));
        assertThrows(com.surprising.wallet.model.mpc.MpcPendingException.class,()->context.getBean(MpcAuthorizationService.class).authorize(tenant,request.id(),request.currentAttemptId()));
        context.getBean(WithdrawalApprovalService.class).reconcile(tenant,approval.withdrawalId());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_event where event_type='WITHDRAWAL.FAILED'",Integer.class));
    }
    @Test void contradictoryNoGrantReceiptCannotRefundAnAlreadyAuthorizedRequest() throws Exception {
        var request=preparedMpcWithdrawal();var issuer=issuer();
        when(authority.authorize(any())).thenAnswer(call->grant(call.getArgument(0),issuer,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),false));
        context.getBean(MpcAuthorizationService.class).authorize(tenant,request.id(),request.currentAttemptId());
        var approval=approvalRows().findByBusiness(tenant,"ETH",request.command().businessReference()).orElseThrow();
        when(authority.status(tenant,approval.withdrawalId())).thenReturn(independent(approval.withdrawalId(),"EXPIRED",false));
        context.getBean(WithdrawalApprovalService.class).reconcile(tenant,approval.withdrawalId());
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
        assertNotEquals(com.surprising.wallet.model.mpc.MpcSignRequest.State.CANCELLED,new WalletMpcSignRequestRepository(jdbc).find(tenant,request.id()).orElseThrow().state());
        assertEquals(0,jdbc.queryForObject("select count(*) from custody_event where event_type='WITHDRAWAL.FAILED'",Integer.class));
    }
    @Test void rejectionAuditFailureRollsBackRefundStateAndEventTogether() {
        var accepted=create("127.0.0.1");when(authority.submit(any())).thenReturn(independent(accepted.id(),"EXPIRED",false));
        jdbc.execute("alter table custody_audit_log rename to audit_unavailable");
        try { assertThrows(RuntimeException.class,()->context.getBean(WithdrawalApprovalService.class).reconcile(tenant,accepted.id())); }
        finally { jdbc.execute("alter table audit_unavailable rename to custody_audit_log"); }
        assertEquals("QUEUED",approvalRows().find(tenant,accepted.id()).orElseThrow().state());
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));assertEquals("FROZEN",jdbc.queryForObject("select status from custody_withdrawal where id=?",String.class,accepted.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from custody_event where event_type='WITHDRAWAL.FAILED'",Integer.class));
    }
    @Test void independentServiceFailureNeverReleasesFrozenFundsOrLosesOriginalIntent() {
        var accepted=create("127.0.0.1");when(authority.submit(any())).thenThrow(new IllegalStateException("unavailable"));
        context.getBean(WithdrawalApprovalService.class).reconcile();
        assertEquals("QUEUED",approvalRows().find(tenant,accepted.id()).orElseThrow().state());
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));assertEquals(0,jdbc.queryForObject("select count(*) from custody_event where event_type='WITHDRAWAL.FAILED'",Integer.class));
    }
    private com.surprising.wallet.model.mpc.MpcSignRequest preparedMpcWithdrawal() {
        var accepted=create("127.0.0.1");when(authority.submit(any())).thenReturn(independent(accepted.id(),"APPROVED",false));
        context.getBean(WithdrawalApprovalService.class).reconcile(tenant,accepted.id());
        var json=new CustodyJacksonConfiguration().custodyObjectMapper();var signing=context.getBean(MpcSigningService.class);
        int namespace=new CustodyTenantTableRepository(jdbc).findById(tenant).orElseThrow().derivationNamespace();
        var parties=List.of("a".repeat(64),"b".repeat(64),"c".repeat(64));
        var key=signing.reserveKey(tenant,com.surprising.wallet.common.chain.ChainType.ETH,7,namespace,0,parties);
        new WalletMpcKeyEpochRepository(jdbc,json).observations(tenant,key.id(),1,com.surprising.wallet.model.mpc.MpcKeyEpoch.State.ACTIVE,Map.of());
        new WalletMpcKeyRepository(jdbc).activate(tenant,key.id(),1,"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
        stubKeyRegistration(json);
        context.getBean(MpcAuthorizationService.class).registerKey(tenant,key.id());
        var raw=org.web3j.crypto.RawTransaction.createEtherTransaction(java.math.BigInteger.ONE,java.math.BigInteger.valueOf(1_000_000_000),java.math.BigInteger.valueOf(21000),
                accepted.toAddress(),java.math.BigInteger.TEN.pow(18));
        var payload=com.surprising.wallet.common.signing.EvmSigningPayload.of(raw,11155111,"ETH",null,BigDecimal.ONE,18,18);
        var command=new com.surprising.wallet.common.signing.MpcSignCommand(tenant,key.id(),1,com.surprising.wallet.common.chain.ChainType.ETH,
                com.surprising.wallet.common.signing.MpcSignCommand.BusinessType.WITHDRAWAL,accepted.orderNo(),"transaction",com.surprising.wallet.common.signing.MpcSignCommand.Purpose.TRANSACTION,
                "EVM_TX_JSON",java.util.HexFormat.of().formatHex(json.writeValueAsBytes(payload)),java.util.HexFormat.of().formatHex(com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec.digest(raw,11155111)));
        return new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status->{
            String sender="0x"+org.web3j.crypto.Keys.getAddress(org.web3j.crypto.Sign.publicFromPoint(org.bitcoinj.crypto.ECKey.fromPublicOnly(
                    java.util.HexFormat.of().parseHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")).decompress().getPubKey()));
            new EvmNonceRepository(jdbc).reserve("ETH",sender,raw.getNonce(),command.reservationOwner());
            return signing.reserveSignature(command,parties.subList(0,2));
        });
    }
    private void stubKeyRegistration(tools.jackson.databind.ObjectMapper json) {
        when(authority.register(anyString(),any(),anyString())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var ceremony=(com.surprising.wallet.common.signing.MpcClient.Request)call.getArgument(1);
            return json.createObjectNode().put("tenantId",tenant.toString()).put("keyId",ceremony.keyId().toString()).put("epoch",ceremony.epoch())
                    .put("keyGroup",(String)call.getArgument(0)).put("publicKeyHex",(String)call.getArgument(2)).put("algorithm",ceremony.algorithm().name()).put("state","ACTIVE").putNull("publicAddress");
        });
    }
    private com.surprising.wallet.model.mpc.MpcKey unregisteredActiveFixtureKey() {
        var signing=context.getBean(MpcSigningService.class);var json=new CustodyJacksonConfiguration().custodyObjectMapper();
        int namespace=new CustodyTenantTableRepository(jdbc).findById(tenant).orElseThrow().derivationNamespace();
        var key=signing.reserveKey(tenant,com.surprising.wallet.common.chain.ChainType.ETH,42,namespace,0,List.of("a".repeat(64),"b".repeat(64),"c".repeat(64)));
        new WalletMpcKeyEpochRepository(jdbc,json).observations(tenant,key.id(),1,com.surprising.wallet.model.mpc.MpcKeyEpoch.State.ACTIVE,Map.of());
        new WalletMpcKeyRepository(jdbc).activate(tenant,key.id(),1,"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
        return new WalletMpcKeyRepository(jdbc).find(tenant,key.id()).orElseThrow();
    }
    @Test void keyCannotBePublishedOrRefreshedUntilIndependentRegistrationCommits() {
        var key=unregisteredActiveFixtureKey();var signing=context.getBean(MpcSigningService.class);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,()->transaction.execute(status->signing.lockActiveKey(tenant,key.id(),com.surprising.wallet.common.chain.ChainType.ETH)));
        assertThrows(IllegalStateException.class,()->signing.reserveRefresh(tenant,key.id(),1,List.of("a".repeat(64),"b".repeat(64),"c".repeat(64)),"operator","refresh original owned key"));
        verifyNoInteractions(authority);
    }
    @Test void cachedKeyRegistrationDoesNotNeedAuthorityOrThreeHoldersOnline() {
        var key=unregisteredActiveFixtureKey();var json=new CustodyJacksonConfiguration().custodyObjectMapper();stubKeyRegistration(json);
        var authorizations=context.getBean(MpcAuthorizationService.class);assertEquals(key,authorizations.registerKey(tenant,key.id()));
        doThrow(new IllegalStateException("authority or holder offline")).when(authority).register(anyString(),any(),anyString());
        assertEquals(key,authorizations.registerKey(tenant,key.id()));verify(authority,times(1)).register(anyString(),any(),anyString());
        assertTrue(new WalletMpcKeyEpochRepository(jdbc,json).registration(tenant,key.id(),1).isPresent());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update wallet_mpc_key_epoch set approval_registration=null where key_id=?",key.id()));
    }
    @Test void keyRegistryAuditFailureRollsBackRegistrationAndStillBlocksPublication() {
        var key=unregisteredActiveFixtureKey();var json=new CustodyJacksonConfiguration().custodyObjectMapper();stubKeyRegistration(json);
        jdbc.execute("alter table custody_audit_log rename to audit_unavailable");
        try { assertThrows(RuntimeException.class,()->context.getBean(MpcAuthorizationService.class).registerKey(tenant,key.id())); }
        finally { jdbc.execute("alter table audit_unavailable rename to custody_audit_log"); }
        assertTrue(new WalletMpcKeyEpochRepository(jdbc,json).registration(tenant,key.id(),1).isEmpty());
        context.getBean(MpcAuthorizationService.class).registerKey(tenant,key.id());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where action='MPC.KEY_REGISTER'",Integer.class));
    }
    private java.security.KeyPair issuer() throws Exception {
        var pair=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] publicKey=pair.getPublic().getEncoded();
        when(authority.verificationKeyHex()).thenReturn(java.util.HexFormat.of().formatHex(publicKey,publicKey.length-32,publicKey.length));return pair;
    }
    private com.surprising.wallet.gateway.WithdrawalApprovalClient.GrantResponse grant(com.surprising.wallet.common.approval.SigningCandidate candidate,
            java.security.KeyPair issuer,java.time.Instant issuedAt,boolean wrongBusiness) {
        var json=new CustodyJacksonConfiguration().custodyObjectMapper();var command=candidate.command();var execution=candidate.execution();
        var receipt=approvalRows().findByBusiness(tenant,command.chain().name(),command.businessReference()).orElseThrow().receipt();
        String hash=com.surprising.wallet.common.approval.ApprovalTransactionHash.business(List.of(receipt),command);
        var claims=new com.surprising.wallet.common.approval.SigningAuthorization.Claims(UUID.randomUUID(),candidate.signingRequestId(),tenant,command.keyId(),1,
                execution.sessionId(),"SIGN",execution.algorithm().name(),execution.online(),com.surprising.wallet.common.approval.SigningAuthorization.hash(java.util.HexFormat.of().parseHex(execution.messageHex())),
                "ETH","sepolia",wrongBusiness?"0".repeat(64):hash,"WITHDRAWAL",issuedAt,issuedAt.plusSeconds(120));
        var proof=com.surprising.wallet.common.approval.SigningAuthorization.sign(claims,issuer.getPrivate());
        return new com.surprising.wallet.gateway.WithdrawalApprovalClient.GrantResponse("AUTHORIZED",new com.surprising.wallet.common.approval.SigningGrant(claims.authorizationId(),tenant,candidate.signingRequestId(),execution.sessionId(),proof,issuedAt,claims.expiresAt()));
    }
    @Test void originalIndependentProofIsCommittedOnceAndRecoverableAfterExpiryWithoutAuthorityAvailability() throws Exception {
        var request=preparedMpcWithdrawal();var issuer=issuer();var attempts=new WalletMpcSignAttemptRepository(jdbc,new CustodyJacksonConfiguration().custodyObjectMapper());
        when(authority.authorize(any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return grant(call.getArgument(0),issuer,java.time.Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),false);
        });
        var authorizations=context.getBean(MpcAuthorizationService.class);var proof=authorizations.authorize(tenant,request.id(),request.currentAttemptId());
        var attempt=attempts.find(tenant,request.currentAttemptId()).orElseThrow();assertEquals(proof,attempt.authorization());
        assertThrows(IllegalArgumentException.class,()->com.surprising.wallet.common.approval.SigningAuthorization.verify(proof,issuer.getPublic(),attempt.request(),java.time.Instant.now()));
        doThrow(new IllegalStateException("authority offline")).when(authority).authorize(any());
        assertEquals(proof,authorizations.authorize(tenant,request.id(),request.currentAttemptId()));verify(authority,times(1)).authorize(any());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where action='MPC.AUTHORIZE'",Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update wallet_mpc_sign_attempt set authorization_proof=null where id=?",request.currentAttemptId()));
    }
    @Test void nativeSignOnlyReceivesTheAlreadyCommittedProofAndNeverUsesUnsignedSubmit() throws Exception {
        var request=preparedMpcWithdrawal();var issuer=issuer();var attempts=new WalletMpcSignAttemptRepository(jdbc,new CustodyJacksonConfiguration().custodyObjectMapper());
        when(authority.authorize(any())).thenAnswer(call->grant(call.getArgument(0),issuer,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),false));
        var execution=attempts.find(tenant,request.currentAttemptId()).orElseThrow().request();
        var clients=execution.online().stream().map(party->{
            var client=mock(com.surprising.wallet.common.signing.MpcClient.class);
            when(client.node()).thenReturn(new com.surprising.wallet.common.signing.MpcClient.Node(party,java.net.URI.create("https://localhost:1")));
            when(client.result(execution)).thenReturn(new com.surprising.wallet.common.signing.MpcClient.Result(com.surprising.wallet.common.signing.MpcClient.State.ABSENT,
                    execution.sessionId(),tenant,execution.keyId(),1,"a".repeat(64),null,null,null,null));
            when(client.submit(eq(execution),any(com.surprising.wallet.common.approval.SigningAuthorization.Proof.class))).thenAnswer(call->{
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                var committed=attempts.find(tenant,execution.sessionId()).orElseThrow();assertEquals(call.getArgument(1),committed.authorization());
                assertEquals(com.surprising.wallet.model.mpc.MpcSignAttempt.State.RUNNING,committed.state());
                return new com.surprising.wallet.common.signing.MpcClient.Result(com.surprising.wallet.common.signing.MpcClient.State.RUNNING,
                        execution.sessionId(),tenant,execution.keyId(),1,"a".repeat(64),null,null,null,null);
            });return client;
        }).toList();
        context.getBean(MpcSigningService.class).advanceSignature(tenant,request.id(),clients);
        for(var client:clients) { verify(client).submit(eq(execution),any(com.surprising.wallet.common.approval.SigningAuthorization.Proof.class));verify(client,never()).submit(execution); }
    }
    @Test void pendingOrInvalidIndependentGrantCannotContactNativeNodesOrPersistAuthorization() throws Exception {
        var request=preparedMpcWithdrawal();var issuer=issuer();var client=mock(com.surprising.wallet.common.signing.MpcClient.class);
        when(authority.authorize(any())).thenReturn(new com.surprising.wallet.gateway.WithdrawalApprovalClient.GrantResponse("PENDING",null));
        assertThrows(com.surprising.wallet.model.mpc.MpcPendingException.class,()->context.getBean(MpcSigningService.class).advanceSignature(tenant,request.id(),List.of(client)));verifyNoInteractions(client);
        when(authority.authorize(any())).thenAnswer(call->grant(call.getArgument(0),issuer,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),true));
        assertThrows(IllegalStateException.class,()->context.getBean(MpcSigningService.class).advanceSignature(tenant,request.id(),List.of(client)));verifyNoInteractions(client);
        assertNull(new WalletMpcSignAttemptRepository(jdbc,new CustodyJacksonConfiguration().custodyObjectMapper()).find(tenant,request.currentAttemptId()).orElseThrow().authorization());
    }
    @Test void proofAuditFailureLeavesNoAuthorizationAndNeverDispatchesNativeSigning() throws Exception {
        var request=preparedMpcWithdrawal();var issuer=issuer();var client=mock(com.surprising.wallet.common.signing.MpcClient.class);
        when(authority.authorize(any())).thenAnswer(call->grant(call.getArgument(0),issuer,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),false));
        jdbc.execute("alter table custody_audit_log rename to audit_unavailable");
        try { assertThrows(RuntimeException.class,()->context.getBean(MpcSigningService.class).advanceSignature(tenant,request.id(),List.of(client))); }
        finally { jdbc.execute("alter table audit_unavailable rename to custody_audit_log"); }
        verifyNoInteractions(client);var attempt=new WalletMpcSignAttemptRepository(jdbc,new CustodyJacksonConfiguration().custodyObjectMapper()).find(tenant,request.currentAttemptId()).orElseThrow();
        assertNull(attempt.authorization());assertEquals(com.surprising.wallet.model.mpc.MpcSignAttempt.State.PENDING,attempt.state());
    }
    @Test void explicitEvmAttemptRecoverySendsThePersistedOperatorAuditAndKeepsTheOriginalCommand() throws Exception {
        var request=preparedMpcWithdrawal();var issuer=issuer();var json=new CustodyJacksonConfiguration().custodyObjectMapper();
        var attempts=new WalletMpcSignAttemptRepository(jdbc,json);var requests=new WalletMpcSignRequestRepository(jdbc);
        var original=new java.util.concurrent.atomic.AtomicReference<com.surprising.wallet.common.approval.SigningCandidate>();
        when(authority.authorize(any())).thenAnswer(call->{original.set(call.getArgument(0));return grant(call.getArgument(0),issuer,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),false);});
        var authorizations=context.getBean(MpcAuthorizationService.class);authorizations.authorize(tenant,request.id(),request.currentAttemptId());
        attempts.observe(tenant,request.currentAttemptId(),com.surprising.wallet.model.mpc.MpcSignAttempt.State.INDETERMINATE,Map.of());
        requests.recordState(tenant,request.id(),request.currentAttemptId(),com.surprising.wallet.model.mpc.MpcSignRequest.State.INDETERMINATE);
        var replay=context.getBean(MpcSigningService.class).replay(tenant,request.id(),List.of("a".repeat(64),"c".repeat(64)),"operator-7","recover original EVM signing after outage");
        assertEquals(request.command(),replay.command());authorizations.authorize(tenant,replay.id(),replay.currentAttemptId());
        assertEquals("operator-7",original.get().replayActor());assertEquals("recover original EVM signing after outage",original.get().replayReason());
        assertEquals(request.id(),original.get().signingRequestId());assertEquals(request.command(),original.get().command());
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
    }

    @Test void subStorageUnitCannotBeSilentlyRoundedToZeroOrFreezeDifferentPrincipal() {
        jdbc.update("update chain_asset set decimals=36 where chain='ETH' and symbol='ETH'");
        var command=new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId,"ETH","ETH",address,"0.0000000000000000000000001","too-small",true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
        assertThrows(IllegalArgumentException.class,()->service.create(CustodyTestSupport.tenantAdmin(tenant),command,"API","too-small","127.0.0.1"));
        assertEquals(0,count("withdrawal_order"));assertEquals(0,count("custody_withdrawal"));
        assertEquals(0,new BigDecimal("5").compareTo(balance("available_balance")));
    }

    @Test void creationReadsEachAddressOnceAndReturnsInsertedOrderIdWithoutRequery() {
        var created = create("127.0.0.1");
        assertEquals(1, jdbc.selects.getOrDefault("custody_address", 0));
        assertEquals(1, jdbc.selects.getOrDefault("chain_address", 0));
        assertEquals(0, jdbc.selects.getOrDefault("withdrawal_order", 0));
        var replay = create("127.0.0.1");
        assertEquals(created.id(), replay.id()); assertEquals(created.orderNo(), replay.orderNo());
        assertEquals(1, count("withdrawal_order")); assertEquals(1, count("custody_withdrawal"));
        assertEquals(0, new BigDecimal("4").compareTo(balance("available_balance")));
        assertEquals(0, BigDecimal.ONE.compareTo(balance("locked_balance")));
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_event where tenant_id=? and event_type='WITHDRAWAL.CREATED'", Integer.class, tenant));
    }

    @Test void internalApprovalHoldRemainsNormalInConsoleApiQueriesReceiptsAndEvents() {
        var principal=CustodyTestSupport.tenantAdmin(tenant);
        for(String source:List.of("API","CONSOLE")) {
            var original=command();
            var command=new CustodyWithdrawalService.CreateWithdrawalCommand(original.custodyAddressId(),original.chain(),original.assetSymbol(),
                    original.toAddress(),original.amount(),"external-review-"+source,true,original.subject(),original.fundingSource());
            var accepted=service.create(principal,command,source,"review-request-"+source,"127.0.0.1");
            assertEquals("FROZEN",accepted.status());assertNull(accepted.txHash());
            assertEquals("FROZEN",service.create(principal,command,source,"review-request-"+source,"127.0.0.1").status());
            var order=new WithdrawalOrderRepository(jdbc).find("ETH",accepted.orderNo(),tenant).orElseThrow();
            assertEquals("PENDING_REVIEW",order.getStatus());
            assertEquals(0,new WithdrawalOrderRepository(jdbc).claimSigning(tenant,"ETH",accepted.orderNo(),address));
        }
        var query=new CustodyTransactionQueryService(new CustodyDepositRepository(jdbc),new CustodyWithdrawalRepository(jdbc),new CustodyAddressRepository(jdbc),
                new WithdrawalOrderRepository(jdbc),new CustodyGasUsageRepository(jdbc));
        var page=query.withdrawalPage(principal,"ETH","ETH","FROZEN","",20,0);
        assertEquals(2,page.total());
        for(var row:page.items()) { assertEquals("FROZEN",row.status());assertNull(row.errorMessage());assertFalse(row.refundable()); }
        assertEquals("FROZEN",query.withdrawalSubmission(principal,"external-review-API").getFirst().status());
        assertEquals(0,new BigDecimal("2").compareTo(balance("locked_balance")));
        for(String payload:jdbc.queryForList("select payload::text from custody_event where tenant_id=?",String.class,tenant)) {
            assertFalse(payload.contains("PENDING_REVIEW"));assertFalse(payload.contains("waiting for platform"));assertTrue(payload.contains("FROZEN"));
        }
    }

    @Test void auditFailureRollsBackOrderFreezeCustodyEventAndIdempotencyTogether() {
        assertThrows(RuntimeException.class, () -> create("invalid-ip"));
        for (String table : List.of("withdrawal_order", "custody_withdrawal", "custody_event", "custody_idempotency_key")) assertEquals(0, count(table), table);
        assertEquals(0, new BigDecimal("5").compareTo(balance("available_balance")));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance("locked_balance")));
    }

    @Test void disabledInactiveAndForeignAddressGuardsRemainEffective() {
        jdbc.update("update chain_address set enabled=false where tenant_id=?", tenant);
        assertThrows(IllegalArgumentException.class, () -> create("127.0.0.1"));
        jdbc.update("update chain_address set enabled=true where tenant_id=?", tenant);
        jdbc.update("update custody_address set status='DISABLED' where id=?", custodyId);
        assertThrows(IllegalArgumentException.class, () -> create("127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> service.create(CustodyTestSupport.tenantAdmin(UUID.randomUUID()),
                command(), "API", "foreign-request", "127.0.0.1"));
        assertEquals(0, count("withdrawal_order")); assertEquals(0, count("custody_idempotency_key"));
        assertEquals(0, new BigDecimal("5").compareTo(balance("available_balance")));
    }

    @Test void acceptedRequestReplaysBeforeChangedAddressNetworkAndDeficitGuards() {
        var original=create("127.0.0.1");
        jdbc.update("update withdrawal_order set status='SENT',tx_hash=? where tenant_id=?","0x"+"ab".repeat(32),tenant);
        long deposit=jdbc.queryForObject("insert into deposit_record(tenant_id,chain,asset_symbol,tx_hash,to_address,amount,block_height,account_id) values (?,'ETH','ETH','reorg-fixture',?,1,1,'withdraw-account') returning id",Long.class,tenant,address);
        new CustodyReorgDeficitRepository(jdbc).open(tenant,custodyId,deposit,"ETH","ETH","withdraw-account",BigDecimal.ONE,BigDecimal.ONE);
        jdbc.update("update custody_address set status='DISABLED' where id=?",custodyId);
        doThrow(new com.surprising.wallet.exception.CustodyForbiddenException("network disabled"))
                .when(tenantChains).requireActive(tenant,"ETH");
        var replay=create("127.0.0.1");
        assertEquals(original.id(),replay.id()); assertEquals(1,count("withdrawal_order"));
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
        assertThrows(RuntimeException.class,()->service.create(CustodyTestSupport.tenantAdmin(tenant),command(),"API","different-request","127.0.0.1"));
        assertEquals(1,count("custody_idempotency_key"));
    }

    @Test void withdrawalReceiptSurvivesThirtyDaysAndCleanupWithoutAnotherFreeze() {
        var original=create("127.0.0.1");
        jdbc.update("update custody_idempotency_key set created_at=now()-interval '30 days' where tenant_id=?",tenant);
        assertNull(jdbc.queryForObject("select expires_at from custody_idempotency_key where tenant_id=?",Object.class,tenant));
        new CustodyIdempotencyRepository(jdbc).deleteExpired();
        assertEquals(original.id(),create("127.0.0.1").id());
        assertEquals(1,count("custody_withdrawal"));
        assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
    }

    @Test void refusalFencesThisIdentityPermanentlyAndCannotOverwriteAnAcceptedReceipt() {
        var principal=CustodyTestSupport.tenantAdmin(tenant);
        var refused=service.resolve(principal,command(),"withdrawal-test-request","127.0.0.1");
        assertEquals("REJECTED",refused.status());
        assertThrows(com.surprising.wallet.exception.CustodyWithdrawalRefusedException.class,()->create("127.0.0.1"));
        assertEquals(0,count("withdrawal_order")); assertEquals(0,balance("locked_balance").signum());
        assertEquals(0,new CustodyIdempotencyRepository(jdbc).complete(tenant,"withdrawal-test-request","WITHDRAWAL.CREATE",202,"{}"));
        assertEquals(422,jdbc.queryForObject("select response_status from custody_idempotency_key where tenant_id=?",Integer.class,tenant));
    }

    @Test void resolutionOfAnAcceptedRequestReturnsItsCommittedIdentity() {
        var original=create("127.0.0.1");
        var resolved=service.resolve(CustodyTestSupport.tenantAdmin(tenant),command(),"withdrawal-test-request","127.0.0.1");
        assertEquals("ACCEPTED",resolved.status()); assertEquals(original.id(),resolved.withdrawal().id());
        assertEquals(1,count("withdrawal_order")); assertEquals(0,BigDecimal.ONE.compareTo(balance("locked_balance")));
        var changed=new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId,"ETH","ETH",command().toAddress(),"2","external-test",true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
        assertThrows(IllegalStateException.class,()->service.resolve(CustodyTestSupport.tenantAdmin(tenant),changed,"withdrawal-test-request","127.0.0.1"));
        assertEquals(original.id(),create("127.0.0.1").id());
    }

    @Test void racingCreateAndResolveHaveOnlyOneCommittedDecision() throws Exception {
        jdbc.update("update ledger_balance set available_balance=100,total_balance=100 where tenant_id=?",tenant);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        int accepted=0;
        try {
            for(int index=0;index<10;index++) {
                var start=new java.util.concurrent.CountDownLatch(1);
                String key="racing-request-"+index;
                var command=new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId,"ETH","ETH",command().toAddress(),"1","race-"+index,true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
                var creation=executor.submit(()->{
                    start.await();
                    try { service.create(CustodyTestSupport.tenantAdmin(tenant),command,"API",key,"127.0.0.1"); return true; }
                    catch(com.surprising.wallet.exception.CustodyWithdrawalRefusedException refused) { return false; }
                });
                var resolution=executor.submit(()->{start.await(); return service.resolve(CustodyTestSupport.tenantAdmin(tenant),command,key,"127.0.0.1");});
                start.countDown();
                boolean created=creation.get(10,java.util.concurrent.TimeUnit.SECONDS);
                var decision=resolution.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(created ? "ACCEPTED" : "REJECTED",decision.status());
                if(created) accepted++;
            }
        } finally { executor.shutdownNow(); }
        assertEquals(accepted,count("withdrawal_order"));
        assertEquals(10,count("custody_idempotency_key"));
        assertEquals(0,BigDecimal.valueOf(accepted).compareTo(balance("locked_balance")));
    }

    @Test void exactSubjectFilteringHappensBeforeWithdrawalPagination() {
        var original=create("127.0.0.1");
        jdbc.update("update custody_address set subject='another-funding-owner' where id=?",custodyId);
        String foreignAddress="0x"+"56".repeat(20);
        long chainAddress=jdbc.queryForObject("insert into chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,address,owner_address,derivation_path,wallet_role,enabled) values (?,'ETH','ETH','foreign-account',2,1,0,?,?,'m/44/60/2/1/0','DEPOSIT',true) returning id",Long.class,tenant,foreignAddress,foreignAddress);
        UUID foreign=UUID.randomUUID();
        jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child) values (?,?,?,'ETH','sepolia',?,'user:420','API','ACTIVE',2,0)",foreign,tenant,chainAddress,foreignAddress);
        // Synthetic history rows only: no signer, scheduler or chain payment runs in this database.
        var orderRepo=new WithdrawalOrderRepository(jdbc); var custodyRepo=new CustodyWithdrawalRepository(jdbc);
        for(int index=0;index<60;index++) {
            String number="foreign-history-"+index;
            long id=orderRepo.createForTenant(tenant,number,2L,"ETH","ETH",foreignAddress,"foreign-account",command().toAddress(),BigDecimal.ONE,BigDecimal.ZERO);
            custodyRepo.insert(UUID.randomUUID(),tenant,foreign,id,number,number,number,"ETH","ETH",command().toAddress(),BigDecimal.ONE,BigDecimal.ZERO,"FROZEN","API_KEY","fixture","user:420","ADDRESS");
        }
        var query=new CustodyTransactionQueryService(new CustodyDepositRepository(jdbc),custodyRepo,new CustodyAddressRepository(jdbc),orderRepo,new CustodyGasUsageRepository(jdbc));
        var rows=query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant),"ETH","ETH","","","user:42","",2,0);
        assertEquals(1,rows.size()); assertEquals(original.id(),rows.getFirst().id());
        assertEquals("user:42",rows.getFirst().subject());
        var exact=query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant),"ETH","ETH","","","","external-test",2,0);
        assertEquals(1,exact.size()); assertEquals(original.id(),exact.getFirst().id());
    }


    @Test void tenantPoolPaysAUserWithNoDepositAddressAndPreservesItsIndependentIdentity() {
        var gasAddress = addPoolSource();
        var created = poolCreate("profit-withdrawal", "4", "user:43");
        assertEquals(gasAddress, created.custodyAddressId());
        assertEquals("user:43", created.subject());
        assertEquals(CustodyWithdrawalService.FundingSource.TENANT_POOL, created.fundingSource());
        var order = new WithdrawalOrderRepository(jdbc).find("ETH", created.orderNo(), tenant).orElseThrow();
        assertEquals(poolAccount(), order.getDebitAccountId());
        assertEquals("0x" + "78".repeat(20), order.getFromAddress());
        assertEquals(0, new BigDecimal("4").compareTo(poolBalance("locked_balance")));
        assertEquals(0, new BigDecimal("1").compareTo(principal("withdraw-account", "available_balance")));
        var query = new CustodyTransactionQueryService(new CustodyDepositRepository(jdbc), new CustodyWithdrawalRepository(jdbc),
                new CustodyAddressRepository(jdbc), new WithdrawalOrderRepository(jdbc), new CustodyGasUsageRepository(jdbc));
        assertEquals(created.id(), query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant), "ETH", "ETH", "", "", "user:43", "", 1, 0).getFirst().id());
        assertTrue(query.withdrawalsForSubject(CustodyTestSupport.tenantAdmin(tenant), "ETH", "ETH", "", "", "withdraw-test", "", 1, 0).isEmpty());
    }

    @Test void poolCombinesPrincipalAccountsButExcludesGasAndKeepsExistingLocks() {
        addPoolSource();
        jdbc.update("update ledger_balance set available_balance=2,locked_balance=1,total_balance=3 where tenant_id=? and account_id='withdraw-account'", tenant);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','second-principal',3,0,3)", tenant);
        poolCreate("combined-principal", "4", "user:43");
        assertEquals(0, BigDecimal.ONE.compareTo(principal("withdraw-account", "available_balance")));
        assertEquals(0, BigDecimal.ONE.compareTo(principal("withdraw-account", "locked_balance")));
        assertEquals(0, principal("second-principal", "available_balance").signum());
        assertEquals(0, new BigDecimal("100").compareTo(principal("pool-physical", "available_balance")));
        assertEquals(2, jdbc.queryForObject("select count(*) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_DEBIT'", Integer.class, tenant));
        assertEquals(0, new BigDecimal("4").compareTo(jdbc.queryForObject("select sum(amount) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_DEBIT'", BigDecimal.class, tenant)));
        assertEquals(0, new BigDecimal("4").compareTo(jdbc.queryForObject("select sum(amount) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_CREDIT'", BigDecimal.class, tenant)));
    }

    @Test void insufficientPoolNeverSpendsGasOrLeavesPartialDebitsAndReceipts() {
        addPoolSource();
        assertThrows(IllegalArgumentException.class, () -> poolCreate("pool-too-large", "6", "user:43"));
        assertEquals(0, new BigDecimal("5").compareTo(principal("withdraw-account", "available_balance")));
        assertEquals(0, new BigDecimal("100").compareTo(principal("pool-physical", "available_balance")));
        assertEquals(0, count("custody_ledger_entry"));
        assertEquals(0, count("custody_idempotency_key"));
        assertEquals(0, count("custody_withdrawal"));
        assertEquals(2, count("ledger_balance"));
    }

    @Test void poolFundingRollsBackWithTheAcceptanceGasFailure() {
        addPoolSource();
        rejectGas=true;
        assertThrows(IllegalStateException.class, () -> poolCreate("pool-gas-failure", "4", "user:43"));
        assertEquals(0, new BigDecimal("5").compareTo(principal("withdraw-account", "available_balance")));
        assertEquals(0, count("custody_ledger_entry"));
        assertEquals(0, count("withdrawal_order"));
        assertEquals(0, count("custody_idempotency_key"));
    }

    @Test void poolCannotAdoptAnUnownedLedgerAccount() {
        addPoolSource();
        jdbc.update("insert into ledger_balance(chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values ('ETH','ETH',?,100,0,100)", poolAccount());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> poolCreate("unowned-pool", "4", "user:43"));
        assertNull(jdbc.queryForObject("select tenant_id from ledger_balance where account_id=?", UUID.class, poolAccount()));
        assertEquals(0, count("custody_idempotency_key"));
    }

    @Test void parallelPoolWithdrawalsCannotReserveTheSameTenantPrincipalTwice() throws Exception {
        addPoolSource();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i=0; i<16; i++) {
                int id=i;
                tasks.add(() -> {
                    try { poolCreate("parallel-pool-"+id, "1", "user:"+(43+id)); return true; }
                    catch (IllegalArgumentException insufficient) {
                        assertTrue(insufficient.getMessage().contains("insufficient")); return false;
                    }
                });
            }
            int accepted=0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertEquals(5, accepted);
        } finally { executor.shutdownNow(); }
        assertEquals(5, count("custody_withdrawal"));
        assertEquals(5, count("custody_idempotency_key"));
        assertEquals(0, new BigDecimal("5").compareTo(poolBalance("locked_balance")));
        assertEquals(0, principal("withdraw-account", "available_balance").signum());
        assertEquals(0, new BigDecimal("100").compareTo(principal("pool-physical", "available_balance")));
    }

    @Test void failedPoolPrincipalReleasesOnceAndCanFundTheNextBeneficiary() {
        addPoolSource();
        var failed=poolCreate("pool-failed-first", "4", "user:43");
        String hash="0x"+"ab".repeat(32);
        jdbc.update("update withdrawal_order set status='SENT',tx_hash=? where order_no=?", hash, failed.orderNo());
        var settlements=context.getBean(WithdrawalSettlementService.class);
        assertTrue(settlements.fail(tenant,"ETH",failed.orderNo(),hash,"synthetic canonical failure"));
        assertFalse(settlements.fail(tenant,"ETH",failed.orderNo(),hash,"repeat"));
        assertEquals(0, new BigDecimal("4").compareTo(poolBalance("available_balance")));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select funds_released from withdrawal_order where order_no=?",Boolean.class,failed.orderNo()));
        var next=poolCreate("pool-after-refund", "4", "user:44");
        assertEquals(1, jdbc.queryForObject("select count(*) from custody_ledger_entry where tenant_id=? and entry_type='POOL_FUNDING_CREDIT'",Integer.class,tenant));
        jdbc.update("update withdrawal_order set status='SENT',tx_hash=? where order_no=?",hash,next.orderNo());
        assertTrue(settlements.confirm(tenant,"ETH",next.orderNo(),hash));
        assertFalse(settlements.confirm(tenant,"ETH",next.orderNo(),hash));
        assertEquals(0,poolBalance("total_balance").signum());
        assertEquals(0,BigDecimal.ONE.compareTo(principal("withdraw-account","total_balance")));
    }

    @Test void poolReplayKeepsBeneficiaryAndSurvivesNewAssetDeficits() {
        addPoolSource();
        var original=poolCreate("pool-stable-receipt", "1", "user:43");
        long deposit=jdbc.queryForObject("insert into deposit_record(tenant_id,chain,asset_symbol,tx_hash,to_address,amount,block_height,account_id) values (?,'ETH','ETH','pool-reorg',?,1,1,'withdraw-account') returning id",Long.class,tenant,address);
        new CustodyReorgDeficitRepository(jdbc).open(tenant,custodyId,deposit,"ETH","ETH","withdraw-account",BigDecimal.ONE,BigDecimal.ONE);
        assertEquals(original.id(),poolCreate("pool-stable-receipt","1","user:43").id());
        assertThrows(IllegalStateException.class,()->poolCreate("pool-stable-receipt","1","user:44"));
        assertThrows(IllegalStateException.class,()->poolCreate("pool-new-deficit","1","user:44"));
        assertEquals(1,count("custody_withdrawal"));
    }

    private UUID addPoolSource() {
        UUID id=UUID.randomUUID();
        String physical="0x"+"78".repeat(20);
        long chainAddress=jdbc.queryForObject("insert into chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,address,owner_address,derivation_path,wallet_role,enabled) values (?,'ETH','ETH','pool-physical',3,1,0,?,?,'m/44/60/3/1/0','GAS_FUNDING',true) returning id",Long.class,tenant,physical,physical);
        jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child) values (?,?,?,'ETH','sepolia',?,'system:gas','API','ACTIVE',3,0)",id,tenant,chainAddress,physical);
        jdbc.update("insert into custody_gas_account(id,tenant_id,custody_address_id,chain,network,native_symbol,low_balance_threshold,status) values (?,?,?,'ETH','sepolia','ETH',0,'ACTIVE')",UUID.randomUUID(),tenant,id);
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'ETH','ETH','pool-physical',100,0,100)",tenant);
        return id;
    }
    private CustodyWithdrawalService.WithdrawalView poolCreate(String reference,String amount,String subject) {
        var command=new CustodyWithdrawalService.CreateWithdrawalCommand(null,"ETH","ETH",command().toAddress(),amount,reference,true,subject,CustodyWithdrawalService.FundingSource.TENANT_POOL);
        return service.create(CustodyTestSupport.tenantAdmin(tenant),command,"API",reference,"127.0.0.1");
    }
    private String poolAccount() { return CustodyPoolFundingService.accountId(tenant,"ETH","ETH"); }
    private BigDecimal poolBalance(String column) { return principal(poolAccount(),column); }
    private BigDecimal principal(String account,String column) {
        return jdbc.queryForObject("select "+column+" from ledger_balance where tenant_id=? and account_id=?",BigDecimal.class,tenant,account);
    }

    private CustodyWithdrawalService.WithdrawalView create(String sourceIp) {
        return service.create(CustodyTestSupport.tenantAdmin(tenant), command(), "API", "withdrawal-test-request", sourceIp);
    }
    private CustodyWithdrawalService.CreateWithdrawalCommand command() {
        return new CustodyWithdrawalService.CreateWithdrawalCommand(custodyId, "ETH", "ETH", "0x" + "34".repeat(20), "1", "external-test", true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
    }
    private BigDecimal balance(String column) { return jdbc.queryForObject("select " + column + " from ledger_balance where tenant_id=?", BigDecimal.class, tenant); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Integer.class, tenant); }

    private static final class CountingJdbc extends JdbcTemplate {
        private final Map<String, Integer> selects = new java.util.concurrent.ConcurrentHashMap<>();
        CountingJdbc(DataSource dataSource) { super(dataSource); }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            String normalized = sql.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (normalized.startsWith("select ")) for (String table : List.of("custody_address", "chain_address", "withdrawal_order")) {
                if (normalized.contains(" from " + table + " ")) selects.merge(table, 1, Integer::sum);
            }
            return super.query(sql, mapper, args);
        }
    }
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }
}
