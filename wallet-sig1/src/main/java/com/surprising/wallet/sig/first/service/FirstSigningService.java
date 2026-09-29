package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.sig.first.SignContent;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sig.first.service.ISignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.WalletQueue;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class FirstSigningService {
    private final QueueWorker worker;
    private final ObjectMapper objectMapper;
    private final SignContent signContent;
    public void process() {
        worker.drain(WalletQueue.SIGN_FIRST, 20, message -> {
            ObjectNode txJson = JacksonJson.readObject(objectMapper, message.body());
            WithdrawTransaction transaction = JacksonJson.toValue(objectMapper, txJson, WithdrawTransaction.class);
            QueueTenant.verifySignature(objectMapper, message, transaction.getSignature());
            AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
            ISignService signService = signContent.getSignService(currency);
            if (signService == null) {
                ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
                signature.put("valid", false);
                signature.put("error", "no first sign service for " + currency.getName());
                transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
            } else {
                signService.signTransaction(transaction);
            }
            String signatureStr = transaction.getSignature();
            ObjectNode sigJson = JacksonJson.readObject(objectMapper, signatureStr);
            WalletQueue next;
            if (JacksonJson.booleanValue(sigJson, "valid")) {
                log.info("签名验证成功 开始推送到第二次签名服务队列");
                next = WalletQueue.SIGN_SECOND;
            } else {
                log.warn("签名验证失败 推送到签名失败队列");
                next = WalletQueue.SIGN_DONE;
            }
            return new QueueWorker.Next(next, JacksonJson.writeValue(objectMapper, transaction));
        });
    }
}
