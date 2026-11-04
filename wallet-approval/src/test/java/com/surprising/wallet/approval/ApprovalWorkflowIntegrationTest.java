package com.surprising.wallet.approval;

import com.surprising.wallet.common.approval.SigningGrant;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.approval.service.ApprovalWorkflowService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SW_TEST_APPROVAL_DB_URL",matches=".+")
class ApprovalWorkflowIntegrationTest {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static com.sun.net.httpserver.HttpServer feeNode;
    private static final java.util.Map<String,java.math.BigInteger> nativeBalances=new java.util.concurrent.ConcurrentHashMap<>(),tokenBalances=new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicReference<String> incidentEvmCode=new AtomicReference<>("0x");
    private static final String INSTALLED_HOT_DELEGATE="0x5555555555555555555555555555555555555555";
    private static final AtomicReference<String> installedDelegateCode=new AtomicReference<>("0x6000");
    private static final AtomicReference<String> evmFollowupHash=new AtomicReference<>("0x"+"a".repeat(64));
    private static final AtomicReference<String> evmLatestNumber=new AtomicReference<>("0x42");
    private static final AtomicReference<String> evmLatestHash=new AtomicReference<>("0x"+"a".repeat(64));
    private static final AtomicReference<tools.jackson.databind.JsonNode> finalizedReceipt=new AtomicReference<>();
    private static final AtomicReference<tools.jackson.databind.JsonNode> finalizedTransaction=new AtomicReference<>();
    private static ApprovalWorkflowService service;
    private static MutableClock clock;
    private static final UUID tenant=UUID.randomUUID(), other=UUID.randomUUID(), admin=UUID.randomUUID(), reviewer=UUID.randomUUID(), reviewer2=UUID.randomUUID();
    private static final Instant NOW=Instant.parse("2026-11-01T08:00:00Z");
    private static java.security.KeyPair issuer;
    private static final UUID evmKey=UUID.randomUUID();
    private static final String PUBLIC_KEY="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final UUID aptosKey=UUID.randomUUID();
    private static final String APTOS_PUBLIC="d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";

