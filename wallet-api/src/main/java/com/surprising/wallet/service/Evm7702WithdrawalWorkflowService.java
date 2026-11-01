package com.surprising.wallet.service;

import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;
import com.surprising.wallet.common.signing.Evm7702PayoutPayload;
import com.surprising.wallet.common.signing.MpcSignCommand;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import com.surprising.wallet.chain.evm.Evm7702SignedBatchTransaction;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutCodec;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutItem;
import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutRequest;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutSigner;
import com.surprising.wallet.chain.evm.Evm7702RpcClient;
import com.surprising.wallet.chain.evm.EvmFeeSupport;
import com.surprising.wallet.chain.evm.EvmTransactionReceipt;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator;
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
 * EIP-7702 提现的批次领取、签名准备、广播恢复与回执确认。
 */
@Service
public class Evm7702WithdrawalWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(Evm7702WithdrawalWorkflowService.class);

    private static final String NATIVE_TOKEN = "0x0000000000000000000000000000000000000000";

    private static final BigInteger ITEM_GAS = BigInteger.valueOf(120_000L);

    private static final BigInteger MIN_BATCH_GAS = BigInteger.valueOf(80_000L);
    private final Evm7702WithdrawalCoordinator coordinator;
    private final ChainProfileRepository chainProfileRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final Evm7702RpcClient rpc;
    private final Evm7702SigningService mpc;
    private final CustodyCryptoService crypto;
    private final WalletRuntimeConfigService runtimeConfig;
    private final Evm7702PayoutSigner payoutSigner = new Evm7702PayoutSigner();
    private final Evm7702PayoutCodec payoutCodec = new Evm7702PayoutCodec();
    private final Evm7702PayoutReceiptParser receiptParser = new Evm7702PayoutReceiptParser();
    private final AtomicBoolean running = new AtomicBoolean();

    public Evm7702WithdrawalWorkflowService(
            EvmWithdrawalBatchRepository batches,
            EvmWithdrawalBatchItemRepository items,
            EvmWithdrawalBatchAttemptRepository attempts,
            Evm7702ConfigRepository configurations,
            Evm7702WithdrawalCoordinator coordinator,
            ChainProfileRepository chainProfileRepository,
            ChainAssetRepository chainAssetRepository,
            Evm7702RpcClient rpc,
            Evm7702SigningService mpc,
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
        this.mpc = mpc;
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
                        throw new IllegalStateException("EIP-7702 payout target must have EVM family");
                    }
                    recoverFailedUnbroadcast(profile);
                    recoverUnknown(profile);
                    recoverSigning(profile);
                    confirm(profile);
                    if (target.active() && runtimeConfig.isTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_WITHDRAW)) {
                        processOne(profile);
                    }
                } catch (RuntimeException e) {
                    log.error(
                            "EIP-7702 payout cycle failed for {}/{}: {}",
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

    /**
     * 恢复历史预广播失败批次，释放余额并让提现对账任务发送失败回调。
     */
    private void recoverFailedUnbroadcast(AccountChainProfile profile) {
        int recovered = 0;
        for (Evm7702WithdrawalCoordinator.UnbroadcastBatch batch : listFailedUnbroadcastBatches(
                profile.getChain(),
                profile.getNetwork(),
                100)) {
            coordinator.failUnbroadcast(batch);
            recovered++;
        }
        if (recovered > 0) {
            log.info(
                    "EIP-7702 pre-broadcast failures recovered: chain={} network={} batches={}",
                    profile.getChain(),
                    profile.getNetwork(),
                    recovered);
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
                    String rawTransaction = crypto.decrypt(attempt.signedTxCiphertext());
                    var original = coordinator.claimed(attempt.tenantId(), attempt.batchId()).orElseThrow();
                    var operation = Evm7702WithdrawalCoordinator.operation(original);
                    mpc.verifySelectedRaw(operation, rawTransaction, attempt.txHash());
                    requireChain(web3j, profile, BigInteger.valueOf(mpc.plan(operation).orElseThrow().chainId()));
                    if (rpc.isTransactionKnown(web3j, attempt.txHash())) {
                        coordinator.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                        continue;
                    }
                    if (!Hash.sha3(rawTransaction).equalsIgnoreCase(attempt.txHash())) {
                        coordinator.markRecoveryError(
                                attempt,
                                "OUTBOX_HASH_MISMATCH",
                                "decrypted signed payout transaction does not match persisted tx hash");
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
                            "EIP-7702 payout recovery failed for tenant={} batch={}: {}",
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
        var batch=coordinator.claimNextBatch(profile.getChain(),profile.getNetwork()).orElse(null);
        return batch==null?Optional.empty():Optional.of(execute(profile,batch));
    }

    public void recoverSigning(AccountChainProfile profile) {
        for(var batch:coordinator.listSigning(profile.getChain(),profile.getNetwork(),20)) {
            try { execute(profile,batch); }
            catch(RuntimeException pending) { log.warn("Original EIP-7702 batch remains pending: tenant={} batch={} reason={}",batch.tenantId(),batch.id(),pending.getMessage()); }
        }
    }

    private String execute(AccountChainProfile profile,Evm7702WithdrawalCoordinator.Batch batch) {
        var operation=Evm7702WithdrawalCoordinator.operation(batch);
        boolean rawCommitted=false;
        try {
            HttpService http=rpc.http(profile);Web3j web3j=Web3j.build(http);
            try {
                if(mpc.plan(operation).isEmpty()) {
                    var preparation=prepare(web3j,profile,batch);
                    coordinator.reserveSigningPlan(batch,preparation,preparation.rpcPendingNonce());
                }
                var preparation=mpc.preparation(operation,Evm7702PayoutPayload.class);
                if(mpc.outer(operation).isEmpty())prepareOuter(web3j,http,profile,batch,preparation);
                mpc.signOuter(operation);
                var signed=coordinator.persistSignedAttempt(batch);rawCommitted=true;
                EthSendTransaction sent=web3j.ethSendRawTransaction(signed.rawTransaction()).send();
                if(sent.hasError())throw new IllegalStateException("eth_sendRawTransaction rejected original batch");
                if(!signed.transactionHash().equalsIgnoreCase(sent.getTransactionHash()))throw new IllegalStateException("RPC batch hash differs from selected MPC hash");
                coordinator.markSubmitted(batch.tenantId(),batch.id(),signed.transactionHash());
                return signed.transactionHash();
            } finally { web3j.shutdown(); }
        } catch(Exception error) {
            if(error instanceof com.surprising.wallet.model.mpc.MpcPendingException pending && pending.state().equals("NONCE_ADMISSION_PENDING"))throw pending;
            if(rawCommitted)coordinator.markBroadcastUnknown(batch.tenantId(),batch.id(),"BROADCAST_UNCERTAIN",error.getMessage());
            else if(mpc.plan(operation).isEmpty()) {
                if(isDeterministicPreparationFailure(error))coordinator.failUnbroadcast(new Evm7702WithdrawalCoordinator.UnbroadcastBatch(
                        batch.tenantId(),batch.id(),batch.chain(),batch.network(),"PREPARATION_FAILED",error.getMessage()));
                else coordinator.releaseUnbroadcastBatch(batch,"PREPARATION_FAILED",error.getMessage());
            }
            throw error instanceof RuntimeException runtime?runtime:new IllegalStateException("original MPC batch pending",error);
        }
    }

    public void confirm(AccountChainProfile profile) {
        HttpService http = rpc.http(profile);
        Web3j web3j = Web3j.build(http);
        try {
            BigInteger latest = send(() -> web3j.ethBlockNumber().send().getBlockNumber());
            for (Evm7702WithdrawalCoordinator.PendingBatch batch : listPendingBatches(profile.getChain(), profile.getNetwork(), 100)) {
                Optional<EvmTransactionReceipt> found = send(() -> rpc.transactionReceipt(http, batch.txHash()));
                if (found.isEmpty() || found.get().getBlockNumber() == null) {
                    continue;
                }
                EvmTransactionReceipt receipt = found.get();
                BigInteger confirmations = latest.subtract(receipt.getBlockNumber()).add(BigInteger.ONE);
                if (confirmations.compareTo(BigInteger.valueOf(batch.requiredConfirmations())) < 0) {
                    continue;
                }
                EthBlock canonical = send(() -> web3j.ethGetBlockByHash(receipt.getBlockHash(), false).send());
                if (canonical.getBlock() == null || !canonical.getBlock().getHash().equalsIgnoreCase(receipt.getBlockHash())) {
                    throw new IllegalStateException("payout receipt block is no longer canonical");
                }
                BigInteger effectiveGasPrice = receipt.getEffectiveGasPrice() == null ? BigInteger.ZERO : Numeric.decodeQuantity(receipt.getEffectiveGasPrice());
                EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                        web3j,
                        profile,
                        receipt.getFrom(),
                        receipt.getGasUsed(),
                        effectiveGasPrice,
                        receipt.getBlockNumber(),
                        receipt.getL1Fee(),
                        receipt.getGasUsedForL1(),
                        receipt.getOperatorFeeScalar(),
                        receipt.getOperatorFeeConstant());
                if (!receipt.isStatusOK()) {
                    coordinator.completeReverted(
                            batch,
                            batch.txHash(),
                            receipt.getGasUsed(),
                            effectiveGasPrice,
                            fee.executionFee(),
                            fee.l1Fee(),
                            fee.operatorFee(),
                            receipt.getBlockNumber(),
                            receipt.getBlockHash(),
                            Hash.sha3String("outer-reverted:" + batch.txHash().toLowerCase()));
                    continue;
                }
                var identities = items.listByBatch(batch.tenantId(), batch.batchId());
                Evm7702PayoutReceiptParser.ParsedReceipt parsed = receiptParser.parse(
                        receipt,
                        batch.hotWallet(),
                        batchId(batch.tenantId(), batch.batchId()),
                        identities.stream().map(item -> new Evm7702PayoutReceiptParser.ExpectedPayout(
                                Numeric.hexStringToByteArray(item.withdrawalIdHash()),
                                item.tokenContract(),
                                item.recipient(),
                                item.requestedAmountAtomic())).toList());
                coordinator.complete(
                        batch,
                        batch.txHash(),
                        receipt.getGasUsed(),
                        effectiveGasPrice,
                        fee.executionFee(),
                        fee.l1Fee(),
                        fee.operatorFee(),
                        receipt.getBlockNumber(),
                        receipt.getBlockHash(),
                        parsed.items());
            }
        } finally {
            web3j.shutdown();
        }
    }

    private Evm7702PayoutPayload prepare(Web3j web3j,AccountChainProfile profile,Evm7702WithdrawalCoordinator.Batch batch) throws Exception {
        var config=batch.config();requireChain(web3j,profile,config.chainId());
        if(!batch.tenantId().equals(batch.hotChainAddress().getTenantId()) || !batch.hotChainAddress().getAddress().equalsIgnoreCase(batch.hotWallet()))
            throw new IllegalStateException("payout authority differs from original owned claim");
        var latest=web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST,false).send().getBlock();
        Instant timestamp=chainBlockTimestamp(latest);
        rpc.requireCodeHash(web3j,config.payoutDelegateAddress(),config.payoutDelegateCodeHash(),"payout delegate");
        BigInteger total=batch.items().stream().map(Evm7702WithdrawalCoordinator.ClaimedItem::amountAtomic).reduce(BigInteger.ZERO,BigInteger::add);
        BigInteger balance=NATIVE_TOKEN.equalsIgnoreCase(batch.tokenContract())?web3j.ethGetBalance(batch.hotWallet(),DefaultBlockParameterName.LATEST).send().getBalance()
                :rpc.tokenBalance(web3j,batch.tokenContract(),batch.hotWallet());
        if(balance.compareTo(total)<0)throw new IllegalStateException("tenant hot-wallet balance is lower than payout batch amount");
        String code=web3j.ethGetCode(batch.hotWallet(),DefaultBlockParameterName.LATEST).send().getCode();
        BigInteger nonce=web3j.ethGetTransactionCount(batch.hotWallet(),DefaultBlockParameterName.PENDING).send().getTransactionCount();
        var mode=payoutAuthorizationMode(code,config.delegateAddress(),config.payoutDelegateAddress());
        MpcEvm7702Codec.Authorization authorization=null;BigInteger operationNonce;
        if(mode==PayoutAuthorizationMode.AUTHORIZE_PAYOUT) {
            authorization=new MpcEvm7702Codec.Authorization(config.chainId(),batch.hotWallet(),config.payoutDelegateAddress(),nonce);operationNonce=BigInteger.ZERO;
        } else if(mode==PayoutAuthorizationMode.ALREADY_PAYOUT_DELEGATED)operationNonce=rpc.operationNonce(web3j,batch.hotWallet());
        else throw new IllegalStateException("hot wallet has unapproved delegation");
        var items=new ArrayList<Evm7702PayoutItem>();
        for(int index=0;index<batch.items().size();index++) {
            var item=batch.items().get(index);items.add(new Evm7702PayoutItem(item.withdrawalId(),BigInteger.valueOf(index),batch.tokenContract(),item.recipient(),item.amountAtomic(),ITEM_GAS));
        }
        var request=new Evm7702PayoutRequest(Numeric.hexStringToByteArray(batch.batchHash()),batch.hotWallet(),config.relayerAddress(),items,operationNonce,BigInteger.valueOf(batch.signatureDeadline().getEpochSecond()));
        request.requireNotExpired(timestamp);var quote=EvmFeeSupport.quote(web3j,profile);
        BigInteger pending=web3j.ethGetTransactionCount(batch.relayerChainAddress().getAddress(),DefaultBlockParameterName.PENDING).send().getTransactionCount();
        return new Evm7702PayoutPayload(config.chainId(),pending,quote.maxPriorityFeePerGas(),quote.maxFeePerGas(),latest.getGasLimit(),batch.hotChainAddress(),authorization,request);
    }

    private void prepareOuter(Web3j web3j,HttpService http,AccountChainProfile profile,Evm7702WithdrawalCoordinator.Batch batch,Evm7702PayoutPayload payload) throws Exception {
        var operation=Evm7702WithdrawalCoordinator.operation(batch);var plan=mpc.plan(operation).orElseThrow();
        byte[] publicKey=mpc.publicKey(payload.authority(),batch.chain());var authorizations=new ArrayList<AuthorizationTuple>();
        if(payload.authorization()!=null)authorizations.add(MpcEvm7702Codec.authorization(payload.authorization(),publicKey,mpc.signature(operation,
                mpc.input(payload.authority(),"authorization-0",MpcSignCommand.Purpose.EIP7702_AUTHORIZATION,payload.authorization(),MpcEvm7702Codec.digest(payload.authorization())))));
        byte[] signature=MpcEvm7702Codec.payoutSignature(payload.chainId(),payload.request(),publicKey,mpc.signature(operation,
                mpc.input(payload.authority(),"operation-0",MpcSignCommand.Purpose.EIP7702_OPERATION,payload.request(),payoutSigner.digest(payload.chainId(),payload.request()))));
        String data=payoutCodec.encode(payload.request(),signature);
        BigInteger estimated=rpc.estimateGas(http,plan.relayer().getAddress(),batch.hotWallet(),data,payload.maxPriorityFeePerGas(),payload.maxFeePerGas(),authorizations);
        BigInteger gasLimit=gasLimit(estimated,payload.blockGasLimit(),batch.config(),MIN_BATCH_GAS);
        var raw=MpcEvm7702Codec.batch(plan.chainId(),plan.relayerNonce(),payload.maxPriorityFeePerGas(),payload.maxFeePerGas(),gasLimit,batch.hotWallet(),data,authorizations);
        BigInteger fee=gasLimit.multiply(payload.maxFeePerGas()).add(EvmFeeSupport.estimateUnsignedSeparateL1Fee(web3j,profile,plan.relayer().getAddress(),MpcEvmTransactionCodec.unsignedPayload(raw,plan.chainId())))
                .add(EvmFeeSupport.estimateOperatorFee(web3j,profile,plan.relayer().getAddress(),gasLimit));
        EvmFeeSupport.requireBalance(web3j,plan.relayer().getAddress(),fee,"EIP-7702 relayer");
        coordinator.reserveOuter(batch,raw,estimated.longValueExact(),EvmFeeSupport.atomicToNative(fee,nativeDecimals(profile),RoundingMode.UP));
    }

    private static boolean isDeterministicPreparationFailure(Throwable error) {
        for(Throwable current=error;current!=null;current=current.getCause()) {
            String message=String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if(message.contains("eth_estimategas failed") && (message.contains("revert") || message.contains("custom error")))return true;
        }
        return false;
    }

    private int nativeDecimals(AccountChainProfile profile) {
        return chainAssetRepository.findDefinition(profile.getChain(),profile.getNativeSymbol()).orElseThrow().getDecimals();
    }

    private static void requireChain(Web3j web3j,AccountChainProfile profile,BigInteger chainId) throws Exception {
        if(!chainId.equals(BigInteger.valueOf(profile.getChainId())) || !chainId.equals(web3j.ethChainId().send().getChainId()))
            throw new IllegalStateException("RPC/config/profile chain identity mismatch");
    }

    private static BigInteger gasLimit(BigInteger estimated,BigInteger blockGasLimit,Evm7702ConfigRepository.Configuration config,BigInteger minimum) {
        BigInteger limit=new BigDecimal(estimated).multiply(config.gasLimitMultiplier()).setScale(0,RoundingMode.UP).toBigIntegerExact();
        BigInteger cap=BigInteger.valueOf(config.maxBatchGas()).min(new BigDecimal(blockGasLimit).multiply(config.blockGasRatio()).toBigInteger());
        if(limit.compareTo(cap)>0 || limit.compareTo(minimum)<0)throw new IllegalStateException("estimated batch gas exceeds safety bounds");
        return limit;
    }

    enum PayoutAuthorizationMode {

        AUTHORIZE_PAYOUT, ALREADY_PAYOUT_DELEGATED, REJECT
    }

    static PayoutAuthorizationMode payoutAuthorizationMode(String code, String collectionDelegate, String payoutDelegate) {
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
        return Hash.sha3((tenantId + ":WITHDRAWAL:" + batchId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private final EvmWithdrawalBatchRepository batches;
    private final EvmWithdrawalBatchItemRepository items;
    private final EvmWithdrawalBatchAttemptRepository attempts;
    private final Evm7702ConfigRepository configurations;

    public List<RuntimeTarget> listRuntimeTargets() {
        List<RuntimeTarget> result = new ArrayList<>();
        var definitions = configurations.listAll();
        for (var profile : chainProfileRepository.listProfiles(false)) {
            if (!"evm".equalsIgnoreCase(profile.getFamily())) {
                continue;
            }
            String chain = profile.getChain();
            String network = profile.getNetwork();
            var matching = definitions.stream().filter(row -> chain.equals(row.chain()) &&
                    network.equals(row.network()) &&
                    row.batchWithdrawalEnabled()).toList();
            boolean active = Boolean.TRUE.equals(profile.getEnabled()) && matching.stream().anyMatch(row -> "ACTIVE".equals(row.status()));
            boolean managed = matching.stream().anyMatch(row -> java.util.Set.of("ACTIVE", "PAUSED").contains(row.status()));
            if (managed || batches.existsPending(chain,network)) {
                result.add(new RuntimeTarget(profile, active));
            }
        }
        return result.stream().sorted(java.util.Comparator.comparing((RuntimeTarget target) -> target.profile().getChain()).thenComparing(target -> target.profile().getNetwork())).toList();
    }

    public List<EvmBroadcastAttempt> listUnknownAttempts(String chain, String network, int limit) {
        List<UUID> ids = batches.listBroadcastUnknown(chain, network);
        return attempts.listUnknown(ids, limit);
    }

    public List<Evm7702WithdrawalCoordinator.PendingBatch> listPendingBatches(String chain, String network, int limit) {
        return batches.listPending(chain,network,limit).stream().map(row -> {
            var config=coordinator.claimed(row.tenantId(),row.id()).orElseThrow().config();
            return new Evm7702WithdrawalCoordinator.PendingBatch(
                    row.tenantId(),
                    row.id(),
                    chain,
                    row.canonicalTxHash(),
                    row.status(),
                    row.hotWallet(),
                    config.requiredConfirmations());
        }).toList();
    }

    public record RuntimeTarget(AccountChainProfile profile, boolean active) {
    }

    public List<Evm7702WithdrawalCoordinator.UnbroadcastBatch> listFailedUnbroadcastBatches(String chain, String network, int limit) {
        return batches.listFailedUnbroadcast(chain, network, limit).stream().filter(row -> {
            UUID tenantId = row.tenantId();
            UUID batchId = row.id();
            return attempts.countByBatch(tenantId, batchId) == 0 && items.hasPreBroadcastRecoverableItems(tenantId, batchId);
        }).map(row -> new Evm7702WithdrawalCoordinator.UnbroadcastBatch(
                row.tenantId(),
                row.id(),
                String.valueOf(row.chain()),
                String.valueOf(row.network()),
                String.valueOf(row.errorCode()),
                row.errorMessage())).toList();
    }
}
