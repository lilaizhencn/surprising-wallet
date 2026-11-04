package com.surprising.wallet.custody;

import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec;
import com.surprising.wallet.service.MpcAuthorizationService;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PG18 Wallet proof boundary only. Authority transport/issuer and native DKG are explicit test substitutes. */
@EnabledIfEnvironmentVariable(named="SW_TEST_CUSTODY_DB_URL",matches=".+")
class UtxoMpcAuthorizationIntegrationTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ObjectMapper json=new ObjectMapper();
    private JdbcTemplate jdbc;private MpcSigningTestSupport fixture;private MpcAuthorizationService service;
    private WalletWithdrawalApprovalRepository approvals;private WalletMpcSignAttemptRepository attempts;
    private WithdrawalApprovalClient authority;private KeyPair issuer;
    private final List<ApprovalRequest> reviewed=new ArrayList<>();
    private final List<SigningCandidate> candidates=new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        var ds=CustodyIntegrationDatabase.dataSource();CustodyIntegrationDatabase.reset(ds);jdbc=new JdbcTemplate(ds);
        jdbc.update("update chain_profile set enabled=false where chain='BTC'");jdbc.update("update chain_profile set enabled=true where chain='BTC' and network='regtest'");
        new CustodyTenantChainTableRepository(jdbc).setStatus(TENANT,"BTC","ACTIVE",
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
        fixture=new MpcSigningTestSupport(jdbc);var source=fixture.address(jdbc,TENANT,ChainType.BTC,1,0,"HOT");
        UUID custody=UUID.randomUUID();jdbc.update("insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child) values (?,?,?,'BTC','regtest',?,'batch-proof','API','ACTIVE',1,0)",custody,TENANT,source.getId(),source.getAddress());
        jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,'BTC','BTC','batch-principal',0,1,1)",TENANT);
        approvals=new WalletWithdrawalApprovalRepository(jdbc,json);attempts=new WalletMpcSignAttemptRepository(jdbc,json);
        var orders=new WithdrawalOrderRepository(jdbc);var publicOrders=new CustodyWithdrawalRepository(jdbc);
        var asset=new ApprovalAssetSnapshot("BTC","regtest","BTC","BTC","utxo:"+"a".repeat(64),null,8,8,new BigDecimal("0.01"));
        var profile=BitcoinLikeNetwork.REGTEST;String publicKey=fixture.signing.requireKey(TENANT,source.getMpcKeyId()).publicKeyHex();
        for(int n=0;n<2;n++) {
            String recipient=MpcUtxoTransactionCodec.address("BTC",profile,HexFormat.of().formatHex(org.bitcoinj.crypto.ECKey.fromPublicOnly(HexFormat.of().parseHex(publicKey)).getPubKeyPoint().multiply(java.math.BigInteger.valueOf(n+3)).getEncoded(true)));
            String reference="CW-BATCH-"+n;BigDecimal amount=new BigDecimal(n==0?"0.4":"0.6");
            var intent=new WithdrawalIntent(UUID.randomUUID(),TENANT,reference,"BTC","regtest","BTC",recipient,amount,new BigDecimal("0.01"),"API",UUID.randomUUID(),"customer-"+n,"merchant-"+n,"TENANT_POOL");
            long order=orders.createForTenant(TENANT,reference,1,"BTC","BTC",source.getAddress(),"batch-principal",recipient,amount,BigDecimal.ZERO);
            orders.updateStatus(TENANT,"BTC",reference,"SIGNING",source.getAddress(),null,null);
            publicOrders.insert(intent.id(),TENANT,custody,order,reference,intent.externalReference(),"batch-"+n,"BTC","BTC",recipient,amount,BigDecimal.ZERO,"FROZEN","API_KEY",intent.initiatorId().toString(),intent.subject(),intent.fundingSource());
            approvals.insert(intent.id(),order,intent);
            reviewed.add(new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),2,"APPROVED",false,Instant.now(),Instant.now().plusSeconds(3600)));
        }
        var inputs=List.of(new UtxoSigningRequest.Input("c".repeat(64),0,source.getAddress(),new BigDecimal("0.8"),new UtxoSigningRequest.Key(1,source.getBiz(),0,null)),
                new UtxoSigningRequest.Input("d".repeat(64),1,source.getAddress(),new BigDecimal("0.7"),new UtxoSigningRequest.Key(1,source.getBiz(),0,null)));
        var request=new UtxoSigningRequest(TENANT,UUID.randomUUID(),new UtxoSigningRequest.Asset("BTC",profile,1,8,0),UtxoSigningRequest.Operation.WITHDRAWAL,null,inputs,
                reviewed.stream().map(r->new UtxoSigningRequest.Payment(r.intent().businessReference(),1,r.intent().recipient(),r.intent().amount(),BigDecimal.ZERO)).toList(),source.getAddress(),2,546);
        var unsigned=MpcUtxoTransactionCodec.prepare("BTC",profile,inputs.stream().map(i->new MpcUtxoTransactionCodec.Input(i.txId(),i.vout(),request.asset().atomic(i.amount()),publicKey)).toList(),request.payments().stream().map(p->new MpcUtxoTransactionCodec.Payment(p.address(),request.asset().atomic(p.amount()))).toList(),source.getAddress(),2,546);
        var payload=new UtxoSigningPayload(new UtxoSigningTask(42,request),unsigned,List.of(source.getMpcKeyId(),source.getMpcKeyId()),source.getMpcKeyId());
        var requests=new WalletMpcSignRequestRepository(jdbc);
        for(int index=0;index<2;index++) {
            String message=HexFormat.of().formatHex(MpcUtxoTransactionCodec.digest(unsigned,index));
            var command=new MpcSignCommand(TENANT,source.getMpcKeyId(),1,ChainType.BTC,MpcSignCommand.BusinessType.WITHDRAWAL,request.requestId().toString(),"input/"+index,MpcSignCommand.Purpose.TRANSACTION,"UTXO_MPC_JSON_V1",HexFormat.of().formatHex(json.writeValueAsBytes(payload)),message);
            var execution=new MpcClient.Request(UUID.randomUUID(),TENANT,source.getMpcKeyId(),1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,MpcSigningTestSupport.PARTIES.subList(0,2),message);
            var candidate=new SigningCandidate(UUID.randomUUID(),reviewed.stream().map(r->r.intent().id()).toList(),command,execution,null,null);candidates.add(candidate);
            new TransactionTemplate(fixture.manager).executeWithoutResult(status->{requests.reserve(candidate.signingRequestId(),command,command.businessReferences(json));attempts.create(candidate.signingRequestId(),execution,null,null);requests.pointToAttempt(TENANT,candidate.signingRequestId(),execution.sessionId());});
        }
        authority=mock(WithdrawalApprovalClient.class);issuer=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] encoded=issuer.getPublic().getEncoded();
        when(authority.verificationKeyHex()).thenReturn(HexFormat.of().formatHex(encoded,encoded.length-32,encoded.length));
        service=com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,fixture.manager,authority);
    }
    private void approveAll() { for(var r:reviewed)approvals.record(TENANT,r.intent().id(),"APPROVED",r); }
    private WithdrawalApprovalClient.GrantResponse grant(SigningCandidate candidate,boolean omitOne) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(new HashSet<>(reviewed.stream().map(r->r.intent().id()).toList()),new HashSet<>(candidate.approvalRequestIds()));
        var command=candidate.command();var e=candidate.execution();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var claims=new SigningAuthorization.Claims(UUID.randomUUID(),candidate.signingRequestId(),TENANT,command.keyId(),1,e.sessionId(),"SIGN",e.algorithm().name(),e.online(),SigningAuthorization.hash(HexFormat.of().parseHex(e.messageHex())),
                "BTC","regtest",ApprovalTransactionHash.business(omitOne?reviewed.subList(0,1):reviewed,command),"WITHDRAWAL",now,now.plusSeconds(120));
        return new WithdrawalApprovalClient.GrantResponse("AUTHORIZED",new SigningGrant(claims.authorizationId(),TENANT,candidate.signingRequestId(),e.sessionId(),SigningAuthorization.sign(claims,issuer.getPrivate()),now,claims.expiresAt()));
    }
    @Test void eachInputProofBindsAllOrdersAndPersistsOnceWhilePrincipalRemainsFrozen() {
        approveAll();when(authority.authorize(any())).thenAnswer(call->grant(call.getArgument(0),false));
        for(var candidate:candidates) {
            var proof=service.authorize(TENANT,candidate.signingRequestId(),candidate.execution().sessionId());
            assertEquals(proof,attempts.find(TENANT,candidate.execution().sessionId()).orElseThrow().authorization());
        }
        doThrow(new IllegalStateException("authority now unavailable")).when(authority).authorize(any());
        var first=candidates.getFirst();assertNotNull(service.authorize(TENANT,first.signingRequestId(),first.execution().sessionId()));
        verify(authority,times(2)).authorize(any());
        assertEquals(2,jdbc.queryForObject("select count(*) from custody_audit_log where action='MPC.AUTHORIZE'",Integer.class));
        assertEquals(0,BigDecimal.ONE.compareTo(jdbc.queryForObject("select locked_balance from ledger_balance where chain='BTC' and account_id='batch-principal'",BigDecimal.class)));
    }
    @Test void aPendingPaymentStopsEveryInputBeforeAnyAuthorityCall() {
        var first=reviewed.getFirst();approvals.record(TENANT,first.intent().id(),"APPROVED",first);
        var candidate=candidates.getFirst();assertThrows(IllegalStateException.class,()->service.authorize(TENANT,candidate.signingRequestId(),candidate.execution().sessionId()));
        verifyNoInteractions(authority);assertNull(attempts.find(TENANT,candidate.execution().sessionId()).orElseThrow().authorization());
    }
    @Test void aCryptographicallyValidProofForOnlyOnePaymentCannotAuthorizeTheBatch() {
        approveAll();when(authority.authorize(any())).thenAnswer(call->grant(call.getArgument(0),true));
        var candidate=candidates.getFirst();assertThrows(IllegalStateException.class,()->service.authorize(TENANT,candidate.signingRequestId(),candidate.execution().sessionId()));
        assertNull(attempts.find(TENANT,candidate.execution().sessionId()).orElseThrow().authorization());
        assertEquals(0,jdbc.queryForObject("select count(*) from custody_audit_log where action='MPC.AUTHORIZE'",Integer.class));
    }
    @Test void auditFailureRollsBackProofPersistenceBeforeItCanBeDispatched() {
        approveAll();when(authority.authorize(any())).thenAnswer(call->grant(call.getArgument(0),false));var candidate=candidates.getFirst();
        jdbc.execute("alter table custody_audit_log rename to audit_unavailable");
        try { assertThrows(RuntimeException.class,()->service.authorize(TENANT,candidate.signingRequestId(),candidate.execution().sessionId())); }
        finally { jdbc.execute("alter table audit_unavailable rename to custody_audit_log"); }
        assertNull(attempts.find(TENANT,candidate.execution().sessionId()).orElseThrow().authorization());
    }
}
