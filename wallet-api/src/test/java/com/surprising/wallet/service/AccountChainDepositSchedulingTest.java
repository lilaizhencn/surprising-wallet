package com.surprising.wallet.service;

import com.surprising.wallet.chain.aptos.AptosDepositScanner;
import com.surprising.wallet.chain.cardano.CardanoDepositScanner;
import com.surprising.wallet.chain.evm.EvmDepositScanner;
import com.surprising.wallet.chain.hypercore.HyperCoreDepositScanner;
import com.surprising.wallet.chain.monero.MoneroDepositScanner;
import com.surprising.wallet.chain.near.NearDepositScanner;
import com.surprising.wallet.chain.polkadot.PolkadotDepositScanner;
import com.surprising.wallet.chain.solana.SolanaDepositScanner;
import com.surprising.wallet.chain.sui.SuiDepositScanner;
import com.surprising.wallet.chain.ton.TonDepositScanner;
import com.surprising.wallet.chain.tron.TronClientFactory;
import com.surprising.wallet.chain.tron.TronDepositScanner;
import com.surprising.wallet.chain.xrp.XrpDepositScanner;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountChainDepositSchedulingTest {
    @Test
    void chainsKeepIndependentIntervalsAndMoneroRemainsWithItsSerialTask() throws Exception {
        var f = new Fixture();
        when(f.runtime.enabledAccountProfiles()).thenReturn(List.of(profile("XMR"), profile("SOLANA"), profile("APTOS")));
        when(f.runtime.scanIntervalMillis("APTOS")).thenReturn(0L);
        f.workflow.scanDueDeposits();
        f.workflow.scanDueDeposits();
        verify(f.solana).scanAndCredit();
        verify(f.aptos, times(2)).scanAndCredit();
        verifyNoInteractions(f.monero);
    }

    @Test
    void turningOffGlobalScanningClearsThrottleSoReopeningScansImmediately() throws Exception {
        var f = new Fixture();
        when(f.runtime.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_SCAN)).thenReturn(true, false, true);
        f.workflow.scanDueDeposits();
        f.workflow.scanDueDeposits();
        f.workflow.scanDueDeposits();
        verify(f.solana, times(2)).scanAndCredit();
        verify(f.runtime, times(2)).enabledAccountProfiles();
    }

    @Test
    void turningOffAChainClearsOnlyItsThrottleAndDoesNotScanIt() throws Exception {
        var f = new Fixture();
        var chain = profile("SOLANA");
        when(f.runtime.enabledAccountProfiles()).thenReturn(List.of(chain));
        f.workflow.scanDueDeposits();
        chain.setScanEnabled(false);
        f.workflow.scanDueDeposits();
        verify(f.solana).scanAndCredit();
        chain.setScanEnabled(true);
        f.workflow.scanDueDeposits();
        verify(f.solana, times(2)).scanAndCredit();
    }

    private static AccountChainProfile profile(String chain) {
        return AccountChainProfile.builder().chain(chain).family("account").network("testnet")
                .enabled(true).scanEnabled(true).build();
    }

    private static final class Fixture {
        final WalletRuntimeConfigService runtime = mock(WalletRuntimeConfigService.class);
        final SolanaDepositScanner solana = mock(SolanaDepositScanner.class);
        final AptosDepositScanner aptos = mock(AptosDepositScanner.class);
        final MoneroDepositScanner monero = mock(MoneroDepositScanner.class);
        final AccountChainDepositWorkflow workflow = new AccountChainDepositWorkflow(
                mock(ChainAddressRepository.class), mock(ChainScanHeightRepository.class), mock(ChainProfileRepository.class),
                mock(TokenConfigRepository.class), runtime, mock(EvmDepositScanner.class), mock(HyperCoreDepositScanner.class),
                solana, aptos, mock(SuiDepositScanner.class), mock(TonDepositScanner.class), mock(XrpDepositScanner.class),
                mock(CardanoDepositScanner.class), monero, mock(NearDepositScanner.class), mock(PolkadotDepositScanner.class),
                mock(TronClientFactory.class), mock(TronDepositScanner.class));

        Fixture() {
            when(runtime.enabledAccountProfiles()).thenReturn(List.of(profile("SOLANA")));
            when(runtime.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_SCAN)).thenReturn(true);
            when(runtime.isTaskEnabled(anyString(), eq(WalletRuntimeConfigService.TASK_SCAN))).thenReturn(true);
            when(runtime.scanIntervalMillis(anyString())).thenReturn(3_600_000L);
        }
    }
}
