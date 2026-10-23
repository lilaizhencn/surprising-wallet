package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import static com.surprising.wallet.testutil.UtxoSigningTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RbfBumpServiceTest {
    @Test void withdrawalRbfPreservesTheImmutablePaymentsAndInputLocks() {
        var signing = mock(ChainSigningTransactionRepository.class);
        var utxos = mock(UtxoRepository.class);
        var orders = mock(WithdrawalOrderRepository.class);
        var rates = mock(ChainFeeRateRepository.class);
        var request = request(UUID.randomUUID(), UtxoSigningRequest.Operation.WITHDRAWAL, "0.01", "0.005");
        String hash = "a".repeat(64);
        var transaction = row(42, request, Constants.SENT, signed(hash, 2020));
        when(signing.lockById("BTC", 42)).thenReturn(Optional.of(transaction));
        when(utxos.isLockedBy("BTC", "c".repeat(64), 0, "42")).thenReturn(true);
        when(signing.update(any())).thenReturn(1);
        when(orders.updateStatus(any(), eq("BTC"), eq("WD-1"), eq("SIGNING"), isNull(), isNull(), isNull())).thenReturn(1);
        when(rates.find("btc")).thenReturn("35");
        var service = new RbfBumpService(signing, utxos, mock(CollectionRecordRepository.class), orders,
                mock(WalletRuntimeConfigService.class), mock(QueueWorker.class), rates, new ObjectMapper());
        var task = service.bumpFee(42, request.tenantId(), hash);
        var captured = ArgumentCaptor.forClass(UtxoSigningTransaction.class);
        verify(signing).update(captured.capture());
        var updated = captured.getValue();
        assertEquals(request.payments(), task.request().payments());
        assertEquals(35, task.request().feeRate());
        assertNotEquals(request.requestId(), task.request().requestId());
        assertEquals(1, updated.attempts().size());
        assertEquals(request, updated.attempts().getFirst().request());
        assertEquals(hash, updated.attempts().getFirst().transaction().txId());
        assertNull(updated.signed());
        verify(utxos, never()).release(anyString(), anyString());
        verify(utxos, never()).lock(anyString(), anyString(), anyInt(), anyString());
    }
}
