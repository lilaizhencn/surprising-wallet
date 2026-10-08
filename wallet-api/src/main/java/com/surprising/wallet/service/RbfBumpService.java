package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.QueueTenant;
import java.util.UUID;
import com.surprising.wallet.common.queue.WalletQueue;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.math.BigDecimal;
import com.surprising.wallet.chain.BitcoinLikeRbfHistory;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import org.springframework.transaction.annotation.Transactional;

/**
 * RBF 手续费替换服务，负责重建并重新投递 BTC 签名交易。
 */
@Slf4j
@Service
public class RbfBumpService {
    /** 默认费率倍数。 */
    private static final long DEFAULT_FEE_BUMP_FACTOR = 2L;

    /** 签名交易仓储。 */
    private final ChainJdbcRepository repository;
    /** 链元数据服务。 */
    private final BlockchainRuntimeService blockchainRuntimeService;
    /** 提现任务开关服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;
    /** PostgreSQL / PGMQ 队列。 */
    private final QueueWorker worker;
    private final ChainFeeRateService feeRates;
    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 构造 RBF 服务。 */
    public RbfBumpService(
            ChainJdbcRepository repository,
            BlockchainRuntimeService blockchainRuntimeService,
            WalletRuntimeConfigService runtimeConfigService,
            QueueWorker worker,
            ChainFeeRateService feeRates,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.runtimeConfigService = runtimeConfigService;
        this.worker = worker;
        this.feeRates = feeRates;
        this.objectMapper = objectMapper;
    }

    /** Queue acknowledgement and next signing enqueue commit with the fee replacement. */
    public void process() {
        worker.drain(WalletQueue.RBF, 100, message -> {
            var request = JacksonJson.readObject(objectMapper, message.body());
            String body = bumpFee(request.path("transactionId").asInt(),
                    QueueTenant.require(objectMapper, message), request.path("expectedTxId").asText());
            return body == null ? null : new QueueWorker.Next(WalletQueue.SIGN_FIRST, body);
        });
    }

