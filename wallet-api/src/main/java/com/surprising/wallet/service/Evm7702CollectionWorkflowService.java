package com.surprising.wallet.service;

import com.surprising.wallet.chain.evm.Evm7702AuthorizationService;
import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.Evm7702CollectionRequest;
import com.surprising.wallet.chain.evm.Evm7702ContractCodec;
import com.surprising.wallet.chain.evm.Evm7702OperationSigner;
import com.surprising.wallet.chain.evm.Evm7702ReceiptParser;
import com.surprising.wallet.chain.evm.Evm7702RpcClient;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.chain.evm.EvmTransactionReceipt;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.coordinator.Evm7702CollectionCoordinator;
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
import com.surprising.wallet.repository.*;
import com.surprising.wallet.chain.model.EvmBroadcastAttempt;
import java.util.Map;
import java.util.HashMap;

/**
 * EIP-7702 归集的批次领取、签名准备、广播恢复与回执确认。
 */
@Service
public class Evm7702CollectionWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(Evm7702CollectionWorkflowService.class);

    private static final BigInteger MIN_ITEM_GAS = BigInteger.valueOf(60_000L);

    private static final BigInteger DEFAULT_ITEM_GAS = BigInteger.valueOf(180_000L);

    private static final String NATIVE_TOKEN = "0x0000000000000000000000000000000000000000";
    private final Evm7702CollectionCoordinator coordinator;
    private final ChainProfileRepository chainProfileRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final Evm7702RpcClient rpc;
    private final AccountSecp256k1KeyService keyService;
    private final CustodyCryptoService crypto;
    private final WalletRuntimeConfigService runtimeConfig;
    private final Evm7702AuthorizationService authorizationService = new Evm7702AuthorizationService();
    private final Evm7702OperationSigner operationSigner = new Evm7702OperationSigner();
    private final Evm7702ContractCodec contractCodec = new Evm7702ContractCodec();
    private final Evm7702BatchTransactionService transactionService = new Evm7702BatchTransactionService();
    private final Evm7702ReceiptParser receiptParser = new Evm7702ReceiptParser();
    private final AtomicBoolean running = new AtomicBoolean();

    public Evm7702CollectionWorkflowService(
            EvmCollectionBatchRepository batches,
            EvmCollectionBatchItemRepository items,
            EvmCollectionBatchAttemptRepository attempts,
            Evm7702ConfigRepository configurations,
            Evm7702CollectionCoordinator coordinator,
            ChainProfileRepository chainProfileRepository,
            ChainAssetRepository chainAssetRepository,
            Evm7702RpcClient rpc,
            AccountSecp256k1KeyService keyService,
            CustodyCryptoService crypto,
            WalletRuntimeConfigService runtimeConfig) {
        this.batches = batches;
        this.items = items;
        this.attempts = attempts;
        this.configurations = configurations;
        this.coordinator = coordinator;
        this.chainProfileRepository = chainProfileRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.rpc = rpc;
        this.keyService = keyService;
        this.crypto = crypto;
        this.runtimeConfig = runtimeConfig;
    }

    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (RuntimeTarget target : listRuntimeTargets()) {
                try {
                    AccountChainProfile profile = target.profile();
                    if (!"evm".equalsIgnoreCase(profile.getFamily())) {
                        throw new IllegalStateException("EIP-7702 target must have EVM family");
                    }
                    recoverUnknown(profile);
                    confirm(profile);
                    if (target.active() && runtimeConfig.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_COLLECTION)) {
                        processOne(profile);
                    }
                } catch (RuntimeException e) {
                    log.error(
                            "EIP-7702 collection cycle failed for {}/{}: {}",
                            target.profile().getChain(),
                            target.profile().getNetwork(),
                            e.getMessage(),
                            e);
                }
            }
        } finally {
            running.set(false);
        }
    }

    public void recoverUnknown(AccountChainProfile profile) {
        List<EvmBroadcastAttempt> attempts = listUnknownAttempts(profile.getChain(), profile.getNetwork(), 20);
        if (attempts.isEmpty()) {
            return;
        }
        HttpService http = rpc.http(profile);
        Web3j web3j = Web3j.build(http);
        try {
            for (EvmBroadcastAttempt attempt : attempts) {
                try {
                    if (rpc.isTransactionKnown(web3j, attempt.txHash())) {
                        coordinator.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                        continue;
                    }
                    String rawTransaction = crypto.decrypt(attempt.signedTxCiphertext());
                    String localHash = Hash.sha3(rawTransaction);
                    if (!localHash.equalsIgnoreCase(attempt.txHash())) {
                        coordinator.markRecoveryError(
                                attempt,
                                "OUTBOX_HASH_MISMATCH",
                                "decrypted signed transaction does not match persisted tx hash");
                        log.error("EIP-7702 outbox hash mismatch for tenant={} batch={}", attempt.tenantId(), attempt.batchId());
                        continue;
                    }
                    this.attempts.recordRecovery(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                    EthSendTransaction sent = web3j.ethSendRawTransaction(rawTransaction).send();
                    if (!sent.hasError()) {
                        if (!attempt.txHash().equalsIgnoreCase(sent.getTransactionHash())) {
                            throw new IllegalStateException("RPC transaction hash differs from persisted hash");
                        }
                        coordinator.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                    } else if (rpc.isTransactionKnown(web3j, attempt.txHash())) {
                        coordinator.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                    } else {
                        coordinator.markRecoveryError(attempt, "REBROADCAST_FAILED", sent.getError().getMessage());
                    }
                } catch (Exception e) {
                    coordinator.markRecoveryError(attempt, "RECOVERY_UNCERTAIN", e.getMessage());
                    log.error(
                            "EIP-7702 recovery failed for tenant={} batch={}: {}",
                            attempt.tenantId(),
                            attempt.batchId(),
                            e.getMessage(),
                            e);
                }
            }
        } finally {
            web3j.shutdown();
        }
    }

    public Optional<String> processOne(AccountChainProfile profile) {
        Evm7702CollectionCoordinator.Batch batch = coordinator.claimNextBatch(profile.getChain(), profile.getNetwork()).orElse(null);
        if (batch == null) {
            return Optional.empty();
        }
        boolean outboxPersisted = false;
        try {
            HttpService http = rpc.http(profile);
            Web3j web3j = Web3j.build(http);
            try {
                Prepared prepared = prepare(web3j, http, profile, batch);
                Evm7702BatchTransactionService.SignedBatchTransaction signed = coordinator.persistSignedAttempt(
                        batch,
                        prepared.relayer().getAddress(),
                        prepared.rpcPendingNonce(),
                        reservedNonce -> signPrepared(web3j, profile, batch, prepared, reservedNonce));
                outboxPersisted = true;
                EthSendTransaction sent = web3j.ethSendRawTransaction(signed.rawTransaction()).send();
                if (sent.hasError()) {
                    throw new IllegalStateException("eth_sendRawTransaction failed: " + sent.getError().getMessage());
                }
                if (!signed.transactionHash().equalsIgnoreCase(sent.getTransactionHash())) {
                    throw new IllegalStateException("RPC transaction hash differs from local signed hash");
                }
                coordinator.markSubmitted(batch.tenantId(), batch.id(), signed.transactionHash());
                return Optional.of(signed.transactionHash());
            } finally {
                web3j.shutdown();
            }
        } catch (Exception e) {
            if (outboxPersisted) {
                coordinator.markBroadcastUnknown(batch.tenantId(), batch.id(), "BROADCAST_UNCERTAIN", e.getMessage());
            } else {
                coordinator.releaseUnbroadcastBatch(batch, "PREPARATION_FAILED", e.getMessage());
            }
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException("failed to process EIP-7702 batch", e);
        }
    }

    public void confirm(AccountChainProfile profile) {
        HttpService http = rpc.http(profile);
        Web3j web3j = Web3j.build(http);
        try {
            BigInteger latest = send(() -> web3j.ethBlockNumber().send().getBlockNumber());
            for (Evm7702CollectionCoordinator.PendingBatch batch : listPendingBatches(profile.getChain(), profile.getNetwork(), 100)) {
                Optional<EvmTransactionReceipt> receipt = send(() -> rpc.transactionReceipt(http, batch.txHash()));
                if (receipt.isEmpty() || receipt.get().getBlockNumber() == null) {
                    continue;
                }
                BigInteger confirmations = latest.subtract(receipt.get().getBlockNumber()).add(BigInteger.ONE);
                if (confirmations.compareTo(BigInteger.valueOf(batch.requiredConfirmations())) < 0) {
                    continue;
                }
                EvmTransactionReceipt value = receipt.get();
                EthBlock canonical = send(() -> web3j.ethGetBlockByHash(value.getBlockHash(), false).send());
                if (canonical.getBlock() == null || !canonical.getBlock().getHash().equalsIgnoreCase(value.getBlockHash())) {
                    throw new IllegalStateException("receipt block is no longer canonical");
                }
                var identities = items.listIdentities(batch.tenantId(), batch.batchId());
                byte[] batchId = Numeric.hexStringToByteArray(batchHash(batch.tenantId(), batch.batchId()));
                Evm7702ReceiptParser.ParsedReceipt parsed = receiptParser.parse(
                        value,
                        batch.collectorAddress(),
                        batchId,
                        identities.stream().map(identity -> new Evm7702ReceiptParser.ExpectedTransfer(
                                identity.authority(),
                                identity.token(),
                                identity.recipient(),
                                identity.amount())).toList());
                BigInteger effectiveGasPrice = value.getEffectiveGasPrice() == null ? BigInteger.ZERO : Numeric.decodeQuantity(value.getEffectiveGasPrice());
                EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                        web3j,
                        profile,
                        value.getFrom(),
                        value.getGasUsed(),
                        effectiveGasPrice,
                        value.getBlockNumber(),
                        value.getL1Fee(),
                        value.getGasUsedForL1(),
                        value.getOperatorFeeScalar(),
                        value.getOperatorFeeConstant());
                coordinator.complete(
                        profile.getChain(),
                        batch,
                        batch.txHash(),
                        value.getGasUsed(),
                        effectiveGasPrice,
                        fee.executionFee(),
                        fee.l1Fee(),
                        fee.operatorFee(),
                        value.getBlockNumber(),
                        value.getBlockHash(),
                        parsed.items());
            }
        } finally {
            web3j.shutdown();
        }
    }

    private Prepared prepare(
            Web3j web3j,
            HttpService http,
            AccountChainProfile profile,
            Evm7702CollectionCoordinator.Batch batch) throws Exception {
        var config = batch.config();
        BigInteger rpcChainId = web3j.ethChainId().send().getChainId();
        if (!rpcChainId.equals(config.chainId()) || !rpcChainId.equals(BigInteger.valueOf(profile.getChainId()))) {
            throw new IllegalStateException("RPC/config/profile chainId mismatch");
        }
        EthBlock.Block latestBlock = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().getBlock();
        Instant chainTimestamp = chainBlockTimestamp(latestBlock);
        Instant signatureDeadline = chainTimestamp.plusSeconds(config.signatureTtlSeconds());
        rpc.requireCodeHash(web3j, config.delegateAddress(), config.delegateCodeHash(), "delegate");
        rpc.requireCodeHash(web3j, config.collectorAddress(), config.collectorCodeHash(), "collector");
        Credentials relayer = keyService.credentials(profile, batch.relayerChainAddress());
        if (!relayer.getAddress().equalsIgnoreCase(config.relayerAddress())) {
            throw new IllegalStateException("derived relayer key does not match configuration");
        }
        byte[] batchId = Numeric.hexStringToByteArray(batch.batchHash());
        List<Evm7702CollectionRequest> requests = new ArrayList<>();
        List<byte[]> signatures = new ArrayList<>();
        List<AuthorizationTuple> authorizations = new ArrayList<>();
        List<Evm7702CollectionCoordinator.PreparedItem> preparedItems = new ArrayList<>();
        for (int index = 0; index < batch.items().size(); index++) {
            var item = batch.items().get(index);
            Credentials authority = keyService.credentials(profile, item.authorityChainAddress());
            if (!authority.getAddress().equalsIgnoreCase(item.fromAddress())) {
                throw new IllegalStateException("derived authority key does not match claimed address");
            }
            BigInteger assetBalance = NATIVE_TOKEN.equalsIgnoreCase(batch.tokenContract()) ? web3j.ethGetBalance(
                    item.fromAddress(),
                    DefaultBlockParameterName.LATEST).send().getBalance() : rpc.tokenBalance(
                    web3j,
                    batch.tokenContract(),
                    item.fromAddress());
            if (assetBalance.compareTo(item.amountAtomic()) < 0) {
                throw new IllegalStateException("authority asset balance is lower than collection amount");
            }
            String code = web3j.ethGetCode(item.fromAddress(), DefaultBlockParameterName.LATEST).send().getCode();
            BigInteger authorityNonce = web3j.ethGetTransactionCount(
                    item.fromAddress(),
                    DefaultBlockParameterName.PENDING).send().getTransactionCount();
            boolean includeAuthorization;
            BigInteger operationNonce;
            if (code == null || "0x".equalsIgnoreCase(code) || "0x0".equalsIgnoreCase(code)) {
                includeAuthorization = true;
                operationNonce = BigInteger.ZERO;
                authorizations.add(authorizationService.authorize(config.chainId(), config.delegateAddress(), authorityNonce, authority));
            } else if (code.equalsIgnoreCase(delegationCode(config.delegateAddress()))) {
                includeAuthorization = false;
                operationNonce = rpc.operationNonce(web3j, item.fromAddress());
            } else {
                throw new IllegalStateException("authority has unapproved code/delegate: " + item.fromAddress());
            }
            Evm7702CollectionRequest request = new Evm7702CollectionRequest(
                    batchId,
                    BigInteger.valueOf(index),
                    item.fromAddress(),
                    config.collectorAddress(),
                    batch.tokenContract(),
                    batch.hotWallet(),
                    item.amountAtomic(),
                    operationNonce,
                    BigInteger.valueOf(signatureDeadline.getEpochSecond()),
                    DEFAULT_ITEM_GAS);
            request.requireNotExpired(chainTimestamp);
            requests.add(request);
            signatures.add(operationSigner.sign(config.chainId(), request, authority));
            preparedItems.add(new Evm7702CollectionCoordinator.PreparedItem(
                    index,
                    item.fromAddress(),
                    includeAuthorization,
                    includeAuthorization ? authorityNonce : null,
                    operationNonce,
                    signatureDeadline,
                    DEFAULT_ITEM_GAS.longValueExact()));
        }
        String calldata = contractCodec.encodeCollectBatch(requests, signatures);
        EvmFeeSupport.FeeQuote feeQuote = EvmFeeSupport.quote(web3j, profile);
        BigInteger priority = feeQuote.maxPriorityFeePerGas();
        BigInteger maxFee = feeQuote.maxFeePerGas();
        BigInteger relayerPendingNonce = web3j.ethGetTransactionCount(
                relayer.getAddress(),
                DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger estimated = rpc.estimateGas(
                http,
                relayer.getAddress(),
                config.collectorAddress(),
                calldata,
                priority,
                maxFee,
                authorizations);
        BigInteger gasLimit = new BigDecimal(estimated).multiply(config.gasLimitMultiplier()).setScale(
                0,
                RoundingMode.UP).toBigIntegerExact();
        BigInteger blockCap = new BigDecimal(latestBlock.getGasLimit()).multiply(config.blockGasRatio()).setScale(
                0,
                RoundingMode.DOWN).toBigIntegerExact();
        BigInteger configuredCap = BigInteger.valueOf(config.maxBatchGas());
        if (gasLimit.compareTo(configuredCap.min(blockCap)) > 0 || gasLimit.compareTo(MIN_ITEM_GAS) < 0) {
            throw new IllegalStateException("estimated batch gas exceeds configured/block safety limit");
        }
        return new Prepared(
                relayer,
                config.chainId(),
                relayerPendingNonce,
                priority,
                maxFee,
                gasLimit,
                estimated,
                config.collectorAddress(),
                calldata,
                List.copyOf(authorizations),
                List.copyOf(preparedItems));
    }

    private Evm7702CollectionCoordinator.SignedAttempt signPrepared(
            Web3j web3j,
            AccountChainProfile profile,
            Evm7702CollectionCoordinator.Batch batch,
            Prepared prepared,
            BigInteger reservedNonce) {
        var signed = transactionService.signBatch(
                prepared.chainId().longValueExact(),
                reservedNonce,
                prepared.maxPriorityFeePerGas(),
                prepared.maxFeePerGas(),
                prepared.gasLimit(),
                prepared.collectorAddress(),
                prepared.calldata(),
                prepared.authorizations(),
                prepared.relayer());
        String encrypted = crypto.encrypt(signed.rawTransaction());
        var attempt = new Evm7702CollectionCoordinator.PreparedAttempt(
                batch.tenantId(),
                batch.id(),
                prepared.estimatedGas().longValueExact(),
                prepared.gasLimit().longValueExact(),
                prepared.maxFeePerGas(),
                prepared.maxPriorityFeePerGas(),
                reservedNonce,
                signed.transactionHash(),
                Hash.sha3(prepared.calldata()),
                encrypted,
                "custody-v1",
                prepared.items());
        BigInteger reservedFeeAtomic = prepared.gasLimit().multiply(prepared.maxFeePerGas()).add(EvmFeeSupport.estimateSeparateL1Fee(
                web3j,
                profile,
                prepared.relayer().getAddress(),
                signed.rawTransaction())).add(EvmFeeSupport.estimateOperatorFee(
                web3j,
                profile,
                prepared.relayer().getAddress(),
                prepared.gasLimit()));
        send(() -> {
            EvmFeeSupport.requireBalance(web3j, prepared.relayer().getAddress(), reservedFeeAtomic, "EIP-7702 relayer");
            return null;
        });
        int nativeDecimals = nativeDecimals(profile);
        BigDecimal reservedFee = EvmFeeSupport.atomicToNative(reservedFeeAtomic, nativeDecimals, RoundingMode.UP);
        return new Evm7702CollectionCoordinator.SignedAttempt(signed, attempt, reservedFee);
    }

    private int nativeDecimals(AccountChainProfile profile) {
        var asset = chainAssetRepository.findActiveDefinition(
                profile.getChain(),
                profile.getNativeSymbol()).orElseThrow(() -> new IllegalStateException("missing active native chain_asset for " + profile.getChain()));
        if (!Boolean.TRUE.equals(asset.getNativeAsset()) || asset.getDecimals() == null) {
            throw new IllegalStateException("invalid native chain_asset for " + profile.getChain());
        }
        return asset.getDecimals();
    }

    private static String batchHash(java.util.UUID tenantId, java.util.UUID batchId) {
        return Numeric.toHexString(Hash.sha3((tenantId + ":" + batchId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private record Prepared(
            Credentials relayer,
            BigInteger chainId,
            BigInteger rpcPendingNonce,
            BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas,
            BigInteger gasLimit,
            BigInteger estimatedGas,
            String collectorAddress,
            String calldata,
            List<AuthorizationTuple> authorizations,
            List<Evm7702CollectionCoordinator.PreparedItem> items) {
    }

    private final EvmCollectionBatchRepository batches;
    private final EvmCollectionBatchItemRepository items;
    private final EvmCollectionBatchAttemptRepository attempts;
    private final Evm7702ConfigRepository configurations;

    public List<RuntimeTarget> listRuntimeTargets() {
        List<RuntimeTarget> result = new ArrayList<>();
        var definitions = configurations.listAll();
        for (var profile : chainProfileRepository.listProfiles(true)) {
            if (!"evm".equalsIgnoreCase(profile.getFamily())) {
                continue;
            }
            String chain = profile.getChain();
            String network = profile.getNetwork();
            var matching = definitions.stream().filter(row -> chain.equals(row.chain()) && network.equals(row.network())).toList();
            boolean active = matching.stream().anyMatch(row -> "ACTIVE".equals(row.status()));
            boolean managed = matching.stream().anyMatch(row -> java.util.Set.of("ACTIVE", "PAUSED").contains(row.status()));
            if (managed || batches.existsPending(chain, network)) {
                result.add(new RuntimeTarget(profile, active));
            }
        }
        return result.stream().sorted(java.util.Comparator.comparing((RuntimeTarget target) -> target.profile().getChain()).thenComparing(target -> target.profile().getNetwork())).toList();
    }

    public List<EvmBroadcastAttempt> listUnknownAttempts(String chain, String network, int limit) {
        List<UUID> ids = batches.listBroadcastUnknown(chain, network);
        return attempts.listUnknown(ids, limit);
    }

    public List<Evm7702CollectionCoordinator.PendingBatch> listPendingBatches(String chain, String network, int limit) {
        Map<Integer, Evm7702ConfigRepository.Configuration> versions = new HashMap<>();
        return batches.listPending(chain, network, limit).stream().map(row -> {
            int version = row.delegateVersion();
            var config = versions.computeIfAbsent(
                    version,
                    key -> configurations.find(chain, network, null, key).stream().findFirst().orElseThrow());
            return new Evm7702CollectionCoordinator.PendingBatch(
                    row.tenantId(),
                    row.id(),
                    row.canonicalTxHash(),
                    row.status(),
                    config.requiredConfirmations(),
                    config.collectorAddress());
        }).toList();
    }

    public record RuntimeTarget(AccountChainProfile profile, boolean active) {
    }
}
