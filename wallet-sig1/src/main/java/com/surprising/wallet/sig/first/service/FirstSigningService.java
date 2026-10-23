package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.signing.UtxoPartialSigningTask;
import com.surprising.wallet.common.signing.UtxoSigningResult;
import com.surprising.wallet.common.signing.UtxoSigningTask;
import com.surprising.wallet.sig.first.FirstSignerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class FirstSigningService {
    private final QueueWorker worker;
    private final ObjectMapper json;
    private final FirstSignerRegistry signers;

    public void process() {
        worker.drain(WalletQueue.SIGN_FIRST, 20, message -> {
            var task = JacksonJson.readValue(json, message.body(), UtxoSigningTask.class);
            QueueTenant.verify(json, message, task.request().tenantId());
            try {
                var signer = signers.find(task.request().asset().metadata());
                if (signer == null) throw new IllegalArgumentException("no first signer for " + task.request().asset().chain());
                var partial = signer.sign(task.request());
                return new QueueWorker.Next(WalletQueue.SIGN_SECOND,
                        JacksonJson.writeValue(json, new UtxoPartialSigningTask(task, partial)));
            } catch (Exception error) {
                return new QueueWorker.Next(WalletQueue.SIGN_DONE,
                        JacksonJson.writeValue(json, UtxoSigningResult.failure(task, error.getMessage())));
            }
        });
    }
}