    /** expectedTxId fences duplicates even when a replacement has already been broadcast. */
    @Transactional(rollbackFor = Throwable.class)
    public String bumpFee(int transactionId, UUID tenantId, String expectedTxId) {
        if (expectedTxId == null || !expectedTxId.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("RBF expectedTxId is required");
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata("BTC");
        String chain = currency.chain().toUpperCase(Locale.ROOT);
        WithdrawTransaction transaction = repository.lockBitcoinLikeSigningTransaction(currency, transactionId)
                .orElseThrow(() -> new IllegalArgumentException("transaction missing"));
        ObjectNode signature = JacksonJson.readObject(objectMapper, transaction.getSignature());
        if (!tenantId.toString().equals(signature.path("tenantId").asText()))
            throw new IllegalArgumentException("RBF tenant mismatch");
        if (transaction.getStatus() != Constants.SENT || !expectedTxId.equals(transaction.getTxId())) {
            log.info("RBF duplicate/stale/terminal request skipped id={}", transactionId);
            return null;
        }
        boolean collection = "COLLECTION".equals(signature.path("operationType").asText());
        if (!runtimeConfigService.isTaskEnabled("BTC", collection
                ? WalletRuntimeConfigService.TASK_COLLECTION : WalletRuntimeConfigService.TASK_WITHDRAW))
            throw new IllegalStateException("RBF task disabled");
        // sig2 removes firstSignTx after final signing. The broadcast raw transaction is authoritative.
        if (signature.path("rawTransaction").asText().isBlank())
            throw new IllegalStateException("broadcast signature missing");
        if (signature.path("rbfHistory").size() >= 5)
            throw new IllegalArgumentException("RBF attempt limit exceeded");
        long oldFeeRate = signature.path("feeRate").asLong();
        String configured = feeRates.get("BTC");
        long newFeeRate = Math.max(configured == null ? 0 : Long.parseLong(configured),
                Math.max(Math.multiplyExact(oldFeeRate, DEFAULT_FEE_BUMP_FACTOR), Math.addExact(oldFeeRate, 5)));
        if (oldFeeRate <= 0 || newFeeRate > 1000)
            throw new IllegalArgumentException("RBF fee rate limit exceeded");
        List<UtxoTransaction> utxos = JacksonJson.toList(objectMapper, signature.get("utxos"), UtxoTransaction.class);
        List<WithdrawRecord> records = JacksonJson.toList(objectMapper, signature.get("withdraw"), WithdrawRecord.class);
        if (utxos.isEmpty() || records.isEmpty()) throw new IllegalArgumentException("RBF inputs/outputs missing");
        long input = utxos.stream().map(UtxoTransaction::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(currency.getDecimal()).longValueExact();
        if (transaction.getBalance().multiply(currency.getDecimal()).longValueExact() != input)
            throw new IllegalArgumentException("RBF input amount mismatch");
        long newFee = Math.multiplyExact(P2wshFeeCalculator.estimateVBytes(utxos.size(),
                collection ? 1 : records.size() + 1), newFeeRate);
        if (newFee > 100_000 || newFee > input / 10 || newFee <= signature.path("fee").asLong())
            throw new IllegalArgumentException("RBF absolute/relative fee limit exceeded");
        for (UtxoTransaction utxo : utxos) {
            if (!repository.isUtxoLockedBy(chain, utxo.getTxId(), utxo.getSeq(), String.valueOf(transactionId)))
                throw new IllegalStateException("RBF original input lock missing");
        }
        BitcoinLikeRbfHistory.archive(signature, transaction.getTxId());
        if (collection) {
            if (records.size() != 1 || !records.getFirst().getAddress().equals(signature.path("changeAddress").asText()))
                throw new IllegalArgumentException("RBF collection output mismatch");
            long output = input - newFee;
            long dust = Math.max(546, signature.path("dustThreshold").asLong());
            if (output < dust) throw new IllegalArgumentException("RBF collection output dust");
            BigDecimal amount = BigDecimal.valueOf(output).divide(currency.getDecimal());
            records.getFirst().setBalance(amount);
            signature.set("withdraw", objectMapper.valueToTree(records));
            if (repository.updateCollectionAmounts(tenantId, chain, signature.path("collectionNo").asText(),
                    amount, BigDecimal.valueOf(newFee).divide(currency.getDecimal())) != 1)
                throw new IllegalStateException("RBF collection is terminal or missing");
            if (repository.updateCollectionStatus(tenantId, chain, signature.path("collectionNo").asText(),
                    "SIGNING", null, null, null) != 1)
                throw new IllegalStateException("RBF collection claim failed");
        } else {
            long sent = records.stream().map(WithdrawRecord::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(currency.getDecimal()).longValueExact();
            if (input - sent - newFee < 546) throw new IllegalArgumentException("RBF insufficient change");
            for (WithdrawRecord record : records)
                repository.updateWithdrawalStatus(tenantId, chain, record.getWithdrawId(), "SIGNING", null, null, null);
        }
        for (String field : List.of("firstSignTx", "rawTransaction", "txId", "valid", "error",
                "witnessScripts", "utxoValues", "weight", "vBytes")) signature.remove(field);
        signature.put("feeRate", newFeeRate);
        signature.put("fee", newFee);
        signature.put("signingRequestId", UUID.randomUUID().toString());
        transaction.setSignature(JacksonJson.writeValue(objectMapper, signature));
        transaction.setStatus(Constants.SIGNING);
        transaction.setTxId("rbf-" + transactionId);
        currency.applyTo(transaction);
        if (repository.updateBitcoinLikeSigningTransaction(currency, transaction) != 1)
            throw new IllegalStateException("RBF signing update failed");
        log.info("RBF queued id={} previousTxId={} feeRate={}", transactionId, expectedTxId, newFeeRate);
        return JacksonJson.writeValue(objectMapper, transaction);
    }
}
