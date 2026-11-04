package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.approval.ApprovalKeyQuarantine;
import com.surprising.wallet.common.approval.HotWalletIncidentInventoryObservation;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.testutil.CustodyTestSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustodyHotWalletIncidentInventoryTest {
    @Test void quarantinedKeyCanBeObservedWithoutOpeningSigningOrCustomerCredit() {
        UUID tenant=UUID.randomUUID(),walletId=UUID.randomUUID(),addressId=UUID.randomUUID();
        UUID keyId=UUID.randomUUID(),actor=UUID.randomUUID();
        Instant now=Instant.now();
        String address="0x"+"7".repeat(40);
        var event=new ApprovalKeyQuarantine(tenant,keyId,actor,now.minusSeconds(1),"suspected signing key compromise");
        var wallet=new CustodyHotWalletRepository.Wallet(walletId,tenant,addressId,keyId,
                "ETH","sepolia","Old hot","QUARANTINED",100,actor,event.quarantinedAt(),
                event.reason(),now,actor,now.minusSeconds(100),now);
        var wallets=mock(CustodyHotWalletRepository.class);
        var policies=mock(CustodyHotWalletAssetPolicyRepository.class);
        var addresses=mock(CustodyAddressRepository.class);
        var chainAddresses=mock(ChainAddressRepository.class);
        var assets=mock(ChainAssetRepository.class);
        var tokenConfigs=mock(TokenConfigRepository.class);
        var audit=mock(CustodyAuditRepository.class);
        var independent=mock(WithdrawalApprovalClient.class);
        when(wallets.find(tenant,walletId)).thenReturn(Optional.of(wallet));
        when(addresses.require(tenant,addressId)).thenReturn(new CustodyAddressRecord(addressId,tenant,
                42L,"ETH","sepolia",address,null,"__sw_hot__:ETH:"+walletId,"Old hot","{}",
                "CONSOLE","ACTIVE",1,1,2,now,now));
        when(chainAddresses.findOwned(tenant,42L)).thenReturn(Optional.of(ChainAddressRecord.builder()
                .tenantId(tenant).chain("ETH").address(address).mpcKeyId(keyId)
                .walletRole("HOT_WITHDRAW").enabled(true).build()));
        when(policies.list(tenant,walletId)).thenReturn(List.of());
        when(assets.listDefinitionsByChain("ETH")).thenReturn(List.of(
                ChainAsset.builder().chain("ETH").symbol("ETH").decimals(18)
                        .nativeAsset(true).active(true).build(),
                ChainAsset.builder().chain("ETH").symbol("DAI").decimals(18).active(true).build()));
        String usdcContract="0x"+"c".repeat(40);
        var usdc=mock(com.surprising.wallet.chain.model.TokenConfiguration.class);
        when(usdc.symbol()).thenReturn("USDC");
        when(usdc.contractAddress()).thenReturn(usdcContract);
        when(usdc.decimals()).thenReturn(6);
        when(tokenConfigs.listByChainAndNetwork("ETH","sepolia")).thenReturn(List.of(usdc));
        var observation=new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                "eip155:11155111","ETH",null,address,18,18,new BigInteger("2000000000000000000"),
                BigInteger.ZERO,now,"DISABLED",event.hash(),"EIP7702_DELEGATED",
                "0x"+"b".repeat(40),"0x"+"a".repeat(64));
        when(independent.incidentInventory(tenant,"ETH","sepolia","ETH",keyId)).thenReturn(observation);
        when(independent.incidentInventory(tenant,"ETH","sepolia","USDC",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                        "eip155:11155111","USDC",usdcContract,address,6,18,new BigInteger("5000000"),
                        BigInteger.ZERO,now,"DISABLED",event.hash(),"EIP7702_DELEGATED",
                        "0x"+"b".repeat(40),"0x"+"a".repeat(64)));
        var service=new CustodyHotWalletService(wallets,policies,mock(CustodyAddressService.class),
                addresses,chainAddresses,mock(CustodyTenantChainService.class),assets,tokenConfigs,audit,independent,
                mock(com.surprising.wallet.chain.signing.MpcNodes.class),mock(HotWalletInventoryService.class),
                mock(CustodyHotWalletInventoryObservationRepository.class),
                mock(CustodyTenantChainTableRepository.class),mock(WalletMpcKeyRepository.class),
                mock(HotWalletAggregateExposureService.class),
                mock(PlatformTransactionManager.class));
        var principal=CustodyTestSupport.tenantAdmin(tenant);
        var view=service.incidentInventory(principal,walletId,"127.0.0.1");
        assertEquals(address,view.address());
        assertEquals(0,new BigDecimal("2").compareTo(view.assets().getFirst().observedBalance()));
        assertEquals(2,view.assets().size());
        assertEquals("USDC",view.assets().get(1).assetSymbol());
        assertEquals(usdcContract,view.assets().get(1).tokenIdentifier());
        assertEquals(0,new BigDecimal("5").compareTo(view.assets().get(1).observedBalance()));
        assertFalse(view.assetTransferComplete());
        assertEquals("NO_INDEPENDENT_RECOVERY_AUTHORITY",view.rescueReadiness());
        assertEquals("EIP7702_DELEGATED",view.assets().getFirst().accountCodeState());
        assertEquals("0x"+"b".repeat(40),view.assets().getFirst().delegatedTo());
        verify(independent,never()).hotInventory(any(),anyString(),anyString(),anyString(),any());
        verify(independent,never()).authorize(any());
        verify(independent,never()).incidentInventory(tenant,"ETH","sepolia","DAI",keyId);
        verify(audit).insert(any(),eq(tenant),anyString(),anyString(),eq("HOT_WALLET.INCIDENT_INVENTORY"),
                eq("HOT_WALLET"),eq(walletId.toString()),eq("127.0.0.1"),anyString());
        when(independent.incidentInventory(tenant,"ETH","sepolia","USDC",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                        "eip155:11155111","USDC","0x"+"d".repeat(40),address,6,18,
                        new BigInteger("5000000"),BigInteger.ZERO,now,"DISABLED",event.hash(),
                        "EMPTY",null,"0x"+"a".repeat(64)));
        assertThrows(IllegalStateException.class,()->service.incidentInventory(principal,walletId,null));
        when(independent.incidentInventory(tenant,"ETH","sepolia","USDC",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                        "eip155:11155111","USDC",usdcContract,address,6,18,
                        new BigInteger("5000000"),BigInteger.ZERO,now,"DISABLED",event.hash(),
                        "EMPTY",null,"0x"+"a".repeat(64)));
        when(independent.incidentInventory(tenant,"ETH","sepolia","ETH",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(UUID.randomUUID(),keyId,"ETH","sepolia",
                        "eip155:11155111","ETH",null,address,18,18,BigInteger.ZERO,BigInteger.ZERO,
                        now,"DISABLED",event.hash(),"EMPTY",null,"0x"+"a".repeat(64)));
        assertThrows(IllegalStateException.class,()->service.incidentInventory(principal,walletId,null));
        when(independent.incidentInventory(tenant,"ETH","sepolia","ETH",keyId)).thenReturn(observation);
        when(independent.incidentInventory(tenant,"ETH","sepolia","USDC",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                        "eip155:11155111","USDC",usdcContract,address,18,18,new BigInteger("5000000"),
                        BigInteger.ZERO,now,"DISABLED",event.hash(),"EMPTY",null,"0x"+"a".repeat(64)));
        assertThrows(IllegalStateException.class,()->service.incidentInventory(principal,walletId,null));
        when(independent.incidentInventory(tenant,"ETH","sepolia","USDC",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                        "eip155:11155111","USDC",usdcContract,address,6,18,new BigInteger("5000000"),
                        BigInteger.ZERO,now,"DISABLED",event.hash(),"EIP7702_DELEGATED",
                        "0x"+"b".repeat(40),"0x"+"a".repeat(64)));
        when(independent.incidentInventory(tenant,"ETH","sepolia","ETH",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                        "eip155:11155111","ETH",null,address,18,18,BigInteger.ZERO,BigInteger.ZERO,
                        now,"DISABLED","wrong-event","EMPTY",null,"0x"+"a".repeat(64)));
        assertThrows(IllegalStateException.class,()->service.incidentInventory(principal,walletId,null));
        when(independent.incidentInventory(tenant,"ETH","sepolia","ETH",keyId)).thenReturn(
                new HotWalletIncidentInventoryObservation(tenant,keyId,"ETH","sepolia",
                        "eip155:11155111","ETH",null,address,18,18,BigInteger.ZERO,BigInteger.ZERO,
                        now,"DISABLED",event.hash(),"EIP7702_DELEGATED",null,"0x"+"a".repeat(64)));
        assertThrows(IllegalStateException.class,()->service.incidentInventory(principal,walletId,null));
    }
}
