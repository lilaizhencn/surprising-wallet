package com.surprising.wallet.sig.second.service;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.signing.UtxoPartialSigningTask;
import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.common.signing.UtxoSigningResult;
import com.surprising.wallet.sig.second.SecondSignerRegistry;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.core.Transaction;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class SecondSigningService {
    private final QueueWorker worker;
    private final SecondSignerRegistry signers;
    private final ObjectMapper json;

    public void process() {
        worker.drain(WalletQueue.SIGN_SECOND, 20, message -> {
            var partialTask = JacksonJson.readValue(json, message.body(), UtxoPartialSigningTask.class);
            var task = partialTask.task();
            var request = task.request();
            QueueTenant.verify(json, message, request.tenantId());
            UtxoSigningResult result;
            try {
                var signer = signers.find(request.asset().metadata());
                if (signer == null) throw new IllegalArgumentException("no second signer for " + request.asset().chain());
                String raw = signer.sign(request, partialTask.signature());
                var tx = Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(raw)));
                if (tx.getInputs().size() != request.inputs().size()) throw new IllegalArgumentException("signed input count mismatch");
                for (int index = 0; index < tx.getInputs().size(); index++) {
                    var outpoint = tx.getInput(index).getOutpoint();
                    var expected = request.inputs().get(index);
                    if (!outpoint.getHash().toString().equalsIgnoreCase(expected.txId()) || outpoint.getIndex() != expected.vout()) {
                        throw new IllegalArgumentException("signed input identity mismatch");
                    }
                }
                long output = 0;
                for (var payment : tx.getOutputs()) output = Math.addExact(output, payment.getValue().getValue());
                long fee = Math.subtractExact(request.inputAtomic(), output);
                if (fee < 0 || fee != partialTask.signature().feeAtomic()) throw new IllegalArgumentException("signed fee mismatch");
                var signed = new UtxoSignedTransaction(raw, tx.getTxId().toString(), fee,
                        tx.getWeight(), tx.getVsize(), partialTask.signature().estimates());
                result = UtxoSigningResult.success(task, signed);
            } catch (Exception error) {
                result = UtxoSigningResult.failure(task, error.getMessage());
            }
            return new QueueWorker.Next(WalletQueue.SIGN_DONE, JacksonJson.writeValue(json, result));
        });
    }
}
