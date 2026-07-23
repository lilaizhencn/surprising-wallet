package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.surprising.wallet.custody.model.CustodyPrincipal.ActorType;
import com.surprising.wallet.custody.service.CustodyAssetRecoveryService.ApproveCommand;
import com.surprising.wallet.custody.gateway.CustodyAssetRecoveryChainGateway;
import com.surprising.wallet.custody.repository.CustodyAssetRecoveryRepository;
import com.surprising.wallet.custody.service.CustodyAssetRecoveryService;
import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.gateway.CustodyAssetRecoveryChainGateway.ExecutionRequest;
import com.surprising.wallet.custody.service.CustodyAssetRecoveryService.SubmitCommand;
import com.surprising.wallet.custody.gateway.CustodyAssetRecoveryChainGateway.Verification;
import com.surprising.wallet.custody.gateway.CustodyAssetRecoveryChainGateway.VerificationRequest;

class CustodyAssetRecoveryIntegrationTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = CustodyIntegrationDatabase.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        CustodyIntegrationDatabase.reset(dataSource);
    }

    @Test
    void tenantSubmissionIsVerifiedApprovedAndExecutedWithOriginalDerivation() {
        Fixture fixture = fixture();
        FakeGateway gateway = new FakeGateway();
        CustodyAssetRecoveryService service = new CustodyAssetRecoveryService(
                new CustodyAssetRecoveryRepository(jdbc), new CustodyRepository(jdbc), jdbc,
                List.of(gateway), new CustodyJacksonConfiguration().custodyObjectMapper());
        var command = new CustodyAssetRecoveryService.SubmitCommand(
                "BNB", "ETH", "USDT", "0x1111111111111111111111111111111111111111",
                "0xwrong-chain-transfer", 7L, fixture.address(), "3.25");

        var submitted = service.submit(fixture.tenantPrincipal(), command, "127.0.0.1");
        assertEquals("VERIFIED", submitted.status());
        assertEquals(fixture.custodyAddressId(), submitted.custodyAddressId());
        assertAmount("3.25", submitted.verifiedAmount());
        assertEquals(18, submitted.confirmations());
        assertEquals("VERIFIED", service.verify(
                fixture.platformPrincipal(), submitted.id(), "127.0.0.1").status());

        var duplicate = service.submit(fixture.tenantPrincipal(), command, "127.0.0.1");
        assertEquals(submitted.id(), duplicate.id());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from custody_asset_recovery", Integer.class));

        var approved = service.approve(fixture.platformPrincipal(), submitted.id(),
                new CustodyAssetRecoveryService.ApproveCommand(
                        "0x9999999999999999999999999999999999999999"), "127.0.0.1");
        assertEquals("APPROVED", approved.status());
        var recovered = service.execute(
                fixture.platformPrincipal(), submitted.id(), "127.0.0.1");
        assertEquals("BROADCAST", recovered.status());
        gateway.confirmed = false;
        assertEquals("BROADCAST", service.confirm(
                fixture.platformPrincipal(), submitted.id(), "127.0.0.1").status());
        gateway.confirmed = true;
        recovered = service.confirm(
                fixture.platformPrincipal(), submitted.id(), "127.0.0.1");
        assertEquals("RECOVERED", recovered.status());
        assertEquals("0xrecovery-transaction", recovered.recoveryTxHash());
        assertNotNull(gateway.executedSource);
        assertEquals("BNB", gateway.executedSource.getChain());
        assertEquals(fixture.address(), gateway.executedSource.getAddress());
        assertEquals(fixture.derivationSubject(), gateway.executedSource.getUserId());
        assertEquals(fixture.namespace(), gateway.executedSource.getBiz());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from custody_audit_log
                 where tenant_id = ? and action = 'ASSET_RECOVERY.EXECUTE'
                """, Integer.class, fixture.tenantId()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from custody_event
                 where tenant_id = ? and event_type = 'ASSET_RECOVERY.RECOVERED'
                """, Integer.class, fixture.tenantId()));
    }

    @Test
    void unownedDestinationIsRejectedBeforeARecoveryCaseIsCreated() {
        Fixture fixture = fixture();
        CustodyAssetRecoveryService service = new CustodyAssetRecoveryService(
                new CustodyAssetRecoveryRepository(jdbc), new CustodyRepository(jdbc), jdbc,
                List.of(new FakeGateway()), new CustodyJacksonConfiguration().custodyObjectMapper());
        var command = new CustodyAssetRecoveryService.SubmitCommand(
                "BNB", "ETH", "BNB", null, "0xunknown", 0L,
                "0x8888888888888888888888888888888888888888", null);
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(fixture.tenantPrincipal(), command, "127.0.0.1"));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from custody_asset_recovery", Integer.class));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID tenantUserId = UUID.randomUUID();
        UUID custodyAddressId = UUID.randomUUID();
        int namespace = jdbc.queryForObject(
                "select nextval('custody_derivation_namespace_seq')::integer", Integer.class);
        int subject = jdbc.queryForObject(
                "select nextval('custody_derivation_subject_index_seq')::integer", Integer.class);
        String address = "0x" + suffix + suffix.substring(0, 8);
        jdbc.update("""
                insert into custody_tenant(id, slug, name, derivation_namespace)
                values (?, ?, 'Asset recovery integration', ?)
                """, tenantId, "recovery-" + suffix.substring(0, 16), namespace);
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, ?, 'Recovery administrator', 'hash', 'TENANT_ADMIN', 'ACTIVE')
                """, tenantUserId, tenantId, "recovery-" + suffix.substring(0, 10) + "@test.invalid");
        Long chainAddressId = jdbc.queryForObject("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                    address, owner_address, derivation_path, wallet_role, enabled)
                values (?, 'ETH', 'ETH', ?, ?, ?, 0, ?, ?, ?, 'DEPOSIT', true)
                returning id
                """, Long.class, tenantId, address, Integer.toUnsignedLong(subject), namespace,
                address, address, "m/44/60/" + namespace + "/" + subject + "/0");
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address, subject,
                    source, status, derivation_subject, derivation_child)
                values (?, ?, ?, 'ETH', 'integration', ?, 'wrong-chain-user',
                        'API', 'ACTIVE', ?, 0)
                """, custodyAddressId, tenantId, chainAddressId, address, subject);
        Long bnbAddressId = jdbc.queryForObject("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                    address, owner_address, derivation_path, wallet_role, enabled)
                values (?, 'BNB', 'BNB', ?, ?, ?, 0, ?, ?, ?, 'DEPOSIT', true)
                returning id
                """, Long.class, tenantId, address, Integer.toUnsignedLong(subject), namespace,
                address, address, "m/44/60/" + namespace + "/" + subject + "/0");
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address, subject,
                    source, status, derivation_subject, derivation_child)
                values (?, ?, ?, 'BNB', 'integration', ?, 'wrong-chain-user',
                        'API', 'ACTIVE', ?, 0)
                """, UUID.randomUUID(), tenantId, bnbAddressId, address, subject);
        CustodyPrincipal tenant = new CustodyPrincipal(
                CustodyPrincipal.ActorType.TENANT_USER, tenantUserId, tenantId,
                "recovery-tenant", "TENANT_ADMIN", Set.of("*"));
        CustodyPrincipal platform = new CustodyPrincipal(
                CustodyPrincipal.ActorType.PLATFORM_USER, UUID.randomUUID(), null,
                null, "PLATFORM_ADMIN", Set.of("*"));
        return new Fixture(tenantId, custodyAddressId, address,
                Integer.toUnsignedLong(subject), namespace, tenant, platform);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static final class FakeGateway implements CustodyAssetRecoveryChainGateway {
        private ChainAddressRecord executedSource;
        private boolean confirmed = true;

        @Override
        public boolean supports(String chain) {
            return "BNB".equals(chain);
        }

        @Override
        public Verification verify(VerificationRequest request) {
            assertEquals("BNB", request.chain());
            assertEquals(7L, request.requestedLogIndex());
            return new Verification(
                    request.tokenContract(), 6, 7L, new BigDecimal("3.25"),
                    123_456L, "0xcanonical-block", 18,
                    "{\"canonical\":true,\"nativeBalanceForGas\":\"0.01\"}");
        }

        @Override
        public String execute(ExecutionRequest request) {
            executedSource = request.source();
            assertAmount("3.25", request.amount());
            assertEquals("0x9999999999999999999999999999999999999999",
                    request.recoveryAddress());
            return "0xrecovery-transaction";
        }

        @Override
        public boolean confirmed(String chain, String txHash) {
            assertEquals("BNB", chain);
            assertEquals("0xrecovery-transaction", txHash);
            return confirmed;
        }
    }

    private record Fixture(
            UUID tenantId, UUID custodyAddressId, String address,
            long derivationSubject, int namespace, CustodyPrincipal tenantPrincipal,
            CustodyPrincipal platformPrincipal) {
    }
}
