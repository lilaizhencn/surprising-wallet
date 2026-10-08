package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionQueueSafetyTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();
    private final ChainJdbcRepository repository = mock(ChainJdbcRepository.class);
    private final BlockchainRuntimeService runtime = mock(BlockchainRuntimeService.class);
    private final PgmqClient queue = mock(PgmqClient.class);
    private final AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromProfile(0, "BTC", "BTC", 1, 0, 8, null);
    private final TransactionService service;
    TransactionQueueSafetyTest() {
        var lease = mock(WalletTaskLeaseService.class);
        service = new TransactionService(mock(AddressService.class), runtime, repository,
                mock(WalletRuntimeConfigService.class), queue, new ObjectMapper(), lease);
        when(runtime.assetMetadata(0)).thenReturn(currency);
        when(runtime.isBitcoinLikeRuntime(currency)).thenReturn(true);
    }
    private WithdrawTransaction tx(UUID tenantId, UUID requestId, short status) {
        return WithdrawTransaction.builder().id(1).currency(0).status(status).txId("confirmed-hash")
                .signature("{\"tenantId\":\"" + tenantId + "\",\"signingRequestId\":\"" + requestId + "\",\"valid\":false}").build();
    }
    @Test void duplicateFailedSignatureCannotUndoAnAlreadySentTransaction() {
        when(repository.lockBitcoinLikeSigningTransaction(currency, 1)).thenReturn(Optional.of(tx(tenant, request, Constants.SENT)));
        assertTrue(service.sendWithdrawTransaction(tx(tenant, request, Constants.SIGNING)));
        verify(repository).lockBitcoinLikeSigningTransaction(currency, 1);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(queue);
    }
    @Test void replayFromAnOlderSigningRequestCannotModifyCurrentTransaction() {
        when(repository.lockBitcoinLikeSigningTransaction(currency, 1)).thenReturn(Optional.of(tx(tenant, request, Constants.SIGNING)));
        assertThrows(IllegalArgumentException.class, () -> service.sendWithdrawTransaction(tx(tenant, UUID.randomUUID(), Constants.SIGNING)));
        verify(repository).lockBitcoinLikeSigningTransaction(currency, 1);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(queue);
    }
    @Test void wrongTenantCannotModifyPersistedSigningTransaction() {
        when(repository.lockBitcoinLikeSigningTransaction(currency, 1)).thenReturn(Optional.of(tx(tenant, request, Constants.SIGNING)));
        assertThrows(IllegalArgumentException.class, () -> service.sendWithdrawTransaction(tx(UUID.randomUUID(), request, Constants.SIGNING)));
        verify(repository).lockBitcoinLikeSigningTransaction(currency, 1);
        verifyNoMoreInteractions(repository);
    }
}
