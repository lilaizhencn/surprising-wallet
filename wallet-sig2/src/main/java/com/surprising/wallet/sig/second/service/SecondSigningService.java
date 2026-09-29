package com.surprising.wallet.sig.second.service;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.second.ISignService;
import com.surprising.wallet.sig.second.SignContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;

import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.WalletQueue;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SecondSigningService {
    private final QueueWorker worker;
    private final ObjectMapper objectMapper;
    private static final HexFormat HEX = HexFormat.of();
    public void process() {
        worker.drain(WalletQueue.SIGN_SECOND, 20, message -> {
            WithdrawTransaction transaction = JacksonJson.readValue(objectMapper, message.body(), WithdrawTransaction.class);
            QueueTenant.verifySignature(objectMapper, message, transaction.getSignature());
            AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
            ISignService signService = SignContent.getSignService(currency);
            ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
            if (signService == null) {
                signature.put("valid", false);
                signature.put("error", "no sign service for " + currency.getName());
            } else {
                String rawTransaction = signService.signTransaction(transaction);
                signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
                if (StringUtils.hasText(rawTransaction)) {
                    Transaction signedTx = Transaction.read(ByteBuffer.wrap(HEX.parseHex(rawTransaction)));
                    signature.put("rawTransaction", rawTransaction);
                    signature.put("txId", signedTx.getTxId().toString());
                    signature.put("weight", signedTx.getWeight());
                    signature.put("vBytes", signedTx.getVsize());
                    signature.remove("firstSignTx");
                    signature.put("valid", true);
                    log.info("二次签名成功 txId={}, finalTxId={}, weight={}, vBytes={}",
                            transaction.getId(), signedTx.getTxId(), signedTx.getWeight(), signedTx.getVsize());
                } else {
                    signature.put("valid", false);
                    if (!signature.has("error")) {
                        signature.put("error", "second sign returned empty raw transaction");
                    }
                    log.warn("二次签名失败 txId={}, error={}", transaction.getId(), JacksonJson.text(signature, "error"));
                }
            }
            transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
            return new QueueWorker.Next(WalletQueue.SIGN_DONE, JacksonJson.writeValue(objectMapper, transaction));
        });
    }
}
