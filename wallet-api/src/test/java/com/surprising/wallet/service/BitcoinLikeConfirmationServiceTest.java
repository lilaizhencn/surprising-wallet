package com.surprising.wallet.service;

import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.testutil.UtxoSigningTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class BitcoinLikeConfirmationServiceTest {
    private final ChainProfileRepository profiles = mock(ChainProfileRepository.class);
    private final BitcoinLikeDepositScanService deposits = mock(BitcoinLikeDepositScanService.class);
    private final BitcoinLikeChainRuntime runtime = mock(BitcoinLikeChainRuntime.class);
    private final ChainSigningTransactionRepository signing = mock(ChainSigningTransactionRepository.class);
    private final BitcoinLikeSettlementService settlements = mock(BitcoinLikeSettlementService.class);
    private final AccountChainProfile profile = AccountChainProfile.builder()
            .chain("BTC").family("bitcoin-like").withdrawConfirmations(3).build();
    private final BitcoinLikeConfirmationService service =
            new BitcoinLikeConfirmationService(profiles, deposits, runtime, signing, settlements);

    @Test void unknownReplacementStillChecksAndSettlesTheOlderMinedAttempt() {
        var request = UtxoSigningTestSupport.request(UUID.randomUUID(), UtxoSigningRequest.Operation.WITHDRAWAL, "2", "1");
        var row = new UtxoSigningTransaction(42, request, UtxoSigningTestSupport.signed("replacement", 200),
                List.of(new UtxoSigningTransaction.Attempt(request, UtxoSigningTestSupport.signed("original", 100), Instant.EPOCH)),
                Constants.SIGNING, null, null, Instant.EPOCH, Instant.EPOCH);
        when(profiles.listProfiles(true)).thenReturn(List.of(profile));
        when(signing.listPendingConfirmation("BTC")).thenReturn(List.of(row));
        when(runtime.confirmations(ChainType.BTC, "replacement")).thenReturn(0);
        when(runtime.confirmations(ChainType.BTC, "original")).thenReturn(3);

        service.confirmAll();

        verify(settlements).settleConfirmed("BTC", 42, "original");
        verifyNoMoreInteractions(settlements);
    }

    @Test void depositAndOneWithdrawalRpcFailureDoNotStopOtherWithdrawals() {
        when(profiles.listProfiles(true)).thenReturn(List.of(profile));
        doThrow(new IllegalStateException("deposit RPC unavailable")).when(deposits).refresh(profile);
        when(signing.listPendingConfirmation("BTC")).thenReturn(List.of(row(1, "unavailable"), row(2, "mined")));
        when(runtime.confirmations(ChainType.BTC, "unavailable")).thenThrow(new IllegalStateException("RPC unavailable"));
        when(runtime.confirmations(ChainType.BTC, "mined")).thenReturn(3);

        service.confirmAll();

        verify(settlements).settleConfirmed("BTC", 2, "mined");
        verifyNoMoreInteractions(settlements);
    }

    @Test void pendingTransactionsStayPendingAndMinedTransactionsBelowThresholdBecomeConfirming() {
        when(profiles.listProfiles(true)).thenReturn(List.of(profile));
        when(signing.listPendingConfirmation("BTC")).thenReturn(List.of(row(1, "pending"), row(2, "confirming")));
        when(runtime.confirmations(ChainType.BTC, "pending")).thenReturn(0);
        when(runtime.confirmations(ChainType.BTC, "confirming")).thenReturn(1);

        service.confirmAll();

        verify(settlements).markConfirming("BTC", 2, "confirming");
        verifyNoMoreInteractions(settlements);
    }

    private UtxoSigningTransaction row(int id, String hash) {
        var request = UtxoSigningTestSupport.request(UUID.randomUUID(), UtxoSigningRequest.Operation.WITHDRAWAL, "2", "1");
        return UtxoSigningTestSupport.row(id, request, Constants.SENT, UtxoSigningTestSupport.signed(hash, 100));
    }
}
