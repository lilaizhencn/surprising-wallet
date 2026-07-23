package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.ChainType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.surprising.wallet.custody.service.CustodyGasService;

class CustodyGasServiceTest {

    @Test
    void allEvmChainsShareOneTenantCollectionSubject() {
        Arrays.stream(ChainType.values())
                .filter(ChainType::isEvm)
                .forEach(chain -> assertEquals(
                        "__sw_collection__:evm",
                        CustodyGasService.collectionSubject(chain.name(), chain)));
    }

    @Test
    void nonEvmChainsKeepChainSpecificCollectionSubjects() {
        assertEquals("__sw_collection__:btc",
                CustodyGasService.collectionSubject("BTC", ChainType.BTC));
        assertEquals("__sw_collection__:solana",
                CustodyGasService.collectionSubject("SOLANA", ChainType.SOLANA));
    }
}
