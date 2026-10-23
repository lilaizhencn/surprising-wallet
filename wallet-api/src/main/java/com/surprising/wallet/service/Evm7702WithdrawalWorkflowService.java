package com.surprising.wallet.service;

import com.surprising.wallet.chain.evm.Evm7702AuthorizationService;
import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.Evm7702PayoutCodec;
import com.surprising.wallet.chain.evm.Evm7702PayoutItem;
import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import com.surprising.wallet.chain.evm.Evm7702PayoutRequest;
import com.surprising.wallet.chain.evm.Evm7702PayoutSigner;
import com.surprising.wallet.chain.evm.Evm7702RpcClient;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.chain.evm.EvmTransactionReceipt;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.Evm7702CollectionRepository;
import com.surprising.wallet.repository.Evm7702WithdrawalRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import static com.surprising.wallet.chain.evm.Evm7702RpcClient.chainBlockTimestamp;
import static com.surprising.wallet.chain.evm.Evm7702RpcClient.delegationCode;
import static com.surprising.wallet.chain.evm.Evm7702RpcClient.send;

/** EIP-7702 提现的批次领取、签名准备、广播恢复与回执确认。 */
@Service
public class Evm7702WithdrawalWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(Evm7702WithdrawalWorkflowService.class);
    private static final String NATIVE_TOKEN = "0x0000000000000000000000000000000000000000";
    private static final BigInteger ITEM_GAS = BigInteger.valueOf(120_000L);
    private static final BigInteger MIN_BATCH_GAS = BigInteger.valueOf(80_000L);
    private final Evm7702WithdrawalRepository repository;
    private final Evm7702WithdrawalCoordinator coordinator;
    private final ChainProfileRepository chainProfileRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final Evm7702RpcClient rpc;

    private final AccountSecp256k1KeyService keyService;
    private final CustodyCryptoService crypto;
    private final WalletRuntimeConfigService runtimeConfig;

    private final Evm7702AuthorizationService authorizationService = new Evm7702AuthorizationService();
    private final Evm7702PayoutSigner payoutSigner = new Evm7702PayoutSigner();
    private final Evm7702PayoutCodec payoutCodec = new Evm7702PayoutCodec();
    private final Evm7702BatchTransactionService transactionService = new Evm7702BatchTransactionService();
    private final Evm7702PayoutReceiptParser receiptParser = new Evm7702PayoutReceiptParser();
    private final AtomicBoolean running = new AtomicBoolean();

    public Evm7702WithdrawalWorkflowService(
            Evm7702WithdrawalRepository repository,
            Evm7702WithdrawalCoordinator coordinator,
            ChainProfileRepository chainProfileRepository,
            ChainAssetRepository chainAssetRepository,
            Evm7702RpcClient rpc,
            AccountSecp256k1KeyService keyService,
            CustodyCryptoService crypto,
            WalletRuntimeConfigService runtimeConfig) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.chainProfileRepository = chainProfileRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.rpc = rpc;
        this.keyService = keyService;
        this.crypto = crypto;
        this.runtimeConfig = runtimeConfig;
    }

    public void run() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (Evm7702WithdrawalRepository.RuntimeTarget target : repository.listRuntimeTargets()) {
                try {
                    AccountChainProfile profile = chainProfileRepository.findAccountByChain(target.chain())
                            .orElseThrow(() -> new IllegalStateException(
                                    "enabled EVM profile is missing for " + target.chain()));
                    if (!"evm".equalsIgnoreCase(profile.getFamily())
                            || !profile.getNetwork().equalsIgnoreCase(target.network())) {
                        throw new IllegalStateException("EIP-7702 payout target/profile network mismatch");
                    }
                    recoverFailedUnbroadcast(profile);
                    recoverUnknown(profile);
                    confirm(profile);
                    if (target.active() && runtimeConfig.isTaskEnabled(
                            profile.getChain(), WalletRuntimeConfigService.TASK_WITHDRAW)) {
                        processOne(profile);
                    }
                } catch (RuntimeException e) {
                    log.error("EIP-7702 payout cycle failed for {}/{}: {}",
                            target.chain(), target.network(), e.getMessage(), e);
                }
            }
        } finally {
            running.set(false);
        }
    }

    /** 恢复历史预广播失败批次，释放余额并让提现对账任务发送失败回调。 */
    private void recoverFailedUnbroadcast(AccountChainProfile profile) {
        int recovered = 0;
        for (Evm7702WithdrawalRepository.UnbroadcastBatch batch
                : repository.listFailedUnbroadcastBatches(profile.getChain(), profile.getNetwork(), 100)) {
            coordinator.failUnbroadcast(batch);
            recovered++;
        }
        if (recovered > 0) {
            log.info("EIP-7702 pre-broadcast failures recovered: chain={} network={} batches={}",
                    profile.getChain(), profile.getNetwork(), recovered);
        }
    }

    public void recoverUnknown(AccountChainProfile profile) {
        List<Evm7702WithdrawalRepository.UnknownAttempt> attempts = repository.listUnknownAttempts(
                profile.getChain(), profile.getNetwork(), 20);
        if (attempts.isEmpty()) return;
        HttpService http = rpc.http(profile);
        Web3j web3j = Web3j.build(http);
        try {
            for (Evm7702WithdrawalRepository.UnknownAttempt attempt : attempts) {
                try {
                    if (rpc.isTransactionKnown(web3j, attempt.txHash())) {
                        repository.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                        continue;
                    }
                    String rawTransaction = crypto.decrypt(attempt.signedTxCiphertext());
                    if (!Hash.sha3(rawTransaction).equalsIgnoreCase(attempt.txHash())) {
                        repository.markRecoveryError(attempt, "OUTBOX_HASH_MISMATCH",
                                "decrypted signed payout transaction does not match persisted tx hash");
                        continue;
                    }
                    repository.recordRecoveryAttempt(attempt);
                    EthSendTransaction sent = web3j.ethSendRawTransaction(rawTransaction).send();
                    if (!sent.hasError()) {
                        if (!attempt.txHash().equalsIgnoreCase(sent.getTransactionHash())) {
                            throw new IllegalStateException("RPC transaction hash differs from persisted hash");
                        }
                        repository.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                    } else if (rpc.isTransactionKnown(web3j, attempt.txHash())) {
                        repository.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                    } else {
                        repository.markRecoveryError(
                                attempt, "REBROADCAST_FAILED", sent.getError().getMessage());
                    }
                } catch (Exception e) {
                    repository.markRecoveryError(attempt, "RECOVERY_UNCERTAIN", e.getMessage());
                    log.error("EIP-7702 payout recovery failed for tenant={} batch={}: {}",
                            attempt.tenantId(), attempt.batchId(), e.getMessage(), e);
                }
            }
        } finally {
            web3j.shutdown();
        }
    }

    public Optional<String> processOne(AccountChainProfile profile) {
        Evm7702WithdrawalRepository.Batch batch = repository
                .claimNextBatch(profile.getChain(), profile.getNetwork()).orElse(null);
        if (batch == null) return Optional.empty();
        boolean outboxPersisted = false;
        try {
            HttpService http = rpc.http(profile);
            Web3j web3j = Web3j.build(http);
            try {
                Prepared prepared = prepare(web3j, http, profile, batch);
                Evm7702BatchTransactionService.SignedBatchTransaction signed =
                        coordinator.persistSignedAttempt(
                                batch, prepared.relayer().getAddress(), prepared.rpcPendingNonce(),
                                reservedNonce -> signPrepared(
                                        web3j, profile, batch, prepared, reservedNonce));
                outboxPersisted = true;
                EthSendTransaction sent = web3j.ethSendRawTransaction(signed.rawTransaction()).send();
                if (sent.hasError()) {
                    throw new IllegalStateException(
                            "eth_sendRawTransaction failed: " + sent.getError().getMessage());
                }
                if (!signed.transactionHash().equalsIgnoreCase(sent.getTransactionHash())) {
                    throw new IllegalStateException("RPC transaction hash differs from local signed hash");
                }
                repository.markSubmitted(batch.tenantId(), batch.id(), signed.transactionHash());
                return Optional.of(signed.transactionHash());
            } finally {
                web3j.shutdown();
            }
        } catch (Exception e) {
            if (outboxPersisted) {
                repository.markBroadcastUnknown(
                        batch.tenantId(), batch.id(), "BROADCAST_UNCERTAIN", e.getMessage());
            } else if (isDeterministicPreparationFailure(e)) {
                coordinator.failUnbroadcast(new Evm7702WithdrawalRepository.UnbroadcastBatch(
                        batch.tenantId(), batch.id(), batch.chain(), batch.network(),
                        "PREPARATION_FAILED", e.getMessage()));
            } else {
                repository.releaseUnbroadcastBatch(batch, "PREPARATION_FAILED", e.getMessage());
            }
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("failed to process EIP-7702 payout batch", e);
        }
    }

    public void confirm(AccountChainProfile profile) {
        HttpService http = rpc.http(profile);
        Web3j web3j = Web3j.build(http);
        try {
            BigInteger latest = send(() -> web3j.ethBlockNumber().send().getBlockNumber());
            for (Evm7702WithdrawalRepository.PendingBatch batch
                    : repository.listPendingBatches(profile.getChain(), profile.getNetwork(), 100)) {
                Optional<EvmTransactionReceipt> found = send(
                        () -> rpc.transactionReceipt(http, batch.txHash()));
                if (found.isEmpty() || found.get().getBlockNumber() == null) continue;
                EvmTransactionReceipt receipt = found.get();
                BigInteger confirmations = latest.subtract(receipt.getBlockNumber()).add(BigInteger.ONE);
                if (confirmations.compareTo(BigInteger.valueOf(batch.requiredConfirmations())) < 0) continue;
                EthBlock canonical = send(
                        () -> web3j.ethGetBlockByHash(receipt.getBlockHash(), false).send());
                if (canonical.getBlock() == null
                        || !canonical.getBlock().getHash().equalsIgnoreCase(receipt.getBlockHash())) {
                    throw new IllegalStateException("payout receipt block is no longer canonical");
                }
                BigInteger effectiveGasPrice = receipt.getEffectiveGasPrice() == null
                        ? BigInteger.ZERO : Numeric.decodeQuantity(receipt.getEffectiveGasPrice());
                EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                        web3j, profile, receipt.getFrom(), receipt.getGasUsed(),
                        effectiveGasPrice, receipt.getBlockNumber(), receipt.getL1Fee(),
                        receipt.getGasUsedForL1(), receipt.getOperatorFeeScalar(),
                        receipt.getOperatorFeeConstant());
                if (!receipt.isStatusOK()) {
                    coordinator.completeReverted(
                            batch, batch.txHash(), receipt.getGasUsed(), effectiveGasPrice,
                            fee.executionFee(), fee.l1Fee(), fee.operatorFee(), receipt.getBlockNumber(),
                            receipt.getBlockHash(), Hash.sha3String(
                                    "outer-reverted:" + batch.txHash().toLowerCase()));
                    continue;
                }
                List<Evm7702WithdrawalRepository.BatchItemIdentity> identities =
                        repository.listBatchItems(batch.tenantId(), batch.batchId());
                Evm7702PayoutReceiptParser.ParsedReceipt parsed = receiptParser.parse(
                        receipt, batch.hotWallet(), batchId(batch.tenantId(), batch.batchId()),
                        identities.stream().map(item -> new Evm7702PayoutReceiptParser.ExpectedPayout(
                                item.withdrawalId(), item.token(), item.recipient(), item.amountAtomic()))
                                .toList());
                Evm7702CollectionRepository.RuntimeConfig config =
                        requireVersionedConfig(profile, repository.requireBatchState(
                                batch.tenantId(), batch.batchId()).delegateVersion());
                coordinator.complete(
                        batch, batch.txHash(), receipt.getGasUsed(), effectiveGasPrice,
                        fee.executionFee(), fee.l1Fee(), fee.operatorFee(), receipt.getBlockNumber(),
                        receipt.getBlockHash(), parsed.items(), config.payoutDelegateAddress());
            }
        } finally {
            web3j.shutdown();
        }
    }

    private Prepared prepare(Web3j web3j, HttpService http, AccountChainProfile profile,
                             Evm7702WithdrawalRepository.Batch batch) throws Exception {
        Evm7702CollectionRepository.RuntimeConfig config = batch.config();
        BigInteger rpcChainId = web3j.ethChainId().send().getChainId();
        if (!rpcChainId.equals(config.chainId())
                || !rpcChainId.equals(BigInteger.valueOf(profile.getChainId()))) {
            throw new IllegalStateException("RPC/config/profile chainId mismatch");
        }
        EthBlock.Block latestBlock = web3j.ethGetBlockByNumber(
                DefaultBlockParameterName.LATEST, false).send().getBlock();
        Instant chainTimestamp = chainBlockTimestamp(latestBlock);
        Instant wallClock = Instant.now();
        Instant deadlineBase = chainTimestamp.isAfter(wallClock) ? chainTimestamp : wallClock;
        Instant signatureDeadline = deadlineBase.plusSeconds(config.signatureTtlSeconds());
        rpc.requireCodeHash(web3j, config.payoutDelegateAddress(),
                config.payoutDelegateCodeHash(), "payout delegate");
        Credentials relayer = keyService.credentials(profile, config.relayerChainAddress());
        if (!relayer.getAddress().equalsIgnoreCase(config.relayerAddress())) {
            throw new IllegalStateException("derived relayer key does not match configuration");
        }
        Credentials authority = keyService.credentials(profile, batch.hotChainAddress());
        if (!authority.getAddress().equalsIgnoreCase(batch.hotWallet())) {
            throw new IllegalStateException("derived hot-wallet key does not match payout authority");
        }
        BigInteger totalAmount = batch.items().stream()
                .map(Evm7702WithdrawalRepository.ClaimedItem::amountAtomic)
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger balance = NATIVE_TOKEN.equalsIgnoreCase(batch.tokenContract())
                ? web3j.ethGetBalance(batch.hotWallet(), DefaultBlockParameterName.LATEST)
                        .send().getBalance()
                : rpc.tokenBalance(web3j, batch.tokenContract(), batch.hotWallet());
        if (balance.compareTo(totalAmount) < 0) {
            throw new IllegalStateException("tenant hot-wallet balance is lower than payout batch amount");
        }

        String code = web3j.ethGetCode(
                batch.hotWallet(), DefaultBlockParameterName.LATEST).send().getCode();
        BigInteger authorityNonce = web3j.ethGetTransactionCount(
                batch.hotWallet(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
        boolean includeAuthorization;
        BigInteger operationNonce;
        List<AuthorizationTuple> authorizations;
        PayoutAuthorizationMode authorizationMode = payoutAuthorizationMode(
                code, config.delegateAddress(), config.payoutDelegateAddress());
        if (authorizationMode == PayoutAuthorizationMode.AUTHORIZE_PAYOUT) {
            includeAuthorization = true;
            operationNonce = BigInteger.ZERO;
            authorizations = List.of(authorizationService.authorize(
                    config.chainId(), config.payoutDelegateAddress(), authorityNonce, authority));
        } else if (authorizationMode == PayoutAuthorizationMode.ALREADY_PAYOUT_DELEGATED) {
            includeAuthorization = false;
            operationNonce = rpc.operationNonce(web3j, batch.hotWallet());
            authorizations = List.of();
        } else {
            throw new IllegalStateException("hot wallet has an unapproved code/delegate");
        }

        List<Evm7702PayoutItem> items = new ArrayList<>();
        for (int index = 0; index < batch.items().size(); index++) {
            Evm7702WithdrawalRepository.ClaimedItem item = batch.items().get(index);
            items.add(new Evm7702PayoutItem(
                    item.withdrawalId(), BigInteger.valueOf(index), batch.tokenContract(),
                    item.recipient(), item.amountAtomic(), ITEM_GAS));
        }
        Evm7702PayoutRequest request = new Evm7702PayoutRequest(
                Numeric.hexStringToByteArray(batch.batchHash()), batch.hotWallet(),
                config.relayerAddress(), items, operationNonce,
                BigInteger.valueOf(signatureDeadline.getEpochSecond()));
        request.requireNotExpired(chainTimestamp);
        String calldata = payoutCodec.encode(
                request, payoutSigner.sign(config.chainId(), request, authority));
        EvmFeeSupport.FeeQuote feeQuote = EvmFeeSupport.quote(web3j, profile);
        BigInteger priority = feeQuote.maxPriorityFeePerGas();
        BigInteger maxFee = feeQuote.maxFeePerGas();
        BigInteger relayerPendingNonce = web3j.ethGetTransactionCount(
                relayer.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger estimated;
        try {
            estimated = rpc.estimateGas(
                    http, relayer.getAddress(), batch.hotWallet(), calldata,
                    priority, maxFee, authorizations);
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "payout gas estimation rejected; chainTimestamp=" + chainTimestamp
                            + ", wallClock=" + wallClock + ", deadline=" + signatureDeadline
                            + ": " + error.getMessage(), error);
        }
        BigInteger gasLimit = new BigDecimal(estimated).multiply(config.gasLimitMultiplier())
                .setScale(0, RoundingMode.UP).toBigIntegerExact();
        BigInteger blockCap = new BigDecimal(latestBlock.getGasLimit())
                .multiply(config.blockGasRatio()).setScale(0, RoundingMode.DOWN).toBigIntegerExact();
        if (gasLimit.compareTo(BigInteger.valueOf(config.maxBatchGas()).min(blockCap)) > 0
                || gasLimit.compareTo(MIN_BATCH_GAS) < 0) {
            throw new IllegalStateException("estimated payout gas exceeds configured/block safety limit");
        }
        return new Prepared(
                relayer, config.chainId(), relayerPendingNonce, priority, maxFee,
                gasLimit, estimated, batch.hotWallet(), calldata, authorizations,
                includeAuthorization, includeAuthorization ? authorityNonce : null,
                operationNonce, signatureDeadline);
    }

    /** 判断估算失败是否为链上合约明确拒绝，避免把确定性失败无限重试。 */
    private static boolean isDeterministicPreparationFailure(Throwable error) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) message.append(' ').append(current.getMessage());
        }
        String lower = message.toString().toLowerCase(java.util.Locale.ROOT);
        return lower.contains("eth_estimategas failed")
                && (lower.contains("revert") || lower.contains("custom error")
                || lower.contains("return data:"));
    }

    /** 判断持久化的预广播错误文本是否来自链上明确拒绝。 */
    private static boolean isDeterministicPreparationFailure(String errorMessage) {
        return errorMessage != null
                && isDeterministicPreparationFailure(new IllegalStateException(errorMessage));
    }

    private Evm7702WithdrawalCoordinator.SignedAttempt signPrepared(
            Web3j web3j, AccountChainProfile profile,
            Evm7702WithdrawalRepository.Batch batch, Prepared prepared,
            BigInteger reservedNonce) {
        Evm7702BatchTransactionService.SignedBatchTransaction signed = transactionService.signBatch(
                prepared.chainId().longValueExact(), reservedNonce,
                prepared.maxPriorityFeePerGas(), prepared.maxFeePerGas(), prepared.gasLimit(),
                prepared.hotWallet(), prepared.calldata(), prepared.authorizations(),
                prepared.relayer());
        String encrypted = crypto.encrypt(signed.rawTransaction());
        Evm7702WithdrawalRepository.PreparedAttempt attempt =
                new Evm7702WithdrawalRepository.PreparedAttempt(
                        batch.tenantId(), batch.id(), prepared.estimatedGas().longValueExact(),
                        prepared.gasLimit().longValueExact(), prepared.maxFeePerGas(),
                        prepared.maxPriorityFeePerGas(), reservedNonce, signed.transactionHash(),
                        Hash.sha3(prepared.calldata()), encrypted, "custody-v1",
                        prepared.authorizationIncluded(), prepared.authorizationNonce(),
                        prepared.operationNonce(), prepared.signatureDeadline());
        BigInteger reservedFeeAtomic = prepared.gasLimit().multiply(prepared.maxFeePerGas())
                .add(EvmFeeSupport.estimateSeparateL1Fee(
                        web3j, profile, prepared.relayer().getAddress(), signed.rawTransaction()))
                .add(EvmFeeSupport.estimateOperatorFee(
                        web3j, profile, prepared.relayer().getAddress(), prepared.gasLimit()));
        send(() -> {
            EvmFeeSupport.requireBalance(
                    web3j, prepared.relayer().getAddress(), reservedFeeAtomic, "EIP-7702 relayer");
            return null;
        });
        BigDecimal reservedFee = EvmFeeSupport.atomicToNative(
                reservedFeeAtomic, nativeDecimals(profile), RoundingMode.UP);
        return new Evm7702WithdrawalCoordinator.SignedAttempt(signed, attempt, reservedFee);
    }

    private int nativeDecimals(AccountChainProfile profile) {
        var asset = chainAssetRepository.findActiveDefinition(profile.getChain(), profile.getNativeSymbol())
                .orElseThrow(() -> new IllegalStateException(
                        "missing active native chain_asset for " + profile.getChain()));
        if (!Boolean.TRUE.equals(asset.getNativeAsset()) || asset.getDecimals() == null) {
            throw new IllegalStateException(
                    "invalid native chain_asset for " + profile.getChain());
        }
        return asset.getDecimals();
    }

    private Evm7702CollectionRepository.RuntimeConfig requireVersionedConfig(
            AccountChainProfile profile, int version) {
        return repository.requireRuntimeConfigVersion(
                profile.getChain(), profile.getNetwork(), version);
    }

    enum PayoutAuthorizationMode {
        AUTHORIZE_PAYOUT,
        ALREADY_PAYOUT_DELEGATED,
        REJECT
    }

    static PayoutAuthorizationMode payoutAuthorizationMode(
            String code, String collectionDelegate, String payoutDelegate) {
        if (code == null || "0x".equalsIgnoreCase(code) || "0x0".equalsIgnoreCase(code)) {
            return PayoutAuthorizationMode.AUTHORIZE_PAYOUT;
        }
        if (code.equalsIgnoreCase(delegationCode(payoutDelegate))) {
            return PayoutAuthorizationMode.ALREADY_PAYOUT_DELEGATED;
        }
        if (code.equalsIgnoreCase(delegationCode(collectionDelegate))) {
            return PayoutAuthorizationMode.AUTHORIZE_PAYOUT;
        }
        return PayoutAuthorizationMode.REJECT;
    }

    private static byte[] batchId(java.util.UUID tenantId, java.util.UUID batchId) {
        return Hash.sha3((tenantId + ":WITHDRAWAL:" + batchId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record Prepared(
            Credentials relayer, BigInteger chainId, BigInteger rpcPendingNonce,
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
            BigInteger gasLimit, BigInteger estimatedGas, String hotWallet,
            String calldata, List<AuthorizationTuple> authorizations,
            boolean authorizationIncluded, BigInteger authorizationNonce,
            BigInteger operationNonce, Instant signatureDeadline) { }
}
