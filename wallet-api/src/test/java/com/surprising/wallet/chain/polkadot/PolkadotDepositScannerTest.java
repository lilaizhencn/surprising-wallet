package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PolkadotDepositScannerTest {
    private static final String FROM=MpcPolkadotTransactionCodec.address(new byte[32],42),TO=MpcPolkadotTransactionCodec.address(HexFormat.of().parseHex("ab".repeat(32)),42),HASH="0x"+"11".repeat(32),BLOCK="0x"+"22".repeat(32);
    @Test void publishedNativeAccountReceivesExactNativeAndAssetDepositsWithCanonicalBlock() {
        var fixture=new Fixture();var events=fixture.scanner.scanAndCredit();assertEquals(2,events.size());
        assertEquals(0,new BigDecimal("9.997224699029").compareTo(events.getFirst().amount()));
        assertEquals("USDC",events.getLast().assetSymbol());assertEquals(BLOCK,events.getFirst().blockHash());
        verify(fixture.deposits,times(2)).recordAndCredit(any(),eq(3L),eq(1),eq("account"));
    }
    @Test void disabledInternalSenderAndCanonicalReplayNeverCreditAgain() {
        var fixture=new Fixture();when(fixture.addresses.existsAddress("DOT",FROM)).thenReturn(true);
        assertTrue(fixture.scanner.scanAndCredit().isEmpty());verifyNoInteractions(fixture.deposits);
        when(fixture.addresses.existsAddress("DOT",FROM)).thenReturn(false);when(fixture.records.existsCanonical(eq("DOT"),anyString(),eq(3L))).thenReturn(true);
        assertTrue(fixture.scanner.scanAndCredit().isEmpty());verifyNoInteractions(fixture.deposits);
    }
    @Test void invalidCanonicalPositionFailsBeforeCreditOrHeightAdvance() {
        var fixture=new Fixture();when(fixture.rpc.scanTransfers(anyLong(),anyLong(),anyCollection(),anyMap())).thenReturn(List.of(new PolkadotRuntimeClient.TransferEvent(HASH,FROM,TO,BigInteger.ONE,999,3,null,BLOCK,"{}")));
        assertThrows(RuntimeException.class,fixture.scanner::scanAndCredit);verifyNoInteractions(fixture.deposits);verify(fixture.heights,never()).upsert(anyString(),anyString(),anyLong(),anyLong());
    }
    private static final class Fixture {
        final PolkadotRuntimeClient rpc=mock(PolkadotRuntimeClient.class);final ChainScanHeightRepository heights=mock(ChainScanHeightRepository.class);
        final DepositService deposits=mock(DepositService.class);final ChainAddressRepository addresses=mock(ChainAddressRepository.class);
        final DepositRecordRepository records=mock(DepositRecordRepository.class);final PolkadotDepositScanner scanner;
        Fixture() {
            var profiles=mock(ChainProfileRepository.class);var tokens=mock(TokenConfigRepository.class);var assets=mock(ChainAssetRepository.class);
            when(profiles.findAccountByChain("DOT")).thenReturn(Optional.of(AccountChainProfile.builder().chain("DOT").network("localnet").chainId(42L).scanStartHeight(90L).depositConfirmations(1).build()));
            when(rpc.finalized(any())).thenReturn(new ObjectMapper().readTree("{\"height\":100}"));
            when(addresses.listEnabled("DOT","DOT")).thenReturn(List.of(ChainAddressRecord.builder().address(TO).walletRole("DEPOSIT").accountId("account").mpcKeyId(UUID.randomUUID()).build()));
            when(tokens.listDefinitions("DOT","localnet")).thenReturn(List.of(TokenDefinition.builder().symbol("USDC").contractAddress("10002").decimals(6).active(true).build()));
            when(assets.findActiveDefinition("DOT","DOT")).thenReturn(Optional.of(ChainAsset.builder().decimals(12).build()));
            when(rpc.scanTransfers(anyLong(),anyLong(),anyCollection(),anyMap())).thenReturn(List.of(
                    new PolkadotRuntimeClient.TransferEvent(HASH,FROM,TO,new BigInteger("9997224699029"),95,3,null,BLOCK,"{}"),
                    new PolkadotRuntimeClient.TransferEvent("0x"+"33".repeat(32),FROM,TO,new BigInteger("1000001"),95,3,"10002",BLOCK,"{}")));
            scanner=new PolkadotDepositScanner(rpc,heights,deposits,addresses,profiles,tokens,assets,records,mock(WalletRuntimeConfigService.class));
        }
    }
}
