package com.surprising.wallet.service;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.approval.ApprovalKeyQuarantine;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.repository.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustodyHotWalletFenceDeliveryTest {
    @Test void independentApprovalAndConfirmationWaitForAllThreeNativeHolders() {
        UUID tenant = UUID.randomUUID(), walletId = UUID.randomUUID(), key = UUID.randomUUID(), actor = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-04T00:00:00Z");
        var wallet = new CustodyHotWalletRepository.Wallet(walletId, tenant, UUID.randomUUID(), key,
                "ETH", "sepolia", "Reserve", "QUARANTINED", 100,
                actor, at, "suspected key compromise", null, actor, at, at);
        var rows = mock(CustodyHotWalletRepository.class);
        when(rows.pendingApprovalQuarantine(100)).thenReturn(List.of(wallet));
        when(rows.lock(tenant, walletId)).thenReturn(java.util.Optional.of(wallet));
        when(rows.confirmQuarantineFence(tenant, walletId, actor, at)).thenReturn(1);
        var approval = mock(WithdrawalApprovalClient.class);
        var nodes = mock(MpcNodes.class);
        var first = holder('a');
        var second = holder('b');
        var third = holder('c');
        when(nodes.clients()).thenReturn(List.of(first, second, third));
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service = new CustodyHotWalletService(rows, mock(CustodyHotWalletAssetPolicyRepository.class),
                mock(CustodyAddressService.class), mock(CustodyAddressRepository.class),
                mock(ChainAddressRepository.class), mock(CustodyTenantChainService.class),
                mock(ChainAssetRepository.class), mock(TokenConfigRepository.class),
                mock(CustodyAuditRepository.class), approval, nodes,
                mock(HotWalletInventoryService.class),
                mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class),
                mock(com.surprising.wallet.repository.CustodyTenantChainTableRepository.class),
                mock(com.surprising.wallet.repository.WalletMpcKeyRepository.class),
                mock(HotWalletAggregateExposureService.class),manager);

        doThrow(new MpcClient.CallException("offline", null)).when(second).quarantineKey(any());
        service.reconcilePendingQuarantines();
        verify(first).quarantineKey(any());
        verify(second).quarantineKey(any());
        verify(third).quarantineKey(any());
        verifyNoInteractions(approval);
        verify(rows, never()).confirmQuarantineFence(any(), any(), any(), any());

        reset(first, second, third);
        service.reconcilePendingQuarantines();
        var event = new ApprovalKeyQuarantine(tenant, key, actor, at, "suspected key compromise");
        verify(first).quarantineKey(event);
        verify(second).quarantineKey(event);
        verify(third).quarantineKey(event);
        verify(approval).quarantineKey(event);
        verify(rows).confirmQuarantineFence(tenant, walletId, actor, at);
        assertEquals(1, mockingDetails(approval).getInvocations().size());
    }

    private static MpcClient holder(char id) {
        var client = mock(MpcClient.class);
        when(client.node()).thenReturn(new MpcClient.Node(String.valueOf(id).repeat(64),
                URI.create("https://" + id + ".example.test")));
        return client;
    }
}
