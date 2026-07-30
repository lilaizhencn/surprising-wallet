package com.surprising.wallet.account.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.custody.service.CustodyCryptoService;
import com.surprising.wallet.chain.evm.Evm7702AuthorizationService;
import com.surprising.wallet.chain.evm.Evm7702BatchTransactionService;
import com.surprising.wallet.chain.evm.Evm7702PayoutCodec;
import com.surprising.wallet.chain.evm.Evm7702PayoutItem;
import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import com.surprising.wallet.chain.evm.Evm7702PayoutRequest;
import com.surprising.wallet.chain.evm.Evm7702PayoutSigner;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.config.AccountSecp256k1KeyService;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.bitcoinj.crypto.ECKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
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

import com.surprising.wallet.account.repository.Evm7702CollectionRepository;
import com.surprising.wallet.account.coordinator.Evm7702WithdrawalCoordinator;
import com.surprising.wallet.account.repository.Evm7702WithdrawalRepository;

/**
 * Tenant-isolated EIP-7702 payout worker. One relayer transaction can execute
 * multiple withdrawals from one tenant hot wallet, while receipt events settle
 * every withdrawal independently.
 */
@Service
public class Evm7702WithdrawalWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(Evm7702WithdrawalWorkflowService.class);
    private static final String NATIVE_TOKEN = "0x0000000000000000000000000000000000000000";
    private static final BigInteger ITEM_GAS = BigInteger.valueOf(120_000L);
    private static final BigInteger MIN_BATCH_GAS = BigInteger.valueOf(80_000L);
    private final Evm7702WithdrawalRepository repository;
    private final Evm7702WithdrawalCoordinator coordinator;
    private final ChainJdbcRepository chainRepository;
    private final ChainRpcNodeService rpcNodes;
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
    public void run() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (Evm7702WithdrawalRepository.RuntimeTarget target : repository.listRuntimeTargets()) {
                try {
                    AccountChainProfile profile = chainRepository.findProfileByChain(target.chain())
                            .orElseThrow(() -> new IllegalStateException(
                                    "enabled EVM profile is missing for " + target.chain()));
                    if (!"evm".equalsIgnoreCase(profile.getFamily())
                            || !profile.getNetwork().equalsIgnoreCase(target.network())) {
                        throw new IllegalStateException("EIP-7702 payout target/profile network mismatch");
                    }
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
    public void recoverUnknown(AccountChainProfile profile) {
        List<Evm7702WithdrawalRepository.UnknownAttempt> attempts = repository.listUnknownAttempts(
                profile.getChain(), profile.getNetwork(), 20);
        if (attempts.isEmpty()) return;
        ChainRpcNode node = requireRpcNode(profile);
        Web3j web3j = Web3j.build(http(node));
        try {
            for (Evm7702WithdrawalRepository.UnknownAttempt attempt : attempts) {
                try {
                    if (isTransactionKnown(web3j, attempt.txHash())) {
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
                    } else if (isTransactionKnown(web3j, attempt.txHash())) {
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
            } else {
                repository.releaseUnbroadcastBatch(batch, "PREPARATION_FAILED", e.getMessage());
            }
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("failed to process EIP-7702 payout batch", e);
        }
    }
    public void confirm(AccountChainProfile profile) {
        ChainRpcNode node = requireRpcNode(profile);
        HttpService http = http(node);
        Web3j web3j = Web3j.build(http);
        try {
            BigInteger latest = send(() -> web3j.ethBlockNumber().send().getBlockNumber());
            for (Evm7702WithdrawalRepository.PendingBatch batch
                    : repository.listPendingBatches(profile.getChain(), profile.getNetwork(), 100)) {
                Optional<EvmTransactionReceipt> found = send(
                        () -> transactionReceipt(http, batch.txHash()));
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
        requireCodeHash(web3j, config.payoutDelegateAddress(),
                config.payoutDelegateCodeHash(), "payout delegate");
        Credentials relayer = credentials(profile, config.relayerChainAddress());
        if (!relayer.getAddress().equalsIgnoreCase(config.relayerAddress())) {
            throw new IllegalStateException("derived relayer key does not match configuration");
        }
        Credentials authority = credentials(profile, batch.hotChainAddress());
        if (!authority.getAddress().equalsIgnoreCase(batch.hotWallet())) {
            throw new IllegalStateException("derived hot-wallet key does not match payout authority");
        }
        BigInteger totalAmount = batch.items().stream()
                .map(Evm7702WithdrawalRepository.ClaimedItem::amountAtomic)
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger balance = NATIVE_TOKEN.equalsIgnoreCase(batch.tokenContract())
                ? web3j.ethGetBalance(batch.hotWallet(), DefaultBlockParameterName.LATEST)
                        .send().getBalance()
                : tokenBalance(web3j, batch.tokenContract(), batch.hotWallet());
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
        if (code == null || "0x".equalsIgnoreCase(code) || "0x0".equalsIgnoreCase(code)) {
            includeAuthorization = true;
            operationNonce = BigInteger.ZERO;
            authorizations = List.of(authorizationService.authorize(
                    config.chainId(), config.payoutDelegateAddress(), authorityNonce, authority));
        } else if (code.equalsIgnoreCase(delegationCode(config.payoutDelegateAddress()))) {
            includeAuthorization = false;
            operationNonce = operationNonce(web3j, batch.hotWallet());
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
                BigInteger.valueOf(batch.signatureDeadline().getEpochSecond()));
        request.requireNotExpired(Instant.now());
        String calldata = payoutCodec.encode(
                request, payoutSigner.sign(config.chainId(), request, authority));
        EvmFeeSupport.FeeQuote feeQuote = EvmFeeSupport.quote(web3j, profile);
        BigInteger priority = feeQuote.maxPriorityFeePerGas();
        BigInteger maxFee = feeQuote.maxFeePerGas();
        BigInteger relayerPendingNonce = web3j.ethGetTransactionCount(
                relayer.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger estimated = estimateGas(
                http, relayer.getAddress(), batch.hotWallet(), calldata,
                priority, maxFee, authorizations);
        BigInteger gasLimit = new BigDecimal(estimated).multiply(config.gasLimitMultiplier())
                .setScale(0, RoundingMode.UP).toBigIntegerExact();
        EthBlock.Block latestBlock = web3j.ethGetBlockByNumber(
                DefaultBlockParameterName.LATEST, false).send().getBlock();
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
                operationNonce, batch.signatureDeadline());
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
        var asset = chainRepository.findAsset(profile.getChain(), profile.getNativeSymbol())
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

    private Optional<EvmTransactionReceipt> transactionReceipt(
            HttpService http, String txHash) throws Exception {
        EvmReceiptResponse response = new Request<>(
                "eth_getTransactionReceipt", List.of(txHash), http,
                EvmReceiptResponse.class).send();
        if (response.hasError()) {
            throw new IllegalStateException(
                    "eth_getTransactionReceipt failed: " + response.getError().getMessage());
        }
        return Optional.ofNullable(response.getResult());
    }
    private BigInteger tokenBalance(Web3j web3j, String token, String owner) throws Exception {
        Function function = new Function(
                "balanceOf", List.of(new Address(owner)),
                List.of(new TypeReference<Uint256>() { }));
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(owner, token, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        if (call.hasError()) {
            throw new IllegalStateException("token balanceOf failed: " + call.getError().getMessage());
        }
        List<Type> values = FunctionReturnDecoder.decode(call.getValue(), function.getOutputParameters());
        if (values.size() != 1) {
            throw new IllegalStateException("token balanceOf returned malformed data");
        }
        return (BigInteger) values.getFirst().getValue();
    }
    private BigInteger operationNonce(Web3j web3j, String authority) throws Exception {
        Function function = new Function(
                "operationNonce", List.of(), List.of(new TypeReference<Uint256>() { }));
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(
                        authority, authority, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        if (call.hasError()) {
            throw new IllegalStateException("operationNonce failed: " + call.getError().getMessage());
        }
        List<Type> values = FunctionReturnDecoder.decode(call.getValue(), function.getOutputParameters());
        if (values.size() != 1) {
            throw new IllegalStateException("operationNonce returned malformed data");
        }
        return (BigInteger) values.getFirst().getValue();
    }

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
        if (authorizations.isEmpty()) {
            tx.put("type", "0x2");
        } else {
            tx.put("type", "0x4");
            tx.put("authorizationList", authorizations.stream()
                    .map(this::authorizationJson).toList());
        }
        QuantityResponse response = new Request<>(
                "eth_estimateGas", List.of(tx), http, QuantityResponse.class).send();
        if (response.hasError()) {
            throw new IllegalStateException(
                    "eth_estimateGas failed: " + response.getError().getMessage());
        }
        return Numeric.decodeQuantity(response.getResult());
    }
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
    private boolean isTransactionKnown(Web3j web3j, String txHash) throws Exception {
        if (web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt().isPresent()) {
            return true;
        }
        return web3j.ethGetTransactionByHash(txHash).send().getTransaction().isPresent();
    }

    private void requireCodeHash(
            Web3j web3j, String address, String expected, String label) throws Exception {
        String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
        if (code == null || "0x".equalsIgnoreCase(code)) {
            throw new IllegalStateException(label + " runtime code is missing");
        }
        String actual = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code)));
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException(label + " runtime code hash mismatch");
        }
    }

    private Credentials credentials(
            AccountChainProfile profile,
            com.surprising.wallet.common.chain.ChainAddressRecord address) {
        ECKey key = keyService.key(profile, address);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(key.getPrivKey(), 64));
    }
    private ChainRpcNode requireRpcNode(AccountChainProfile profile) {
        List<ChainRpcNode> nodes = rpcNodes.enabledNodes(
                profile.getChain(), profile.getNetwork(), "rpc");
        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                    "no enabled RPC node for " + profile.getChain() + "/" + profile.getNetwork());
        }
        return nodes.getFirst();
    }
    private HttpService http(ChainRpcNode node) {
        HttpService service = new HttpService(node.getRpcUrl());
        service.addHeaders(rpcNodes.authHeaders(node));
        return service;
    }
    private static String delegationCode(String delegate) {
        return "0xef0100" + Numeric.cleanHexPrefix(delegate).toLowerCase();
    }
    private static byte[] batchId(java.util.UUID tenantId, java.util.UUID batchId) {
        return Hash.sha3((tenantId + ":WITHDRAWAL:" + batchId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static <T> T send(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static class QuantityResponse extends Response<String> { }

    public static class EvmReceiptResponse extends Response<EvmTransactionReceipt> { }
    public static class EvmTransactionReceipt extends TransactionReceipt {
        private String l1Fee;
        private String gasUsedForL1;
        private String operatorFeeScalar;
        private String operatorFeeConstant;

        public String getL1Fee() { return l1Fee; }
        public void setL1Fee(String l1Fee) { this.l1Fee = l1Fee; }
        public String getGasUsedForL1() { return gasUsedForL1; }
        public void setGasUsedForL1(String gasUsedForL1) { this.gasUsedForL1 = gasUsedForL1; }
        public String getOperatorFeeScalar() { return operatorFeeScalar; }
        public void setOperatorFeeScalar(String operatorFeeScalar) {
            this.operatorFeeScalar = operatorFeeScalar;
        }
        public String getOperatorFeeConstant() { return operatorFeeConstant; }
        public void setOperatorFeeConstant(String operatorFeeConstant) {
            this.operatorFeeConstant = operatorFeeConstant;
        }
    }

    private record Prepared(
            Credentials relayer, BigInteger chainId, BigInteger rpcPendingNonce,
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
            BigInteger gasLimit, BigInteger estimatedGas, String hotWallet,
            String calldata, List<AuthorizationTuple> authorizations,
            boolean authorizationIncluded, BigInteger authorizationNonce,
            BigInteger operationNonce, Instant signatureDeadline) { }
}
