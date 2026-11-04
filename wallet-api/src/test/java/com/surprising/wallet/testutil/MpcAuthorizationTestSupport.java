package com.surprising.wallet.testutil;

import com.surprising.wallet.config.WithdrawalApprovalProperties;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.MpcAuthorizationService;
import com.surprising.wallet.service.MpcNonceAdmissionService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real authority client for native probes; no issuer key, automatic approvals or unsigned fallback. */
public final class MpcAuthorizationTestSupport {
    private MpcAuthorizationTestSupport() { }
    public static MpcAuthorizationService service(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) {
        var properties=new WithdrawalApprovalProperties(null,null,null,null,null,null,null);
        String file=System.getenv("SW_TEST_APPROVAL_CLIENT_PROFILE");
        if(file!=null && !file.isBlank())try {
            properties=json.readValue(Files.readAllBytes(Path.of(file)),WithdrawalApprovalProperties.class);
        } catch(java.io.IOException failure) { throw new IllegalArgumentException("test approval client profile unavailable"); }
        return service(jdbc,json,manager,new WithdrawalApprovalClient(properties,json));
    }
    public static MpcAuthorizationService service(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,WithdrawalApprovalClient authority) {
        return new MpcAuthorizationService(new WalletMpcSignRequestRepository(jdbc),new WalletMpcSignAttemptRepository(jdbc,json),
                new WalletWithdrawalApprovalRepository(jdbc,json),new CustodyAuditRepository(jdbc),authority,json,manager,
                new WalletMpcKeyRepository(jdbc),new WalletMpcKeyEpochRepository(jdbc,json),new WalletMpcRelayRepository(jdbc,json),new CustodyTenantUserRepository(jdbc),
                new MpcNonceAdmissionService(new EvmNonceRepository(jdbc),new AccountSequenceRepository(jdbc),new WalletMpcKeyRepository(jdbc),
                        new WalletMpcSignRequestRepository(jdbc),new WalletMpcSignAttemptRepository(jdbc,json),json),
                new CustodyTenantChainTableRepository(jdbc), new CustodyHotWalletRepository(jdbc),
                new CustodyHotWalletInventoryObservationRepository(jdbc),
                new CustodyAddressRepository(jdbc),new ChainAddressRepository(jdbc));
    }
    /** Explicit DB-only substitute. Actual native probes must use service(), never this helper. */
    public static MpcAuthorizationService forBoundaryTests(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) {
        var service=org.mockito.Mockito.spy(service(jdbc,json,manager));
        org.mockito.Mockito.doAnswer(call->registerFixture(jdbc,json,call.getArgument(0),call.getArgument(1)))
                .when(service).registerKey(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        return service;
    }
    public static com.surprising.wallet.model.mpc.MpcKey registerFixture(JdbcTemplate jdbc,ObjectMapper json,java.util.UUID tenant,java.util.UUID keyId) {
        var key=new WalletMpcKeyRepository(jdbc).find(tenant,keyId).orElseThrow();
        var receipt=json.createObjectNode().put("tenantId",tenant.toString()).put("keyId",keyId.toString()).put("keyGroup",key.keyGroup())
                .put("epoch",key.activeEpoch()).put("publicKeyHex",key.publicKeyHex()).put("algorithm",key.algorithm()).put("state","ACTIVE").putNull("publicAddress");
        var epochs=new WalletMpcKeyEpochRepository(jdbc,json);
        if(epochs.registration(tenant,keyId,key.activeEpoch()).isEmpty() && epochs.register(tenant,keyId,key.activeEpoch(),receipt)!=1
                && epochs.registration(tenant,keyId,key.activeEpoch()).filter(receipt::equals).isEmpty())
            throw new IllegalStateException("DB-only fixture key registration failed");
        return key;
    }
}
