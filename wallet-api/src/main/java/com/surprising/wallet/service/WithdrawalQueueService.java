package com.surprising.wallet.service;

import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WithdrawalQueueService {
    private final QueueWorker worker;
    private final TransactionService transactions;
    private final ObjectMapper objectMapper;
    private final WalletRuntimeConfigService runtimeConfig;
    public void withdraw() {
        if (!runtimeConfig.isGlobalTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW)) return;
        worker.drain(WalletQueue.WITHDRAW, 100, message -> {
            if (!transactions.withdraw(JacksonJson.readValue(objectMapper, message.body(), WithdrawRecord.class), QueueTenant.require(objectMapper, message)))
                throw new IllegalStateException("withdrawal not completed");
            return null;
        });
    }
    public void broadcast() {
        worker.drain(WalletQueue.SIGN_DONE, 100, message -> {
            var tx = JacksonJson.readValue(objectMapper, message.body(), WithdrawTransaction.class);
            QueueTenant.verifySignature(objectMapper, message, tx.getSignature());
            if (!transactions.sendWithdrawTransaction(tx))
                throw new IllegalStateException("broadcast not completed");
            return null;
        });
    }
}
