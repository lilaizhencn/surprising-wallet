package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.common.approval.CollectionDestinationPolicy;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyHotWalletAssetPolicyRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.testutil.CustodyTestSupport;
import java.util.Optional;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CustodyHotWalletPolicyTest {
    @Test
    void preflightRequiresAnIndependentExactDestinationForEveryEnabledAsset() {
        UUID tenant=UUID.randomUUID(), walletId=UUID.randomUUID(), addressId=UUID.randomUUID(), keyId=UUID.randomUUID();
        var updated=java.time.Instant.now();
        var wallet=new CustodyHotWalletRepository.Wallet(walletId,tenant,addressId,keyId,
                "ETH","sepolia","Reserve","STANDBY",100,null,null,null,null,UUID.randomUUID(),updated,updated);
        var wallets=mock(CustodyHotWalletRepository.class);
        var policies=mock(CustodyHotWalletAssetPolicyRepository.class);
        var custodyAddresses=mock(CustodyAddressRepository.class);
        var chainAddresses=mock(ChainAddressRepository.class);
        var assets=mock(ChainAssetRepository.class);
        var approval=mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class);
        String address="0x"+"1".repeat(40);
        when(wallets.find(tenant,walletId)).thenReturn(Optional.of(wallet));
        when(custodyAddresses.require(tenant,addressId)).thenReturn(new CustodyAddressRecord(
                addressId,tenant,12L,"ETH","sepolia",address,null,"__sw_hot__:ETH:"+walletId,
                "Reserve","{}","CONSOLE","ACTIVE",1,1,2,updated,updated));
        when(chainAddresses.findOwned(tenant,12L)).thenReturn(Optional.of(ChainAddressRecord.builder()
                .tenantId(tenant).mpcKeyId(keyId).chain("ETH").address(address)
                .walletRole("HOT_WITHDRAW").enabled(true).build()));
        when(assets.listActiveByChain("ETH")).thenReturn(List.of(
                ChainAsset.builder().chain("ETH").symbol("ETH").build(),
                ChainAsset.builder().chain("ETH").symbol("USDT").build()));
        var eth=new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,"ETH","ETH",
                BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN);
        var usdt=new CustodyHotWalletAssetPolicyRepository.Policy(tenant,walletId,"ETH","USDT",
                BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.TEN);
        when(policies.list(tenant,walletId)).thenReturn(List.of(eth,usdt));
        for(String symbol:List.of("ETH","USDT"))when(approval.collectionDestination(tenant,"ETH","sepolia",symbol,keyId))
                .thenReturn(new CollectionDestinationPolicy(tenant,"ETH","sepolia",symbol,keyId,address,
                        UUID.randomUUID(),"a".repeat(64),BigDecimal.ONE,BigDecimal.TEN));
        var service=new CustodyHotWalletService(wallets,policies,mock(CustodyAddressService.class),
                custodyAddresses,chainAddresses,mock(CustodyTenantChainService.class),assets,
                mock(TokenConfigRepository.class),
                mock(CustodyAuditRepository.class),approval,mock(com.surprising.wallet.chain.signing.MpcNodes.class),
                mock(HotWalletInventoryService.class),
                mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class),
                mock(com.surprising.wallet.repository.CustodyTenantChainTableRepository.class),
                mock(com.surprising.wallet.repository.WalletMpcKeyRepository.class),
                mock(HotWalletAggregateExposureService.class),
                mock(PlatformTransactionManager.class));
        var admin=CustodyTestSupport.tenantAdmin(tenant);
        assertEquals(2,service.collectionRulePreflight(admin,walletId).independentPolicies().size());
        when(approval.collectionDestination(tenant,"ETH","sepolia","USDT",keyId))
                .thenReturn(new CollectionDestinationPolicy(tenant,"ETH","sepolia","USDT",keyId,
                        "0x"+"2".repeat(40),UUID.randomUUID(),"b".repeat(64),BigDecimal.ONE,BigDecimal.TEN));
        assertThrows(IllegalStateException.class,()->service.collectionRulePreflight(admin,walletId));
        when(policies.list(tenant,walletId)).thenReturn(List.of(eth));
        assertThrows(IllegalStateException.class,()->service.collectionRulePreflight(admin,walletId));
    }

    @Test
    void rejectsExcessPrecisionAndContradictoryLimitsBeforeSaving() {
        UUID tenant = UUID.randomUUID(), walletId = UUID.randomUUID();
        var wallets = mock(CustodyHotWalletRepository.class);
        var policies = mock(CustodyHotWalletAssetPolicyRepository.class);
        var assets = mock(ChainAssetRepository.class);
        var audits = mock(CustodyAuditRepository.class);
        when(wallets.find(tenant, walletId)).thenReturn(Optional.of(new CustodyHotWalletRepository.Wallet(
                walletId, tenant, UUID.randomUUID(), UUID.randomUUID(), "ETH", "sepolia", "Reserve A", "STANDBY", 100,
                null, null, null, null, UUID.randomUUID(), null, null)));
        var standby=wallets.find(tenant,walletId);
        when(wallets.lock(tenant,walletId)).thenReturn(standby);
        when(assets.findActiveDefinition("ETH", "USDT")).thenReturn(Optional.of(
                ChainAsset.builder().chain("ETH").symbol("USDT").decimals(6).build()));
        var service = new CustodyHotWalletService(wallets, policies, mock(CustodyAddressService.class),
                mock(CustodyAddressRepository.class), mock(ChainAddressRepository.class),
                mock(CustodyTenantChainService.class),
                assets, mock(TokenConfigRepository.class), audits,
                mock(com.surprising.wallet.gateway.WithdrawalApprovalClient.class),
                mock(com.surprising.wallet.chain.signing.MpcNodes.class),
                mock(HotWalletInventoryService.class),
                mock(com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository.class),
                mock(com.surprising.wallet.repository.CustodyTenantChainTableRepository.class),
                mock(com.surprising.wallet.repository.WalletMpcKeyRepository.class),
                mock(HotWalletAggregateExposureService.class),
                mock(PlatformTransactionManager.class));
        var admin = CustodyTestSupport.tenantAdmin(tenant);

        assertThrows(IllegalArgumentException.class, () -> service.setPolicy(admin, walletId, "USDT",
                new CustodyHotWalletService.PolicyCommand("0", "100", "1.0000001", "10"), null));
        assertThrows(IllegalArgumentException.class, () -> service.setPolicy(admin, walletId, "USDT",
                new CustodyHotWalletService.PolicyCommand("101", "100", "1", "10"), null));
        assertThrows(IllegalArgumentException.class, () -> service.setPolicy(admin, walletId, "USDT",
                new CustodyHotWalletService.PolicyCommand("0", "100", "11", "10"), null));
        verifyNoInteractions(policies, audits);
    }
}
