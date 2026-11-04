package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.signing.Evm7702PayoutPayload;
import com.surprising.wallet.common.signing.Evm7702CollectionPayload;
import com.surprising.wallet.common.signing.Evm7702SigningPayload;
import com.surprising.wallet.common.signing.MpcSignCommand;
import com.surprising.wallet.common.approval.AutomaticOperation;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.common.approval.AutomaticParentPlan;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702CollectionRequest;
import com.surprising.wallet.model.mpc.MpcPendingException;
import com.surprising.wallet.model.mpc.MpcSignAttempt;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import java.util.Optional;
import java.util.List;
import java.util.HexFormat;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class HotWalletSigningFenceTest {
    @Test
    void pausedCollectionFinalAuthorizationRequiresLockedActiveOwnedDestination() {
        UUID tenant=UUID.randomUUID(),walletId=UUID.randomUUID(),key=UUID.randomUUID(),addressId=UUID.randomUUID();
        var now=java.time.Instant.now();
        String destination="0x"+"4".repeat(40);
        var active=new CustodyHotWalletRepository.Wallet(walletId,tenant,addressId,key,
                "ETH","sepolia","Safe","ACTIVE",10,null,null,null,null,UUID.randomUUID(),now,now);
        var wallets=mock(CustodyHotWalletRepository.class);
        var chains=mock(CustodyTenantChainTableRepository.class);
        var addresses=mock(CustodyAddressRepository.class);
        var chainAddresses=mock(ChainAddressRepository.class);
        var hotObservations=mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class);
        when(chains.withdrawalPausedForSigning(tenant,"ETH")).thenReturn(true);
        when(wallets.list(tenant,"ETH")).thenReturn(List.of(active));
        when(wallets.lock(tenant,walletId)).thenReturn(Optional.of(active));
        when(hotObservations.allUninvalidated(tenant,walletId)).thenReturn(true);
        when(hotObservations.lockAllUninvalidated(tenant,walletId)).thenReturn(true);
        when(addresses.require(tenant,addressId)).thenReturn(new CustodyAddressRecord(addressId,tenant,
                11L,"ETH","sepolia",destination,null,"__sw_hot__:ETH:"+walletId,
                "Safe","{}","CONSOLE","ACTIVE",1,1,2,now,now));
        when(chainAddresses.findOwned(tenant,11L)).thenReturn(Optional.of(ChainAddressRecord.builder()
                .tenantId(tenant).chain("ETH").address(destination).mpcKeyId(key)
                .walletRole("HOT_WITHDRAW").enabled(true).build()));
        var operation=mock(AutomaticOperation.class);
        var payment=mock(ApprovalRequest.class);
        var intent=mock(WithdrawalIntent.class);
        when(operation.businessType()).thenReturn("COLLECTION");
        when(operation.payments()).thenReturn(List.of(payment));
        when(payment.intent()).thenReturn(intent);
        when(intent.tenantId()).thenReturn(tenant);
        when(intent.chain()).thenReturn("ETH");
        when(intent.network()).thenReturn("sepolia");
        when(intent.recipient()).thenReturn(destination);
        var command=mock(MpcSignCommand.class);
        when(command.businessType()).thenReturn(MpcSignCommand.BusinessType.COLLECTION);
        when(command.chain()).thenReturn(ChainType.ETH);
        var independent=mock(WithdrawalApprovalClient.class);
        var service=new MpcAuthorizationService(mock(WalletMpcSignRequestRepository.class),
                mock(WalletMpcSignAttemptRepository.class),mock(WalletWithdrawalApprovalRepository.class),
                mock(CustodyAuditRepository.class),independent,mock(ObjectMapper.class),
                mock(PlatformTransactionManager.class),mock(WalletMpcKeyRepository.class),
                mock(WalletMpcKeyEpochRepository.class),mock(WalletMpcRelayRepository.class),
                mock(CustodyTenantUserRepository.class),mock(MpcNonceAdmissionService.class),
                chains,wallets,hotObservations,
                addresses,chainAddresses);
        UUID requestId=UUID.randomUUID();
        assertDoesNotThrow(()->service.requireSafePausedCollection(tenant,command,operation,requestId,true));
        when(hotObservations.lockAllUninvalidated(tenant,walletId)).thenReturn(false);
        assertThrows(MpcPendingException.class,()->service.requireSafePausedCollection(
                tenant,command,operation,requestId,true));
        when(hotObservations.lockAllUninvalidated(tenant,walletId)).thenReturn(true);
        verify(wallets,times(2)).lock(tenant,walletId);
        when(chains.withdrawalPaused(tenant,"ETH")).thenReturn(true);
        var safePlan=new AutomaticParentPlan(tenant,UUID.randomUUID(),"ETH","sepolia","ETH",
                "collection-safe",destination,java.math.BigDecimal.ONE,null);
        var oldPlan=new AutomaticParentPlan(tenant,UUID.randomUUID(),"ETH","sepolia","ETH",
                "collection-old","0x"+"5".repeat(40),java.math.BigDecimal.ONE,null);
        assertThrows(IllegalStateException.class,()->service.planCollection(oldPlan));
        verifyNoInteractions(independent);
        when(independent.plan(safePlan)).thenThrow(new IllegalStateException("independent reached"));
        assertEquals("independent reached",assertThrows(IllegalStateException.class,
                ()->service.planCollection(safePlan)).getMessage());
        when(wallets.lock(tenant,walletId)).thenReturn(Optional.of(new CustodyHotWalletRepository.Wallet(
                walletId,tenant,addressId,key,"ETH","sepolia","Safe","QUARANTINED",10,
                null,null,null,null,UUID.randomUUID(),now,now)));
        assertThrows(MpcPendingException.class,
                ()->service.requireSafePausedCollection(tenant,command,operation,requestId,true));
    }

    @Test
    void delegatedCollectionRelayerCannotSignOrBroadcastAfterAuthorityQuarantine() {
        UUID tenant=UUID.randomUUID(),relayerKey=UUID.randomUUID(),hotKey=UUID.randomUUID();
        UUID requestId=UUID.randomUUID();
        var json=new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper();
        var authority=ChainAddressRecord.builder().tenantId(tenant).chain("ETH")
                .mpcKeyId(hotKey).address("0x"+"1".repeat(40)).build();
        var relayer=ChainAddressRecord.builder().tenantId(tenant).chain("ETH")
                .mpcKeyId(relayerKey).address("0x"+"2".repeat(40)).build();
        var prepared=new Evm7702CollectionPayload(BigInteger.ONE,BigInteger.ZERO,BigInteger.ONE,
                BigInteger.ONE,BigInteger.valueOf(300_000),
                List.of(new Evm7702CollectionPayload.Item(authority,null,null)));
        var envelope=new Evm7702SigningPayload(new Evm7702SigningPayload.Plan(
                1L,BigInteger.ZERO,relayer,json.writeValueAsString(prepared),List.of("collection-1")),null);
        var command=new MpcSignCommand(tenant,relayerKey,1,ChainType.ETH,
                MpcSignCommand.BusinessType.COLLECTION,"batch-collection","relayer",
                MpcSignCommand.Purpose.TRANSACTION,Evm7702SigningPayload.ENCODING,
                HexFormat.of().formatHex(json.writeValueAsBytes(envelope)),"00");
        var requests=mock(WalletMpcSignRequestRepository.class);
        var attempts=mock(WalletMpcSignAttemptRepository.class);
        var wallets=mock(CustodyHotWalletRepository.class);
        var approval=mock(WithdrawalApprovalClient.class);
        var chains=mock(CustodyTenantChainTableRepository.class);
        when(requests.find(tenant,requestId)).thenReturn(Optional.of(new MpcSignRequest(
                requestId,command,MpcSignRequest.State.PENDING,UUID.randomUUID())));
        when(attempts.find(eq(tenant),any())).thenReturn(Optional.of(mock(MpcSignAttempt.class)));
        var compromised=new CustodyHotWalletRepository.Wallet(UUID.randomUUID(),tenant,UUID.randomUUID(),
                        hotKey,"ETH","sepolia","Old","QUARANTINED",100,null,null,null,null,
                        UUID.randomUUID(),null,null);
        when(wallets.findByKey(tenant,"ETH",hotKey)).thenReturn(Optional.of(compromised));
        when(wallets.lockByKeyForSigning(tenant,"ETH",hotKey)).thenReturn(Optional.of(compromised));
        var service=new MpcAuthorizationService(requests,attempts,
                mock(WalletWithdrawalApprovalRepository.class),mock(CustodyAuditRepository.class),
                approval,json,mock(PlatformTransactionManager.class),mock(WalletMpcKeyRepository.class),
                mock(WalletMpcKeyEpochRepository.class),mock(WalletMpcRelayRepository.class),
                mock(CustodyTenantUserRepository.class),mock(MpcNonceAdmissionService.class),chains,wallets,
                mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class),
                mock(CustodyAddressRepository.class),mock(ChainAddressRepository.class));
        assertThrows(MpcPendingException.class,()->service.authorize(tenant,requestId,UUID.randomUUID()));
        verifyNoInteractions(approval);
        when(chains.lockCollectionRoutingOpen(tenant,"ETH")).thenReturn(true);
        var fence=new HotWalletBroadcastFence(chains,wallets,
                mock(CustodyAddressRepository.class),mock(ChainAddressRepository.class),
                mock(com.surprising.wallet.repository.CollectionRecordRepository.class),
                mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class),
                mock(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,()->fence.requireCollectionAllowed(tenant,"ETH","sepolia",prepared));
        when(wallets.lockByKeyForSigning(tenant,"ETH",hotKey)).thenReturn(Optional.empty());
        assertDoesNotThrow(()->fence.requireCollectionAllowed(tenant,"ETH","sepolia",prepared));
        when(chains.withdrawalPausedForSigning(tenant,"ETH")).thenReturn(true);
        assertThrows(IllegalStateException.class,()->fence.requireCollectionAllowed(tenant,"ETH","sepolia",prepared));
        UUID safeId=UUID.randomUUID(),safeKey=UUID.randomUUID();
        String safe="0x"+"6".repeat(40);
        var safeWallet=new CustodyHotWalletRepository.Wallet(UUID.randomUUID(),tenant,safeId,safeKey,
                "ETH","sepolia","Safe","ACTIVE",10,null,null,null,null,UUID.randomUUID(),null,null);
        var addresses=mock(CustodyAddressRepository.class);
        var chainAddresses=mock(ChainAddressRepository.class);
        var safeManager=mock(PlatformTransactionManager.class);
        when(safeManager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var safeObservations=mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class);
        var safeFence=new HotWalletBroadcastFence(chains,wallets,addresses,chainAddresses,
                mock(com.surprising.wallet.repository.CollectionRecordRepository.class),safeObservations,safeManager);
        when(safeObservations.lockAllUninvalidated(tenant,safeWallet.id())).thenReturn(true);
        when(wallets.list(tenant,"ETH")).thenReturn(List.of(safeWallet));
        when(wallets.lock(tenant,safeWallet.id())).thenReturn(Optional.of(safeWallet));
        when(addresses.require(tenant,safeId)).thenReturn(new CustodyAddressRecord(safeId,tenant,42L,
                "ETH","sepolia",safe,null,"__sw_hot__:ETH:"+safeWallet.id(),"Safe","{}",
                "CONSOLE","ACTIVE",1,1,2,java.time.Instant.now(),java.time.Instant.now()));
        when(chainAddresses.findOwned(tenant,42L)).thenReturn(Optional.of(ChainAddressRecord.builder()
                .tenantId(tenant).chain("ETH").address(safe).mpcKeyId(safeKey)
                .walletRole("HOT_WITHDRAW").enabled(true).build()));
        var safeRequest=new Evm7702CollectionRequest(new byte[32],BigInteger.ZERO,
                authority.getAddress(),relayer.getAddress(),"0x"+"0".repeat(40),safe,
                BigInteger.ONE,BigInteger.ZERO,BigInteger.valueOf(4_000_000_000L),BigInteger.ONE);
        var safePrepared=new Evm7702CollectionPayload(BigInteger.ONE,BigInteger.ZERO,BigInteger.ONE,
                BigInteger.ONE,BigInteger.valueOf(300_000),
                List.of(new Evm7702CollectionPayload.Item(authority,null,safeRequest)));
        assertDoesNotThrow(()->safeFence.requireCollectionAllowed(tenant,"ETH","sepolia",safePrepared));
        assertEquals("sent",safeFence.withEip7702CollectionBroadcastPermit(tenant,"ETH","sepolia",
                safePrepared,()->"sent"));
        when(safeObservations.lockAllUninvalidated(tenant,safeWallet.id())).thenReturn(false);
        assertThrows(IllegalStateException.class,()->safeFence.requireCollectionAllowed(
                tenant,"ETH","sepolia",safePrepared));
    }

    @Test
    void signedEvmCollectionCannotBroadcastToOldTargetAfterIncidentPause() {
        UUID tenant=UUID.randomUUID(),sourceKey=UUID.randomUUID(),safeId=UUID.randomUUID(),safeKey=UUID.randomUUID();
        String hash="0x"+"a".repeat(64),safe="0x"+"6".repeat(40);
        var chains=mock(CustodyTenantChainTableRepository.class);
        var wallets=mock(CustodyHotWalletRepository.class);
        var addresses=mock(CustodyAddressRepository.class);
        var owners=mock(ChainAddressRepository.class);
        var collections=mock(CollectionRecordRepository.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(chains.lockCollectionRoutingOpen(tenant,"ETH")).thenReturn(true);
        when(chains.withdrawalPausedForSigning(tenant,"ETH")).thenReturn(true);
        when(wallets.lockByKeyForSigning(tenant,"ETH",sourceKey)).thenReturn(Optional.empty());
        var original=com.surprising.wallet.chain.model.ChainCollectionRecord.builder()
                .tenantId(tenant).chain("ETH").collectionNo("collection-1")
                .toAddress("0x"+"5".repeat(40)).txHash(hash).status("BROADCAST_UNKNOWN").build();
        when(collections.lockForSubmission(tenant,"ETH","collection-1")).thenReturn(Optional.of(original));
        var observations=mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class);
        var fence=new HotWalletBroadcastFence(chains,wallets,addresses,owners,collections,observations,manager);
        var sent=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class,()->fence.withEvmCollectionBroadcastPermit(
                tenant,"ETH","sepolia","collection-1",hash,sourceKey,()->{sent.incrementAndGet();return "tx";}));
        assertEquals(0,sent.get());
        var active=new CustodyHotWalletRepository.Wallet(UUID.randomUUID(),tenant,safeId,safeKey,
                "ETH","sepolia","Safe","ACTIVE",10,null,null,null,null,UUID.randomUUID(),null,null);
        when(wallets.list(tenant,"ETH")).thenReturn(List.of(active));
        when(wallets.lock(tenant,active.id())).thenReturn(Optional.of(active));
        when(observations.lockAllUninvalidated(tenant,active.id())).thenReturn(true);
        when(addresses.require(tenant,safeId)).thenReturn(new CustodyAddressRecord(safeId,tenant,42L,
                "ETH","sepolia",safe,null,"__sw_hot__:ETH:"+active.id(),"Safe","{}",
                "CONSOLE","ACTIVE",1,1,2,java.time.Instant.now(),java.time.Instant.now()));
        when(owners.findOwned(tenant,42L)).thenReturn(Optional.of(ChainAddressRecord.builder()
                .tenantId(tenant).chain("ETH").address(safe).mpcKeyId(safeKey)
                .walletRole("HOT_WITHDRAW").enabled(true).build()));
        original.setToAddress(safe);
        assertEquals("tx",fence.withEvmCollectionBroadcastPermit(tenant,"ETH","sepolia",
                "collection-1",hash,sourceKey,()->{sent.incrementAndGet();return "tx";}));
        assertEquals(1,sent.get());
    }

    @Test
    void signedEvmPayoutCannotBeBroadcastAfterPauseOrAuthorityQuarantine() {
        UUID tenant = UUID.randomUUID(), hotKey = UUID.randomUUID();
        var chains = mock(CustodyTenantChainTableRepository.class);
        var wallets = mock(CustodyHotWalletRepository.class);
        var observations=mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class);
        var fence = new HotWalletBroadcastFence(chains, wallets,
                mock(CustodyAddressRepository.class),mock(ChainAddressRepository.class),
                mock(com.surprising.wallet.repository.CollectionRecordRepository.class),observations,
                mock(PlatformTransactionManager.class));
        when(chains.withdrawalPausedForSigning(tenant, "ETH")).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> fence.requireAllowed(tenant, "ETH", hotKey));
        verifyNoInteractions(wallets);
        when(chains.withdrawalPausedForSigning(tenant, "ETH")).thenReturn(false);
        when(wallets.lockByKeyForSigning(tenant, "ETH", hotKey)).thenReturn(Optional.of(
                new CustodyHotWalletRepository.Wallet(UUID.randomUUID(), tenant, UUID.randomUUID(),
                        hotKey, "ETH", "mainnet", "Compromised", "QUARANTINED", 100,
                        UUID.randomUUID(), java.time.Instant.now(), "compromised key", null,
                        UUID.randomUUID(), null, null)));
        assertThrows(IllegalStateException.class, () -> fence.requireAllowed(tenant, "ETH", hotKey));
        when(wallets.lockByKeyForSigning(tenant, "ETH", hotKey)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> fence.requireAllowed(tenant, "ETH", hotKey));
        var active=new CustodyHotWalletRepository.Wallet(UUID.randomUUID(),tenant,UUID.randomUUID(),
                hotKey,"ETH","mainnet","Active","ACTIVE",100,null,null,null,null,
                UUID.randomUUID(),null,null);
        when(wallets.lockByKeyForSigning(tenant,"ETH",hotKey)).thenReturn(Optional.of(active));
        assertThrows(IllegalStateException.class,()->fence.requireAllowed(tenant,"ETH",hotKey));
        when(observations.lockAllUninvalidated(tenant,active.id())).thenReturn(true);
        assertDoesNotThrow(()->fence.requireAllowed(tenant,"ETH",hotKey));
    }

    @Test
    void delegatedPayoutRelayerCannotBypassQuarantinedAuthority() {
        UUID tenant = UUID.randomUUID(), relayerKey = UUID.randomUUID(), hotKey = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var json = new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper();
        var hotAddress = ChainAddressRecord.builder().tenantId(tenant).chain("ETH")
                .mpcKeyId(hotKey).address("0x" + "1".repeat(40)).build();
        var relayer = ChainAddressRecord.builder().tenantId(tenant).chain("ETH")
                .mpcKeyId(relayerKey).address("0x" + "2".repeat(40)).build();
        var preparation = new Evm7702PayoutPayload(BigInteger.ONE, BigInteger.ZERO,
                BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(30_000), hotAddress, null, null);
        var envelope = new Evm7702SigningPayload(new Evm7702SigningPayload.Plan(
                1L, BigInteger.ZERO, relayer, json.writeValueAsString(preparation), List.of("order-1")), null);
        var command = new MpcSignCommand(tenant, relayerKey, 1, ChainType.ETH,
                MpcSignCommand.BusinessType.WITHDRAWAL, "batch-1", "relayer",
                MpcSignCommand.Purpose.TRANSACTION, Evm7702SigningPayload.ENCODING,
                HexFormat.of().formatHex(json.writeValueAsBytes(envelope)), "00");
        var requests = mock(WalletMpcSignRequestRepository.class);
        var attempts = mock(WalletMpcSignAttemptRepository.class);
        var hotWallets = mock(CustodyHotWalletRepository.class);
        var approval = mock(WithdrawalApprovalClient.class);
        when(requests.find(tenant, requestId)).thenReturn(Optional.of(new MpcSignRequest(
                requestId, command, MpcSignRequest.State.PENDING, UUID.randomUUID())));
        when(attempts.find(eq(tenant), any())).thenReturn(Optional.of(mock(MpcSignAttempt.class)));
        when(hotWallets.findByKey(tenant, "ETH", relayerKey)).thenReturn(Optional.empty());
        when(hotWallets.findByKey(tenant, "ETH", hotKey)).thenReturn(Optional.of(
                new CustodyHotWalletRepository.Wallet(UUID.randomUUID(), tenant, UUID.randomUUID(),
                        hotKey, "ETH", "mainnet", "Compromised", "QUARANTINED", 100,
                        UUID.randomUUID(), java.time.Instant.now(), "compromised key", null,
                        UUID.randomUUID(), null, null)));
        var service = new MpcAuthorizationService(requests, attempts,
                mock(WalletWithdrawalApprovalRepository.class), mock(CustodyAuditRepository.class),
                approval, json, mock(PlatformTransactionManager.class), mock(WalletMpcKeyRepository.class),
                mock(WalletMpcKeyEpochRepository.class), mock(WalletMpcRelayRepository.class),
                mock(CustodyTenantUserRepository.class), mock(MpcNonceAdmissionService.class),
                mock(CustodyTenantChainTableRepository.class), hotWallets,
                mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class),
                mock(CustodyAddressRepository.class),mock(ChainAddressRepository.class));

        assertThrows(MpcPendingException.class, () -> service.authorize(tenant, requestId, UUID.randomUUID()));
        verify(hotWallets).findByKey(tenant, "ETH", hotKey);
        verifyNoInteractions(approval);
    }

    @Test
    void standbyOrQuarantinedKeyCannotObtainAnAuthorization() {
        UUID tenant = UUID.randomUUID(), key = UUID.randomUUID(), requestId = UUID.randomUUID();
        var requests = mock(WalletMpcSignRequestRepository.class);
        var attempts = mock(WalletMpcSignAttemptRepository.class);
        var hotWallets = mock(CustodyHotWalletRepository.class);
        var hotObservations=mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class);
        var authority = mock(WithdrawalApprovalClient.class);
        var command = mock(MpcSignCommand.class);
        when(command.tenantId()).thenReturn(tenant);
        when(command.chain()).thenReturn(ChainType.ETH);
        when(command.keyId()).thenReturn(key);
        when(requests.find(tenant, requestId)).thenReturn(Optional.of(new MpcSignRequest(
                requestId, command, MpcSignRequest.State.PENDING, UUID.randomUUID())));
        when(attempts.find(eq(tenant), any())).thenReturn(Optional.of(mock(MpcSignAttempt.class)));
        var service = new MpcAuthorizationService(requests, attempts,
                mock(WalletWithdrawalApprovalRepository.class), mock(CustodyAuditRepository.class),
                authority, mock(ObjectMapper.class),
                mock(PlatformTransactionManager.class), mock(WalletMpcKeyRepository.class),
                mock(WalletMpcKeyEpochRepository.class), mock(WalletMpcRelayRepository.class),
                mock(CustodyTenantUserRepository.class), mock(MpcNonceAdmissionService.class),
                mock(CustodyTenantChainTableRepository.class), hotWallets,hotObservations,
                mock(CustodyAddressRepository.class),mock(ChainAddressRepository.class));

        for (String state : new String[]{"STANDBY", "QUARANTINED"}) {
            when(hotWallets.findByKey(tenant, "ETH", key)).thenReturn(Optional.of(
                    new CustodyHotWalletRepository.Wallet(UUID.randomUUID(), tenant, UUID.randomUUID(),
                            key, "ETH", "sepolia", "Reserve", state, 100,
                            null, null, null, null, UUID.randomUUID(), null, null)));
            assertThrows(MpcPendingException.class, () -> service.authorize(tenant, requestId, UUID.randomUUID()));
        }
        UUID activeId=UUID.randomUUID();
        when(hotWallets.findByKey(tenant,"ETH",key)).thenReturn(Optional.of(
                new CustodyHotWalletRepository.Wallet(activeId,tenant,UUID.randomUUID(),key,
                        "ETH","sepolia","Untrusted inventory","ACTIVE",100,
                        null,null,null,null,UUID.randomUUID(),null,null)));
        assertThrows(MpcPendingException.class,()->service.authorize(tenant,requestId,UUID.randomUUID()));
        verify(hotObservations).allUninvalidated(tenant,activeId);
        verifyNoInteractions(authority);
    }
}
