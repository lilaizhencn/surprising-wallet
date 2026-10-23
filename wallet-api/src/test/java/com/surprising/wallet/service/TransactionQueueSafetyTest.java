package com.surprising.wallet.service;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoSigningResult;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import static com.surprising.wallet.testutil.UtxoSigningTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionQueueSafetyTest {
    private final UUID tenant = UUID.randomUUID();
    private final UtxoSigningRequest request = request(tenant, UtxoSigningRequest.Operation.WITHDRAWAL, "0.01", "0.005");
    private final ChainSigningTransactionRepository signing = mock(ChainSigningTransactionRepository.class);
    private final PgmqClient queue = mock(PgmqClient.class);
    private final UtxoBroadcastService service = new UtxoBroadcastService(signing, mock(WithdrawalOrderRepository.class),
            mock(CollectionRecordRepository.class), mock(UtxoRepository.class), mock(LedgerBalanceRepository.class),
            mock(BlockchainRuntimeService.class), queue, new ObjectMapper(), mock(WalletTaskLeaseService.class));

    @Test void duplicateFailedSignatureCannotUndoAnAlreadySentTransaction() {
        when(signing.lockById("BTC", 1)).thenReturn(Optional.of(row(1, request, Constants.SENT, signed("a".repeat(64), 1590))));
        service.apply(new UtxoSigningResult(1, "BTC", tenant, request.requestId(), null, "late failure"));
        verify(signing).lockById("BTC", 1);
        verifyNoMoreInteractions(signing);
        verifyNoInteractions(queue);
    }
    @Test void replayFromAnOlderSigningRequestCannotModifyCurrentTransaction() {
        when(signing.lockById("BTC", 1)).thenReturn(Optional.of(row(1, request, Constants.SIGNING, null)));
        assertThrows(IllegalArgumentException.class, () -> service.apply(
                new UtxoSigningResult(1, "BTC", tenant, UUID.randomUUID(), null, "stale")));
        verify(signing).lockById("BTC", 1);
        verifyNoMoreInteractions(signing);
    }
    @Test void wrongTenantCannotModifyPersistedSigningTransaction() {
        when(signing.lockById("BTC", 1)).thenReturn(Optional.of(row(1, request, Constants.SIGNING, null)));
        assertThrows(IllegalArgumentException.class, () -> service.apply(
                new UtxoSigningResult(1, "BTC", UUID.randomUUID(), request.requestId(), null, "foreign")));
        verify(signing).lockById("BTC", 1);
        verifyNoMoreInteractions(signing);
    }
}
