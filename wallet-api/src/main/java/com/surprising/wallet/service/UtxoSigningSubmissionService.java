package com.surprising.wallet.service;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.signing.UtxoSigningTask;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UtxoSigningSubmissionService {
    private final ChainSigningTransactionRepository transactions;
    private final PgmqClient queue;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Throwable.class)
    public void enqueue(UUID tenantId, String chain, int transactionId, UUID requestId) {
        var transaction = transactions.lockById(chain, transactionId)
                .orElseThrow(() -> new IllegalArgumentException("signing transaction missing"));
        var request = transaction.request();
        if (!tenantId.equals(request.tenantId()) || !requestId.equals(request.requestId())) {
            throw new IllegalArgumentException("stale or foreign signing submission");
        }
        if (requestId.equals(transaction.enqueuedRequestId())) return;
        if (transaction.status() != Constants.SIGNING) throw new IllegalStateException("transaction is not signing");
        if (transactions.recordEnqueued(chain, transactionId, requestId) != 1) {
            throw new IllegalStateException("signing transaction changed");
        }
        queue.send(WalletQueue.SIGN_FIRST, JacksonJson.writeValue(json, new UtxoSigningTask(transactionId, request)),
                QueueTenant.headers(tenantId));
    }
}
