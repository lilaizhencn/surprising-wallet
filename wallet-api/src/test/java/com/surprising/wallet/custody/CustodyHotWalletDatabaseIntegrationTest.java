package com.surprising.wallet.custody;

import com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class CustodyHotWalletDatabaseIntegrationTest {
    private JdbcTemplate jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        var source = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(source);
        jdbc = new JdbcTemplate(source);
        jdbc.update("insert into custody_tenant(id,slug,name,derivation_namespace) values (?,?,'Hot wallet test',nextval('custody_derivation_namespace_seq'))",
                tenant, "hot-wallet-test-" + tenant);
        jdbc.update("insert into custody_tenant_user(id,tenant_id,email,display_name,password_hash,role,status) "
                        + "values (?,?,?,'Hot wallet admin','test-only-hash','TENANT_ADMIN','ACTIVE')",
                actor, tenant, "hot-wallet-" + tenant + "@example.test");
        new CustodyTenantChainTableRepository(jdbc).setStatus(tenant, "ETH", "ACTIVE", actor);
    }

    @Test void multipleDistinctStandbyAddressesAndAssetLimitsRemainTenantOwned() {
        UUID firstKey = UUID.randomUUID(), secondKey = UUID.randomUUID();
        UUID firstAddress = address(1, firstKey), secondAddress = address(2, secondKey);
        UUID firstWallet = UUID.randomUUID(), secondWallet = UUID.randomUUID();
        var wallets = new CustodyHotWalletRepository(jdbc);
        assertEquals(1, wallets.insertStandby(firstWallet, tenant, firstAddress, firstKey,
                "ETH", "sepolia", "Reserve A", actor));
        assertEquals(1, wallets.insertStandby(secondWallet, tenant, secondAddress, secondKey,
                "ETH", "sepolia", "Reserve B", actor));
        assertEquals(2, wallets.list(tenant, "ETH").size());
        assertTrue(wallets.list(tenant, "ETH").stream().allMatch(row -> "STANDBY".equals(row.status())));
        assertEquals(firstWallet, wallets.findByKey(tenant, "ETH", firstKey).orElseThrow().id());
        var quarantinedAt = java.time.Instant.now();
        assertEquals(1, wallets.quarantine(tenant, firstWallet, actor, quarantinedAt, "suspected key leak"));
        assertEquals("QUARANTINED", wallets.findByKey(tenant, "ETH", firstKey).orElseThrow().status());
        assertEquals("suspected key leak", wallets.find(tenant, firstWallet).orElseThrow().quarantineReason());
        assertEquals(1, wallets.confirmQuarantineFence(tenant, firstWallet, actor, quarantinedAt));
        assertNotNull(wallets.find(tenant, firstWallet).orElseThrow().quarantineFenceConfirmedAt());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_hot_wallet set status='ACTIVE',quarantine_actor_id=null,quarantine_at=null,quarantine_reason=null,quarantine_fence_confirmed_at=null where id=?",
                firstWallet));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_hot_wallet set quarantine_reason='rewritten event' where id=?", firstWallet));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_hot_wallet set quarantine_fence_confirmed_at=null where id=?", firstWallet));
        assertEquals("STANDBY", wallets.findByKey(tenant, "ETH", secondKey).orElseThrow().status());
        assertThrows(DataIntegrityViolationException.class, () -> wallets.insertStandby(
                UUID.randomUUID(), tenant, firstAddress, firstKey, "ETH", "sepolia", "Duplicate", actor));

        var policies = new CustodyHotWalletAssetPolicyRepository(jdbc);
        assertEquals(1, policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant, firstWallet,
                "ETH", "ETH", BigDecimal.ONE, new BigDecimal("10"),
                new BigDecimal("2"), new BigDecimal("5"))));
        assertEquals(1, policies.list(tenant, firstWallet).size());
        assertTrue(policies.list(tenant, secondWallet).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> policies.upsert(
                new CustodyHotWalletAssetPolicyRepository.Policy(tenant, firstWallet, "BTC", "BTC",
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN)));
    }

    @Test void payoutDelegationProjectionKeepsEachHotAddressIndependent() {
        UUID firstAddressId=address(601,UUID.randomUUID());
        UUID secondAddressId=address(602,UUID.randomUUID());
        var addresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        var first=addresses.require(tenant,firstAddressId);
        var second=addresses.require(tenant,secondAddressId);
        var accounts=new com.surprising.wallet.repository.Evm7702PayoutAccountRepository(jdbc);
        assertEquals(1,accounts.upsert(UUID.randomUUID(),tenant,"ETH","sepolia",
                first.chainAddressId(),first.address()));
        assertEquals(1,accounts.upsert(UUID.randomUUID(),tenant,"ETH","sepolia",
                second.chainAddressId(),second.address()));
        assertEquals(2,jdbc.queryForObject(
                "select count(*) from evm_7702_payout_account where tenant_id=? and chain='ETH'",
                Integer.class,tenant));
        assertEquals(1,accounts.markCompleted(tenant,"ETH",first.address(),true,
                java.math.BigInteger.ONE,java.math.BigInteger.ONE,
                "0x"+"a".repeat(64),"0x"+"b".repeat(40),1));
        assertEquals("ACTIVE",jdbc.queryForObject("""
                select delegation_status from evm_7702_payout_account
                 where tenant_id=? and authority_address=?
                """,String.class,tenant,first.address()));
        assertEquals("NOT_DELEGATED",jdbc.queryForObject("""
                select delegation_status from evm_7702_payout_account
                 where tenant_id=? and authority_address=?
                """,String.class,tenant,second.address()));
        assertEquals(1,accounts.markReverted(tenant,"ETH",second.address(),true,
                java.math.BigInteger.valueOf(2),java.math.BigInteger.ZERO,
                "0x"+"c".repeat(64),"0x"+"b".repeat(40),1));
        assertEquals(0,accounts.markCompleted(tenant,"ETH","0x"+"f".repeat(40),false,
                null,java.math.BigInteger.ONE,"0x"+"d".repeat(64),
                "0x"+"b".repeat(40),1));
    }

    @Test void batchClaimAcceptsOnlyFreshActiveHotPayoutAuthority() {
        UUID key=UUID.randomUUID(),addressId=address(603,key),walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        var addresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        var chainAddresses=new com.surprising.wallet.repository.ChainAddressRepository(jdbc);
        var owned=addresses.require(tenant,addressId);
        assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,
                "ETH","sepolia","Batch hot",actor));
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(
                tenant,walletId,"ETH","ETH",BigDecimal.ONE,BigDecimal.TEN,
                BigDecimal.ONE,BigDecimal.TEN)));
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,owned.address(),"eip155:11155111",18,18,
                java.math.BigInteger.TEN.pow(18),java.math.BigInteger.TEN.pow(18),
                java.time.Instant.now(),UUID.randomUUID(),"a".repeat(64),
                42L,"0x"+"a".repeat(64))));
        var source=chainAddresses.findEnabledByTenantAndAddress(tenant,"ETH",owned.address()).orElseThrow();
        var claim=new com.surprising.wallet.service.HotWalletPayoutClaimSourceService(
                wallets,addresses,observations,new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc));
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertEquals(Boolean.FALSE,transaction.execute(status->claim.isClaimable(
                tenant,"ETH","sepolia","ETH",source)));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        assertEquals(Boolean.TRUE,transaction.execute(status->claim.isClaimable(
                tenant,"ETH","sepolia","ETH",source)));
        assertEquals(1,observations.invalidateIfUnchanged(tenant,walletId,"ETH",
                observations.find(tenant,walletId,"ETH").orElseThrow().observedAt()));
        assertEquals(Boolean.FALSE,transaction.execute(status->claim.isClaimable(
                tenant,"ETH","sepolia","ETH",source)));
    }

    @Test void eip7702BatchClaimsOriginalHotSourceOnlyWhileTenantChainIsOpen() {
        UUID hotKey=UUID.randomUUID(),hotAddressId=address(611,hotKey),walletId=UUID.randomUUID();
        UUID relayerAddressId=address(612,UUID.randomUUID());
        var addresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        var owned=addresses.require(tenant,hotAddressId);
        var relayer=addresses.require(tenant,relayerAddressId);
        var wallets=new CustodyHotWalletRepository(jdbc);
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,hotAddressId,hotKey,
                "ETH","sepolia","Batch source",actor));
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(
                tenant,walletId,"ETH","ETH",BigDecimal.ONE,BigDecimal.TEN,
                BigDecimal.ONE,BigDecimal.TEN)));
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",hotKey,owned.address(),"eip155:11155111",18,18,
                java.math.BigInteger.TEN.pow(18),java.math.BigInteger.TEN.pow(18),
                java.time.Instant.now(),UUID.randomUUID(),"a".repeat(64),
                42L,"0x"+"a".repeat(64))));
        jdbc.update("""
                insert into evm_7702_config(id,chain,network,chain_id,version,
                    delegate_address,delegate_code_hash,collector_address,collector_code_hash,
                    payout_delegate_address,payout_delegate_code_hash,
                    relayer_chain_address_id,relayer_address,status,
                    batch_withdrawal_enabled,withdrawal_max_wait_ms)
                values (?,'ETH','sepolia',11155111,1,?,?,?,?,?,?,?,?,'ACTIVE',true,0)
                """,UUID.randomUUID(),"0x"+"a".repeat(40),"0x"+"a".repeat(64),
                "0x"+"b".repeat(40),"0x"+"b".repeat(64),
                "0x"+"c".repeat(40),"0x"+"c".repeat(64),
                relayer.chainAddressId(),relayer.address());
        String orderNo="hot-7702-"+walletId;
        var orders=new com.surprising.wallet.repository.WithdrawalOrderRepository(jdbc);
        long orderId=orders.createForTenant(tenant,orderNo,1L,"ETH","ETH",
                owned.address(),"customer-account","0x"+"d".repeat(40),
                new BigDecimal("0.1"),BigDecimal.ZERO);
        assertTrue(orderId>0);
        assertEquals(1,jdbc.update("update withdrawal_order set status='FROZEN' where id=?",orderId));
        assertEquals(1,new com.surprising.wallet.repository.CustodyWithdrawalRepository(jdbc).insert(
                UUID.randomUUID(),tenant,hotAddressId,orderId,orderNo,"external-"+orderNo,
                "idempotent-"+orderNo,"ETH","ETH","0x"+"d".repeat(40),
                new BigDecimal("0.1"),BigDecimal.ZERO,"FROZEN","API_KEY",
                actor.toString(),"test-subject","ADDRESS"));
        var coordinator=com.surprising.wallet.testutil.CustodyTestSupport.evmWithdrawals(jdbc);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertTrue(transaction.execute(status->coordinator.claimNextBatch("ETH","sepolia")).isEmpty());
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        var tenantChains=new CustodyTenantChainTableRepository(jdbc);
        assertEquals(1,tenantChains.setWithdrawalPaused(tenant,"ETH",true,actor));
        assertTrue(transaction.execute(status->coordinator.claimNextBatch("ETH","sepolia")).isEmpty());
        assertEquals(1,tenantChains.setWithdrawalPaused(tenant,"ETH",false,actor));
        var claimed=transaction.execute(status->coordinator.claimNextBatch("ETH","sepolia")).orElseThrow();
        assertEquals(owned.address(),claimed.hotWallet());
        assertEquals(owned.chainAddressId(),claimed.hotChainAddress().getId());
        assertEquals("SIGNING",orders.findById(tenant,orderId).orElseThrow().getStatus());
        assertEquals(owned.address(),jdbc.queryForObject("""
                select authority_address from evm_7702_payout_account where tenant_id=? and chain='ETH'
                """,String.class,tenant));
    }

    @Test void aggregateActiveHotWalletCapCountsEveryAddressBeforeNewCollection() {
        var wallets=new CustodyHotWalletRepository(jdbc);
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        var limits=new com.surprising.wallet.repository.CustodyHotWalletExposureLimitRepository(jdbc);
        var custodyAddresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        for(int index=1;index<=2;index++) {
            UUID key=UUID.randomUUID(),addressId=address(300+index,key),walletId=UUID.randomUUID();
            assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,"ETH","sepolia","Aggregate "+index,actor));
            assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,
                    "ETH","ETH",BigDecimal.ONE,new BigDecimal("5"),BigDecimal.ONE,new BigDecimal("5"))));
            String address=custodyAddresses.require(tenant,addressId).address();
            var balance=java.math.BigInteger.TEN.pow(18).multiply(java.math.BigInteger.valueOf(index+1L));
            assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                    tenant,walletId,"ETH","ETH",key,address,"eip155:11155111",18,18,
                    balance,balance,java.time.Instant.now(),UUID.randomUUID(),"a".repeat(64),
                    42L,"0x"+"a".repeat(64))));
            assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        }
        var aggregate=new com.surprising.wallet.service.HotWalletAggregateExposureService(limits,wallets,policies,
                observations,custodyAddresses,new com.surprising.wallet.repository.CollectionRecordRepository(jdbc),
                new CustodyTenantChainTableRepository(jdbc),new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc));
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var principal=new com.surprising.wallet.model.CustodyPrincipal(
                com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,
                "hot-wallet-test","TENANT_ADMIN",java.util.Set.of("*"));
        tx.execute(status->aggregate.set(principal,"ETH","ETH",
                new com.surprising.wallet.service.HotWalletAggregateExposureService.SetLimitCommand("6"),null));
        assertEquals(0,limits.list(tenant,"ETH").getFirst().maximumActiveBalance().compareTo(new BigDecimal("6")));
        tx.executeWithoutResult(status->aggregate.requireHeadroom(tenant,"ETH","ETH",null,BigDecimal.ONE));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->
                aggregate.requireHeadroom(tenant,"ETH","ETH",null,new BigDecimal("2"))));
        tx.execute(status->aggregate.set(principal,"ETH","ETH",
                new com.surprising.wallet.service.HotWalletAggregateExposureService.SetLimitCommand("4"),null));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->
                aggregate.requireHeadroom(tenant,"ETH","ETH",null,new BigDecimal("0.1"))));
    }

    @Test void finalizedDirectCollectionReleasesOnlyItsAccountedInboundExposure() {
        UUID sourceKey=UUID.randomUUID(),targetKey=UUID.randomUUID();
        UUID sourceId=address(411,sourceKey),targetId=address(412,targetKey),walletId=UUID.randomUUID();
        var addresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        String sourceAddress=addresses.require(tenant,sourceId).address();
        String targetAddress=addresses.require(tenant,targetId).address();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,targetId,targetKey,"ETH","sepolia","Direct target",actor));
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,
                "ETH","ETH",BigDecimal.ONE,new BigDecimal("10"),BigDecimal.ONE,new BigDecimal("5"))));
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        UUID rule=UUID.randomUUID();String fingerprint="a".repeat(64),block="0x"+"b".repeat(64);
        var firstAt=java.time.Instant.now().minusMillis(100);
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",targetKey,targetAddress,"eip155:11155111",18,18,
                java.math.BigInteger.TEN.pow(18),java.math.BigInteger.TEN.pow(18),firstAt,
                rule,fingerprint,42L,block)));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        var collections=new com.surprising.wallet.repository.CollectionRecordRepository(jdbc);
        String reference="direct-inbound",hash="0x"+"c".repeat(64);
        assertEquals(1,collections.create(tenant,sourceId,reference,"ETH","ETH",sourceAddress,
                targetAddress,new BigDecimal("2"),BigDecimal.ZERO,null));
        assertEquals(1,collections.markConfirmed(tenant,"ETH",reference,hash));
        assertEquals(0,collections.inboundExposure(tenant,"ETH","ETH",targetAddress).compareTo(new BigDecimal("2")));
        var authority=org.mockito.Mockito.mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class);
        var proof=new com.surprising.wallet.common.approval.HotWalletSettledCollectionObservation(
                tenant,sourceKey,targetKey,"ETH","sepolia","eip155:11155111","ETH",null,
                sourceAddress,targetAddress,hash,java.math.BigInteger.TWO.multiply(java.math.BigInteger.TEN.pow(18)),
                18,18,java.math.BigInteger.valueOf(3).multiply(java.math.BigInteger.TEN.pow(18)),
                java.math.BigInteger.valueOf(3).multiply(java.math.BigInteger.TEN.pow(18)),
                42L,block,43L,"0x"+"d".repeat(64),java.time.Instant.now(),rule,fingerprint);
        org.mockito.Mockito.when(authority.settledDirectCollection(org.mockito.ArgumentMatchers.any())).thenReturn(proof);
        var service=new com.surprising.wallet.service.HotWalletSettledCollectionReconciliationService(
                collections,wallets,observations,addresses,
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.TokenConfigRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),authority,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var principal=new com.surprising.wallet.model.CustodyPrincipal(
                com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,
                "hot-wallet-test","TENANT_ADMIN",java.util.Set.of("*"));
        var accounted=service.reconcile(principal,walletId,reference,null);
        assertTrue(accounted.newlyAccounted());
        assertEquals(0,collections.inboundExposure(tenant,"ETH","ETH",targetAddress).signum());
        assertEquals(0,observations.find(tenant,walletId,"ETH").orElseThrow().availableAtomic().compareTo(
                java.math.BigInteger.valueOf(3).multiply(java.math.BigInteger.TEN.pow(18))));
        assertFalse(service.reconcile(principal,walletId,reference,null).newlyAccounted());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "update collection_record set hot_inbound_accounted_at=null where tenant_id=? and collection_no=?",
                tenant,reference));
        assertEquals(1,collections.create(tenant,sourceId,"unproven-inbound","ETH","ETH",sourceAddress,
                targetAddress,BigDecimal.ONE,BigDecimal.ZERO,null));
        assertEquals(1,collections.markConfirmed(tenant,"ETH","unproven-inbound","0x"+"f".repeat(64)));
        assertThrows(IllegalStateException.class,()->service.reconcile(principal,walletId,"unproven-inbound",null));
        assertEquals(0,collections.inboundExposure(tenant,"ETH","ETH",targetAddress).compareTo(BigDecimal.ONE));
    }

    @Test void emergencyPauseAndLocalQuarantineCommitTogetherBeforeRemoteFence() {
        UUID oldKey=UUID.randomUUID(),nextKey=UUID.randomUUID();
        UUID oldId=UUID.randomUUID(),nextId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(oldId,tenant,address(51,oldKey),oldKey,
                "ETH","sepolia","Compromised",actor));
        assertEquals(1,wallets.insertStandby(nextId,tenant,address(52,nextKey),nextKey,
                "ETH","sepolia","Safe",actor));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",oldId));
        var rows=new CustodyTenantChainTableRepository(jdbc);
        var tenantChains=org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyTenantChainService.class);
        var principal=new com.surprising.wallet.model.CustodyPrincipal(
                com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,
                "hot-wallet-test","TENANT_ADMIN",java.util.Set.of("*"));
        org.mockito.Mockito.doAnswer(call->{
            assertEquals(1,rows.setWithdrawalPaused(tenant,"ETH",true,actor));
            return null;
        }).when(tenantChains).setWithdrawalPaused(org.mockito.ArgumentMatchers.eq(principal),
                org.mockito.ArgumentMatchers.eq("ETH"),org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        var service=new com.surprising.wallet.service.CustodyHotWalletService(wallets,
                new CustodyHotWalletAssetPolicyRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyAddressService.class),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),tenantChains,
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.TokenConfigRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.signing.MpcNodes.class),
                org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletInventoryService.class),
                new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),
                rows,new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletAggregateExposureService.class),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalStateException.class,()->service.emergencySwitch(principal,
                new com.surprising.wallet.service.CustodyHotWalletService.EmergencySwitchCommand(
                        oldId,nextId,10,"suspected signing key compromise"),null));
        assertTrue(rows.withdrawalPaused(tenant,"ETH"));
        assertEquals("QUARANTINED",wallets.find(tenant,oldId).orElseThrow().status());
        assertEquals("STANDBY",wallets.find(tenant,nextId).orElseThrow().status());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=? and action='HOT_WALLET.QUARANTINE'",
                Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id=?",
                Integer.class,tenant));
    }

    @Test void standaloneQuarantineCommitsEmergencyPauseBeforeRemoteFence() {
        UUID key=UUID.randomUUID(),walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,address(62,key),key,
                "ETH","sepolia","Standalone quarantine",actor));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        var chainRows=new CustodyTenantChainTableRepository(jdbc);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var chains=com.surprising.wallet.testutil.CustodyTestSupport.tenantChains(jdbc,
                org.mockito.Mockito.mock(com.surprising.wallet.chain.BlockchainRuntimeService.class));
        var nodes=org.mockito.Mockito.mock(com.surprising.wallet.chain.signing.MpcNodes.class);
        org.mockito.Mockito.when(nodes.clients()).thenReturn(java.util.List.of());
        var approval=org.mockito.Mockito.mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class);
        var service=new com.surprising.wallet.service.CustodyHotWalletService(wallets,
                new CustodyHotWalletAssetPolicyRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyAddressService.class),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),chains,
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.TokenConfigRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),approval,nodes,
                org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletInventoryService.class),
                new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),
                chainRows,new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletAggregateExposureService.class),manager);
        var principal=new com.surprising.wallet.model.CustodyPrincipal(
                com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,
                "hot-wallet-test","TENANT_ADMIN",java.util.Set.of("*"));

        assertThrows(IllegalStateException.class,()->service.quarantine(principal,walletId,
                "suspected key compromise",null));
        assertTrue(chainRows.withdrawalPaused(tenant,"ETH"));
        assertEquals("QUARANTINED",wallets.find(tenant,walletId).orElseThrow().status());
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=? and action='WITHDRAWAL.EMERGENCY_PAUSE'",
                Integer.class,tenant));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=? and action='HOT_WALLET.QUARANTINE'",
                Integer.class,tenant));
        org.mockito.Mockito.verifyNoInteractions(approval);
    }

    @Test void configuredLegacyUtxoBroadcastStopsAtEmergencyPause() {
        var rows=new CustodyTenantChainTableRepository(jdbc);
        var fence=new com.surprising.wallet.service.HotWalletBroadcastFence(rows,
                new CustodyHotWalletRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.CollectionRecordRepository(jdbc),
                new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var sent=new java.util.concurrent.atomic.AtomicInteger();
        assertEquals("sent",fence.withExternalLegacyBroadcastPermit(tenant,"BTC",()->{
            sent.incrementAndGet();return "sent";
        }));
        rows.setStatus(tenant,"BTC","ACTIVE",actor);
        assertEquals("sent",fence.withExternalLegacyBroadcastPermit(tenant,"BTC",()->{
            sent.incrementAndGet();return "sent";
        }));
        assertEquals(1,rows.setWithdrawalPaused(tenant,"BTC",true,actor));
        assertThrows(IllegalStateException.class,()->fence.withExternalLegacyBroadcastPermit(tenant,"BTC",()->{
            sent.incrementAndGet();return "sent";
        }));
        assertEquals(2,sent.get());
    }

    @Test void emergencyPauseCreatesClosedLegacyRowAfterInFlightBroadcast() throws Exception {
        var rows=new CustodyTenantChainTableRepository(jdbc);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var fence=new com.surprising.wallet.service.HotWalletBroadcastFence(rows,
                new CustodyHotWalletRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.CollectionRecordRepository(jdbc),
                new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),manager);
        assertTrue(rows.list(tenant).stream().noneMatch(row->"BTC".equals(row.chain())));
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var sending=java.util.concurrent.CompletableFuture.supplyAsync(()->fence.withExternalLegacyBroadcastPermit(
                tenant,"BTC",()->{
                    entered.countDown();
                    if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("legacy broadcast fixture was not released");
                    return "submitted";
                }));
        try {
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var pauseStarted=new java.util.concurrent.CountDownLatch(1);
            var pausing=java.util.concurrent.CompletableFuture.runAsync(()->{
                pauseStarted.countDown();
                new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->
                        assertEquals(1,rows.ensureEmergencyPaused(tenant,"BTC",actor)));
            });
            assertTrue(pauseStarted.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    ()->pausing.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("submitted",sending.get(10,java.util.concurrent.TimeUnit.SECONDS));
            pausing.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var paused=rows.list(tenant).stream().filter(row->"BTC".equals(row.chain())).findFirst().orElseThrow();
            assertEquals("CLOSED",paused.status());assertTrue(paused.withdrawalPaused());
            assertThrows(IllegalStateException.class,()->fence.withExternalLegacyBroadcastPermit(tenant,"BTC",()->"late"));
        } finally {release.countDown();}
    }

    @Test void consoleCanPauseUnconfiguredLegacyChainWithoutOpeningIt() {
        jdbc.update("update chain_profile set enabled=false where chain='BTC'");
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var rows=new CustodyTenantChainTableRepository(jdbc);
        var service=new com.surprising.wallet.service.CustodyTenantChainService(rows,
                new com.surprising.wallet.repository.ChainProfileRepository(jdbc),
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.TokenConfigRepository(jdbc),
                new com.surprising.wallet.repository.Evm7702ConfigRepository(jdbc),
                new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.BlockchainRuntimeService.class),
                new CustodyHotWalletRepository(jdbc));
        var principal=new com.surprising.wallet.model.CustodyPrincipal(
                com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,
                "hot-wallet-test","TENANT_ADMIN",java.util.Set.of("*"));
        assertTrue(rows.list(tenant).stream().noneMatch(row->"BTC".equals(row.chain())));
        assertTrue(service.list(principal).stream().anyMatch(row->"BTC".equals(row.chain())
                && !row.platformEnabled() && !row.enabled() && !row.withdrawalPaused()));
        var view=new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->
                service.setWithdrawalPaused(principal,"BTC",true,"suspected key compromise","127.0.0.1"));
        assertNotNull(view);assertEquals("CLOSED",view.status());assertTrue(view.withdrawalPaused());
        assertFalse(view.enabled());
        assertTrue(service.list(principal).stream().anyMatch(row->"BTC".equals(row.chain())
                && "CLOSED".equals(row.status()) && row.withdrawalPaused()));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=? and action='WITHDRAWAL.EMERGENCY_PAUSE'",
                Integer.class,tenant));
        var firstPause=jdbc.queryForObject("select withdrawal_paused_at from custody_tenant_chain where tenant_id=? and chain='BTC'",
                java.sql.Timestamp.class,tenant);
        new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->
                service.setWithdrawalPaused(principal,"BTC",true,"continuing key investigation","127.0.0.1"));
        assertEquals(firstPause,jdbc.queryForObject(
                "select withdrawal_paused_at from custody_tenant_chain where tenant_id=? and chain='BTC'",
                java.sql.Timestamp.class,tenant));
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager)
                .execute(status->service.setWithdrawalPaused(principal,"BTC",false,
                        "attempting unsafe resumption","127.0.0.1")));
        assertTrue(rows.withdrawalPaused(tenant,"BTC"));
    }

    @Test void resumeWaitsForConcurrentQuarantineAndKeepsIncidentPause() throws Exception {
        UUID key=UUID.randomUUID(),walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,address(61,key),key,
                "ETH","sepolia","Quarantine race",actor));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        var rows=new CustodyTenantChainTableRepository(jdbc);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
        var service=com.surprising.wallet.testutil.CustodyTestSupport.tenantChains(jdbc,
                org.mockito.Mockito.mock(com.surprising.wallet.chain.BlockchainRuntimeService.class));
        var principal=new com.surprising.wallet.model.CustodyPrincipal(
                com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,
                "hot-wallet-test","TENANT_ADMIN",java.util.Set.of("*"));
        var quarantined=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pausing=java.util.concurrent.CompletableFuture.runAsync(()->transaction.executeWithoutResult(status->{
            assertEquals(1,rows.ensureEmergencyPaused(tenant,"ETH",actor));
            assertEquals(1,wallets.quarantine(tenant,walletId,actor,java.time.Instant.now(),
                    "suspected key leak"));
            quarantined.countDown();
            try {
                if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("quarantine fixture was not released");
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);
            }
        }));
        try {
            assertTrue(quarantined.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var resumeStarted=new java.util.concurrent.CountDownLatch(1);
            var resuming=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                resumeStarted.countDown();
                return transaction.execute(status->service.setWithdrawalPaused(principal,"ETH",false,
                        "incident appears resolved",null));
            });
            assertTrue(resumeStarted.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    ()->resuming.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            pausing.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,
                    ()->resuming.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(rows.withdrawalPaused(tenant,"ETH"));
        } finally {release.countDown();}
    }

    @Test void incidentPauseWaitsForOriginalEvmBroadcastAndRejectsLaterOldDestination() throws Exception {
        UUID oldKey=UUID.randomUUID(),sourceKey=UUID.randomUUID();
        UUID oldAddressId=address(61,oldKey),sourceAddressId=address(62,sourceKey),oldWalletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(oldWalletId,tenant,oldAddressId,oldKey,
                "ETH","sepolia","Old destination",actor));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",oldWalletId));
        var addresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        String oldAddress=addresses.require(tenant,oldAddressId).address();
        String sourceAddress=addresses.require(tenant,sourceAddressId).address();
        var collections=new com.surprising.wallet.repository.CollectionRecordRepository(jdbc);
        assertEquals(1,collections.create(tenant,sourceAddressId,"incident-pending","ETH","ETH",
                sourceAddress,oldAddress,BigDecimal.ONE,BigDecimal.ZERO,"{}"));
        String hash="0x"+"c".repeat(64);
        assertEquals(1,collections.updateStatus(tenant,"ETH","incident-pending","SIGNING",hash,null,null));
        var rows=new CustodyTenantChainTableRepository(jdbc);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var fence=new com.surprising.wallet.service.HotWalletBroadcastFence(rows,wallets,addresses,
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),collections,
                new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),manager);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pauseStarted=new java.util.concurrent.CountDownLatch(1);
        var sending=java.util.concurrent.CompletableFuture.supplyAsync(()->fence.withEvmCollectionBroadcastPermit(
                tenant,"ETH","sepolia","incident-pending",hash,sourceKey,()->{
                    entered.countDown();
                    if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("broadcast fixture was not released");
                    return "submitted";
                }));
        try {
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var pausing=java.util.concurrent.CompletableFuture.runAsync(()->{
                pauseStarted.countDown();
                new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
                    assertEquals(1,rows.setWithdrawalPaused(tenant,"ETH",true,actor));
                    assertEquals(1,wallets.quarantine(tenant,oldWalletId,actor,
                            java.time.Instant.now(),"suspected signing key compromise"));
                });
            });
            assertTrue(pauseStarted.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    ()->pausing.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("submitted",sending.get(10,java.util.concurrent.TimeUnit.SECONDS));
            pausing.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(rows.withdrawalPaused(tenant,"ETH"));
            assertEquals("QUARANTINED",wallets.find(tenant,oldWalletId).orElseThrow().status());
            assertThrows(IllegalStateException.class,()->fence.withEvmCollectionBroadcastPermit(
                    tenant,"ETH","sepolia","incident-pending",hash,sourceKey,()->"second-submit"));
        } finally { release.countDown(); }
    }

    @Test void nativeRelayRemainsOutsideSpringTransactionWhilePauseWaitsForRpc() throws Exception {
        var rows=new CustodyTenantChainTableRepository(jdbc);
        assertTrue(rows.list(tenant).stream().noneMatch(row->"XMR".equals(row.chain())));
        var fence=new com.surprising.wallet.service.HotWalletBroadcastFence(rows,
                new CustodyHotWalletRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.CollectionRecordRepository(jdbc),
                new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var sending=java.util.concurrent.CompletableFuture.supplyAsync(()->
                fence.withExternalLegacyBroadcastPermit(tenant,"XMR",()->{
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    entered.countDown();
                    if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("native relay fixture was not released");
                    return "relayed";
                }));
        try {
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var pauseStarted=new java.util.concurrent.CountDownLatch(1);
            var pausing=java.util.concurrent.CompletableFuture.runAsync(()->{
                pauseStarted.countDown();
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                        .executeWithoutResult(status->assertEquals(1,rows.ensureEmergencyPaused(tenant,"XMR",actor)));
            });
            assertTrue(pauseStarted.await(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    ()->pausing.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("relayed",sending.get(10,java.util.concurrent.TimeUnit.SECONDS));
            pausing.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("CLOSED",rows.list(tenant).stream().filter(row->"XMR".equals(row.chain()))
                    .findFirst().orElseThrow().status());
            assertThrows(IllegalStateException.class,()->fence.withExternalLegacyBroadcastPermit(
                    tenant,"XMR",()->"late-relay"));
        } finally {release.countDown();}
    }

    @Test void independentInventoryObservationIsExactMonotonicAndNeverCreditsCustomers() {
        UUID key=UUID.randomUUID(), addressId=address(11,key), walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,"ETH","sepolia","Inventory A",actor));
        var policy=new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,"ETH","ETH",
                BigDecimal.ONE,new BigDecimal("10"),BigDecimal.ONE,new BigDecimal("5"));
        assertEquals(1,new CustodyHotWalletAssetPolicyRepository(jdbc).upsert(policy));
        String addressValue=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc)
                .require(tenant,addressId).address();
        var repo=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        var observedAt=java.time.Instant.now().minusSeconds(1);
        var first=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,addressValue,"eip155:11155111",18,18,
                new java.math.BigInteger("9000000000000000000"),new java.math.BigInteger("9000000000000000000"),
                observedAt,UUID.randomUUID(),"a".repeat(64),42L,"0x"+"a".repeat(64));
        assertEquals(1,repo.record(first));
        assertEquals(first.availableAtomic(),repo.find(tenant,walletId,"ETH").orElseThrow().availableAtomic());
        assertEquals(0,repo.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,addressValue,"eip155:11155111",18,18,
                java.math.BigInteger.ZERO,java.math.BigInteger.ZERO,observedAt.minusSeconds(1),
                UUID.randomUUID(),"b".repeat(64),41L,"0x"+"b".repeat(64))));
        assertEquals(first.availableAtomic(),repo.find(tenant,walletId,"ETH").orElseThrow().availableAtomic());
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id=?",Integer.class,tenant));

        var approval=org.mockito.Mockito.mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class);
        org.mockito.Mockito.when(approval.hotInventory(tenant,"ETH","sepolia","ETH",key)).thenReturn(
                new com.surprising.wallet.common.approval.HotWalletInventoryObservation(tenant,key,"ETH",
                        "sepolia","eip155:11155111","ETH",addressValue,18,18,
                        first.availableAtomic(),first.nativeAtomic(),observedAt.plusMillis(1),
                        first.collectionRuleId(),first.collectionRuleFingerprint(),
                        first.observedBlockHeight(),first.observedBlockHash()));
        var service=new com.surprising.wallet.service.HotWalletInventoryService(wallets,
                new CustodyHotWalletAssetPolicyRepository(jdbc),repo,
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),approval,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertEquals(0,new BigDecimal("9").compareTo(service.refresh(
                com.surprising.wallet.testutil.CustodyTestSupport.tenantAdmin(tenant),walletId,"ETH",null)
                .observedBalance()));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id=?",Integer.class,tenant));
        UUID brokenKey=UUID.randomUUID(),brokenAddress=address(12,brokenKey);
        UUID brokenWallet=UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(1,wallets.insertStandby(brokenWallet,tenant,brokenAddress,brokenKey,
                "ETH","sepolia","Broken observer",actor));
        assertEquals(1,new CustodyHotWalletAssetPolicyRepository(jdbc).upsert(
                new CustodyHotWalletAssetPolicyRepository.Policy(tenant,brokenWallet,"ETH","ETH",
                        BigDecimal.ONE,new BigDecimal("10"),BigDecimal.ONE,new BigDecimal("5"))));
        assertEquals(1,wallets.activate(tenant,brokenWallet,brokenKey,0));
        assertEquals(1,wallets.activate(tenant,walletId,key,1));
        org.mockito.Mockito.when(approval.hotInventory(tenant,"ETH","sepolia","ETH",key))
                .thenAnswer(ignored->new com.surprising.wallet.common.approval.HotWalletInventoryObservation(
                        tenant,key,"ETH","sepolia","eip155:11155111","ETH",addressValue,18,18,
                        first.availableAtomic(),first.nativeAtomic(),java.time.Instant.now(),
                        first.collectionRuleId(),first.collectionRuleFingerprint(),
                        first.observedBlockHeight(),first.observedBlockHash()));
        service.refreshActive();
        org.mockito.Mockito.verify(approval).hotInventory(tenant,"ETH","sepolia","ETH",brokenKey);
        org.mockito.Mockito.verify(approval,org.mockito.Mockito.times(2))
                .hotInventory(tenant,"ETH","sepolia","ETH",key);
        assertTrue(repo.find(tenant,walletId,"ETH").orElseThrow().observedAt()
                .isAfter(observedAt.plusMillis(1)));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log "
                +"where tenant_id=? and action='HOT_WALLET.INVENTORY_OBSERVED'",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id=?",Integer.class,tenant));
        var beforeFailure=repo.find(tenant,walletId,"ETH").orElseThrow();
        org.mockito.Mockito.when(approval.hotInventory(tenant,"ETH","sepolia","ETH",key))
                .thenThrow(new IllegalStateException("independent EVM code changed"));
        service.refreshActive();
        assertNotNull(repo.find(tenant,walletId,"ETH").orElseThrow().invalidatedAt());
        assertFalse(repo.allUninvalidated(tenant,walletId));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log "
                +"where tenant_id=? and action='HOT_WALLET.INVENTORY_INVALIDATED' "
                +"and resource_id=?",Integer.class,tenant,walletId.toString()));
        var routing=new com.surprising.wallet.service.HotWalletCollectionRoutingService(wallets,
                new CustodyHotWalletAssetPolicyRepository(jdbc),repo,
                new com.surprising.wallet.repository.CollectionRecordRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                new CustodyTenantChainTableRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletAggregateExposureService.class));
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertFalse(Boolean.TRUE.equals(tx.execute(status->repo.lockAllUninvalidated(tenant,walletId))));
        assertThrows(IllegalStateException.class,()->tx.execute(status->
                routing.destination(tenant,"ETH","ETH",BigDecimal.ONE)));
        var withdrawalRouting=new com.surprising.wallet.service.HotWalletWithdrawalRoutingService(wallets,
                new CustodyHotWalletAssetPolicyRepository(jdbc),repo,
                new com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                new CustodyTenantChainTableRepository(jdbc));
        assertThrows(IllegalStateException.class,()->tx.execute(status->
                withdrawalRouting.reserveNewOrder(tenant,"ETH","ETH","blocked-by-invalidated-inventory",
                        BigDecimal.ONE)));
        org.mockito.Mockito.doAnswer(ignored->new com.surprising.wallet.common.approval.HotWalletInventoryObservation(
                        tenant,key,"ETH","sepolia","eip155:11155111","ETH",addressValue,18,18,
                        first.availableAtomic(),first.nativeAtomic(),java.time.Instant.now().plusMillis(10),
                        first.collectionRuleId(),first.collectionRuleFingerprint(),
                        first.observedBlockHeight(),first.observedBlockHash()))
                .when(approval).hotInventory(tenant,"ETH","sepolia","ETH",key);
        service.refreshActive();
        assertNull(repo.find(tenant,walletId,"ETH").orElseThrow().invalidatedAt());
        assertTrue(repo.allUninvalidated(tenant,walletId));
        assertTrue(Boolean.TRUE.equals(tx.execute(status->repo.lockAllUninvalidated(tenant,walletId))));
        assertEquals(0,repo.invalidateIfUnchanged(tenant,walletId,"ETH",beforeFailure.observedAt()));
        assertEquals(addressValue,tx.execute(status->
                routing.destination(tenant,"ETH","ETH",BigDecimal.ONE).orElseThrow()));
    }

    @Test void withdrawalReservationsKeepUnknownFundsCommittedAndCannotBeRetargeted() {
        UUID key=UUID.randomUUID(), addressId=address(12,key), walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,"ETH","sepolia","Reserve C",actor));
        var policy=new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,"ETH","ETH",
                BigDecimal.ONE,new BigDecimal("10"),new BigDecimal("2"),new BigDecimal("5"));
        assertEquals(1,new CustodyHotWalletAssetPolicyRepository(jdbc).upsert(policy));
        var reservations=new com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository(jdbc);
        var observedAt=java.time.Instant.now().minusSeconds(10);
        String source=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc).require(tenant,addressId).address();
        UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        assertEquals(1,reservations.reserve(first,tenant,walletId,"ETH","ETH","unknown-order",
                source,new BigDecimal("2"),observedAt));
        assertEquals(1,reservations.reserve(second,tenant,walletId,"ETH","ETH","confirmed-order",
                source,BigDecimal.ONE,observedAt));
        assertEquals(0,reservations.reserve(UUID.randomUUID(),tenant,walletId,"ETH","ETH","unknown-order",
                source,BigDecimal.ONE,observedAt));
        assertEquals(1,reservations.markTerminal(tenant,"ETH","confirmed-order","CONFIRMED"));
        assertEquals(0,reservations.markTerminal(tenant,"ETH","confirmed-order","FAILED"));
        assertEquals(0,new BigDecimal("3").compareTo(
                reservations.committedExposure(tenant,walletId,"ETH")));
        assertEquals(0,new BigDecimal("3").compareTo(
                reservations.rollingExposure(tenant,walletId,"ETH",observedAt)));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "update custody_hot_wallet_withdrawal_reservation set amount=1 where id=?",first));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "delete from custody_hot_wallet_withdrawal_reservation where id=?",first));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id=?",Integer.class,tenant));
    }

    @Test void activeWithdrawalRouteReservesAgainstFreshObservedBalanceAndDailyLimit() {
        UUID key=UUID.randomUUID(), addressId=address(13,key), walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,"ETH","sepolia","Reserve D",actor));
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,
                "ETH","ETH",BigDecimal.ONE,new BigDecimal("5"),new BigDecimal("2"),new BigDecimal("3"))));
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        var reservations=new com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository(jdbc);
        var custodyAddresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        String source=custodyAddresses.require(tenant,addressId).address();
        var observedAt=java.time.Instant.now().minusSeconds(1);
        var ruleId=UUID.randomUUID();
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,source,"eip155:11155111",18,18,
                new java.math.BigInteger("3000000000000000000"),new java.math.BigInteger("3000000000000000000"),
                observedAt,ruleId,"a".repeat(64),null,null)));
        var routing=new com.surprising.wallet.service.HotWalletWithdrawalRoutingService(wallets,policies,
                observations,reservations,custodyAddresses,
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                new CustodyTenantChainTableRepository(jdbc));
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertTrue(transaction.execute(status->routing.reserveNewOrder(tenant,"ETH","ETH","legacy",
                BigDecimal.ONE)).isEmpty());
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE',updated_at=now() where id=?",walletId));
        assertThrows(IllegalStateException.class,()->transaction.execute(status->routing.reserveNewOrder(
                tenant,"ETH","ETH","unanchored",BigDecimal.ONE)));
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,source,"eip155:11155111",18,18,
                new java.math.BigInteger("3000000000000000000"),new java.math.BigInteger("3000000000000000000"),
                observedAt.plusMillis(1),ruleId,"a".repeat(64),42L,"0x"+"a".repeat(64))));
        assertEquals(source,transaction.execute(status->routing.reserveNewOrder(tenant,"ETH","ETH","hot-1",
                new BigDecimal("2"))).orElseThrow());
        assertEquals(source,transaction.execute(status->routing.reserveNewOrder(tenant,"ETH","ETH","hot-2",
                BigDecimal.ONE)).orElseThrow());
        assertThrows(IllegalStateException.class,()->transaction.execute(status->routing.reserveNewOrder(
                tenant,"ETH","ETH","hot-3",new BigDecimal("0.1"))));
        assertEquals(2,reservations.listOpen(10).size());
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id=?",Integer.class,tenant));
        assertEquals(1,reservations.markTerminal(tenant,"ETH","hot-1","CONFIRMED"));
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,source,"eip155:11155111",18,18,
                new java.math.BigInteger("1000000000000000000"),new java.math.BigInteger("1000000000000000000"),
                java.time.Instant.now().plusMillis(1),ruleId,"a".repeat(64),43L,"0x"+"b".repeat(64))));
        assertThrows(IllegalStateException.class,()->transaction.execute(status->routing.reserveNewOrder(
                tenant,"ETH","ETH","hot-4",new BigDecimal("0.1"))));
    }

    @Test void quarantinedHotWalletNeverFallsBackToLegacyRoutesAfterManualResume() {
        UUID compromisedKey=UUID.randomUUID(), standbyKey=UUID.randomUUID();
        UUID compromisedId=UUID.randomUUID(), standbyId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(compromisedId,tenant,address(14,compromisedKey),
                compromisedKey,"ETH","sepolia","Compromised",actor));
        assertEquals(1,wallets.insertStandby(standbyId,tenant,address(15,standbyKey),
                standbyKey,"ETH","sepolia","Unready standby",actor));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",compromisedId));
        assertEquals(1,wallets.quarantine(tenant,compromisedId,actor,java.time.Instant.now(),
                "suspected key leak"));
        var tenantChains=new CustodyTenantChainTableRepository(jdbc);
        assertFalse(tenantChains.withdrawalPaused(tenant,"ETH"));
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        var addresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        var chainAddresses=new com.surprising.wallet.repository.ChainAddressRepository(jdbc);
        var keys=new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc);
        var withdrawals=new com.surprising.wallet.service.HotWalletWithdrawalRoutingService(wallets,
                policies,observations,
                new com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository(jdbc),
                addresses,chainAddresses,keys,tenantChains);
        var collections=new com.surprising.wallet.service.HotWalletCollectionRoutingService(wallets,
                policies,observations,new com.surprising.wallet.repository.CollectionRecordRepository(jdbc),
                addresses,chainAddresses,keys,tenantChains,
                org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletAggregateExposureService.class));
        var transaction=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalStateException.class,()->transaction.execute(status->withdrawals.reserveNewOrder(
                tenant,"ETH","ETH","must-not-use-legacy",BigDecimal.ONE)));
        assertThrows(IllegalStateException.class,()->transaction.execute(status->collections.destination(
                tenant,"ETH","ETH",BigDecimal.ONE)));
        assertEquals(0,jdbc.queryForObject("select count(*) from custody_hot_wallet_withdrawal_reservation where tenant_id=?",
                Integer.class,tenant));
    }

    @Test void activeCollectionDestinationIsBoundWithItsOriginalRecordAndBalanceHeadroom() {
        UUID key=UUID.randomUUID(), addressId=address(21,key), walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,"ETH","sepolia","Collection reserve",actor));
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,
                "ETH","ETH",new BigDecimal("3"),new BigDecimal("10"),BigDecimal.ONE,new BigDecimal("5"))));
        var limits=new com.surprising.wallet.repository.CustodyHotWalletExposureLimitRepository(jdbc);
        assertEquals(1,limits.upsert(new com.surprising.wallet.repository.CustodyHotWalletExposureLimitRepository.Limit(
                tenant,"ETH","ETH",new BigDecimal("4"))));
        var custodyAddresses=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        String hotAddress=custodyAddresses.require(tenant,addressId).address();
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,hotAddress,"eip155:11155111",18,18,
                new java.math.BigInteger("1000000000000000000"),new java.math.BigInteger("1000000000000000000"),
                java.time.Instant.now(),UUID.randomUUID(),"b".repeat(64),42L,"0x"+"a".repeat(64))));
        String source="0x"+"ab".repeat(20);
        long sourceChainAddress=jdbc.queryForObject("""
                insert into chain_address(tenant_id,chain,asset_symbol,account_id,user_id,biz,address_index,
                    address,owner_address,derivation_path,wallet_role,enabled)
                values (?,'ETH','ETH','deposit-source',30,1,0,?,?,'deposit-test','DEPOSIT',true) returning id
                """,Long.class,tenant,source,source);
        UUID sourceId=UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,
                    status,derivation_subject,derivation_child)
                values (?,?,?,'ETH','sepolia',?,'deposit-test','API','ACTIVE',30,0)
                """,sourceId,tenant,sourceChainAddress,source);
        var records=new com.surprising.wallet.repository.CollectionRecordRepository(jdbc);
        var aggregate=new com.surprising.wallet.service.HotWalletAggregateExposureService(limits,wallets,policies,
                observations,custodyAddresses,records,new CustodyTenantChainTableRepository(jdbc),
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc));
        var routing=new com.surprising.wallet.service.HotWalletCollectionRoutingService(wallets,policies,
                observations,records,custodyAddresses,
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                new CustodyTenantChainTableRepository(jdbc),aggregate);
        var legacy=org.mockito.Mockito.mock(com.surprising.wallet.service.CollectionPlanningService.class);
        var admission=new com.surprising.wallet.service.CollectionAdmissionService(records,legacy,routing,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertEquals(1,new CustodyTenantChainTableRepository(jdbc).setWithdrawalPaused(tenant,"ETH",true,actor));
        assertThrows(IllegalStateException.class,()->admission.create(tenant,sourceId,"paused-no-safe-wallet",
                "ETH","ETH",source,BigDecimal.ONE,BigDecimal.ZERO,null));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        assertTrue(admission.create(tenant,sourceId,"hot-collection-1","ETH","ETH",source,
                BigDecimal.ONE,BigDecimal.ZERO,null));
        assertTrue(admission.create(tenant,sourceId,"hot-collection-2","ETH","ETH",source,
                new BigDecimal("2"),BigDecimal.ZERO,null));
        assertEquals(hotAddress,jdbc.queryForObject("select to_address from collection_record where tenant_id=? and collection_no='hot-collection-1'",
                String.class,tenant));
        assertEquals(0,new BigDecimal("3").compareTo(records.inboundExposure(tenant,"ETH","ETH",hotAddress)));
        assertThrows(IllegalStateException.class,()->admission.create(tenant,sourceId,"hot-collection-3",
                "ETH","ETH",source,new BigDecimal("2"),BigDecimal.ZERO,null));
        assertEquals(2,jdbc.queryForObject("select count(*) from collection_record where tenant_id=?",Integer.class,tenant));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id=?",Integer.class,tenant));
    }

    @Test void standbyActivationUsesIndependentRuleAndInventoryWhileWithdrawalsArePaused() {
        jdbc.update("update chain_asset set active=false where chain='ETH' and symbol<>'ETH'");
        UUID key=UUID.randomUUID(), addressId=address(22,key), walletId=UUID.randomUUID();
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,"ETH","sepolia","Fresh standby",actor));
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,
                "ETH","ETH",BigDecimal.ONE,new BigDecimal("5"),BigDecimal.ONE,new BigDecimal("3"))));
        String hotAddress=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc)
                .require(tenant,addressId).address();
        var ruleId=UUID.randomUUID();
        String fingerprint="c".repeat(64);
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,hotAddress,"eip155:11155111",18,18,
                java.math.BigInteger.ZERO,java.math.BigInteger.ZERO,java.time.Instant.now(),ruleId,fingerprint,
                42L,"0x"+"a".repeat(64))));
        var approval=org.mockito.Mockito.mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class);
        org.mockito.Mockito.when(approval.collectionDestination(tenant,"ETH","sepolia","ETH",key)).thenReturn(
                new com.surprising.wallet.common.approval.CollectionDestinationPolicy(tenant,"ETH","sepolia",
                        "ETH",key,hotAddress,ruleId,fingerprint,new BigDecimal("5"),new BigDecimal("5")));
        var inventory=org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletInventoryService.class);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var service=new com.surprising.wallet.service.CustodyHotWalletService(wallets,policies,
                org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyAddressService.class),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyTenantChainService.class),
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.TokenConfigRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),approval,
                org.mockito.Mockito.mock(com.surprising.wallet.chain.signing.MpcNodes.class),inventory,
                observations,new CustodyTenantChainTableRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                org.mockito.Mockito.mock(com.surprising.wallet.service.HotWalletAggregateExposureService.class),manager);
        var principal=com.surprising.wallet.testutil.CustodyTestSupport.tenantAdmin(tenant);
        assertEquals(1,new CustodyTenantChainTableRepository(jdbc).setWithdrawalPaused(tenant,"ETH",true,actor));
        var active=service.activate(principal,walletId,
                new com.surprising.wallet.service.CustodyHotWalletService.ActivateCommand(25),null);
        assertEquals("ACTIVE",active.status());
        assertEquals(25,active.routePriority());
        org.mockito.Mockito.verify(inventory).refresh(principal,walletId,"ETH",null);
        assertTrue(new CustodyTenantChainTableRepository(jdbc).withdrawalPaused(tenant,"ETH"));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=? and action='HOT_WALLET.ACTIVATE'",
                Integer.class,tenant));
    }

    @Test void pausePersistsIndependentlyOfChainOpenStatus() {
        var chains = new CustodyTenantChainTableRepository(jdbc);
        assertFalse(chains.withdrawalPaused(tenant, "ETH"));
        assertTrue(chains.lockWithdrawalRoutingEnabled(tenant, "ETH"));
        assertTrue(chains.lockForHotWalletActivation(tenant, "ETH"));
        assertEquals(1, chains.setWithdrawalPaused(tenant, "ETH", true, actor));
        assertTrue(chains.active(tenant, "ETH"));
        assertTrue(chains.withdrawalPaused(tenant, "ETH"));
        assertTrue(chains.withdrawalPausedForSigning(tenant, "ETH"));
        assertFalse(chains.lockWithdrawalRoutingEnabled(tenant, "ETH"));
        assertEquals(1, chains.setWithdrawalPaused(tenant, "ETH", false, actor));
        assertFalse(chains.withdrawalPaused(tenant, "ETH"));
    }

    @Test void hotInventoryCannotFundCustomerPool() {
        UUID key = UUID.randomUUID(), hotId = address(7, key);
        String hotAddress = new com.surprising.wallet.repository.CustodyAddressRepository(jdbc)
                .require(tenant, hotId).address();
        var balances = new com.surprising.wallet.repository.LedgerBalanceRepository(jdbc);
        assertEquals(1, balances.increment(tenant, "ETH", "ETH", hotAddress, new BigDecimal("9")));
        var pool = new com.surprising.wallet.service.CustodyPoolFundingService(balances,
                new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.CustodyLedgerEntryRepository(jdbc));
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertThrows(IllegalArgumentException.class, () -> transactions.executeWithoutResult(status ->
                pool.fund(tenant, new com.surprising.wallet.repository.CustodyAddressRepository(jdbc)
                        .require(tenant, hotId), "ETH", BigDecimal.ONE, "hot-exclusion-test")));
        assertEquals(0, new BigDecimal("9").compareTo(jdbc.queryForObject(
                "select available_balance from ledger_balance where tenant_id=? and chain='ETH' and account_id=?",
                BigDecimal.class, tenant, hotAddress)));
    }

    @Test void acceptedWithdrawalKeepsItsOriginalSenderAcrossClaimsAndStatusChanges() {
        var orders = new com.surprising.wallet.repository.WithdrawalOrderRepository(jdbc);
        String original = "0x" + "1".repeat(40);
        String replacement = "0x" + "2".repeat(40);
        long id = orders.createForTenant(tenant, "hot-source-test", 1L, "ETH", "ETH",
                original, "pool:test", replacement, BigDecimal.ONE, BigDecimal.ZERO);
        assertTrue(id > 0);
        assertEquals(0, orders.updateStatus(tenant, "ETH", "hot-source-test", "FROZEN",
                replacement, null, null));
        assertEquals(1, orders.updateStatus(tenant, "ETH", "hot-source-test", "FROZEN",
                original, null, null));
        assertEquals(0, orders.claimSigningDurable(tenant, "ETH", "hot-source-test", replacement));
        assertEquals(1, orders.claimSigningDurable(tenant, "ETH", "hot-source-test", original));
        assertEquals(0, orders.markSent(tenant, "ETH", "hot-source-test", replacement,
                "0x" + "a".repeat(64)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update withdrawal_order set from_address=? where id=?", replacement, id));
        assertEquals(original, orders.findById(tenant, id).orElseThrow().getFromAddress());
    }

    @Test void queuedUtxoOrderBindsItsFirstSelectedSenderOnlyOnce() {
        var orders = new com.surprising.wallet.repository.WithdrawalOrderRepository(jdbc);
        long id = orders.createForTenant(tenant, "queued-source-test", 1L, "BTC", "BTC",
                null, "customer-account", "bc1qrecipient", BigDecimal.ONE, BigDecimal.ZERO);
        assertTrue(id > 0);
        assertEquals(1, orders.updateStatus(tenant, "BTC", "queued-source-test", "FROZEN",
                null, null, null));
        assertEquals(1, orders.claimSigning(tenant, "BTC", "queued-source-test", "bc1qfirstsender"));
        assertEquals("bc1qfirstsender", orders.findById(tenant, id).orElseThrow().getFromAddress());
        assertEquals(0, orders.updateStatus(tenant, "BTC", "queued-source-test", "RETRYING",
                "bc1qsecondsender", null, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update withdrawal_order set from_address='bc1qsecondsender' where id=?", id));
    }

    @Test void registeredHotWalletPreservesItsTokenAndAddressIdentities() {
        UUID key = UUID.randomUUID(), otherKey = UUID.randomUUID(), walletId = UUID.randomUUID();
        UUID custodyAddress = address(81, key);
        address(82, otherKey);
        var wallets = new CustodyHotWalletRepository(jdbc);
        assertEquals(1, wallets.insertStandby(walletId, tenant, custodyAddress, key,
                "ETH", "sepolia", "Historical token guard", actor));
        String originalContract = jdbc.queryForObject("""
                select contract_address from token_config
                 where chain='ETH' and network='sepolia' and symbol='USDT'
                """, String.class);

        assertEquals(1, jdbc.update("""
                update token_config set enabled=false, collect_enabled=false
                 where chain='ETH' and network='sepolia' and symbol='USDT'
                """));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                update token_config set contract_address=?
                 where chain='ETH' and network='sepolia' and symbol='USDT'
                """, "0x" + "f".repeat(40)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                update token_config set decimals=18
                 where chain='ETH' and network='sepolia' and symbol='USDT'
                """));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                delete from token_config where chain='ETH' and network='sepolia' and symbol='USDT'
                """));
        assertEquals(originalContract, jdbc.queryForObject("""
                select contract_address from token_config
                 where chain='ETH' and network='sepolia' and symbol='USDT'
                """, String.class));
        assertFalse(jdbc.queryForObject("""
                select enabled from token_config
                 where chain='ETH' and network='sepolia' and symbol='USDT'
                """, Boolean.class));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_hot_wallet set mpc_key_id=? where id=?", otherKey, walletId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_hot_wallet set network='mainnet' where id=?", walletId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "delete from custody_hot_wallet where id=?", walletId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_address set address=? where id=?", "0x" + "d".repeat(40), custodyAddress));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_address set network='mainnet' where id=?", custodyAddress));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "delete from custody_address where id=?", custodyAddress));
        assertEquals(1, jdbc.update("update custody_address set status='DISABLED' where id=?", custodyAddress));
        assertEquals(1, jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?", walletId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update custody_hot_wallet set status='STANDBY' where id=?", walletId));
        assertEquals(key, wallets.find(tenant, walletId).orElseThrow().mpcKeyId());
    }

    @Test void concurrentRegistrationPreventsTokenIdentityRewrite() throws Exception {
        UUID key = UUID.randomUUID(), walletId = UUID.randomUUID();
        UUID custodyAddress = address(83, key);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var registering = java.util.concurrent.CompletableFuture.runAsync(() ->
                new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
                    assertEquals(1, new CustodyHotWalletRepository(jdbc).insertStandby(
                            walletId, tenant, custodyAddress, key, "ETH", "sepolia", "Race guard", actor));
                    inserted.countDown();
                    try {
                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("registration fixture was not released");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
        try {
            assertTrue(inserted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var editStarted = new java.util.concurrent.CountDownLatch(1);
            var editing = java.util.concurrent.CompletableFuture.runAsync(() -> {
                editStarted.countDown();
                jdbc.update("""
                        update token_config set contract_address=?
                         where chain='ETH' and network='sepolia' and symbol='USDT'
                        """, "0x" + "e".repeat(40));
            });
            assertTrue(editStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> editing.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            registering.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> editing.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(DataIntegrityViolationException.class, rejected.getCause());
        } finally {
            release.countDown();
        }
    }

    @Test void evmInventoryCannotReplaceAnAnchorWithOlderOrConflictingBlockEvidence() {
        UUID key=UUID.randomUUID(),walletId=UUID.randomUUID();
        UUID custodyAddress=address(84,key);
        assertEquals(1,new CustodyHotWalletRepository(jdbc).insertStandby(walletId,tenant,custodyAddress,key,
                "ETH","sepolia","Anchored inventory",actor));
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,
                "ETH","ETH",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN)));
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        String address=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc)
                .require(tenant,custodyAddress).address();
        var at=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID rule=UUID.randomUUID();
        var first=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,address,"eip155:11155111",18,18,
                java.math.BigInteger.valueOf(100),java.math.BigInteger.valueOf(100),at,rule,"a".repeat(64),
                42L,"0x"+"a".repeat(64));
        assertEquals(1,observations.record(first));
        assertEquals(0,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,address,"eip155:11155111",18,18,
                java.math.BigInteger.valueOf(100),java.math.BigInteger.valueOf(100),at.plusSeconds(1),rule,"a".repeat(64),
                41L,"0x"+"b".repeat(64))));
        assertEquals(0,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,address,"eip155:11155111",18,18,
                java.math.BigInteger.valueOf(100),java.math.BigInteger.valueOf(100),at.plusSeconds(1),rule,"a".repeat(64),
                42L,"0x"+"b".repeat(64))));
        assertEquals(0,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,address,"eip155:11155111",18,18,
                java.math.BigInteger.valueOf(99),java.math.BigInteger.valueOf(99),at.plusSeconds(1),rule,"a".repeat(64),
                42L,"0x"+"a".repeat(64))));
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,address,"eip155:11155111",18,18,
                java.math.BigInteger.valueOf(99),java.math.BigInteger.valueOf(99),at.plusSeconds(1),rule,"a".repeat(64),
                43L,"0x"+"c".repeat(64))));
        assertEquals(43L,observations.find(tenant,walletId,"ETH").orElseThrow().observedBlockHeight());
    }

    @Test void finalizedDirectSpendAccountsPrincipalOnceWithoutResettingDailyExposure() {
        UUID key=UUID.randomUUID(),walletId=UUID.randomUUID(),addressId=address(85,key);
        var wallets=new CustodyHotWalletRepository(jdbc);
        assertEquals(1,wallets.insertStandby(walletId,tenant,addressId,key,"ETH","sepolia","Reconcile",actor));
        assertEquals(1,jdbc.update("update custody_hot_wallet set status='ACTIVE' where id=?",walletId));
        var policies=new CustodyHotWalletAssetPolicyRepository(jdbc);
        assertEquals(1,policies.upsert(new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,
                "ETH","ETH",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN)));
        String source=new com.surprising.wallet.repository.CustodyAddressRepository(jdbc)
                .require(tenant,addressId).address();
        String recipient="0x"+"8".repeat(40),txHash="0x"+"f".repeat(64);
        String orderNo="direct-1";
        var orders=new com.surprising.wallet.repository.WithdrawalOrderRepository(jdbc);
        long orderId=orders.createForTenant(tenant,orderNo,1L,"ETH","ETH",source,"customer-account",
                recipient,new BigDecimal("2"),BigDecimal.ZERO);
        var holds=new com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository(jdbc);
        var initial=java.time.Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertEquals(1,holds.reserve(UUID.randomUUID(),tenant,walletId,"ETH","ETH",orderNo,source,
                new BigDecimal("2"),initial));
        assertEquals(1,jdbc.update("update withdrawal_order set status='CONFIRMED',tx_hash=? where id=?",txHash,orderId));
        assertEquals(1,holds.markTerminal(tenant,"ETH",orderNo,"CONFIRMED"));
        var observations=new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc);
        UUID rule=UUID.randomUUID();String fingerprint="a".repeat(64);
        assertEquals(1,observations.record(new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.Observation(
                tenant,walletId,"ETH","ETH",key,source,"eip155:11155111",18,18,
                new java.math.BigInteger("3000000000000000000"),new java.math.BigInteger("3000000000000000000"),
                initial,rule,fingerprint,40L,"0x"+"a".repeat(64))));
        var approval=org.mockito.Mockito.mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class);
        var proof=new com.surprising.wallet.common.approval.HotWalletSettledSpendObservation(
                tenant,key,"ETH","sepolia","eip155:11155111","ETH",null,
                source,recipient,txHash,new java.math.BigInteger("2000000000000000000"),18,18,
                new java.math.BigInteger("990000000000000000"),new java.math.BigInteger("990000000000000000"),
                41L,"0x"+"b".repeat(64),42L,"0x"+"c".repeat(64),java.time.Instant.now(),rule,fingerprint);
        org.mockito.Mockito.when(approval.settledDirectSpend(org.mockito.ArgumentMatchers.any())).thenReturn(proof);
        var service=new com.surprising.wallet.service.HotWalletSettledSpendReconciliationService(wallets,holds,
                observations,orders,new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc),
                new com.surprising.wallet.repository.TokenConfigRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),approval,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertEquals(0,new BigDecimal("2").compareTo(holds.committedExposure(tenant,walletId,"ETH")));
        var principal=com.surprising.wallet.testutil.CustodyTestSupport.tenantAdmin(tenant);
        org.mockito.Mockito.when(approval.settledDirectSpend(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.surprising.wallet.common.approval.HotWalletSettledSpendObservation(
                        tenant,key,"ETH","sepolia","eip155:11155111","ETH",null,
                        source,"0x"+"7".repeat(40),txHash,new java.math.BigInteger("2000000000000000000"),18,18,
                        proof.availableAtomic(),proof.nativeAtomic(),41L,proof.receiptBlockHash(),
                        42L,proof.observedBlockHash(),java.time.Instant.now(),rule,fingerprint));
        assertThrows(IllegalStateException.class,()->service.reconcile(principal,walletId,orderNo,"127.0.0.1"));
        assertEquals(0,new BigDecimal("2").compareTo(holds.committedExposure(tenant,walletId,"ETH")));
        org.mockito.Mockito.reset(approval);
        org.mockito.Mockito.when(approval.settledDirectSpend(org.mockito.ArgumentMatchers.any())).thenReturn(proof);
        var accounted=service.reconcile(principal,walletId,orderNo,"127.0.0.1");
        assertTrue(accounted.newlyAccounted());
        assertEquals(42L,accounted.observedBlockHeight());
        assertEquals(0,BigDecimal.ZERO.compareTo(accounted.remainingCommittedExposure()));
        assertEquals(0,BigDecimal.ZERO.compareTo(holds.committedExposure(tenant,walletId,"ETH")));
        assertEquals(0,new BigDecimal("2").compareTo(holds.rollingExposure(
                tenant,walletId,"ETH",java.time.Instant.now().minusSeconds(86400))));
        assertEquals(42L,observations.find(tenant,walletId,"ETH").orElseThrow().observedBlockHeight());
        assertFalse(service.reconcile(principal,walletId,orderNo,"127.0.0.1").newlyAccounted());
        org.mockito.Mockito.verify(approval,org.mockito.Mockito.times(1))
                .settledDirectSpend(org.mockito.ArgumentMatchers.any());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "update custody_hot_wallet_withdrawal_reservation set accounted_tx_hash=? where order_no=?",
                "0x"+"e".repeat(64),orderNo));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "update withdrawal_order set tx_hash=? where id=?","0x"+"e".repeat(64),orderId));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "update withdrawal_order set to_address=? where id=?","0x"+"7".repeat(40),orderId));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
                "update withdrawal_order set amount=3 where id=?",orderId));

        String stuckOrder="pending-unprovable",validOrder="pending-valid";
        String stuckHash="0x"+"d".repeat(64),validHash="0x"+"c".repeat(64);
        long stuckId=orders.createForTenant(tenant,stuckOrder,1L,"ETH","ETH",source,
                "customer-account",recipient,BigDecimal.ONE,BigDecimal.ZERO);
        long validId=orders.createForTenant(tenant,validOrder,1L,"ETH","ETH",source,
                "customer-account",recipient,BigDecimal.ONE,BigDecimal.ZERO);
        assertEquals(1,holds.reserve(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                tenant,walletId,"ETH","ETH",stuckOrder,source,BigDecimal.ONE,initial));
        assertEquals(1,holds.reserve(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                tenant,walletId,"ETH","ETH",validOrder,source,BigDecimal.ONE,initial));
        jdbc.update("update withdrawal_order set status='CONFIRMED',tx_hash=? where id=?",stuckHash,stuckId);
        jdbc.update("update withdrawal_order set status='CONFIRMED',tx_hash=? where id=?",validHash,validId);
        holds.markTerminal(tenant,"ETH",stuckOrder,"CONFIRMED");
        holds.markTerminal(tenant,"ETH",validOrder,"CONFIRMED");
        var validProof=new com.surprising.wallet.common.approval.HotWalletSettledSpendObservation(
                tenant,key,"ETH","sepolia","eip155:11155111","ETH",null,
                source,recipient,validHash,new java.math.BigInteger("1000000000000000000"),18,18,
                new java.math.BigInteger("500000000000000000"),new java.math.BigInteger("500000000000000000"),
                43L,"0x"+"d".repeat(64),44L,"0x"+"e".repeat(64),java.time.Instant.now(),rule,fingerprint);
        org.mockito.Mockito.when(approval.settledDirectSpend(org.mockito.ArgumentMatchers.any()))
                .thenReturn(validProof);
        service.reconcilePending();
        assertNull(holds.findByOrder(tenant,"ETH",stuckOrder).orElseThrow().accountedAt());
        assertNotNull(holds.findByOrder(tenant,"ETH",validOrder).orElseThrow().accountedAt());
        assertEquals(0,BigDecimal.ONE.compareTo(holds.committedExposure(tenant,walletId,"ETH")));
        assertEquals(1,jdbc.queryForObject("select count(*) from custody_audit_log "
                +"where tenant_id=? and action='HOT_WALLET.CONFIRMED_SPEND_ACCOUNTED' "
                +"and actor_type='SYSTEM' and actor_id='finalized-spend-reconciliation'",Integer.class,tenant));
    }

    private UUID address(int index, UUID keyId) {
        String value = "0x" + String.format("%040x", index);
        Integer namespace = jdbc.queryForObject("select derivation_namespace from custody_tenant where id=?",
                Integer.class, tenant);
        jdbc.update("""
                insert into wallet_mpc_key(id,tenant_id,key_group,user_id,biz,address_index,
                    algorithm,state,active_epoch,public_key_hex)
                values (?,?, 'EVM',?,?,2,'ECDSA_SECP256K1','ACTIVE',1,?)
                """, keyId, tenant, index, namespace, "02" + String.format("%064x", index));
        long chainId = jdbc.queryForObject("""
                insert into chain_address(tenant_id,mpc_key_id,chain,asset_symbol,account_id,user_id,biz,address_index,
                    address,owner_address,derivation_path,wallet_role,enabled)
                values (?,?,'ETH','ETH',?,?,?,2,?,?,'test-only','HOT_WITHDRAW',true) returning id
                """, Long.class, tenant, keyId, value, index, namespace, value, value);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,
                    status,derivation_subject,derivation_child)
                values (?,?,?,'ETH','sepolia',?,?,'CONSOLE','ACTIVE',?,2)
                """, id, tenant, chainId, value, "__sw_hot__:ETH:" + id, index);
        return id;
    }
}
