package com.surprising.wallet.service;

import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.signing.UtxoSigningResult;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WithdrawalQueueService {
    private final QueueWorker worker;
    private final QueuedUtxoWithdrawalService transactions;
    private final UtxoBroadcastService broadcasts;
    private final ObjectMapper objectMapper;
    private final WalletRuntimeConfigService runtimeConfig;
    public void withdraw() {
        if (!runtimeConfig.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW)) return;
        worker.drain(WalletQueue.WITHDRAW, 100, message -> {
            if (!transactions.accept(JacksonJson.readValue(objectMapper, message.body(), WithdrawRecord.class), QueueTenant.require(objectMapper, message)))
                throw new IllegalStateException("withdrawal not completed");
            return null;
        });
    }
    public void broadcast() {
        worker.drain(WalletQueue.SIGN_DONE, 100, message -> {
            var result = JacksonJson.readValue(objectMapper, message.body(), UtxoSigningResult.class);
            QueueTenant.verify(objectMapper, message, result.tenantId());
            broadcasts.apply(result);
            return null;
        });
    }
}
