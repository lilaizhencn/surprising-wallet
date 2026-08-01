package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.chain.evm.Evm7702AuthorizationService;
import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.Evm7702CollectionRequest;
import com.surprising.wallet.chain.evm.Evm7702ContractCodec;
import com.surprising.wallet.chain.evm.Evm7702OperationSigner;
import com.surprising.wallet.chain.evm.Evm7702ReceiptParser;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.config.AccountSecp256k1KeyService;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.bitcoinj.crypto.ECKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.surprising.wallet.coordinator.Evm7702CollectionCoordinator;
import com.surprising.wallet.repository.Evm7702CollectionRepository;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Service
public class Evm7702CollectionWorkflowService {
    /**
     * 保存 {@code log}，用于承载当前对象的运行配置或业务数据。
     */
    private static final Logger log = LoggerFactory.getLogger(Evm7702CollectionWorkflowService.class);
    /**
     * 定义 {@code MIN_ITEM_GAS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigInteger MIN_ITEM_GAS = BigInteger.valueOf(60_000L);
    /**
     * 定义 {@code DEFAULT_ITEM_GAS} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigInteger DEFAULT_ITEM_GAS = BigInteger.valueOf(180_000L);
    /**
     * 定义 {@code NATIVE_TOKEN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String NATIVE_TOKEN = "0x0000000000000000000000000000000000000000";
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Evm7702CollectionRepository repository;
    /**
     * 保存 {@code coordinator}，用于承载当前对象的运行配置或业务数据。
     */
    private final Evm7702CollectionCoordinator coordinator;
    /**
     * 保存 {@code chainRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository chainRepository;
    /**
     * 保存 {@code rpcNodes}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainRpcNodeService rpcNodes;
    /**
     * 保存 {@code keyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final AccountSecp256k1KeyService keyService;
    /**
     * 保存 {@code crypto}，用于承载当前对象的运行配置或业务数据。
     */
    private final CustodyCryptoService crypto;
    /**
     * 保存 {@code runtimeConfig}，用于保存运行配置和策略参数。
     */
    private final WalletRuntimeConfigService runtimeConfig;
    /**
     * 保存 {@code authorizationService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Evm7702AuthorizationService authorizationService = new Evm7702AuthorizationService();
    /**
     * 保存 {@code operationSigner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Evm7702OperationSigner operationSigner = new Evm7702OperationSigner();
    /**
     * 保存 {@code contractCodec}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Evm7702ContractCodec contractCodec = new Evm7702ContractCodec();
    /**
     * 保存 {@code transactionService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Evm7702BatchTransactionService transactionService = new Evm7702BatchTransactionService();
    /**
     * 保存 {@code receiptParser}，用于承载当前对象的运行配置或业务数据。
     */
    private final Evm7702ReceiptParser receiptParser = new Evm7702ReceiptParser();
    /**
     * 保存 {@code running}，用于承载当前对象的运行配置或业务数据。
     */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 构造 {@code Evm7702CollectionWorkflowService}，初始化该组件运行所需的状态和依赖。
     */
    public Evm7702CollectionWorkflowService(
            Evm7702CollectionRepository repository,
            Evm7702CollectionCoordinator coordinator,
            ChainJdbcRepository chainRepository,
            ChainRpcNodeService rpcNodes,
            AccountSecp256k1KeyService keyService,
            CustodyCryptoService crypto,
            WalletRuntimeConfigService runtimeConfig) {
        this.repository = repository;
        this.coordinator = coordinator;
        this.chainRepository = chainRepository;
        this.rpcNodes = rpcNodes;
        this.keyService = keyService;
        this.crypto = crypto;
        this.runtimeConfig = runtimeConfig;
    }
    /**
     * 执行或处理 {@code run} 对应的业务流程，并维护状态和异常边界。
     */
    public void run() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (Evm7702CollectionRepository.RuntimeTarget target : repository.listRuntimeTargets()) {
                try {
                    AccountChainProfile profile = chainRepository.findProfileByChain(target.chain())
                            .orElseThrow(() -> new IllegalStateException(
                                    "enabled EVM profile is missing for " + target.chain()));
                    if (!"evm".equalsIgnoreCase(profile.getFamily())
                            || !profile.getNetwork().equalsIgnoreCase(target.network())) {
                        throw new IllegalStateException("EIP-7702 target/profile network mismatch");
                    }
                    recoverUnknown(profile);
                    confirm(profile);
                    if (target.active() && runtimeConfig.isTaskEnabled(
                            profile.getChain(), WalletRuntimeConfigService.TASK_COLLECTION)) {
                        processOne(profile);
                    }
                } catch (RuntimeException e) {
                    log.error("EIP-7702 collection cycle failed for {}/{}: {}",
                            target.chain(), target.network(), e.getMessage(), e);
                }
            }
        } finally {
            running.set(false);
        }
    }

        /**
     * 执行 {@code recoverUnknown} 对应的签名或签名恢复，保证交易数据可验证。
     */
    public void recoverUnknown(AccountChainProfile profile) {
        List<Evm7702CollectionRepository.UnknownAttempt> attempts = repository.listUnknownAttempts(
                profile.getChain(), profile.getNetwork(), 20);
        if (attempts.isEmpty()) return;
        ChainRpcNode node = requireRpcNode(profile);
        Web3j web3j = Web3j.build(http(node));
        try {
            for (Evm7702CollectionRepository.UnknownAttempt attempt : attempts) {
                try {
                    if (isTransactionKnown(web3j, attempt.txHash())) {
                        repository.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                        continue;
                    }
                    String rawTransaction = crypto.decrypt(attempt.signedTxCiphertext());
                    String localHash = Hash.sha3(rawTransaction);
                    if (!localHash.equalsIgnoreCase(attempt.txHash())) {
                        repository.markRecoveryError(attempt, "OUTBOX_HASH_MISMATCH",
                                "decrypted signed transaction does not match persisted tx hash");
                        log.error("EIP-7702 outbox hash mismatch for tenant={} batch={}",
                                attempt.tenantId(), attempt.batchId());
                        continue;
                    }
                    repository.recordRecoveryAttempt(attempt);
                    EthSendTransaction sent = web3j.ethSendRawTransaction(rawTransaction).send();
                    if (!sent.hasError()) {
                        if (!attempt.txHash().equalsIgnoreCase(sent.getTransactionHash())) {
                            throw new IllegalStateException("RPC transaction hash differs from persisted hash");
                        }
                        repository.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                    } else if (isTransactionKnown(web3j, attempt.txHash())) {
                        repository.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                    } else {
                        repository.markRecoveryError(attempt, "REBROADCAST_FAILED",
                                sent.getError().getMessage());
                    }
                } catch (Exception e) {
                    repository.markRecoveryError(attempt, "RECOVERY_UNCERTAIN", e.getMessage());
                    log.error("EIP-7702 recovery failed for tenant={} batch={}: {}",
                            attempt.tenantId(), attempt.batchId(), e.getMessage(), e);
                }
            }
        } finally {
            web3j.shutdown();
        }
    }
    /**
     * 执行或处理 {@code processOne} 对应的业务流程，并维护状态和异常边界。
     */
    public Optional<String> processOne(AccountChainProfile profile) {
        Evm7702CollectionRepository.Batch batch = repository
                .claimNextBatch(profile.getChain(), profile.getNetwork()).orElse(null);
        if (batch == null) return Optional.empty();
        boolean outboxPersisted = false;
        try {
            ChainRpcNode node = requireRpcNode(profile);
            HttpService http = http(node);
            Web3j web3j = Web3j.build(http);
            try {
                Prepared prepared = prepare(web3j, http, profile, batch);
                Evm7702BatchTransactionService.SignedBatchTransaction signed =
                        coordinator.persistSignedAttempt(
                                batch, prepared.relayer().getAddress(), prepared.rpcPendingNonce(),
                                reservedNonce -> signPrepared(
                                        web3j, profile, batch, prepared, reservedNonce));
                outboxPersisted = true;
                EthSendTransaction sent = web3j.ethSendRawTransaction(
                        signed.rawTransaction()).send();
                if (sent.hasError()) {
                    throw new IllegalStateException("eth_sendRawTransaction failed: "
                            + sent.getError().getMessage());
                }
                if (!signed.transactionHash().equalsIgnoreCase(sent.getTransactionHash())) {
                    throw new IllegalStateException("RPC transaction hash differs from local signed hash");
                }
                repository.markSubmitted(
                        batch.tenantId(), batch.id(), signed.transactionHash());
                return Optional.of(signed.transactionHash());
            } finally {
                web3j.shutdown();
            }
        } catch (Exception e) {
            if (outboxPersisted) {
                repository.markBroadcastUnknown(
                        batch.tenantId(), batch.id(), "BROADCAST_UNCERTAIN", e.getMessage());
            } else {
                repository.releaseUnbroadcastBatch(batch, "PREPARATION_FAILED", e.getMessage());
            }
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("failed to process EIP-7702 batch", e);
        }
    }
    /**
     * 处理 {@code confirm} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public void confirm(AccountChainProfile profile) {
        ChainRpcNode node = requireRpcNode(profile);
        Web3j web3j = Web3j.build(http(node));
        try {
            BigInteger latest = send(() -> web3j.ethBlockNumber().send().getBlockNumber());
            for (Evm7702CollectionRepository.PendingBatch batch
                    : repository.listPendingBatches(profile.getChain(), profile.getNetwork(), 100)) {
                Optional<EvmTransactionReceipt> receipt = send(
                        () -> transactionReceipt(http(node), batch.txHash()));
                if (receipt.isEmpty() || receipt.get().getBlockNumber() == null) continue;
                BigInteger confirmations = latest.subtract(receipt.get().getBlockNumber()).add(BigInteger.ONE);
                if (confirmations.compareTo(BigInteger.valueOf(batch.requiredConfirmations())) < 0) continue;
                EvmTransactionReceipt value = receipt.get();
                EthBlock canonical = send(() -> web3j.ethGetBlockByHash(value.getBlockHash(), false).send());
                if (canonical.getBlock() == null
                        || !canonical.getBlock().getHash().equalsIgnoreCase(value.getBlockHash())) {
                    throw new IllegalStateException("receipt block is no longer canonical");
                }
                List<Evm7702CollectionRepository.BatchItemIdentity> identities =
                        repository.listBatchItemIdentities(batch.tenantId(), batch.batchId());
                byte[] batchId = Numeric.hexStringToByteArray(batchHash(batch.tenantId(), batch.batchId()));
                Evm7702ReceiptParser.ParsedReceipt parsed = receiptParser.parse(
                        value, batch.collectorAddress(), batchId,
                        identities.stream().map(identity -> new Evm7702ReceiptParser.ExpectedTransfer(
                                identity.authority(), identity.token(), identity.recipient(), identity.amount()))
                                .toList());
                BigInteger effectiveGasPrice = value.getEffectiveGasPrice() == null
                        ? BigInteger.ZERO : Numeric.decodeQuantity(value.getEffectiveGasPrice());
                EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                        web3j, profile, value.getFrom(), value.getGasUsed(), effectiveGasPrice,
                        value.getBlockNumber(), value.getL1Fee(), value.getGasUsedForL1(),
                        value.getOperatorFeeScalar(), value.getOperatorFeeConstant());
                coordinator.complete(
                        profile.getChain(), batch, batch.txHash(), value.getGasUsed(), effectiveGasPrice,
                        fee.executionFee(), fee.l1Fee(), fee.operatorFee(),
                        value.getBlockNumber(), value.getBlockHash(), parsed.items());
            }
        } finally {
            web3j.shutdown();
        }
    }

    /**
     * 执行 {@code transactionReceipt} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Optional<EvmTransactionReceipt> transactionReceipt(
            HttpService http, String txHash) throws Exception {
        EvmReceiptResponse response = new Request<>(
                "eth_getTransactionReceipt", List.of(txHash), http,
                EvmReceiptResponse.class).send();
        if (response.hasError()) {
            throw new IllegalStateException("eth_getTransactionReceipt failed: "
                    + response.getError().getMessage());
        }
        return Optional.ofNullable(response.getResult());
    }

    /**
     * 执行 {@code prepare} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Prepared prepare(Web3j web3j, HttpService http, AccountChainProfile profile,
                             Evm7702CollectionRepository.Batch batch) throws Exception {
        var config = batch.config();
        BigInteger rpcChainId = web3j.ethChainId().send().getChainId();
        if (!rpcChainId.equals(config.chainId()) || !rpcChainId.equals(BigInteger.valueOf(profile.getChainId()))) {
            throw new IllegalStateException("RPC/config/profile chainId mismatch");
        }
        requireCodeHash(web3j, config.delegateAddress(), config.delegateCodeHash(), "delegate");
        requireCodeHash(web3j, config.collectorAddress(), config.collectorCodeHash(), "collector");
        Credentials relayer = credentials(profile, config.relayerChainAddress());
        if (!relayer.getAddress().equalsIgnoreCase(config.relayerAddress())) {
            throw new IllegalStateException("derived relayer key does not match configuration");
        }

        byte[] batchId = Numeric.hexStringToByteArray(batch.batchHash());
        List<Evm7702CollectionRequest> requests = new ArrayList<>();
        List<byte[]> signatures = new ArrayList<>();
        List<AuthorizationTuple> authorizations = new ArrayList<>();
        List<Evm7702CollectionRepository.PreparedItem> preparedItems = new ArrayList<>();
        for (int index = 0; index < batch.items().size(); index++) {
            var item = batch.items().get(index);
            Credentials authority = credentials(profile, item.authorityChainAddress());
            if (!authority.getAddress().equalsIgnoreCase(item.fromAddress())) {
                throw new IllegalStateException("derived authority key does not match claimed address");
            }
            BigInteger assetBalance = NATIVE_TOKEN.equalsIgnoreCase(batch.tokenContract())
                    ? web3j.ethGetBalance(item.fromAddress(), DefaultBlockParameterName.LATEST)
                            .send().getBalance()
                    : tokenBalance(web3j, batch.tokenContract(), item.fromAddress());
            if (assetBalance.compareTo(item.amountAtomic()) < 0) {
                throw new IllegalStateException("authority asset balance is lower than collection amount");
            }
            String code = web3j.ethGetCode(item.fromAddress(), DefaultBlockParameterName.LATEST).send().getCode();
            BigInteger authorityNonce = web3j.ethGetTransactionCount(
                    item.fromAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            boolean includeAuthorization;
            BigInteger operationNonce;
            if (code == null || "0x".equalsIgnoreCase(code) || "0x0".equalsIgnoreCase(code)) {
                includeAuthorization = true;
                operationNonce = BigInteger.ZERO;
                authorizations.add(authorizationService.authorize(
                        config.chainId(), config.delegateAddress(), authorityNonce, authority));
            } else if (code.equalsIgnoreCase(delegationCode(config.delegateAddress()))) {
                includeAuthorization = false;
                operationNonce = operationNonce(web3j, item.fromAddress());
            } else {
                throw new IllegalStateException("authority has unapproved code/delegate: " + item.fromAddress());
            }
            Evm7702CollectionRequest request = new Evm7702CollectionRequest(
                    batchId, BigInteger.valueOf(index), item.fromAddress(), config.collectorAddress(),
                    batch.tokenContract(), batch.hotWallet(), item.amountAtomic(), operationNonce,
                    BigInteger.valueOf(batch.signatureDeadline().getEpochSecond()), DEFAULT_ITEM_GAS);
            request.requireNotExpired(Instant.now());
            requests.add(request);
            signatures.add(operationSigner.sign(config.chainId(), request, authority));
            preparedItems.add(new Evm7702CollectionRepository.PreparedItem(
                    index, item.fromAddress(), includeAuthorization,
                    includeAuthorization ? authorityNonce : null, operationNonce,
                    batch.signatureDeadline(), DEFAULT_ITEM_GAS.longValueExact()));
        }
        String calldata = contractCodec.encodeCollectBatch(requests, signatures);
        EvmFeeSupport.FeeQuote feeQuote = EvmFeeSupport.quote(web3j, profile);
        BigInteger priority = feeQuote.maxPriorityFeePerGas();
        BigInteger maxFee = feeQuote.maxFeePerGas();
        BigInteger relayerPendingNonce = web3j.ethGetTransactionCount(
                relayer.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();

        BigInteger estimated = estimateGas(
                http, relayer.getAddress(), config.collectorAddress(), calldata,
                priority, maxFee, authorizations);
        BigInteger gasLimit = new BigDecimal(estimated)
                .multiply(config.gasLimitMultiplier()).setScale(0, RoundingMode.UP).toBigIntegerExact();
        EthBlock.Block latestBlock = web3j.ethGetBlockByNumber(
                DefaultBlockParameterName.LATEST, false).send().getBlock();
        BigInteger blockCap = new BigDecimal(latestBlock.getGasLimit())
                .multiply(config.blockGasRatio()).setScale(0, RoundingMode.DOWN).toBigIntegerExact();
        BigInteger configuredCap = BigInteger.valueOf(config.maxBatchGas());
        if (gasLimit.compareTo(configuredCap.min(blockCap)) > 0 || gasLimit.compareTo(MIN_ITEM_GAS) < 0) {
            throw new IllegalStateException("estimated batch gas exceeds configured/block safety limit");
        }

        return new Prepared(
                relayer, config.chainId(), relayerPendingNonce, priority, maxFee, gasLimit,
                estimated, config.collectorAddress(), calldata, List.copyOf(authorizations),
                List.copyOf(preparedItems));
    }

    /**
     * 为 {@code signPrepared} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    private Evm7702CollectionCoordinator.SignedAttempt signPrepared(
            Web3j web3j, AccountChainProfile profile,
            Evm7702CollectionRepository.Batch batch, Prepared prepared, BigInteger reservedNonce) {
        var signed = transactionService.signBatch(
                prepared.chainId().longValueExact(), reservedNonce,
                prepared.maxPriorityFeePerGas(), prepared.maxFeePerGas(), prepared.gasLimit(),
                prepared.collectorAddress(), prepared.calldata(), prepared.authorizations(),
                prepared.relayer());
        String encrypted = crypto.encrypt(signed.rawTransaction());
        var attempt = new Evm7702CollectionRepository.PreparedAttempt(
                batch.tenantId(), batch.id(), prepared.estimatedGas().longValueExact(),
                prepared.gasLimit().longValueExact(), prepared.maxFeePerGas(),
                prepared.maxPriorityFeePerGas(), reservedNonce, signed.transactionHash(),
                Hash.sha3(prepared.calldata()), encrypted, "custody-v1", prepared.items());
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
        int nativeDecimals = nativeDecimals(profile);
        BigDecimal reservedFee = EvmFeeSupport.atomicToNative(
                reservedFeeAtomic, nativeDecimals, RoundingMode.UP);
        return new Evm7702CollectionCoordinator.SignedAttempt(signed, attempt, reservedFee);
    }

    /**
     * 执行 {@code nativeDecimals} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private int nativeDecimals(AccountChainProfile profile) {
        var asset = chainRepository.findAsset(profile.getChain(), profile.getNativeSymbol())
                .orElseThrow(() -> new IllegalStateException(
                        "missing active native chain_asset for " + profile.getChain()));
        if (!Boolean.TRUE.equals(asset.getNativeAsset()) || asset.getDecimals() == null) {
            throw new IllegalStateException(
                    "invalid native chain_asset for " + profile.getChain());
        }
        return asset.getDecimals();
    }

    /**
     * 计算或估算 {@code estimateGas} 对应的金额、费用或资源消耗。
     */
    private BigInteger estimateGas(HttpService http, String from, String to, String data,
                                   BigInteger priority, BigInteger maxFee,
                                   List<AuthorizationTuple> authorizations) throws Exception {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("from", from);
        tx.put("to", to);
        tx.put("value", "0x0");
        tx.put("data", data);
        tx.put("maxPriorityFeePerGas", Numeric.encodeQuantity(priority));
        tx.put("maxFeePerGas", Numeric.encodeQuantity(maxFee));
        if (!authorizations.isEmpty()) {
            tx.put("type", "0x4");
            tx.put("authorizationList", authorizations.stream().map(this::authorizationJson).toList());
        } else {
            tx.put("type", "0x2");
        }
        QuantityResponse response = new Request<>(
                "eth_estimateGas", List.of(tx), http, QuantityResponse.class).send();
        if (response.hasError()) {
            throw new IllegalStateException("eth_estimateGas failed: " + response.getError().getMessage());
        }
        return Numeric.decodeQuantity(response.getResult());
    }
    /**
     * 执行 {@code authorizationJson} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Map<String, String> authorizationJson(AuthorizationTuple tuple) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("chainId", Numeric.encodeQuantity(tuple.getChainId()));
        result.put("address", tuple.getAddress());
        result.put("nonce", Numeric.encodeQuantity(tuple.getNonce()));
        result.put("yParity", Numeric.encodeQuantity(tuple.getYParity()));
        result.put("r", Numeric.encodeQuantity(tuple.getR()));
        result.put("s", Numeric.encodeQuantity(tuple.getS()));
        return result;
    }
    /**
     * 编码 {@code tokenBalance} 对应的数据，生成链上或接口所需的表示。
     */
    private BigInteger tokenBalance(Web3j web3j, String token, String owner) throws Exception {
        Function function = new Function(
                "balanceOf", List.of(new Address(owner)), List.of(new TypeReference<Uint256>() { }));
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(owner, token, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        if (call.hasError()) throw new IllegalStateException("token balanceOf failed: " + call.getError().getMessage());
        List<Type> values = FunctionReturnDecoder.decode(call.getValue(), function.getOutputParameters());
        if (values.size() != 1) throw new IllegalStateException("token balanceOf returned malformed data");
        return (BigInteger) values.getFirst().getValue();
    }
    /**
     * 执行 {@code operationNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private BigInteger operationNonce(Web3j web3j, String authority) throws Exception {
        Function function = new Function("operationNonce", List.of(), List.of(new TypeReference<Uint256>() { }));
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(authority, authority, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        if (call.hasError()) throw new IllegalStateException("operationNonce failed: " + call.getError().getMessage());
        return (BigInteger) FunctionReturnDecoder.decode(call.getValue(), function.getOutputParameters())
                .getFirst().getValue();
    }
    /**
     * 判断 {@code isTransactionKnown} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isTransactionKnown(Web3j web3j, String txHash) throws Exception {
        if (web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt().isPresent()) {
            return true;
        }
        return web3j.ethGetTransactionByHash(txHash).send().getTransaction().isPresent();
    }
    /**
     * 校验 {@code requireCodeHash} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requireCodeHash(Web3j web3j, String address, String expected, String label) throws Exception {
        String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
        if (code == null || "0x".equalsIgnoreCase(code)) {
            throw new IllegalStateException(label + " runtime code is missing");
        }
        String actual = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code)));
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException(label + " runtime code hash mismatch");
        }
    }

    /**
     * 执行 {@code credentials} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Credentials credentials(AccountChainProfile profile,
                                    com.surprising.wallet.common.chain.ChainAddressRecord address) {
        ECKey key = keyService.key(profile, address);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(key.getPrivKey(), 64));
    }
    /**
     * 校验 {@code requireRpcNode} 对应的前置条件，不满足时抛出明确异常。
     */
    private ChainRpcNode requireRpcNode(AccountChainProfile profile) {
        List<ChainRpcNode> nodes = rpcNodes.enabledNodes(profile.getChain(), profile.getNetwork(), "rpc");
        if (nodes.isEmpty()) {
            throw new IllegalStateException("no enabled RPC node for "
                    + profile.getChain() + "/" + profile.getNetwork());
        }
        return nodes.getFirst();
    }
    /**
     * 执行 {@code http} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private HttpService http(ChainRpcNode node) {
        HttpService service = new HttpService(node.getRpcUrl());
        service.addHeaders(rpcNodes.authHeaders(node));
        return service;
    }
    /**
     * 执行 {@code delegationCode} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String delegationCode(String delegate) {
        return "0xef0100" + Numeric.cleanHexPrefix(delegate).toLowerCase();
    }
    /**
     * 执行 {@code batchHash} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String batchHash(java.util.UUID tenantId, java.util.UUID batchId) {
        return Numeric.toHexString(Hash.sha3(
                (tenantId + ":" + batchId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    /**
     * 发送或广播 {@code send} 对应的链上请求，并返回节点处理结果。
     */
    private static <T> T send(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
    }

    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    @FunctionalInterface
    private interface CheckedSupplier<T> {
        /**
         * 获取或查询 {@code get} 对应的数据，供调用方读取当前状态。
         */
        T get() throws Exception;
    }
    /**
     * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
     */
    public static class QuantityResponse extends Response<String> {
    }
    /**
     * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
     */
    public static class EvmReceiptResponse extends Response<EvmTransactionReceipt> {
    }
    /**
     * 负责 EVM 链交易、费用、扫描或 EIP-7702 相关处理。
     */
    public static class EvmTransactionReceipt extends TransactionReceipt {
        /**
         * 保存 {@code l1Fee}，用于保存金额、费用或链上执行状态。
         */
        private String l1Fee;
        /**
         * 保存 {@code gasUsedForL1}，用于保存金额、费用或链上执行状态。
         */
        private String gasUsedForL1;
        /**
         * 保存 {@code operatorFeeScalar}，用于保存金额、费用或链上执行状态。
         */
        private String operatorFeeScalar;
        /**
         * 保存 {@code operatorFeeConstant}，用于保存金额、费用或链上执行状态。
         */
        private String operatorFeeConstant;

        /**
         * 获取或查询 {@code getL1Fee} 对应的数据，供调用方读取当前状态。
         */
        public String getL1Fee() { return l1Fee; }
        /**
         * 设置或更新 {@code setL1Fee} 对应的状态，并保持相关业务字段一致。
         */
        public void setL1Fee(String l1Fee) { this.l1Fee = l1Fee; }
        /**
         * 获取或查询 {@code getGasUsedForL1} 对应的数据，供调用方读取当前状态。
         */
        public String getGasUsedForL1() { return gasUsedForL1; }
        /**
         * 设置或更新 {@code setGasUsedForL1} 对应的状态，并保持相关业务字段一致。
         */
        public void setGasUsedForL1(String gasUsedForL1) { this.gasUsedForL1 = gasUsedForL1; }
        /**
         * 获取或查询 {@code getOperatorFeeScalar} 对应的数据，供调用方读取当前状态。
         */
        public String getOperatorFeeScalar() { return operatorFeeScalar; }
        /**
         * 设置或更新 {@code setOperatorFeeScalar} 对应的状态，并保持相关业务字段一致。
         */
        public void setOperatorFeeScalar(String operatorFeeScalar) {
            this.operatorFeeScalar = operatorFeeScalar;
        }
        /**
         * 获取或查询 {@code getOperatorFeeConstant} 对应的数据，供调用方读取当前状态。
         */
        public String getOperatorFeeConstant() { return operatorFeeConstant; }
        /**
         * 设置或更新 {@code setOperatorFeeConstant} 对应的状态，并保持相关业务字段一致。
         */
        public void setOperatorFeeConstant(String operatorFeeConstant) {
            this.operatorFeeConstant = operatorFeeConstant;
        }
    }

    private record Prepared(
            Credentials relayer, BigInteger chainId, BigInteger rpcPendingNonce,
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
            BigInteger gasLimit, BigInteger estimatedGas, String collectorAddress,
            String calldata, List<AuthorizationTuple> authorizations,
            List<Evm7702CollectionRepository.PreparedItem> items) {
    }
}
