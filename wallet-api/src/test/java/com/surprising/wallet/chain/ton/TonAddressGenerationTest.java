package com.surprising.wallet.chain.ton;

import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TonAddressGenerationTest {
    private final UUID tenant=UUID.randomUUID(),key=UUID.randomUUID();
    private final TonCenterClient rpc=mock(TonCenterClient.class);
    private final ChainAddressRepository addresses=mock(ChainAddressRepository.class);
    private final ChainProfileRepository profiles=mock(ChainProfileRepository.class);
    private final TokenConfigRepository tokens=mock(TokenConfigRepository.class);
    private final TonAddressService service=new TonAddressService(rpc,addresses,profiles,tokens);
    private final String owner="0:"+"ab".repeat(32),wallet="0:"+"cd".repeat(32),master="0:"+"ef".repeat(32);
    private ChainAddressRecord owner(){return ChainAddressRecord.builder().id(1L).tenantId(tenant).mpcKeyId(key).chain("TON").assetSymbol("TON").accountId(owner).address(owner).ownerAddress(owner).userId(42L).biz(1).addressIndex(7L).walletRole("DEPOSIT").derivationPath("mpc:"+key).enabled(true).build();}
    private void setup(){
        when(addresses.find("TON","TON",42,1,7,"DEPOSIT")).thenReturn(Optional.of(owner()));
        when(profiles.findAccountByChain("TON")).thenReturn(Optional.of(AccountChainProfile.builder().chain("TON").network("testnet").build()));
        when(tokens.findDefinition("TON","testnet","USDC")).thenReturn(Optional.of(TokenDefinition.builder().symbol("USDC").contractAddress(master).decimals(6).build()));
        when(rpc.jettonWallet(owner,master)).thenReturn(wallet);
    }
    @Test void jettonRegistrationInheritsPublishedTenantKeyAndAccount(){
        setup();when(addresses.publishMpc(any())).thenAnswer(call->((ChainAddressRecord)call.getArgument(0)).toBuilder().id(2L).build());
        var row=service.registerJettonWallet(tenant,"USDC",wallet,42,1,7,"DEPOSIT");
        assertEquals(tenant,row.getTenantId());assertEquals(key,row.getMpcKeyId());assertEquals(owner,row.getAccountId());
        assertEquals(owner,row.getOwnerAddress());assertEquals(wallet,row.getAddress());assertEquals("mpc:"+key,row.getDerivationPath());
        verify(addresses,never()).upsert(any());
    }
    @Test void missingOrForeignNativeOwnerCannotCreateAnAddress(){
        assertThrows(IllegalStateException.class,()->service.requireNativeAddress(tenant,42,1,7,"DEPOSIT"));
        setup();assertThrows(IllegalStateException.class,()->service.requireNativeAddress(UUID.randomUUID(),42,1,7,"DEPOSIT"));
        verifyNoInteractions(rpc);verify(addresses,never()).publishMpc(any());
    }
    @Test void wrongMasterResultOrExistingKeyCannotReplacePublishedIdentity(){
        setup();assertThrows(IllegalStateException.class,()->service.registerJettonWallet(tenant,"USDC",master,42,1,7,"DEPOSIT"));
        when(addresses.find("TON","USDC",42,1,7,"DEPOSIT")).thenReturn(Optional.of(owner().toBuilder().assetSymbol("USDC").address(wallet).mpcKeyId(UUID.randomUUID()).build()));
        assertThrows(IllegalStateException.class,()->service.registerJettonWallet(tenant,"USDC",wallet,42,1,7,"DEPOSIT"));
        verify(addresses,never()).publishMpc(any());verify(addresses,never()).upsert(any());
    }
}
