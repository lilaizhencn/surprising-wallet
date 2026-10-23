package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** Submit the persisted signing request and its dedupe receipt in one PostgreSQL transaction. */
@Service
@RequiredArgsConstructor
public class UtxoSigningSubmissionService {
    private final ChainSigningTransactionRepository transactions;
    private final PgmqClient queue;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Throwable.class)
    public void enqueue(UUID tenantId, AssetRuntimeMetadata currency, int transactionId, UUID requestId) {
        var submission = transactions.lockSubmission(currency.chain(), transactionId)
                .orElseThrow(() -> new IllegalArgumentException("signing transaction missing"));
        var transaction = submission.transaction();
        var payload = JacksonJson.readObject(json, transaction.getSignature());
        if (!tenantId.toString().equals(payload.path("tenantId").asText())
                || !requestId.toString().equals(payload.path("signingRequestId").asText())) {
            throw new IllegalArgumentException("stale or foreign signing submission");
        }
        if (requestId.equals(submission.enqueuedRequestId())) {
            return;
        }
        if (transaction.getStatus() != Constants.SIGNING) {
            throw new IllegalStateException("transaction is not signing");
        }
        if (transactions.recordEnqueued(currency.chain(), transactionId, requestId) != 1) {
            throw new IllegalStateException("signing transaction disappeared");
        }
        currency.applyTo(transaction);
        var headers = json.createObjectNode().put("tenant_id", tenantId.toString())
                .put("signing_request_id", requestId.toString());
        queue.send(WalletQueue.SIGN_FIRST, JacksonJson.writeValue(json, transaction),
                JacksonJson.writeValue(json, headers));
    }
}