    @BeforeAll static void start() throws Exception {
        String url=System.getenv("SW_TEST_APPROVAL_DB_URL");
        if(url==null || !url.matches("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/surprising_wallet_test_[a-z0-9_]+$"))
            throw new IllegalStateException("SW_TEST_APPROVAL_DB_URL must name an isolated database in the existing local PostgreSQL 18");
        var data=new DriverManagerDataSource(url,System.getenv().getOrDefault("SW_TEST_APPROVAL_DB_USERNAME",System.getProperty("user.name")),
                System.getenv().getOrDefault("SW_TEST_APPROVAL_DB_PASSWORD",""));
        jdbc=new JdbcTemplate(data);
        assertEquals(18,jdbc.queryForObject("select current_setting('server_version_num')::integer / 10000",Integer.class));
        Path root=Path.of(System.getProperty("user.dir"));
        while(!java.nio.file.Files.exists(root.resolve("resources/docs/db/surprising-wallet-init-pgsql.sql"))) root=root.getParent();
        try(var connection=data.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(root.resolve("resources/docs/db/surprising-wallet-init-pgsql.sql"))),
                    false,false,ScriptUtils.DEFAULT_COMMENT_PREFIXES,ScriptUtils.EOF_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
        context=new AnnotationConfigApplicationContext();
        context.register(TxConfiguration.class);
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.registerBean(ObjectMapper.class,()->new ObjectMapper());
        context.registerBean(com.surprising.wallet.approval.service.ApprovalAssetRegistry.class,()->new com.surprising.wallet.approval.service.ApprovalAssetRegistry(
                new com.surprising.wallet.approval.config.ApprovalAssetProperties(List.of(
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("ETH","eip155:11155111","USDC","ETH","eip155:11155111","0x3333333333333333333333333333333333333333",6,18,new BigDecimal("0.02")),
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("ETH","eip155:1","USDC","ETH","eip155:1","0x4444444444444444444444444444444444444444",6,18,new BigDecimal("0.02")),
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("APTOS","testnet","APT","APT","aptos:2",null,8,8,new BigDecimal("0.2")),
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("APTOS","testnet","USDC","APT","aptos:2","0xcafe",6,8,new BigDecimal("0.2")),
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("BTC","regtest","BTC","BTC","utxo:"+"a".repeat(64),null,8,8,new BigDecimal("0.01")),
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("LTC","regtest","LTC","LTC","utxo:"+"a".repeat(64),null,8,8,new BigDecimal("0.01")),
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("BCH","regtest","BCH","BCH","utxo:"+"a".repeat(64),null,8,8,new BigDecimal("0.01")),
                    new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("DOGE","regtest","DOGE","DOGE","utxo:"+"a".repeat(64),null,8,8,new BigDecimal("0.01"))))));
        feeNode=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        feeNode.createContext("/",exchange->{
            var mapper=new ObjectMapper();var request=mapper.readTree(exchange.getRequestBody().readAllBytes());
            var response=mapper.createObjectNode().put("jsonrpc","2.0");response.set("id",request.path("id"));
            switch(request.path("method").asText()) {
                case "eth_chainId" -> response.put("result","0xaa36a7");
                case "eth_getBlockByNumber" -> {
                    String tag=request.path("params").path(0).asText();
                    response.set("result",mapper.createObjectNode()
                            .put("hash",tag.equals("0x42")?evmFollowupHash.get()
                                    :tag.equals("latest") || tag.equals(evmLatestNumber.get())
                                    ?evmLatestHash.get():"0x"+"a".repeat(64))
                            .put("number",tag.equals("latest") || tag.equals(evmLatestNumber.get())
                                    ?evmLatestNumber.get():"0x42"));
                }
                case "eth_getBalance" -> response.put("result","0x"+nativeBalances.getOrDefault(request.path("params").path(0).asText(),java.math.BigInteger.TEN.pow(20)).toString(16));
                case "eth_getCode" -> response.put("result",
                        request.path("params").path(0).asText().equalsIgnoreCase(INSTALLED_HOT_DELEGATE)
                                ?installedDelegateCode.get():incidentEvmCode.get());
                case "eth_getTransactionReceipt" -> response.set("result",finalizedReceipt.get());
                case "eth_getTransactionByHash" -> response.set("result",finalizedTransaction.get());
                case "eth_call" -> {
                    String callData=request.path("params").path(0).path("data").asText();
                    if(!callData.startsWith("0x70a08231") || callData.length()!=74)throw new IllegalArgumentException("unexpected balance fixture call");
                    response.put("result","0x"+String.format("%064x",tokenBalances.getOrDefault("0x"+callData.substring(callData.length()-40),java.math.BigInteger.valueOf(100_000_000))));
                }
                default -> throw new IllegalArgumentException("unimplemented fee fixture RPC");
            }
            byte[] bytes=mapper.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });feeNode.start();
        context.registerBean(com.surprising.wallet.approval.config.ApprovalChainRpcProperties.class,()->new com.surprising.wallet.approval.config.ApprovalChainRpcProperties(
                List.of(new com.surprising.wallet.approval.config.ApprovalChainRpcProperties.Profile("ETH","eip155:11155111","DEFAULT","eip155:11155111",
                    java.net.URI.create("http://127.0.0.1:"+feeNode.getAddress().getPort()),null,java.util.Map.of(
                            "feeModel","standard","maximumSignedTransactionBytes","4096",
                            "payoutDelegate."+tenant,INSTALLED_HOT_DELEGATE,
                            "payoutDelegateCodeHash."+tenant,org.web3j.crypto.Hash.sha3("0x6000")))),Duration.ofSeconds(3)));
        context.registerBean(com.surprising.wallet.approval.config.ApprovalAutomationProperties.class,()->new com.surprising.wallet.approval.config.ApprovalAutomationProperties(List.of()));
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(data));
        clock=new MutableClock();context.registerBean(Clock.class,()->clock);
        issuer=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        context.registerBean(com.surprising.wallet.approval.service.ApprovalGrantSigner.class,()->org.mockito.Mockito.mock(com.surprising.wallet.approval.service.ApprovalGrantSigner.class));
        context.registerBean(com.surprising.wallet.approval.config.ApprovalNativeProperties.class,()->new com.surprising.wallet.approval.config.ApprovalNativeProperties(
                Path.of("test-ca"),Path.of("test-cert"),Path.of("test-key"),List.of(
                    new com.surprising.wallet.common.signing.MpcClient.Node("a".repeat(64),java.net.URI.create("https://a.example.test")),
                    new com.surprising.wallet.common.signing.MpcClient.Node("b".repeat(64),java.net.URI.create("https://b.example.test")),
                    new com.surprising.wallet.common.signing.MpcClient.Node("c".repeat(64),java.net.URI.create("https://c.example.test"))),Duration.ofSeconds(3)));
        context.registerBean(com.surprising.wallet.approval.service.ApprovalFeeBudgets.class,()->new com.surprising.wallet.approval.service.ApprovalFeeBudgets(
                new com.surprising.wallet.approval.config.ApprovalFeeProperties(List.of(new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(
                        tenant,"ETH","eip155:11155111","eip155:11155111","ETH",18,new BigDecimal("0.00005")),
                    new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"APTOS","testnet","aptos:2","APT",8,new BigDecimal("0.05")),
                    new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"BTC","regtest","utxo:"+"a".repeat(64),"BTC",8,new BigDecimal("0.05")),
                    new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"LTC","regtest","utxo:"+"a".repeat(64),"LTC",8,new BigDecimal("0.05")),
                    new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"BCH","regtest","utxo:"+"a".repeat(64),"BCH",8,new BigDecimal("0.05")),
                    new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"DOGE","regtest","utxo:"+"a".repeat(64),"DOGE",8,new BigDecimal("0.05"))))));
        for(Class<?> type:List.of(ApprovalTenantRepository.class,ApprovalPolicyRepository.class,ApprovalMemberRepository.class,ApprovalMemberRevocationRepository.class,ApprovalAutomaticOperationRepository.class,
                ApprovalRequestRepository.class,ApprovalDecisionRepository.class,ApprovalAuditRepository.class,ApprovalSessionRepository.class,ApprovalKeyRepository.class,
                ApprovalSigningTransactionRepository.class,ApprovalSigningVersionRepository.class,ApprovalSigningSlotRepository.class,ApprovalSigningGrantRepository.class,ApprovalFeeReservationRepository.class,ApprovalSpendLockRepository.class,
                com.surprising.wallet.approval.gateway.ApprovalChainRpcClient.class,com.surprising.wallet.approval.gateway.ApprovalOperatingBalanceReader.class,com.surprising.wallet.approval.service.ApprovalAutomationService.class,com.surprising.wallet.approval.chain.ApprovalWithdrawalVerifiers.class,com.surprising.wallet.approval.service.ApprovalSigningService.class,com.surprising.wallet.approval.service.ApprovalKeyRegistryService.class,com.surprising.wallet.approval.service.ApprovalEnrollmentService.class,com.surprising.wallet.approval.service.ApprovalMemberService.class,com.surprising.wallet.approval.service.ApprovalMemberRevocationService.class,ApprovalWorkflowService.class,com.surprising.wallet.approval.service.ApprovalAuthenticationService.class)) context.register(type);
        context.registerBean(com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.class,()->org.mockito.Mockito.mock(com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.class));
        context.registerBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class,()->org.mockito.Mockito.mock(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class));
        context.refresh();service=context.getBean(ApprovalWorkflowService.class);
    }
    @AfterAll static void stop() { if(context!=null)context.close();if(feeNode!=null)feeNode.stop(0); }
    @BeforeEach void fixture() {
        clock.time.set(NOW);nativeBalances.clear();tokenBalances.clear();incidentEvmCode.set("0x");
        installedDelegateCode.set("0x6000");
        evmLatestNumber.set("0x42");evmLatestHash.set("0x"+"a".repeat(64));
        org.mockito.Mockito.reset(context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class));
        org.mockito.Mockito.when(context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class).rbfPolicy(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.RbfPolicy(0,0,0));
        org.mockito.Mockito.reset(context.getBean(com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.class));
        var signer=context.getBean(com.surprising.wallet.approval.service.ApprovalGrantSigner.class);
        org.mockito.Mockito.reset(signer);
        org.mockito.Mockito.when(signer.sign(org.mockito.ArgumentMatchers.any())).thenAnswer(call->com.surprising.wallet.common.approval.SigningAuthorization.sign(call.getArgument(0),issuer.getPrivate()));
        jdbc.execute("truncate approval.decision,approval.request,approval.policy,approval.member,approval.audit,approval.tenant cascade");
        jdbc.update("insert into approval.tenant(id,slug) values (?,?),(?,?)",tenant,"review-test",other,"other-test");
        for(var entry:List.of(new Object[]{admin,"TENANT_ADMIN"},new Object[]{reviewer,"APPROVER"},new Object[]{reviewer2,"APPROVER"}))
            jdbc.update("insert into approval.member(id,tenant_id,email,display_name,password_hash,role) values (?,?,?,?,?,?)",
                    entry[0],tenant,entry[0]+"@example.test","Reviewer","not-a-login-hash",entry[1]);
    }
    private com.surprising.wallet.approval.service.ApprovalMemberService management() { return context.getBean(com.surprising.wallet.approval.service.ApprovalMemberService.class); }
    @Test void keyQuarantineIsOneWayIdempotentAndBoundToOriginalEvent() {
        UUID key = UUID.randomUUID();
        jdbc.update("""
                insert into approval.key(key_id,tenant_id,key_group,algorithm,epoch,public_key_hex,ceremony_request,observations)
                values (?,?,'EVM','ECDSA_SECP256K1',1,?,'{}'::jsonb,'[]'::jsonb)
                """, key, tenant, PUBLIC_KEY);
        jdbc.update("update approval.tenant set status='DISABLED' where id=?", tenant);
        var registry = context.getBean(com.surprising.wallet.approval.service.ApprovalKeyRegistryService.class);
        var event = new com.surprising.wallet.common.approval.ApprovalKeyQuarantine(
                tenant, key, admin, NOW, "suspected key compromise");
        assertEquals(event.hash(), registry.quarantine(event, "wallet-client").descriptorHash());
        assertEquals(event.hash(), registry.quarantine(event, "wallet-client").descriptorHash());
        assertEquals("DISABLED", context.getBean(ApprovalKeyRepository.class).find(tenant, key).orElseThrow().state());
        var oldAddress=operatingAddress(key);
        tokenBalances.put(oldAddress,java.math.BigInteger.valueOf(12_345_678));
        String delegatedTo="0x"+"b".repeat(40);
        incidentEvmCode.set("0xef0100"+delegatedTo.substring(2));
        var observed=context.getBean(com.surprising.wallet.approval.service.ApprovalAutomationService.class)
                .incidentInventory(tenant,"ETH","eip155:11155111","USDC",key);
        assertEquals(oldAddress,observed.address());
        assertEquals(java.math.BigInteger.valueOf(12_345_678),observed.availableAtomic());
        assertEquals("DISABLED",observed.keyState());
        assertEquals(event.hash(),observed.quarantineHash());
        assertEquals("EIP7702_DELEGATED",observed.accountCodeState());
        assertEquals(delegatedTo,observed.delegatedTo());
        assertEquals("0x"+"a".repeat(64),observed.observedBlockHash());
        jdbc.update("update approval.tenant set status='ACTIVE' where id=?", tenant);
        assertThrows(IllegalArgumentException.class, () -> context.getBean(
                com.surprising.wallet.approval.service.ApprovalAutomationService.class)
                .hotInventory(tenant,"ETH","eip155:11155111","USDC",key));
        assertEquals(0, jdbc.queryForObject("select count(*) from approval.signing_grant", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> registry.quarantine(
                new com.surprising.wallet.common.approval.ApprovalKeyQuarantine(
                        tenant, key, admin, NOW, "different compromise reason"), "wallet-client"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("update approval.key set state='ACTIVE' where tenant_id=? and key_id=?", tenant, key));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("delete from approval.key where tenant_id=? and key_id=?", tenant, key));
        assertEquals(1, jdbc.queryForObject("select count(*) from approval.audit where action='KEY.WALLET_QUARANTINE'", Integer.class));
    }
    private com.surprising.wallet.approval.service.ApprovalMemberService.Activation invite() {
        return management().invite(tenant,admin,new com.surprising.wallet.approval.service.ApprovalMemberService.Invite(UUID.randomUUID(),"new-reviewer@example.test","New reviewer","independent identity verified",0L));
    }
    @Test void delayedWalletRevocationFencesUnknownIdentityAndCannotDisableExplicitlyRestoredReviewerTwice() {
        UUID id=UUID.randomUUID();var events=context.getBean(com.surprising.wallet.approval.service.ApprovalMemberRevocationService.class);
        var event=new com.surprising.wallet.common.approval.ApprovalMemberRevocation(tenant,id,4,admin,NOW);
        assertEquals(4,events.revoke(event,"wallet-client").sequence());
        assertThrows(IllegalArgumentException.class,()->management().invite(tenant,admin,new com.surprising.wallet.approval.service.ApprovalMemberService.Invite(
                id,"restored@example.test","Restored","verified independent identity",0)));
        var invited=management().invite(tenant,admin,new com.surprising.wallet.approval.service.ApprovalMemberService.Invite(
                id,"restored@example.test","Restored","verified independent identity",4));
        management().activate(invited.token(),"independent-password-123");
        var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);var login=auth.login("restored@example.test","independent-password-123");
        assertNotNull(login);events.revoke(event,"wallet-client");assertNotNull(auth.require(login.token()));
        events.revoke(new com.surprising.wallet.common.approval.ApprovalMemberRevocation(tenant,id,3,admin,NOW.minusSeconds(1)),"wallet-client");assertNotNull(auth.require(login.token()));
        var newer=new com.surprising.wallet.common.approval.ApprovalMemberRevocation(tenant,id,5,admin,NOW.plusSeconds(1));events.revoke(newer,"wallet-client");
        assertThrows(SecurityException.class,()->auth.require(login.token()));assertNull(auth.login("restored@example.test","independent-password-123"));
        assertThrows(IllegalArgumentException.class,()->management().reset(tenant,admin,id,"verified independent restoration",4));
        var restored=management().reset(tenant,admin,id,"verified independent restoration",5);management().activate(restored.token(),"new-independent-password-456");
        var next=auth.login("restored@example.test","new-independent-password-456");events.revoke(newer,"wallet-client");assertNotNull(auth.require(next.token()));
        var page=management().list(tenant,admin,20,0);assertEquals(5,page.items().stream().filter(m->m.id().equals(id)).findFirst().orElseThrow().walletRevocationSequence());
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.audit where action='MEMBER.WALLET_REVOKE'",Integer.class));
    }
    @Test void revocationConflictAndAuditFailureCannotChangeMemberFenceOrSessions() {
        var events=context.getBean(com.surprising.wallet.approval.service.ApprovalMemberRevocationService.class);
        var sessions=context.getBean(ApprovalSessionRepository.class);sessions.insert(tenant,reviewer,"a".repeat(64),"csrf",NOW.plusSeconds(3600));
        var event=new com.surprising.wallet.common.approval.ApprovalMemberRevocation(tenant,reviewer,1,admin,NOW);
        jdbc.execute("alter table approval.audit rename to audit_unavailable");
        try{assertThrows(RuntimeException.class,()->events.revoke(event,"wallet-client"));}finally{jdbc.execute("alter table approval.audit_unavailable rename to audit");}
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.member_revocation",Integer.class));assertTrue(sessions.findActive("a".repeat(64),NOW).isPresent());
        assertEquals("ACTIVE",jdbc.queryForObject("select status from approval.member where id=?",String.class,reviewer));
        events.revoke(event,"wallet-client");assertTrue(sessions.findActive("a".repeat(64),NOW).isEmpty());
        assertThrows(IllegalArgumentException.class,()->events.revoke(new com.surprising.wallet.common.approval.ApprovalMemberRevocation(tenant,reviewer,1,admin,NOW.plusSeconds(1)),"wallet-client"));
        assertEquals(event.hash(),jdbc.queryForObject("select descriptor_hash from approval.member_revocation",String.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("delete from approval.member_revocation"));
    }
    @Test void invitationActivatesOnceWithoutExposingTokenOrPasswordInStorageListOrAudit() {
        var activation=invite();var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);
        assertNull(auth.login("new-reviewer@example.test","independent-password-123"));
        assertEquals("INVITED",management().list(tenant,admin,20,0).items().stream().filter(m->m.id().equals(activation.memberId())).findFirst().orElseThrow().status());
        assertNotEquals(activation.token(),jdbc.queryForObject("select activation_token_hash from approval.member where id=?",String.class,activation.memberId()));
        management().activate(activation.token(),"independent-password-123");
        assertThrows(IllegalArgumentException.class,()->management().activate(activation.token(),"another-password-123"));
        assertEquals(activation.memberId(),auth.login("new-reviewer@example.test","independent-password-123").principal().memberId());
        assertNull(jdbc.queryForObject("select activation_token_hash from approval.member where id=?",String.class,activation.memberId()));
        String publicView=context.getBean(ObjectMapper.class).writeValueAsString(management().list(tenant,admin,20,0));
        String auditView=context.getBean(ObjectMapper.class).writeValueAsString(management().audits(tenant,admin,"",100,0));
        assertFalse(publicView.contains(activation.token()) || auditView.contains(activation.token()) || auditView.contains("independent-password-123"));
    }
    @Test void reviewerSetsShortPasswordAndEveryDecisionCheckCreatesNoSession() {
        var activation=invite();var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);
        assertThrows(IllegalArgumentException.class,()->management().activate(activation.token(),"short7"));
        management().activate(activation.token(),"review88");
        assertNull(auth.verifyForDecision(tenant,activation.memberId(),"incorrect"));
        assertNull(auth.verifyForDecision(other,activation.memberId(),"review88"));
        assertEquals(activation.memberId(),auth.verifyForDecision(tenant,activation.memberId(),"review88").memberId());
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.session where member_id=?",Integer.class,activation.memberId()));
        for(int i=0;i<5;i++)assertNull(auth.verifyForDecision(tenant,activation.memberId(),"incorrect"));
        assertNull(auth.verifyForDecision(tenant,activation.memberId(),"review88"));
    }
    @Test void resetRevokesSessionsAndBothOldPasswordAndPreviousActivationThenAllowsOnlyNewCredential() {
        var first=invite();var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);
        management().activate(first.token(),"independent-password-123");var login=auth.login("new-reviewer@example.test","independent-password-123");
        var reset=management().reset(tenant,admin,first.memberId(),"credential compromise suspected",0L);
        assertThrows(SecurityException.class,()->auth.require(login.token()));assertNull(auth.login("new-reviewer@example.test","independent-password-123"));
        var replacement=management().reset(tenant,admin,first.memberId(),"previous link was lost securely",0L);
        assertThrows(IllegalArgumentException.class,()->management().activate(reset.token(),"new-independent-password-456"));
        management().activate(replacement.token(),"new-independent-password-456");
        assertNull(auth.login("new-reviewer@example.test","independent-password-123"));
        assertNotNull(auth.login("new-reviewer@example.test","new-independent-password-456"));
    }
    @Test void disableRevokesCurrentSessionsPendingActivationAndFurtherDecisions() {
        var first=invite();var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);
        management().activate(first.token(),"independent-password-123");var login=auth.login("new-reviewer@example.test","independent-password-123");
        policy(new BigDecimal("10"),null,false,false,1);var request=service.submit(intent("10"),"wallet-client");
        management().disable(tenant,admin,first.memberId(),"reviewer employment has ended");
        assertThrows(SecurityException.class,()->auth.require(login.token()));assertNull(auth.login("new-reviewer@example.test","independent-password-123"));
        assertThrows(SecurityException.class,()->service.decide(tenant,request.intent().id(),first.memberId(),"APPROVE","checked recipient",request.intentHash()));
        var reactivation=management().reset(tenant,admin,first.memberId(),"independently verified reactivation",0L);
        management().disable(tenant,admin,first.memberId(),"cancel mistaken reactivation link");
        assertThrows(IllegalArgumentException.class,()->management().activate(reactivation.token(),"another-independent-password"));
    }
    @Test void expiredActivationAndCrossTenantOrNonAdministratorManagementFailClosed() {
        var invited=invite();clock.time.set(NOW.plusSeconds(86400));
        assertThrows(IllegalArgumentException.class,()->management().activate(invited.token(),"independent-password-123"));
        assertThrows(SecurityException.class,()->management().list(tenant,reviewer,20,0));
        assertThrows(SecurityException.class,()->management().audits(tenant,reviewer,"",20,0));
        assertThrows(SecurityException.class,()->management().reset(tenant,reviewer,reviewer2,"not an independent administrator",0L));
        assertThrows(SecurityException.class,()->management().disable(other,admin,invited.memberId(),"attempt cross tenant disable"));
        assertThrows(IllegalArgumentException.class,()->management().reset(tenant,admin,admin,"attempt self administrator reset",0L));
        assertThrows(IllegalArgumentException.class,()->management().disable(tenant,admin,admin,"attempt self administrator disable"));
    }
    @Test void mandatoryAuditFailureRollsBackDisableAndRetainsPreviouslyValidSession() {
        var invited=invite();var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);
        management().activate(invited.token(),"independent-password-123");var login=auth.login("new-reviewer@example.test","independent-password-123");
        jdbc.execute("create function approval.member_test_reject_audit() returns trigger language plpgsql as $$ begin raise exception 'audit rejected'; end $$; create trigger member_test_reject_audit before insert on approval.audit for each row execute function approval.member_test_reject_audit()");
        try { assertThrows(RuntimeException.class,()->management().disable(tenant,admin,invited.memberId(),"must retain atomic security audit")); }
        finally { jdbc.execute("drop trigger member_test_reject_audit on approval.audit;drop function approval.member_test_reject_audit()"); }
        assertEquals(invited.memberId(),auth.require(login.token()).memberId());
    }
    @Test void concurrentActivationConsumesCapabilityOnce() throws Exception {
        var invited=invite();try(var pool=Executors.newFixedThreadPool(2)) {
            var work=new ArrayList<java.util.concurrent.Callable<Boolean>>();for(int i=0;i<2;i++)work.add(()->{try {management().activate(invited.token(),"independent-password-123");return true;}catch(IllegalArgumentException invalid){return false;}});
            int count=0;for(var result:pool.invokeAll(work))if(result.get())count++;assertEquals(1,count);
        }
        assertEquals(1,management().audits(tenant,admin,"MEMBER.ACTIVATE",20,0).total());
    }
    @Test void reviewerRowLockOrdersDisableAfterAnAlreadyExecutingDecisionWithoutBlockingUnrelatedAdmission() throws Exception {
        policy(new BigDecimal("10"),null,false,false,1);var request=service.submit(intent("10"),"wallet-client");
        var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            new TransactionTemplate(manager).executeWithoutResult(status->{
                context.getBean(ApprovalMemberRepository.class).activeForDecision(tenant,reviewer).orElseThrow();
                pending.set(pool.submit(()->management().disable(tenant,admin,reviewer,"reviewer role revoked concurrently")));
                assertEquals("APPROVED",service.decide(tenant,request.intent().id(),reviewer,"APPROVE","checked before role revocation",request.intentHash()).status());
            });
            pending.get().get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(context.getBean(ApprovalMemberRepository.class).active(tenant,reviewer).isEmpty());
    }

    @Test void administratorSeesAllStatesAssistsAsOneDistinctReviewerAndHistoryStaysTenantScoped() {
        policy(BigDecimal.ONE,null,false,false,2);
        var request=service.submit(intent("10"),"wallet-client");
        assertEquals("PENDING",service.decide(tenant,request.intent().id(),reviewer,"APPROVE","First independent check",request.intentHash()).status());
        assertEquals(1,service.history(tenant,admin,request.intent().id()).size());
        assertEquals(reviewer,service.history(tenant,admin,request.intent().id()).getFirst().reviewerId());
        assertEquals("APPROVED",service.decide(tenant,request.intent().id(),admin,"APPROVE","Administrator independent check",request.intentHash()).status());
        assertEquals(2,service.history(tenant,admin,request.intent().id()).size());
        assertEquals(1,service.list(tenant,admin,"",20,0).total());
        assertEquals(0,service.list(tenant,admin,"PENDING",20,0).total());
        assertThrows(SecurityException.class,()->service.history(other,admin,request.intent().id()));
        assertThrows(IllegalArgumentException.class,()->service.decide(tenant,request.intent().id(),admin,"REJECT","Cannot overwrite",request.intentHash()));
        var self=service.submit(intent("10","0x1111111111111111111111111111111111111111","CONSOLE",admin),"wallet-client");
        assertThrows(IllegalArgumentException.class,()->service.decide(tenant,self.intent().id(),admin,"APPROVE","Cannot approve own request",self.intentHash()));
    }
    @Test void walletServiceCanReadAllAuthorityRequestsWithoutApprovalMemberCredentials() {
        policy(BigDecimal.ONE,null,false,false,1);
        var request=service.submit(intent("10"),"wallet-client");
        var page=service.listForService(tenant,"",20,0);
        assertEquals(1,page.total());
        assertEquals(request.intentHash(),page.items().getFirst().intentHash());
        assertEquals(0,service.listForService(other,"",20,0).total());
        assertThrows(IllegalArgumentException.class,()->service.listForService(tenant,"AUTHORIZED",20,0));
    }

    private void policy(BigDecimal single,BigDecimal rolling,boolean newAddress,boolean console,int reviews) {
        long version=context.getBean(ApprovalPolicyRepository.class).find(tenant,"ETH","eip155:11155111","USDC").map(p->p.version()).orElse(0L);
        service.configure(tenant,admin,new ApprovalWorkflowService.PolicySettings("ETH","eip155:11155111","USDC",version,single,rolling,false,newAddress,console,reviews,3600));
    }
    private WithdrawalIntent intent(String amount,String recipient,String source,UUID actor) {
        UUID id=UUID.randomUUID();return new WithdrawalIntent(id,tenant,"CW-"+id,"ETH","eip155:11155111","USDC",recipient,
                new BigDecimal(amount),new BigDecimal("0.01"),source,actor,"customer-42", "external-test", "ADDRESS");
    }
    private WithdrawalIntent intent(String amount) { return intent(amount,"0x1111111111111111111111111111111111111111","API",UUID.randomUUID()); }

    private UtxoApprovalFixture utxo(String chain,boolean approved,int transactionId) {
        var f=new UtxoApprovalFixture(chain,tenant);f.transactionId=transactionId;
        for(var key:f.keys.values())jdbc.update("insert into approval.key(key_id,tenant_id,key_group,algorithm,epoch,public_key_hex,ceremony_request,observations) values (?,?,?,'ECDSA_SECP256K1',1,?,'{}','[]')",key.keyId(),tenant,chain,key.publicKeyHex());
        var policy=context.getBean(ApprovalPolicyRepository.class).find(tenant,chain,"regtest",chain);
        if(policy.isEmpty())service.configure(tenant,admin,new ApprovalWorkflowService.PolicySettings(chain,"regtest",chain,0,null,null,true,true,true,2,3600));
        for(int i=0;i<f.reviews.size();i++) {
            var request=service.submit(f.reviews.get(i).intent(),"wallet-client");
            if(approved) {
                service.decide(tenant,request.intent().id(),reviewer,"APPROVE","independently checked batch payment",request.intentHash());
                request=service.decide(tenant,request.intent().id(),reviewer2,"APPROVE","separately verified recipient and amount",request.intentHash());
            }
            f.reviews.set(i,request);
        }
        org.mockito.Mockito.when(context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class).observe(org.mockito.ArgumentMatchers.eq(f.reviews.getFirst().assetSnapshot()),org.mockito.ArgumentMatchers.anyList())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return f.evidence;
        });
        return f;
    }
    @Test void allFourUtxoFamiliesBindTwoReviewedPaymentsAndInputSlotsToOneTransactionVersionAndFee() {
        for(String chain:List.of("BTC","LTC","DOGE","BCH")) {
            var f=utxo(chain,true,42);var first=f.candidate(0);var second=f.candidate(1);
            var a=signing().authorize(first,"wallet-client");var b=signing().authorize(second,"wallet-client");
            assertEquals("AUTHORIZED",a.status());assertEquals("AUTHORIZED",b.status());
            assertEquals(a,signing().authorize(first,"wallet-client"));
            assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_transaction where chain=?",Integer.class,chain));
            assertEquals(2,jdbc.queryForObject("select count(*) from approval.spend_lock where chain=?",Integer.class,chain));
            assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation where chain=?",Integer.class,chain));
            assertEquals(java.math.BigInteger.valueOf(f.unsigned.feeAtomic()),jdbc.queryForObject("select maximum_fee_atomic from approval.fee_reservation where chain=?",BigDecimal.class,chain).toBigIntegerExact());
            for(var r:f.reviews)assertTrue(service.status(tenant,r.intent().id()).signingStarted());
            var claimsA=com.surprising.wallet.common.approval.SigningAuthorization.verify(a.grant().proof(),issuer.getPublic(),first.execution(),NOW);
            var claimsB=com.surprising.wallet.common.approval.SigningAuthorization.verify(b.grant().proof(),issuer.getPublic(),second.execution(),NOW);
            assertEquals(claimsA.businessHash(),claimsB.businessHash());assertNotEquals(claimsA.requestId(),claimsB.requestId());
            assertEquals(com.surprising.wallet.common.approval.ApprovalTransactionHash.business(f.reviews,first.command()),claimsA.businessHash());
        }
        for(var table:List.of("signing_version","fee_reservation"))assertEquals(4,jdbc.queryForObject("select count(*) from approval."+table,Integer.class));
        assertEquals(8,jdbc.queryForObject("select count(*) from approval.signing_slot",Integer.class));
        org.mockito.Mockito.verify(context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class),org.mockito.Mockito.times(4)).observe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList());
    }
    @Test void enabledBtcRbfKeepsOneOriginalApprovalInputOwnerAndFeeReservationAcrossVersions() {
        var rpc=context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class);
        org.mockito.Mockito.when(rpc.rbfPolicy(org.mockito.ArgumentMatchers.any())).thenReturn(new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.RbfPolicy(3,20,100000));
        var f=utxo("BTC",true,42);var first=f.candidate(0);var root=signing().authorize(first,"wallet-client");signing().authorize(f.candidate(1),"wallet-client");
        long originalFee=f.unsigned.feeAtomic();replaceBtc(f,4);var replacement=f.candidate(0);var next=signing().authorize(replacement,"wallet-client");signing().authorize(f.candidate(1),"wallet-client");
        assertTrue(f.unsigned.feeAtomic()>originalFee);assertNotEquals(root.grant().id(),next.grant().id());
        assertEquals(root,signing().authorize(first,"wallet-client"));assertEquals(next,signing().authorize(replacement,"wallet-client"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_transaction",Integer.class));assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_version",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.spend_lock",Integer.class));assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
        assertEquals(new java.math.BigInteger("1000000"),jdbc.queryForObject("select maximum_fee_atomic from approval.fee_reservation",BigDecimal.class).toBigIntegerExact());
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.audit where action='SIGNING.BTC_REPLACEMENT'",Integer.class));
        org.mockito.Mockito.verify(rpc,org.mockito.Mockito.times(1)).observe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList());
        var changed=f.request;var payments=new ArrayList<>(changed.payments());var old=payments.getFirst();payments.set(0,new com.surprising.wallet.common.signing.UtxoSigningRequest.Payment(old.reference(),old.userId(),old.address(),old.amount().add(new BigDecimal("0.01")),old.customerFee()));
        f.request=new com.surprising.wallet.common.signing.UtxoSigningRequest(changed.tenantId(),UUID.randomUUID(),changed.asset(),changed.operation(),changed.collectionNo(),changed.inputs(),payments,changed.changeAddress(),6,changed.dustThreshold());f.rebuild();
        assertThrows(IllegalArgumentException.class,()->signing().authorize(f.candidate(0),"wallet-client"));assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_version",Integer.class));
    }
    @Test void btcReplacementCannotExceedItsInstalledVersionQuotaOrUseAnUninstalledPermission() {
        var rpc=context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class);
        org.mockito.Mockito.when(rpc.rbfPolicy(org.mockito.ArgumentMatchers.any())).thenReturn(new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.RbfPolicy(2,20,100000));
        var f=utxo("BTC",true,42);signing().authorize(f.candidate(0),"wallet-client");replaceBtc(f,4);signing().authorize(f.candidate(0),"wallet-client");replaceBtc(f,6);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(f.candidate(0),"wallet-client"));
        org.mockito.Mockito.when(rpc.rbfPolicy(org.mockito.ArgumentMatchers.any())).thenReturn(new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.RbfPolicy(0,0,0));
        assertThrows(IllegalArgumentException.class,()->signing().authorize(f.candidate(0),"wallet-client"));assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_version",Integer.class));
    }
    private void replaceBtc(UtxoApprovalFixture f,long rate) {
        var r=f.request;f.request=new com.surprising.wallet.common.signing.UtxoSigningRequest(r.tenantId(),UUID.randomUUID(),r.asset(),r.operation(),r.collectionNo(),r.inputs(),r.payments(),r.changeAddress(),rate,r.dustThreshold());f.rebuild();
    }
    @Test void automaticCollectionUsesHostInstalledTreasuryActualBalanceAndMonotonicQuotasWithoutHumanRows() {
        grantKey();UUID treasury=registerOperatingKey(2);var rule=collectionRule(UUID.randomUUID(),treasury);var operating=operatingSigning(List.of(rule));
        var first=automaticEvm("COLL-first",0,treasury);var admitted=operating.authorize(first,"wallet-client");assertEquals("AUTHORIZED",admitted.status());
        assertNotNull(admitted.automaticOperation());assertTrue(admitted.automaticOperation().payments().stream().allMatch(r->r.intent().source().equals("SYSTEM") && r.requiredReviewers()==0));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.request",Integer.class));assertEquals(1,jdbc.queryForObject("select count(*) from approval.automatic_operation",Integer.class));
        assertEquals(admitted,operating.authorize(first,"wallet-client"));
        var second=automaticEvm("COLL-second",1,treasury);assertThrows(IllegalArgumentException.class,()->operating.authorize(second,"wallet-client"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_transaction",Integer.class));
        clock.time.set(NOW.plusSeconds(61));assertEquals("AUTHORIZED",operating.authorize(second,"wallet-client").status());
        clock.time.set(NOW.plusSeconds(122));var third=automaticEvm("COLL-third",2,treasury);
        var denied=assertThrows(IllegalArgumentException.class,()->operating.authorize(third,"wallet-client"));assertTrue(denied.getMessage().contains("principal/count quota"));
        var reinstalled=operatingSigning(List.of(collectionRule(UUID.randomUUID(),treasury)));
        assertThrows(IllegalArgumentException.class,()->reinstalled.authorize(automaticEvm("COLL-fourth",3,treasury),"wallet-client"));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.automatic_operation",Integer.class));assertEquals(2,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
    }
    @Test void automaticCollectionRejectsMissingRoleWrongTreasuryAndInventedSourceBalanceBeforeAnyGrant() {
        grantKey();UUID treasury=registerOperatingKey(2),wrong=registerOperatingKey(3);var candidate=automaticEvm("COLL-funds",0,treasury);
        assertThrows(IllegalArgumentException.class,()->operatingSigning(List.of()).authorize(candidate,"wallet-client"));
        var operating=operatingSigning(List.of(collectionRule(UUID.randomUUID(),treasury)));
        assertThrows(IllegalArgumentException.class,()->operating.authorize(automaticEvm("COLL-foreign",0,wrong),"wallet-client"));
        tokenBalances.put(operatingAddress(evmKey),java.math.BigInteger.ZERO);assertThrows(IllegalArgumentException.class,()->operating.authorize(candidate,"wallet-client"));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));assertEquals(0,jdbc.queryForObject("select count(*) from approval.automatic_operation",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.spend_lock",Integer.class));
    }
    @Test void multipleInstalledCollectionDestinationsSelectTheirExactIndependentRule() {
        grantKey();UUID first=registerOperatingKey(2),second=registerOperatingKey(3),unknown=registerOperatingKey(4);
        var firstRule=collectionRule(UUID.randomUUID(),first);
        var secondRule=collectionRule(UUID.randomUUID(),second);
        var rules=List.of(firstRule,secondRule);
        var signing=operatingSigning(rules);
        var firstGrant=signing.authorize(automaticEvm("COLL-reserve-a",0,first),"wallet-client");
        assertEquals(firstRule.id(),firstGrant.automaticOperation().ruleId());
        assertThrows(IllegalArgumentException.class,()->signing.authorize(
                automaticEvm("COLL-unregistered",1,unknown),"wallet-client"));
        clock.time.set(NOW.plusSeconds(61));
        var secondGrant=signing.authorize(automaticEvm("COLL-reserve-b",1,second),"wallet-client");
        assertEquals(secondRule.id(),secondGrant.automaticOperation().ruleId());
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.automatic_operation",Integer.class));
        assertThrows(IllegalArgumentException.class,()->new com.surprising.wallet.approval.config.ApprovalAutomationProperties(
                List.of(firstRule,collectionRule(UUID.randomUUID(),first))));
    }
    @Test void multipleInstalledParentDestinationsCannotRetargetAnOriginalCollection() {
        grantKey();UUID first=registerOperatingKey(2),second=registerOperatingKey(3);
        var firstRule=collectionRule(UUID.randomUUID(),first);
        var secondRule=collectionRule(UUID.randomUUID(),second);
        var automation=operatingAutomation(List.of(firstRule,secondRule));
        var firstPolicy=automation.collectionDestination(tenant,"ETH","eip155:11155111","USDC",first);
        assertEquals(firstRule.id(),firstPolicy.ruleId());
        assertEquals(operatingAddress(first),firstPolicy.destinationAddress());
        tokenBalances.put(operatingAddress(first),java.math.BigInteger.valueOf(12_345_678));
        var inventory=automation.hotInventory(tenant,"ETH","eip155:11155111","USDC",first);
        assertEquals(firstRule.id(),inventory.collectionRuleId());
        assertEquals(firstPolicy.ruleFingerprint(),inventory.collectionRuleFingerprint());
        assertEquals(java.math.BigInteger.valueOf(12_345_678),inventory.availableAtomic());
        assertEquals(operatingAddress(first),inventory.address());
        assertEquals(66L,inventory.observedBlockHeight());
        assertEquals("0x"+"a".repeat(64),inventory.observedBlockHash());
        evmFollowupHash.set("0x"+"b".repeat(64));
        try {
            assertThrows(IllegalArgumentException.class,()->automation.hotInventory(
                    tenant,"ETH","eip155:11155111","USDC",first));
        } finally {
            evmFollowupHash.set("0x"+"a".repeat(64));
        }
        incidentEvmCode.set("0xef0100"+INSTALLED_HOT_DELEGATE.substring(2));
        try {
            assertEquals(66L,automation.hotInventory(
                    tenant,"ETH","eip155:11155111","USDC",first).observedBlockHeight());
            installedDelegateCode.set("0x6001");
            assertThrows(IllegalArgumentException.class,()->automation.hotInventory(
                    tenant,"ETH","eip155:11155111","USDC",first));
            incidentEvmCode.set("0xef0100"+"9".repeat(40));
            assertThrows(IllegalArgumentException.class,()->automation.hotInventory(
                    tenant,"ETH","eip155:11155111","USDC",first));
        } finally {
            incidentEvmCode.set("0x");installedDelegateCode.set("0x6000");
        }
        String txHash="0x"+"f".repeat(64),recipient="0x"+"8".repeat(40);
        String contract="0x3333333333333333333333333333333333333333";
        java.math.BigInteger amountAtomic=java.math.BigInteger.valueOf(5_000_000);
        String transferInput="0xa9059cbb"+"0".repeat(24)+recipient.substring(2)
                +String.format("%064x",amountAtomic);
        var mapper=context.getBean(ObjectMapper.class);
        var transaction=mapper.createObjectNode().put("hash",txHash)
                .put("from",operatingAddress(first)).put("to",contract).put("value","0x0")
                .put("input",transferInput)
                .put("blockNumber","0x42").put("blockHash","0x"+"a".repeat(64));
        var log=mapper.createObjectNode().put("address",contract).put("data","0x"+amountAtomic.toString(16))
                .put("transactionHash",txHash).put("blockNumber","0x42")
                .put("blockHash","0x"+"a".repeat(64)).put("removed",false);
        log.set("topics",mapper.createArrayNode()
                .add("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")
                .add("0x"+"0".repeat(24)+operatingAddress(first).substring(2))
                .add("0x"+"0".repeat(24)+recipient.substring(2)));
        var receipt=mapper.createObjectNode().put("transactionHash",txHash).put("status","0x1")
                .put("from",operatingAddress(first)).put("to",contract)
                .put("blockNumber","0x42").put("blockHash","0x"+"a".repeat(64));
        receipt.set("logs",mapper.createArrayNode().add(log));
        evmLatestNumber.set("0x44");evmLatestHash.set("0x"+"c".repeat(64));
        finalizedTransaction.set(transaction);finalizedReceipt.set(receipt);
        try {
            var request=new com.surprising.wallet.common.approval.HotWalletSettledSpendRequest(
                    tenant,"ETH","eip155:11155111","USDC",first,txHash,recipient,amountAtomic);
            var finalized=automation.settledDirectSpend(request);
            assertEquals(66L,finalized.receiptBlockHeight());
            assertEquals(68L,finalized.observedBlockHeight());
            assertEquals("0x"+"c".repeat(64),finalized.observedBlockHash());
            assertEquals(txHash,finalized.txHash());
            assertEquals(java.math.BigInteger.valueOf(12_345_678),finalized.availableAtomic());
            transaction.put("input","0xdead");
            assertThrows(IllegalArgumentException.class,()->automation.settledDirectSpend(request));
            transaction.put("input",transferInput);
            log.put("data","0x1");
            assertThrows(IllegalArgumentException.class,()->automation.settledDirectSpend(request));
            log.put("data","0x"+amountAtomic.toString(16));
            evmLatestNumber.set("0x41");
            assertThrows(IllegalArgumentException.class,()->automation.settledDirectSpend(request));
            evmLatestNumber.set("0x44");
            incidentEvmCode.set("0xef0100"+"9".repeat(40));
            assertThrows(IllegalArgumentException.class,()->automation.settledDirectSpend(request));
        } finally {
            finalizedTransaction.set(null);finalizedReceipt.set(null);
            evmLatestNumber.set("0x42");evmLatestHash.set("0x"+"a".repeat(64));
            incidentEvmCode.set("0x");
        }
        var nativeAsset=new com.surprising.wallet.common.approval.ApprovalAssetSnapshot(
                "ETH","eip155:11155111","ETH","ETH","eip155:11155111",null,18,18,
                new BigDecimal("0.02"));
        var registered=context.getBean(ApprovalKeyRepository.class).find(tenant,first).orElseThrow();
        var reader=context.getBean(com.surprising.wallet.approval.gateway.ApprovalOperatingBalanceReader.class);
        String nativeHash="0x"+"e".repeat(64),nativeRecipient="0x"+"9".repeat(40);
        java.math.BigInteger nativeValue=new java.math.BigInteger("2000000000000000000");
        finalizedTransaction.set(mapper.createObjectNode().put("hash",nativeHash)
                .put("from",operatingAddress(first)).put("to",nativeRecipient)
                .put("value","0x"+nativeValue.toString(16)).put("input","0x")
                .put("blockNumber","0x42").put("blockHash","0x"+"a".repeat(64)));
        var nativeReceipt=mapper.createObjectNode().put("transactionHash",nativeHash).put("status","0x1")
                .put("from",operatingAddress(first)).put("to",nativeRecipient)
                .put("blockNumber","0x42").put("blockHash","0x"+"a".repeat(64));
        nativeReceipt.set("logs",mapper.createArrayNode());finalizedReceipt.set(nativeReceipt);
        try {
            var nativeProof=reader.observeFinalizedDirectSpend(nativeAsset,registered,
                    operatingAddress(first),nativeRecipient,nativeHash,nativeValue);
            assertEquals(66L,nativeProof.observedBlockHeight());
            assertEquals(nativeValue,nativeProof.amountAtomic());
            nativeReceipt.put("status","0x0");
            assertThrows(IllegalArgumentException.class,()->reader.observeFinalizedDirectSpend(nativeAsset,
                    registered,operatingAddress(first),nativeRecipient,nativeHash,nativeValue));
        } finally {
            finalizedTransaction.set(null);finalizedReceipt.set(null);
        }
        assertEquals(secondRule.id(),automation.collectionDestination(
                tenant,"ETH","eip155:11155111","USDC",second).ruleId());
        assertThrows(IllegalArgumentException.class,()->automation.collectionDestination(
                tenant,"ETH","eip155:11155111","USDC",evmKey));
        var original=new com.surprising.wallet.common.approval.AutomaticParentPlan(tenant,evmKey,
                "ETH","eip155:11155111","USDC","planned-a",operatingAddress(first),new BigDecimal("10"),null);
        assertEquals(firstRule.id(),automation.plan(original,"wallet-client").ruleId());
        var retarget=new com.surprising.wallet.common.approval.AutomaticParentPlan(tenant,evmKey,
                "ETH","eip155:11155111","USDC","planned-a",operatingAddress(second),new BigDecimal("10"),null);
        assertThrows(IllegalArgumentException.class,()->automation.plan(retarget,"wallet-client"));
        clock.time.set(NOW.plusSeconds(61));
        var next=new com.surprising.wallet.common.approval.AutomaticParentPlan(tenant,evmKey,
                "ETH","eip155:11155111","USDC","planned-b",operatingAddress(second),new BigDecimal("10"),null);
        assertEquals(secondRule.id(),automation.plan(next,"wallet-client").ruleId());
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.automatic_operation",Integer.class));
    }
    @Test void finalizedDirectCollectionRequiresOriginalSourceTransferAndReceivingBalance() {
        grantKey();UUID source=registerOperatingKey(2),target=registerOperatingKey(3);
        var automation=operatingAutomation(List.of(collectionRule(UUID.randomUUID(),target)));
        String from=operatingAddress(source),to=operatingAddress(target),hash="0x"+"e".repeat(64);
        String contract="0x3333333333333333333333333333333333333333";
        var amount=java.math.BigInteger.valueOf(5_000_000);
        var mapper=context.getBean(ObjectMapper.class);
        String input="0xa9059cbb"+"0".repeat(24)+to.substring(2)+String.format("%064x",amount);
        var transaction=mapper.createObjectNode().put("hash",hash).put("from",from)
                .put("to",contract).put("value","0x0").put("input",input)
                .put("blockNumber","0x42").put("blockHash","0x"+"a".repeat(64));
        var log=mapper.createObjectNode().put("address",contract).put("data","0x"+amount.toString(16))
                .put("transactionHash",hash).put("blockNumber","0x42")
                .put("blockHash","0x"+"a".repeat(64)).put("removed",false);
        log.set("topics",mapper.createArrayNode()
                .add("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")
                .add("0x"+"0".repeat(24)+from.substring(2))
                .add("0x"+"0".repeat(24)+to.substring(2)));
        var receipt=mapper.createObjectNode().put("transactionHash",hash).put("status","0x1")
                .put("from",from).put("to",contract).put("blockNumber","0x42")
                .put("blockHash","0x"+"a".repeat(64));
        receipt.set("logs",mapper.createArrayNode().add(log));
        tokenBalances.put(to,java.math.BigInteger.valueOf(12_345_678));
        finalizedTransaction.set(transaction);finalizedReceipt.set(receipt);
        evmLatestNumber.set("0x44");evmLatestHash.set("0x"+"c".repeat(64));
        try {
            var request=new com.surprising.wallet.common.approval.HotWalletSettledCollectionRequest(
                    tenant,"ETH","eip155:11155111","USDC",source,from,target,to,hash,amount);
            var proof=automation.settledDirectCollection(request);
            assertEquals(target,proof.destinationKeyId());
            assertEquals(java.math.BigInteger.valueOf(12_345_678),proof.availableAtomic());
            assertEquals(66L,proof.receiptBlockHeight());assertEquals(68L,proof.observedBlockHeight());
            transaction.put("input","0xdead");
            assertThrows(IllegalArgumentException.class,()->automation.settledDirectCollection(request));
            transaction.put("input",input);log.put("data","0x1");
            assertThrows(IllegalArgumentException.class,()->automation.settledDirectCollection(request));
        } finally {
            finalizedTransaction.set(null);finalizedReceipt.set(null);
            evmLatestNumber.set("0x42");evmLatestHash.set("0x"+"a".repeat(64));
        }
    }
    @Test void parentPlanningReservesOneFixedOwnedPaymentAndCannotManufactureOrResetExposure() {
        grantKey();UUID treasury=registerOperatingKey(2),wrong=registerOperatingKey(3);var rule=collectionRule(UUID.randomUUID(),treasury);var automation=operatingAutomation(List.of(rule));
        var plan=new com.surprising.wallet.common.approval.AutomaticParentPlan(tenant,evmKey,"ETH","eip155:11155111","USDC","planned-collection",operatingAddress(treasury),new BigDecimal("10"),null);
        var receipt=automation.plan(plan,"wallet-client");assertEquals(receipt,automation.plan(plan,"wallet-client"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.automatic_operation where transaction_id is null",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
        var changed=new com.surprising.wallet.common.approval.AutomaticParentPlan(tenant,evmKey,plan.chain(),plan.network(),plan.assetSymbol(),plan.businessReference(),plan.recipient(),new BigDecimal("11"),null);
        assertThrows(IllegalArgumentException.class,()->automation.plan(changed,"wallet-client"));
        var foreign=new com.surprising.wallet.common.approval.AutomaticParentPlan(tenant,evmKey,plan.chain(),plan.network(),plan.assetSymbol(),"wrong-treasury",operatingAddress(wrong),plan.amount(),null);
        assertThrows(IllegalArgumentException.class,()->automation.plan(foreign,"wallet-client"));
        var reinstalled=operatingAutomation(List.of(collectionRule(UUID.randomUUID(),treasury)));
        assertThrows(IllegalArgumentException.class,()->reinstalled.plan(plan,"wallet-client"));
        tokenBalances.put(operatingAddress(evmKey),java.math.BigInteger.ZERO);
        var empty=new com.surprising.wallet.common.approval.AutomaticParentPlan(tenant,evmKey,plan.chain(),plan.network(),plan.assetSymbol(),"invented-funds",plan.recipient(),plan.amount(),null);
        assertThrows(IllegalArgumentException.class,()->automation.plan(empty,"wallet-client"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.automatic_operation",Integer.class));
    }
    private UUID registerOperatingKey(int multiple) {
        UUID id=UUID.randomUUID();String publicKey=java.util.HexFormat.of().formatHex(org.bitcoinj.crypto.ECKey.fromPublicOnly(java.util.HexFormat.of().parseHex(PUBLIC_KEY)).getPubKeyPoint().multiply(java.math.BigInteger.valueOf(multiple)).getEncoded(true));
        jdbc.update("insert into approval.key(key_id,tenant_id,key_group,algorithm,epoch,public_key_hex,ceremony_request,observations) values (?,?,'EVM','ECDSA_SECP256K1',1,?,'{}','[]')",id,tenant,publicKey);return id;
    }
    private String operatingAddress(UUID id) {
        var key=context.getBean(ApprovalKeyRepository.class).find(tenant,id).orElseThrow();return "0x"+org.web3j.crypto.Keys.getAddress(org.web3j.crypto.Sign.publicFromPoint(org.bitcoinj.crypto.ECKey.fromPublicOnly(java.util.HexFormat.of().parseHex(key.publicKeyHex())).decompress().getPubKey()));
    }
    private com.surprising.wallet.approval.config.ApprovalAutomationProperties.Rule collectionRule(UUID id,UUID treasury) {
        return new com.surprising.wallet.approval.config.ApprovalAutomationProperties.Rule(id,tenant,"ETH","eip155:11155111","USDC","COLLECTION",java.util.Set.of(evmKey),treasury,
                BigDecimal.ONE,new BigDecimal("20"),new BigDecimal("40"),new BigDecimal("100"),new BigDecimal("0.00003"),new BigDecimal("0.001"),2,60,10000);
    }
    private com.surprising.wallet.common.approval.SigningCandidate automaticEvm(String reference,int nonce,UUID treasury) {
        var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,reference,"ETH","eip155:11155111","USDC",operatingAddress(treasury),new BigDecimal("10"),new BigDecimal("0.00003"),"API",UUID.randomUUID(),"customer","merchant","ADDRESS");
        var original=candidate(intent,nonce);var c=original.command();var command=new com.surprising.wallet.common.signing.MpcSignCommand(c.tenantId(),c.keyId(),c.keyEpoch(),c.chain(),com.surprising.wallet.common.signing.MpcSignCommand.BusinessType.COLLECTION,c.businessReference(),c.signingSlot(),c.purpose(),c.payloadEncoding(),c.payloadHex(),c.preimageHex());
        return new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),List.of(),command,original.execution(),null,null);
    }
    private com.surprising.wallet.approval.service.ApprovalSigningService operatingSigning(List<com.surprising.wallet.approval.config.ApprovalAutomationProperties.Rule> rules) {
        var json=context.getBean(ObjectMapper.class);var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        var automation=operatingAutomation(rules);
        var budgets=new com.surprising.wallet.approval.service.ApprovalFeeBudgets(new com.surprising.wallet.approval.config.ApprovalFeeProperties(List.of(
                new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"ETH","eip155:11155111","eip155:11155111","ETH",18,new BigDecimal("0.01")),
                new com.surprising.wallet.approval.config.ApprovalFeeProperties.Budget(tenant,"BTC","regtest","utxo:"+"a".repeat(64),"BTC",8,new BigDecimal("0.05")))));
        return new com.surprising.wallet.approval.service.ApprovalSigningService(context.getBean(ApprovalTenantRepository.class),context.getBean(ApprovalRequestRepository.class),context.getBean(ApprovalKeyRepository.class),
                context.getBean(ApprovalSigningTransactionRepository.class),context.getBean(ApprovalSigningVersionRepository.class),context.getBean(ApprovalSigningSlotRepository.class),context.getBean(ApprovalSigningGrantRepository.class),
                context.getBean(ApprovalFeeReservationRepository.class),context.getBean(ApprovalAuditRepository.class),service,context.getBean(com.surprising.wallet.approval.service.ApprovalAssetRegistry.class),budgets,
                context.getBean(com.surprising.wallet.approval.service.ApprovalGrantSigner.class),context.getBean(com.surprising.wallet.approval.config.ApprovalNativeProperties.class),manager,json,clock,
                context.getBean(com.surprising.wallet.approval.chain.ApprovalWithdrawalVerifiers.class),context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class),context.getBean(ApprovalSpendLockRepository.class),
                context.getBean(com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.class),automation,
                context.getBean(com.surprising.wallet.approval.gateway.ApprovalOperatingBalanceReader.class));
    }
    private com.surprising.wallet.approval.service.ApprovalAutomationService operatingAutomation(List<com.surprising.wallet.approval.config.ApprovalAutomationProperties.Rule> rules) {
        return new com.surprising.wallet.approval.service.ApprovalAutomationService(new com.surprising.wallet.approval.config.ApprovalAutomationProperties(rules),
                context.getBean(com.surprising.wallet.approval.service.ApprovalAssetRegistry.class),context.getBean(ApprovalKeyRepository.class),context.getBean(ApprovalAutomaticOperationRepository.class),
                context.getBean(ApprovalRequestRepository.class),context.getBean(ApprovalAuditRepository.class),context.getBean(ObjectMapper.class),clock,context.getBean(ApprovalTenantRepository.class),
                context.getBean(com.surprising.wallet.approval.gateway.ApprovalOperatingBalanceReader.class),context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    }
    @Test void pendingRejectedAndExpiredBatchPaymentsCannotReachIndependentChainObservation() {
        var f=utxo("BTC",false,42);var candidate=f.candidate(0);
        assertEquals("PENDING",signing().authorize(candidate,"wallet-client").status());
        var first=f.reviews.getFirst();service.decide(tenant,first.intent().id(),reviewer,"REJECT","recipient was not independently verified",first.intentHash());
        assertEquals("REJECTED",signing().authorize(candidate,"wallet-client").status());
        var expired=utxo("LTC",false,43);clock.time.set(NOW.plusSeconds(3600));
        assertEquals("EXPIRED",signing().authorize(expired.candidate(0),"wallet-client").status());
        org.mockito.Mockito.verifyNoInteractions(context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.signing_transaction",Integer.class));
    }
    @Test void laterInputAndOriginalProofRecoveryUseStoredFactsEvenWhenInputsAreNowSpentAndReviewTimeHasElapsed() {
        var f=utxo("BTC",true,42);var first=f.candidate(0);var original=signing().authorize(first,"wallet-client");
        var client=context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class);
        org.mockito.Mockito.when(client.observe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList())).thenThrow(new IllegalStateException("inputs now spent in mempool"));
        clock.time.set(NOW.plusSeconds(86401));
        assertEquals(original,signing().authorize(first,"wallet-client"));
        assertEquals("AUTHORIZED",signing().authorize(f.candidate(1),"wallet-client").status());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).observe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList());
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
        var stored=context.getBean(ObjectMapper.class).readTree(jdbc.queryForObject("select chain_evidence from approval.signing_version",String.class));
        assertEquals(f.evidence.bestBlock(),stored.path("bestBlock").asText());assertEquals(2,stored.path("outputs").size());
    }
    @Test void exactUtxoReplayRequiresOperatorAuditAndCannotRemapAnExistingInputSlot() {
        var f=utxo("BTC",true,42);var first=f.candidate(0);var original=signing().authorize(first,"wallet-client");
        var e=first.execution();var next=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,e.keyId(),e.epoch(),e.operation(),e.algorithm(),e.online(),e.messageHex());
        var unaudited=new com.surprising.wallet.common.approval.SigningCandidate(first.signingRequestId(),first.approvalRequestIds(),first.command(),next,null,null);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(unaudited,"wallet-client"));
        var replay=new com.surprising.wallet.common.approval.SigningCandidate(first.signingRequestId(),first.approvalRequestIds(),first.command(),next,"operator-7","recover exactly the original locked UTXO batch");
        assertNotEquals(original.grant().id(),signing().authorize(replay,"wallet-client").grant().id());
        assertEquals(original,signing().authorize(first,"wallet-client"));
        var alias=new com.surprising.wallet.common.approval.SigningCandidate(UUID.randomUUID(),first.approvalRequestIds(),first.command(),next,null,null);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(alias,"wallet-client"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_slot",Integer.class));
    }
    @Test void overlappingInputSetsForDifferentApprovedBatchesCannotReuseOriginalOutpoints() {
        var first=utxo("BTC",true,42);signing().authorize(first.candidate(0),"wallet-client");
        var second=utxo("BTC",true,43);
        var r=second.request;var inputs=new ArrayList<>(r.inputs());var old=inputs.get(1);
        inputs.set(1,new com.surprising.wallet.common.signing.UtxoSigningRequest.Input("e".repeat(64),old.vout(),old.address(),old.amount(),old.key()));
        second.request=new com.surprising.wallet.common.signing.UtxoSigningRequest(r.tenantId(),r.requestId(),r.asset(),r.operation(),r.collectionNo(),inputs,r.payments(),r.changeAddress(),r.feeRate(),r.dustThreshold());second.rebuild();
        var facts=new ArrayList<>(second.evidence.outputs());var previous=facts.get(1);
        facts.set(1,new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Output(new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Outpoint("e".repeat(64),previous.outpoint().index()),previous.amountAtomic(),previous.scriptPubKeyHex(),previous.confirmations(),previous.coinbase()));
        second.evidence=new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Observation(second.evidence.chain(),second.evidence.network(),second.evidence.genesisHash(),second.evidence.bestBlock(),second.evidence.observedAt(),facts);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(second.candidate(0),"wallet-client"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_transaction",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.spend_lock",Integer.class));
        for(var review:second.reviews)assertFalse(service.status(tenant,review.intent().id()).signingStarted());
    }
    @Test void authorizationAuditFailureRollsBackAllUtxoInputLocksEvidenceBindingsAndFees() {
        var f=utxo("BTC",true,42);var candidate=f.candidate(0);
        jdbc.execute("alter table approval.audit rename to audit_unavailable");
        try { assertThrows(RuntimeException.class,()->signing().authorize(candidate,"wallet-client")); }
        finally { jdbc.execute("alter table approval.audit_unavailable rename to audit"); }
        for(var table:List.of("signing_transaction","signing_version","signing_slot","signing_grant","fee_reservation","spend_lock"))assertEquals(0,jdbc.queryForObject("select count(*) from approval."+table,Integer.class));
        for(var r:f.reviews)assertFalse(service.status(tenant,r.intent().id()).signingStarted());
        assertEquals("AUTHORIZED",signing().authorize(candidate,"wallet-client").status());
    }
    @Test void concurrentFirstInputSlotsObserveOutsideDatabaseLocksThenCommitOneOriginalBatch() throws Exception {
        var f=utxo("BTC",true,42);var ready=new java.util.concurrent.CountDownLatch(2);
        org.mockito.Mockito.when(context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class).observe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());ready.countDown();
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS),"network read blocked the other transaction preflight");return f.evidence;
        });
        var candidates=List.of(f.candidate(0),f.candidate(1));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var work=candidates.stream().<java.util.concurrent.Callable<String>>map(c->()->signing().authorize(c,"wallet-client").status()).toList();
            for(var result:pool.invokeAll(work))assertEquals("AUTHORIZED",result.get());
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
    }
    @Test void staleOrUnavailableIndependentEvidenceKeepsOriginalReviewsUnboundAndCreatesNoGrant() {
        var f=utxo("BTC",true,42);var candidate=f.candidate(0);var current=f.evidence;
        var old=new com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Observation(current.chain(),current.network(),current.genesisHash(),current.bestBlock(),NOW.minusSeconds(31),current.outputs());
        var client=context.getBean(com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.class);
        org.mockito.Mockito.when(client.observe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList())).thenReturn(old);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(candidate,"wallet-client"));
        org.mockito.Mockito.when(client.observe(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList())).thenThrow(new IllegalStateException("node unavailable"));
        assertThrows(IllegalStateException.class,()->signing().authorize(candidate,"wallet-client"));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
        for(var r:f.reviews)assertFalse(service.status(tenant,r.intent().id()).signingStarted());
    }
    @Test void originalUtxoFactsAndIndividualSpendLocksArePermanentDatabaseReceipts() {
        var f=utxo("BTC",true,42);signing().authorize(f.candidate(0),"wallet-client");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("delete from approval.spend_lock"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("update approval.spend_lock set output_index=output_index+1"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("update approval.signing_version set chain_evidence='{}'"));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.spend_lock",Integer.class));
    }

    private void grantKey() {
        jdbc.update("""
                insert into approval.key(key_id,tenant_id,key_group,algorithm,epoch,public_key_hex,ceremony_request,observations)
                values (?,?,'EVM','ECDSA_SECP256K1',1,?,'{}','[]')
                """,evmKey,tenant,PUBLIC_KEY);
    }
    private com.surprising.wallet.common.approval.SigningCandidate candidate(WithdrawalIntent intent,int nonce) {
        var raw=org.web3j.crypto.RawTransaction.createTransaction(java.math.BigInteger.valueOf(nonce),java.math.BigInteger.valueOf(300_000_000),
                java.math.BigInteger.valueOf(70000),"0x3333333333333333333333333333333333333333",java.math.BigInteger.ZERO,
                "a9059cbb"+"0".repeat(24)+intent.recipient().substring(2)+String.format("%064x",intent.amount().movePointRight(6).toBigIntegerExact()));
        var json=context.getBean(ObjectMapper.class);var envelope=new java.util.LinkedHashMap<String,Object>();
        envelope.put("chainId",11155111);envelope.put("unsignedHex",java.util.HexFormat.of().formatHex(com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec.unsignedPayload(raw,11155111)));
        envelope.put("symbol","USDC");envelope.put("contract","0x3333333333333333333333333333333333333333");envelope.put("amount",intent.amount());
        envelope.put("assetDecimals",6);envelope.put("nativeDecimals",18);
        String digest=java.util.HexFormat.of().formatHex(com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec.digest(raw,11155111));
        var command=new com.surprising.wallet.common.signing.MpcSignCommand(tenant,evmKey,1,com.surprising.wallet.common.chain.ChainType.ETH,
                com.surprising.wallet.common.signing.MpcSignCommand.BusinessType.WITHDRAWAL,intent.businessReference(),"transaction",
                com.surprising.wallet.common.signing.MpcSignCommand.Purpose.TRANSACTION,"EVM_TX_JSON",java.util.HexFormat.of().formatHex(json.writeValueAsBytes(envelope)),digest);
        var execution=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,evmKey,1,
                com.surprising.wallet.common.signing.MpcClient.Operation.SIGN,com.surprising.wallet.common.signing.MpcClient.Algorithm.ECDSA_SECP256K1,
                List.of("a".repeat(64),"c".repeat(64)),digest);
        return new com.surprising.wallet.common.approval.SigningCandidate(UUID.randomUUID(),List.of(intent.id()),command,execution,null,null);
    }
    private com.surprising.wallet.approval.service.ApprovalSigningService signing() {
        return context.getBean(com.surprising.wallet.approval.service.ApprovalSigningService.class);
    }
    private void aptosPolicyAndKey(String symbol) {
        service.configure(tenant,admin,new ApprovalWorkflowService.PolicySettings("APTOS","testnet",symbol,0,null,null,true,false,false,1,3600));
        jdbc.update("""
                insert into approval.key(key_id,tenant_id,key_group,algorithm,epoch,public_key_hex,ceremony_request,observations)
                values (?,?,'APTOS','ED25519',1,?,'{}','[]') on conflict do nothing
                """,aptosKey,tenant,APTOS_PUBLIC);
    }
    private WithdrawalIntent aptosIntent(String symbol) {
        UUID id=UUID.randomUUID();return new WithdrawalIntent(id,tenant,"CW-aptos-"+id,"APTOS","testnet",symbol,"0x2",BigDecimal.ONE,new BigDecimal("0.01"),
                "API",UUID.randomUUID(),"customer-aptos","merchant-aptos","ADDRESS");
    }
    private com.surprising.wallet.common.approval.SigningCandidate aptosCandidate(WithdrawalIntent intent,long sequence) {
        boolean token=!intent.assetSymbol().equals("APT");
        var unsigned=new com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec.Unsigned(
                com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec.address(java.util.HexFormat.of().parseHex(APTOS_PUBLIC)),sequence,intent.recipient(),token?1_000_000:100_000_000,
                token?"0xcafe":null,1000,200,NOW.plusSeconds(600).getEpochSecond(),2,APTOS_PUBLIC);
        var payload=new com.surprising.wallet.common.signing.AptosSigningPayload(intent.assetSymbol(),intent.amount(),BigDecimal.ZERO,token?6:8,unsigned);
        String message=java.util.HexFormat.of().formatHex(com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec.signingBytes(unsigned));
        var command=new com.surprising.wallet.common.signing.MpcSignCommand(tenant,aptosKey,1,com.surprising.wallet.common.chain.ChainType.APTOS,
                com.surprising.wallet.common.signing.MpcSignCommand.BusinessType.WITHDRAWAL,intent.businessReference(),"transaction",com.surprising.wallet.common.signing.MpcSignCommand.Purpose.TRANSACTION,
                "APTOS_TX_JSON",java.util.HexFormat.of().formatHex(context.getBean(ObjectMapper.class).writeValueAsBytes(payload)),message);
        var execution=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,aptosKey,1,com.surprising.wallet.common.signing.MpcClient.Operation.SIGN,
                com.surprising.wallet.common.signing.MpcClient.Algorithm.ED25519,List.of("a".repeat(64),"c".repeat(64)),message);
        return new com.surprising.wallet.common.approval.SigningCandidate(UUID.randomUUID(),List.of(intent.id()),command,execution,null,null);
    }
    @Test void aptosNativeAndPrimaryFaGrantsBindTheActualBcsBytesAndIndependentFeeCurrency() {
        long sequence=0;
        for(String symbol:List.of("APT","USDC")) {
            aptosPolicyAndKey(symbol);var intent=aptosIntent(symbol);var reviewed=service.submit(intent,"wallet");var candidate=aptosCandidate(intent,sequence++);
            assertEquals("PENDING",signing().authorize(candidate,"wallet").status());
            service.decide(tenant,intent.id(),reviewer,"APPROVE","checked exact Aptos network, metadata and payment",reviewed.intentHash());
            var outcome=signing().authorize(candidate,"wallet");assertEquals("AUTHORIZED",outcome.status());
            var claims=com.surprising.wallet.common.approval.SigningAuthorization.verify(outcome.grant().proof(),issuer.getPublic(),candidate.execution(),NOW);
            assertEquals("APTOS",claims.chain());assertEquals("ED25519",claims.algorithm());assertEquals("testnet",claims.network());
            assertTrue(service.status(tenant,intent.id()).signingStarted());
        }
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.fee_reservation where native_symbol='APT' and native_decimals=8",Integer.class));
        assertEquals(new BigDecimal("400000"),jdbc.queryForObject("select sum(maximum_fee_atomic) from approval.fee_reservation",BigDecimal.class));
    }
    @Test void aptosSequenceRecoveryKeepsOneBusinessTransactionAndCannotChangeTheAccountSequence() {
        aptosPolicyAndKey("APT");var intent=aptosIntent("APT");var reviewed=service.submit(intent,"wallet");
        service.decide(tenant,intent.id(),reviewer,"APPROVE","verified sequenced Aptos payment",reviewed.intentHash());
        var original=aptosCandidate(intent,1);var first=signing().authorize(original,"wallet");clock.time.set(NOW.plusSeconds(300));
        var next=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,aptosKey,1,original.execution().operation(),original.execution().algorithm(),
                List.of("b".repeat(64),"c".repeat(64)),original.execution().messageHex());
        var replay=new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),original.command(),next,"operator","recover identical Aptos sequence-number transaction");
        var second=signing().authorize(replay,"wallet");assertNotEquals(first.grant().id(),second.grant().id());
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_transaction",Integer.class));assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
        var changed=aptosCandidate(intent,2);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),changed.command(),changed.execution(),"operator","attempt changed Aptos sequence"),"wallet"));
    }
    @Test void finalAuthorizationCommitsExactProofAndRecoversOnlyOriginalSessionAfterExpiry() {
        policy(BigDecimal.ONE,null,false,false,1);grantKey();var i=intent("1");var review=service.submit(i,"wallet");var c=candidate(i,1);
        assertEquals("PENDING",signing().authorize(c,"wallet").status());
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
        service.decide(tenant,i.id(),reviewer,"APPROVE","verified recipient and fee",review.intentHash());
        var result=signing().authorize(c,"wallet");assertEquals("AUTHORIZED",result.status());
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
        var claims=com.surprising.wallet.common.approval.SigningAuthorization.verify(result.grant().proof(),issuer.getPublic(),c.execution(),NOW);
        assertEquals(c.signingRequestId(),claims.requestId());assertEquals("eip155:11155111",claims.network());assertTrue(service.status(tenant,i.id()).signingStarted());
        clock.time.set(NOW.plusSeconds(90000));
        assertEquals(result,signing().authorize(c,"wallet"));assertEquals("APPROVED",service.status(tenant,i.id()).status());
        var freshSession=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,evmKey,1,c.execution().operation(),c.execution().algorithm(),c.execution().online(),c.execution().messageHex());
        assertThrows(IllegalArgumentException.class,()->signing().authorize(new com.surprising.wallet.common.approval.SigningCandidate(c.signingRequestId(),c.approvalRequestIds(),c.command(),freshSession,null,null),"wallet"));
        assertThrows(IllegalArgumentException.class,()->signing().authorize(candidate(i,2),"wallet"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
    }
    @Test void finalAuthorizationRejectsChangedPaymentAndRegisteredKeyState() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");
        var changed=new WithdrawalIntent(i.id(),tenant,i.businessReference(),i.chain(),i.network(),i.assetSymbol(),i.recipient(),new BigDecimal("2"),i.maximumNetworkFee(),i.source(),i.initiatorId(),i.subject(), "external-test", "ADDRESS");
        assertThrows(IllegalArgumentException.class,()->signing().authorize(candidate(changed,1),"wallet"));
        jdbc.update("update approval.key set state='DISABLED' where tenant_id=? and key_id=?",tenant,evmKey);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(candidate(i,1),"wallet"));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.signing_transaction",Integer.class));assertFalse(service.status(tenant,i.id()).signingStarted());
    }
    @Test void finalWireEnvelopeKeepsAmountsBeyondDoublePrecisionExact() {
        policy(null,null,false,false,1);grantKey();var i=intent("9007199254740993.123456");service.submit(i,"wallet");
        assertEquals("AUTHORIZED",signing().authorize(candidate(i,1),"wallet").status());
    }
    @Test void concurrentRetriesReturnOneCommittedGrantAndReserveFeeOnlyOnce() throws Exception {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var c=candidate(i,1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var work=new ArrayList<java.util.concurrent.Callable<com.surprising.wallet.approval.service.ApprovalSigningService.Outcome>>();
            for(int n=0;n<8;n++)work.add(()->signing().authorize(c,"wallet"));
            var results=pool.invokeAll(work);var first=results.getFirst().get();for(var result:results)assertEquals(first,result.get());
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.fee_reservation",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.audit where action='SIGNING.AUTHORIZE'",Integer.class));
    }
    @Test void explicitEvmRecoveryCanChangeNativeSessionButCannotCreateAnotherPaymentOrReserveTheFeeTwice() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var original=candidate(i,1);
        var first=signing().authorize(original,"wallet");
        clock.time.set(NOW.plusSeconds(90000)); // Original grant expired; already-bound intent itself cannot expire.
        var execution=original.execution();var retryExecution=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,evmKey,1,
                execution.operation(),execution.algorithm(),List.of("b".repeat(64),"c".repeat(64)),execution.messageHex());
        var replay=new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),original.command(),retryExecution,
                "operator-7","recover original EVM account nonce after holder outage");
        incidentEvmCode.set("0x6000");
        try { assertThrows(IllegalArgumentException.class,()->signing().authorize(replay,"wallet")); }
        finally { incidentEvmCode.set("0x"); }
        var second=signing().authorize(replay,"wallet");assertNotEquals(first.grant().id(),second.grant().id());
        var firstClaims=com.surprising.wallet.common.approval.SigningAuthorization.verifyOriginalProof(first.grant().proof(),issuer.getPublic(),execution);
        var nextClaims=com.surprising.wallet.common.approval.SigningAuthorization.verify(second.grant().proof(),issuer.getPublic(),retryExecution,clock.instant());
        assertEquals(firstClaims.businessHash(),nextClaims.businessHash());assertEquals(firstClaims.requestId(),nextClaims.requestId());
        assertEquals(second,signing().authorize(replay,"wallet"));assertEquals(first,signing().authorize(original,"wallet"));
        for(var table:List.of("signing_transaction","signing_version","signing_slot","fee_reservation"))assertEquals(1,jdbc.queryForObject("select count(*) from approval."+table,Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.audit where action='SIGNING.AUTHORIZE_REPLAY' and detail->>'replayActor'='operator-7'",Integer.class));
    }
    @Test void operatorReplayAuditCannotChangeNoncePayloadParticipantsOfAdmittedSessionOrItsReason() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var original=candidate(i,1);signing().authorize(original,"wallet");
        var changedNonce=candidate(i,2);
        assertThrows(IllegalArgumentException.class,()->signing().authorize(new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),
                changedNonce.command(),changedNonce.execution(),"operator","recover original signing"),"wallet"));
        var execution=original.execution();var next=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,evmKey,1,
                execution.operation(),execution.algorithm(),List.of("b".repeat(64),"c".repeat(64)),execution.messageHex());
        var replay=new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),original.command(),next,"operator","recover original signing");
        signing().authorize(replay,"wallet");
        assertThrows(IllegalArgumentException.class,()->signing().authorize(new com.surprising.wallet.common.approval.SigningCandidate(replay.signingRequestId(),replay.approvalRequestIds(),
                replay.command(),next,"operator","rewrite previous recovery reason"),"wallet"));
        var sameSessionDifferentQuorum=new com.surprising.wallet.common.signing.MpcClient.Request(next.sessionId(),tenant,evmKey,1,next.operation(),next.algorithm(),execution.online(),next.messageHex());
        assertThrows(IllegalArgumentException.class,()->signing().authorize(new com.surprising.wallet.common.approval.SigningCandidate(replay.signingRequestId(),replay.approvalRequestIds(),
                replay.command(),sameSessionDifferentQuorum,"operator","recover original signing"),"wallet"));
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
    }
    @Test void freshNativeAttemptRequiresOriginalSlotAndExplicitCompleteAudit() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var original=candidate(i,1);
        var premature=new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),original.command(),original.execution(),"operator","recover missing original");
        assertThrows(IllegalArgumentException.class,()->signing().authorize(premature,"wallet"));
        assertThrows(IllegalArgumentException.class,()->new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),original.command(),original.execution(),"operator",null));
        signing().authorize(original,"wallet");
        var next=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,evmKey,1,original.execution().operation(),original.execution().algorithm(),original.execution().online(),original.execution().messageHex());
        assertThrows(IllegalArgumentException.class,()->signing().authorize(new com.surprising.wallet.common.approval.SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),original.command(),next,null,null),"wallet"));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
    }
    @Test void independentFeeBudgetIncludesPendingSignaturesAndCannotBeEvadedByConcurrentRequests() throws Exception {
        policy(null,null,false,false,1);grantKey();
        var candidates=new ArrayList<com.surprising.wallet.common.approval.SigningCandidate>();
        for(int n=1;n<=8;n++) { var i=intent("1");service.submit(i,"wallet");candidates.add(candidate(i,n)); }
        try(var pool=Executors.newFixedThreadPool(8)) {
            var work=new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(var c:candidates)work.add(()->{try { signing().authorize(c,"wallet");return true; } catch(IllegalArgumentException failure) { assertTrue(failure.getMessage().contains("fee budget"));return false; }});
            int issued=0;for(var result:pool.invokeAll(work))if(result.get())issued++;
            assertEquals(2,issued);
        }
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
        assertEquals(new BigDecimal("42000000000000"),jdbc.queryForObject("select sum(maximum_fee_atomic) from approval.fee_reservation",BigDecimal.class));
    }
    @Test void differentBusinessCannotReuseAnAlreadyReservedAccountNonce() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");var second=intent("1");service.submit(i,"wallet");service.submit(second,"wallet");
        signing().authorize(candidate(i,1),"wallet");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->signing().authorize(candidate(second,1),"wallet"));
        assertFalse(service.status(tenant,second.id()).signingStarted());assertEquals(1,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
    }
    @Test void missingGrantAuditRollsBackProofReceiptBindingAndFeeReservation() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var c=candidate(i,1);
        jdbc.execute("alter table approval.audit rename to audit_unavailable");
        try { assertThrows(RuntimeException.class,()->signing().authorize(c,"wallet")); }
        finally { jdbc.execute("alter table approval.audit_unavailable rename to audit"); }
        for(var table:List.of("signing_transaction","signing_version","signing_slot","signing_grant","fee_reservation"))
            assertEquals(0,jdbc.queryForObject("select count(*) from approval."+table,Integer.class));
        assertFalse(service.status(tenant,i.id()).signingStarted());assertEquals("AUTHORIZED",signing().authorize(c,"wallet").status());
    }
    @Test void expiredApprovalCommitsItsExpiryWithoutCreatingAnyGrant() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var c=candidate(i,1);clock.time.set(NOW.plusSeconds(3600));
        assertEquals("EXPIRED",signing().authorize(c,"wallet").status());
        assertEquals("EXPIRED",jdbc.queryForObject("select status from approval.request where id=?",String.class,i.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.signing_grant",Integer.class));
    }
    @Test void originalSigningReceiptsAndRequestBindingCannotBeClearedOrRewritten() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var c=candidate(i,1);signing().authorize(c,"wallet");
        for(var table:List.of("signing_transaction","signing_version","signing_slot","signing_grant","fee_reservation"))
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("delete from approval."+table));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("update approval.request set signing_transaction_id=null where id=?",i.id()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("update approval.request set status='EXPIRED' where id=?",i.id()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("update approval.signing_grant set proof='{}'"));
        assertEquals("AUTHORIZED",signing().authorize(c,"wallet").status());
    }
    @Test void ordinaryEvmWithdrawalChecksLiveAccountCodeBeforeIndependentSigning() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");var candidate=candidate(i,1);
        incidentEvmCode.set("0xef0100"+"9".repeat(40));
        try {
            assertThrows(IllegalArgumentException.class,()->signing().authorize(candidate,"wallet"));
            assertFalse(service.status(tenant,i.id()).signingStarted());
            incidentEvmCode.set("0xef0100"+INSTALLED_HOT_DELEGATE.substring(2));
            assertEquals("AUTHORIZED",signing().authorize(candidate,"wallet").status());
        } finally { incidentEvmCode.set("0x"); }
    }
    @Test void changingNativeFeeUnitsWithinTheRollingWindowCannotMixAtomicAmounts() {
        policy(null,null,false,false,1);grantKey();var i=intent("1");service.submit(i,"wallet");
        UUID tx=UUID.randomUUID();context.getBean(ApprovalSigningTransactionRepository.class).insert(new com.surprising.wallet.approval.model.ApprovalSigningTransaction(
                tx,tenant,"ETH","eip155:11155111","WITHDRAWAL","previous-independent-profile","previous-spend","a".repeat(64),NOW));
        var former=new com.surprising.wallet.common.approval.ApprovalAssetSnapshot("ETH","eip155:11155111","USDC","ETH","eip155:1",
                "0x3333333333333333333333333333333333333333",6,18,new BigDecimal("0.02"));
        context.getBean(ApprovalFeeReservationRepository.class).insert(tenant,tx,former,java.math.BigInteger.ONE,NOW);
        var failure=assertThrows(IllegalArgumentException.class,()->signing().authorize(candidate(i,1),"wallet"));
        assertTrue(failure.getMessage().contains("accounting domain changed"));assertFalse(service.status(tenant,i.id()).signingStarted());
    }

    @Test void unconfiguredOrUnenrolledTenantCannotObtainAutomaticApproval() {
        assertThrows(IllegalStateException.class,()->service.submit(intent("1"),"wallet-service"));
        var value=intent("1");
        var unknown=new WithdrawalIntent(value.id(),UUID.randomUUID(),value.businessReference(),value.chain(),value.network(),value.assetSymbol(),
                value.recipient(),value.amount(),value.maximumNetworkFee(),value.source(),value.initiatorId(),value.subject(), "external-test", "ADDRESS");
        assertThrows(IllegalArgumentException.class,()->service.submit(unknown,"wallet-service"));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.request",Integer.class));
    }
    @Test void thresholdsIncludeBoundaryAndPendingRequestsWithoutMixingTenantAsset() {
        policy(new BigDecimal("100"),new BigDecimal("150"),false,false,1);
        assertEquals("APPROVED",service.submit(intent("60"),"wallet-service").status());
        var pending=service.submit(intent("100"),"wallet-service");
        assertEquals(List.of("SINGLE_AMOUNT","ROLLING_24H_AMOUNT"),pending.reasons());
        assertEquals(List.of("ROLLING_24H_AMOUNT"),service.submit(intent("1"),"wallet-service").reasons());
        var p=context.getBean(ApprovalPolicyRepository.class).find(tenant,"ETH","eip155:11155111","USDC").orElseThrow();
        assertThrows(IllegalArgumentException.class,()->p.reasons(new WithdrawalIntent(UUID.randomUUID(),other,"ref","ETH","sepolia","USDC","address",
                BigDecimal.ONE,BigDecimal.ZERO,"API",UUID.randomUUID(),null, "external-test", "ADDRESS"),BigDecimal.ZERO,true));
        clock.time.set(NOW.plusSeconds(86401));
        assertEquals("APPROVED",service.submit(intent("60"),"wallet-service").status());
    }
    @Test void concurrentSmallRequestsCannotBypassRollingLimit() throws Exception {
        policy(null,new BigDecimal("100"),false,false,1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<java.util.concurrent.Callable<com.surprising.wallet.common.approval.ApprovalRequest>>();
            for(int i=0;i<12;i++)tasks.add(()->service.submit(intent("60"),"wallet-service"));
            int auto=0,pending=0;
            for(var result:pool.invokeAll(tasks)) { if(result.get().status().equals("APPROVED"))auto++;else pending++; }
            assertEquals(1,auto);assertEquals(11,pending);
        }
    }
    @Test void consoleAndNewAddressScenariosRequireReviewAndCannotSelfApprove() {
        policy(null,null,true,true,1);
        var request=service.submit(intent("1","address-one","CONSOLE",reviewer),"wallet-service");
        assertEquals(List.of("NEW_RECIPIENT","CONSOLE_WITHDRAWAL"),request.reasons());
        assertThrows(IllegalArgumentException.class,()->service.decide(tenant,request.intent().id(),reviewer,"APPROVE","checked",request.intentHash()));
        assertThrows(SecurityException.class,()->service.decide(other,request.intent().id(),reviewer2,"APPROVE","checked",request.intentHash()));
        assertThrows(IllegalArgumentException.class,()->service.decide(tenant,request.intent().id(),reviewer2,"APPROVE","checked","0".repeat(64)));
        assertEquals("APPROVED",service.decide(tenant,request.intent().id(),reviewer2,"APPROVE","checked",request.intentHash()).status());
        assertEquals("APPROVED",service.submit(intent("2","address-one","API",UUID.randomUUID()),"wallet-service").status());
        assertEquals("PENDING",service.submit(intent("2","address-two","API",UUID.randomUUID()),"wallet-service").status());
    }
    @Test void twoDistinctReviewersAreRequiredAndDecisionsAreImmutable() {
        policy(BigDecimal.ONE,null,false,false,2);
        var r=service.submit(intent("1"),"wallet-service");
        assertEquals("PENDING",service.decide(tenant,r.intent().id(),reviewer,"APPROVE","checked",r.intentHash()).status());
        assertEquals("PENDING",service.decide(tenant,r.intent().id(),reviewer,"APPROVE","checked",r.intentHash()).status());
        assertThrows(IllegalArgumentException.class,()->service.decide(tenant,r.intent().id(),reviewer,"REJECT","changed",r.intentHash()));
        assertEquals("APPROVED",service.decide(tenant,r.intent().id(),reviewer2,"APPROVE","checked independently",r.intentHash()).status());
        assertEquals(2,jdbc.queryForObject("select count(*) from approval.decision",Integer.class));
        assertThrows(SecurityException.class,()->service.configure(tenant,reviewer,
                new ApprovalWorkflowService.PolicySettings("ETH","eip155:11155111","USDC",1,null,null,false,false,false,1,3600)));
    }
    @Test void rejectionAndExpiryNeverCancelAnAlreadyStartedSignature() {
        policy(BigDecimal.ONE,null,false,false,1);
        var rejected=service.submit(intent("1"),"wallet-service");
        assertEquals("REJECTED",service.decide(tenant,rejected.intent().id(),reviewer,"REJECT","recipient not trusted",rejected.intentHash()).status());
        var expired=service.submit(intent("1"),"wallet-service");
        var approved=service.submit(intent("1"),"wallet-service");
        service.decide(tenant,approved.intent().id(),reviewer,"APPROVE","checked",approved.intentHash());
        new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status -> {
            UUID transaction=UUID.randomUUID();
            context.getBean(ApprovalSigningTransactionRepository.class).insert(new com.surprising.wallet.approval.model.ApprovalSigningTransaction(
                    transaction,tenant,"ETH","eip155:11155111","WITHDRAWAL",approved.intent().businessReference(),"test-original-spend","a".repeat(64),NOW));
            assertEquals(1,context.getBean(ApprovalRequestRepository.class).bindSigningTransaction(tenant,approved.intent().id(),transaction));
        });
        clock.time.set(NOW.plusSeconds(3600));
        assertEquals("EXPIRED",service.decide(tenant,expired.intent().id(),reviewer,"APPROVE","too late",expired.intentHash()).status());
        assertEquals("EXPIRED",service.status(tenant,expired.intent().id()).status());
        assertTrue(service.status(tenant,approved.intent().id()).signingStarted());
        assertEquals("APPROVED",service.status(tenant,approved.intent().id()).status());
    }
    @Test void existingIntentAndPolicyVersionCannotBeRewritten() {
        policy(BigDecimal.ONE,null,false,false,1);
        var i=intent("10");var r=service.submit(i,"wallet-service");
        assertEquals(r,service.submit(i,"wallet-service"));
        var altered=new WithdrawalIntent(i.id(),i.tenantId(),i.businessReference(),i.chain(),i.network(),i.assetSymbol(),i.recipient(),
                new BigDecimal("11"),i.maximumNetworkFee(),i.source(),i.initiatorId(),i.subject(), "external-test", "ADDRESS");
        assertThrows(IllegalArgumentException.class,()->service.submit(altered,"wallet-service"));
        policy(null,null,false,false,1);
        assertEquals(1,service.status(tenant,i.id()).policyVersion());
        assertEquals("PENDING",service.status(tenant,i.id()).status());
        assertThrows(IllegalArgumentException.class,()->service.configure(tenant,admin,
                new ApprovalWorkflowService.PolicySettings("ETH","eip155:11155111","USDC",1,null,null,false,false,false,1,3600)));
    }
    @Test void missingAuditRollsBackIntentAndReview() {
        policy(BigDecimal.ONE,null,false,false,1);
        var r=service.submit(intent("1"),"wallet-service");
        jdbc.execute("alter table approval.audit rename to audit_unavailable");
        try {
            assertThrows(RuntimeException.class,()->service.submit(intent("2"),"wallet-service"));
            assertThrows(RuntimeException.class,()->service.decide(tenant,r.intent().id(),reviewer,"APPROVE","checked",r.intentHash()));
        } finally { jdbc.execute("alter table approval.audit_unavailable rename to audit"); }
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.request",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.decision",Integer.class));
        assertEquals("PENDING",service.status(tenant,r.intent().id()).status());
    }
    @Test void independentCredentialsThrottleFailuresAndRejectWalletSessions() {
        var passwords=new com.surprising.wallet.common.security.PasswordHashes();
        var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);
        jdbc.update("update approval.member set password_hash=? where id=?",passwords.hash("independent-review-password-123"),reviewer);
        String email=reviewer+"@example.test";
        assertThrows(SecurityException.class,()->auth.require("cs_"+"a".repeat(64)));
        for(int i=0;i<5;i++) assertNull(auth.login(email,"invalid-password-123456"));
        assertNotNull(jdbc.queryForObject("select locked_until from approval.member where id=?",java.sql.Timestamp.class,reviewer));
        assertNull(auth.login(email,"independent-review-password-123"));
        clock.time.set(NOW.plusSeconds(901));
        var login=auth.login(email,"independent-review-password-123");
        assertNotNull(login);assertEquals(reviewer,auth.require(login.token()).memberId());
        assertEquals(64,login.principal().csrfToken().length());
        auth.logout(login.token());assertThrows(SecurityException.class,()->auth.require(login.token()));
        var second=auth.login(email,"independent-review-password-123");
        jdbc.update("update approval.member set status='DISABLED' where id=?",reviewer);
        assertThrows(SecurityException.class,()->auth.require(second.token()));
        assertNull(auth.login(email,"independent-review-password-123"));
    }
    @Test void trustedEnrollmentUsesIndependentPasswordAndCannotOverwriteIdentity() {
        var enroll=context.getBean(com.surprising.wallet.approval.service.ApprovalEnrollmentService.class);
        var auth=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class);
        var newTenant=UUID.randomUUID();var newMember=UUID.randomUUID();
        var command=new com.surprising.wallet.approval.service.ApprovalEnrollmentService.Enrollment(newTenant,"new-independent",newMember,
                "independent@example.test","Independent","APPROVER","independent-password-12345");
        enroll.enroll(command);
        assertEquals(newMember,auth.login(command.email(),command.password()).principal().memberId());
        assertThrows(RuntimeException.class,()->enroll.enroll(command));
        assertThrows(IllegalArgumentException.class,()->enroll.enroll(new com.surprising.wallet.approval.service.ApprovalEnrollmentService.Enrollment(
                newTenant,"different-slug",UUID.randomUUID(),"another@example.test","Another","APPROVER","independent-password-12345")));
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.member where tenant_id=?",Integer.class,newTenant));
    }
    @Test void trustedHostInvitesWalletAdministratorToSetOwnShortPassword() {
        var enroll=context.getBean(com.surprising.wallet.approval.service.ApprovalEnrollmentService.class);
        var member=UUID.randomUUID();
        var command=new com.surprising.wallet.approval.service.ApprovalEnrollmentService.AdminInvitation(
                tenant,"review-test",member,"admin-2@example.test","Tenant administrator");
        var activation=enroll.inviteAdministrator(command);
        assertEquals("INVITED",jdbc.queryForObject("select status from approval.member where id=?",String.class,member));
        assertNotEquals(activation.token(),jdbc.queryForObject("select activation_token_hash from approval.member where id=?",String.class,member));
        assertThrows(RuntimeException.class,()->enroll.inviteAdministrator(command));
        management().activate(activation.token(),"admin888");
        var actor=context.getBean(com.surprising.wallet.approval.service.ApprovalAuthenticationService.class)
                .verifyForDecision(tenant,member,"admin888");
        assertEquals("TENANT_ADMIN",actor.role());
        assertNull(jdbc.queryForObject("select activation_token_hash from approval.member where id=?",String.class,member));
    }
    @Test void walletRuntimeRoleCannotReadOrWriteIndependentApprovalState() throws Exception {
        String role="sw_test_approval_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create role "+role+" nologin");
        try(var connection=jdbc.getDataSource().getConnection();var statement=connection.createStatement()) {
            statement.execute("set role "+role);
            assertThrows(java.sql.SQLException.class,()->statement.executeQuery("select * from approval.member"));
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("update approval.policy set required_reviewers=1"));
            assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("update approval.request set status='APPROVED'"));
            statement.execute("reset role");
        } finally { jdbc.execute("drop role "+role); }
    }
    @Test void admissionLockDoesNotBlockAnExistingReviewAuditForeignKey() {
        policy(BigDecimal.ONE,null,false,false,1);
        var request=service.submit(intent("1"),"wallet-service");
        try(var pool=Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->{
                assertTrue(context.getBean(ApprovalTenantRepository.class).lockActive(tenant));
                var result=pool.submit(()->service.decide(tenant,request.intent().id(),reviewer,"APPROVE","checked",request.intentHash()));
                try { assertEquals("APPROVED",result.get(3,java.util.concurrent.TimeUnit.SECONDS).status()); }
                catch(Exception failure) { throw new AssertionError("admission lock blocked independent decision foreign-key/audit completion",failure); }
            });
        }
    }
    @Test void reviewAllPresetKeepsLaterKnownRecipientsBehindHumanReview() {
        service.configure(tenant,admin,new ApprovalWorkflowService.PolicySettings("ETH","eip155:11155111","USDC",0,null,null,true,false,false,1,3600));
        var first=service.submit(intent("0.000001"),"wallet-service");
        assertEquals(List.of("ALL_WITHDRAWALS"),first.reasons());
        service.decide(tenant,first.intent().id(),reviewer,"APPROVE","checked",first.intentHash());
        var second=service.submit(intent("1000000000"),"wallet-service");
        assertEquals("PENDING",second.status());assertEquals(List.of("ALL_WITHDRAWALS"),second.reasons());
        assertThrows(IllegalArgumentException.class,()->new ApprovalWorkflowService.PolicySettings("ETH","eip155:11155111","USDC",1,null,null,null,true,true,1,3600));
    }
    @Test void publicKeyRegistrationRequiresObservedIdentityAndMonotonicRefresh() {
        var nativeRegistry=context.getBean(com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.class);
        var registry=context.getBean(com.surprising.wallet.approval.service.ApprovalKeyRegistryService.class);
        var parties=List.of("a".repeat(64),"b".repeat(64),"c".repeat(64));
        var keyId=UUID.randomUUID();
        var initial=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,keyId,1,
                com.surprising.wallet.common.signing.MpcClient.Operation.CREATE_KEY,com.surprising.wallet.common.signing.MpcClient.Algorithm.ECDSA_SECP256K1,parties,"");
        String publicKey="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
        org.mockito.Mockito.when(nativeRegistry.observe(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            com.surprising.wallet.common.signing.MpcClient.Request request=call.getArgument(0);
            return new com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.Ceremony(request,publicKey,null,
                    parties.stream().map(p->new com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.Observation(p,"f".repeat(64),publicKey,null)).toList());
        });
        assertEquals(1,registry.register("EVM",initial,"wallet-service").epoch());
        assertEquals(1,registry.register("EVM",initial,"wallet-service").epoch());
        assertEquals(1,jdbc.queryForObject("select count(*) from approval.audit where action='KEY.REGISTER'",Integer.class));
        assertThrows(IllegalArgumentException.class,()->registry.register("TRON",initial,"wallet-service"));
        var skipped=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,keyId,3,
                com.surprising.wallet.common.signing.MpcClient.Operation.REFRESH,initial.algorithm(),parties,"");
        assertThrows(IllegalArgumentException.class,()->registry.register("EVM",skipped,"wallet-service"));
        var refresh=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,keyId,2,
                com.surprising.wallet.common.signing.MpcClient.Operation.REFRESH,initial.algorithm(),parties,"");
        assertEquals(2,registry.register("EVM",refresh,"wallet-service").epoch());
        assertThrows(IllegalArgumentException.class,()->registry.register("EVM",initial,"wallet-service"));
        assertTrue(context.getBean(ApprovalKeyRepository.class).find(other,keyId).isEmpty());
        var otherTenantCeremony=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),other,keyId,1,
                com.surprising.wallet.common.signing.MpcClient.Operation.CREATE_KEY,initial.algorithm(),parties,"");
        assertEquals(other,registry.register("EVM",otherTenantCeremony,"other-wallet-service").tenantId());
        assertEquals(1,context.getBean(ApprovalKeyRepository.class).find(other,keyId).orElseThrow().epoch());
        assertEquals(2,context.getBean(ApprovalKeyRepository.class).find(tenant,keyId).orElseThrow().epoch());
        jdbc.update("update approval.key set state='DISABLED' where tenant_id=? and key_id=?",tenant,keyId);
        assertThrows(IllegalArgumentException.class,()->registry.register("EVM",refresh,"wallet-service"));
    }
    @Test void unknownNativeCeremonyOrMissingAuditCannotCreateAnIndependentKey() {
        var nativeRegistry=context.getBean(com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.class);
        var registry=context.getBean(com.surprising.wallet.approval.service.ApprovalKeyRegistryService.class);
        var request=new com.surprising.wallet.common.signing.MpcClient.Request(UUID.randomUUID(),tenant,UUID.randomUUID(),1,
                com.surprising.wallet.common.signing.MpcClient.Operation.CREATE_KEY,com.surprising.wallet.common.signing.MpcClient.Algorithm.ECDSA_SECP256K1,
                List.of("a".repeat(64),"b".repeat(64),"c".repeat(64)),"");
        org.mockito.Mockito.when(nativeRegistry.observe(request)).thenThrow(new IllegalStateException("holder unavailable"));
        assertThrows(IllegalStateException.class,()->registry.register("EVM",request,"wallet-service"));
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.key",Integer.class));
        org.mockito.Mockito.reset(nativeRegistry);
        org.mockito.Mockito.when(nativeRegistry.observe(request)).thenReturn(new com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.Ceremony(request,
                "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",null,
                request.online().stream().map(p->new com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.Observation(p,"f".repeat(64),
                    "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",null)).toList()));
        jdbc.execute("alter table approval.audit rename to audit_unavailable");
        try { assertThrows(RuntimeException.class,()->registry.register("EVM",request,"wallet-service")); }
        finally { jdbc.execute("alter table approval.audit_unavailable rename to audit"); }
        assertEquals(0,jdbc.queryForObject("select count(*) from approval.key",Integer.class));
    }
    @Test void requestFreezesIndependentAssetFactsAndBindsThemToTheReviewedHash() {
        policy(BigDecimal.ONE,null,false,false,1);
        var command=intent("1");var submitted=service.submit(command,"wallet-service");
        assertEquals("0x3333333333333333333333333333333333333333",submitted.assetSnapshot().tokenIdentifier());
        assertEquals("eip155:11155111",submitted.assetSnapshot().networkIdentity());
        assertNotEquals(command.hash(mapper()),submitted.intentHash());
        var stored=service.status(tenant,command.id());
        assertEquals(submitted.assetSnapshot(),stored.assetSnapshot());
        assertEquals(submitted.intentHash(),stored.intentHash());
    }
    @Test void mainnetCannotUseTestnetPolicyAndRollingVolumesDoNotMixNetworks() {
        policy(null,new BigDecimal("100"),false,false,1);
        var original=intent("60");
        var mainnet=new WithdrawalIntent(original.id(),tenant,original.businessReference(),"ETH","eip155:1","USDC",original.recipient(),
                original.amount(),original.maximumNetworkFee(),"API",original.initiatorId(),original.subject(), "external-test", "ADDRESS");
        assertThrows(IllegalStateException.class,()->service.submit(mainnet,"wallet-service"));
        service.configure(tenant,admin,new ApprovalWorkflowService.PolicySettings("ETH","eip155:1","USDC",0,null,new BigDecimal("100"),false,false,false,1,3600));
        assertEquals("APPROVED",service.submit(intent("60"),"wallet-service").status());
        assertEquals("APPROVED",service.submit(mainnet,"wallet-service").status());
        assertEquals(List.of("ROLLING_24H_AMOUNT"),service.submit(intent("60"),"wallet-service").reasons());
    }
    private ObjectMapper mapper() { return context.getBean(ObjectMapper.class); }
    @Configuration @EnableTransactionManagement static class TxConfiguration { }
    private static class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(NOW);
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time.get();}
    }
}
