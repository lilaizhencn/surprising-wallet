package com.surprising.wallet.service;

import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.testutil.CustodyTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustodyEmergencyHotWalletSwitchTest {
    @Test void pausesAndConfirmsOldKeyQuarantineBeforeEnablingReplacement() {
        var fixture=new Fixture();
        var result=fixture.service.emergencySwitch(fixture.principal,fixture.command,"127.0.0.1");
        assertTrue(result.withdrawalPaused());
        assertFalse(result.assetTransferComplete());
        assertEquals("ACTIVE",result.activatedWallet().status());
        var order=inOrder(fixture.chains,fixture.wallets,fixture.service);
        order.verify(fixture.chains).setWithdrawalPaused(fixture.principal,"ETH",true,
                "suspected signing key compromise","127.0.0.1");
        order.verify(fixture.wallets).quarantine(eq(fixture.tenant),eq(fixture.oldId),
                eq(fixture.principal.actorId()),any(),eq("suspected signing key compromise"));
        order.verify(fixture.service).quarantine(fixture.principal,fixture.oldId,
                "suspected signing key compromise","127.0.0.1");
        order.verify(fixture.service).activate(fixture.principal,fixture.nextId,
                new CustodyHotWalletService.ActivateCommand(10),"127.0.0.1");
    }

    @Test void failedIndependentQuarantineLeavesPauseAndDoesNotActivate() {
        var fixture=new Fixture();
        doThrow(new IllegalStateException("native fence pending")).when(fixture.service)
                .quarantine(any(),eq(fixture.oldId),anyString(),any());
        assertThrows(IllegalStateException.class,()->fixture.service.emergencySwitch(
                fixture.principal,fixture.command,"127.0.0.1"));
        verify(fixture.chains).setWithdrawalPaused(fixture.principal,"ETH",true,
                "suspected signing key compromise","127.0.0.1");
        verify(fixture.service,never()).activate(any(),any(),any(),any());
    }

    @Test void sameOnChainAddressCannotBeTreatedAsReplacement() {
        var fixture=new Fixture();
        String sameAddress=fixture.oldAddress.address().toUpperCase();
        when(fixture.nextAddress.address()).thenReturn(sameAddress);
        assertThrows(IllegalStateException.class,()->fixture.service.emergencySwitch(
                fixture.principal,fixture.command,"127.0.0.1"));
        verify(fixture.chains,never()).setWithdrawalPaused(any(),anyString(),anyBoolean(),anyString(),any());
        verify(fixture.service,never()).activate(any(),any(),any(),any());
    }

    private static final class Fixture {
        final UUID tenant=UUID.randomUUID(),oldId=UUID.randomUUID(),nextId=UUID.randomUUID();
        final com.surprising.wallet.model.CustodyPrincipal principal=CustodyTestSupport.tenantAdmin(tenant);
        final CustodyHotWalletRepository wallets=mock(CustodyHotWalletRepository.class);
        final CustodyTenantChainService chains=mock(CustodyTenantChainService.class);
        final CustodyAddressRepository addresses=mock(CustodyAddressRepository.class);
        final com.surprising.wallet.chain.model.CustodyAddressRecord oldAddress=
                mock(com.surprising.wallet.chain.model.CustodyAddressRecord.class);
        final com.surprising.wallet.chain.model.CustodyAddressRecord nextAddress=
                mock(com.surprising.wallet.chain.model.CustodyAddressRecord.class);
        final CustodyTenantChainTableRepository chainRows=mock(CustodyTenantChainTableRepository.class);
        final CustodyHotWalletService service;
        final CustodyHotWalletService.EmergencySwitchCommand command;

        Fixture() {
            Instant now=Instant.now();
            UUID oldAddressId=UUID.randomUUID(),nextAddressId=UUID.randomUUID();
            when(oldAddress.address()).thenReturn("0x"+"1".repeat(40));
            when(nextAddress.address()).thenReturn("0x"+"2".repeat(40));
            when(addresses.require(tenant,oldAddressId)).thenReturn(oldAddress);
            when(addresses.require(tenant,nextAddressId)).thenReturn(nextAddress);
            var active=new CustodyHotWalletRepository.Wallet(
                    oldId,tenant,oldAddressId,UUID.randomUUID(),"ETH","sepolia","Old",
                    "ACTIVE",100,null,null,null,null,UUID.randomUUID(),now,now);
            var quarantined=new CustodyHotWalletRepository.Wallet(oldId,tenant,active.custodyAddressId(),
                    active.mpcKeyId(),"ETH","sepolia","Old","QUARANTINED",100,
                    principal.actorId(),now,"suspected signing key compromise",null,
                    active.createdBy(),active.createdAt(),now);
            when(wallets.find(tenant,oldId)).thenReturn(Optional.of(active),Optional.of(quarantined));
            when(wallets.lock(tenant,oldId)).thenReturn(Optional.of(active));
            when(wallets.quarantine(eq(tenant),eq(oldId),eq(principal.actorId()),any(),anyString())).thenReturn(1);
            when(wallets.find(tenant,nextId)).thenReturn(Optional.of(new CustodyHotWalletRepository.Wallet(
                    nextId,tenant,nextAddressId,UUID.randomUUID(),"ETH","sepolia","Safe",
                    "STANDBY",100,null,null,null,null,UUID.randomUUID(),now,now)));
            when(chainRows.withdrawalPaused(tenant,"ETH")).thenReturn(true);
            var manager=mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
            service=spy(new CustodyHotWalletService(wallets,
                    mock(com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository.class),
                    mock(CustodyAddressService.class),
                    addresses,
                    mock(com.surprising.wallet.repository.ChainAddressRepository.class),chains,
                    mock(com.surprising.wallet.repository.ChainAssetRepository.class),
                    mock(com.surprising.wallet.repository.TokenConfigRepository.class),
                    mock(com.surprising.wallet.repository.CustodyAuditRepository.class),
                    mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class),
                    mock(com.surprising.wallet.chain.signing.MpcNodes.class),
                    mock(HotWalletInventoryService.class),
                    mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class),
                    chainRows,mock(com.surprising.wallet.repository.WalletMpcKeyRepository.class),
                    mock(HotWalletAggregateExposureService.class),
                    manager));
            doReturn(new CustodyHotWalletService.WalletView(oldId,UUID.randomUUID(),"ETH","sepolia",
                    "0xold","Old","QUARANTINED",100,true,List.of()))
                    .when(service).quarantine(any(),eq(oldId),anyString(),any());
            doReturn(new CustodyHotWalletService.WalletView(nextId,UUID.randomUUID(),"ETH","sepolia",
                    "0xsafe","Safe","ACTIVE",10,false,List.of()))
                    .when(service).activate(any(),eq(nextId),any(),any());
            command=new CustodyHotWalletService.EmergencySwitchCommand(oldId,nextId,10,
                    "suspected signing key compromise");
        }
    }
}
