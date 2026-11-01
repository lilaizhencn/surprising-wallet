package com.surprising.wallet.service;

import com.surprising.wallet.chain.evm.MpcEvm7702Codec;
import com.surprising.wallet.model.mpc.Evm7702CollectionPayload;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import com.surprising.wallet.chain.evm.Evm7702SignedBatchTransaction;
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
    private final Evm7702SigningService mpc;
    private final CustodyCryptoService crypto;
    private final WalletRuntimeConfigService runtimeConfig;
    private final Evm7702OperationSigner operationSigner = new Evm7702OperationSigner();
    private final Evm7702ContractCodec contractCodec = new Evm7702ContractCodec();
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
                        throw new IllegalStateException("EIP-7702 target must have EVM family");
                    }
                    recoverUnknown(profile);
                    recoverSigning(profile);
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
                    String rawTransaction = crypto.decrypt(attempt.signedTxCiphertext());
                    var original=coordinator.claimed(attempt.tenantId(),attempt.batchId()).orElseThrow();
                    var operation=Evm7702CollectionCoordinator.operation(original);
                    mpc.verifySelectedRaw(operation,rawTransaction,attempt.txHash());
                    requireChain(web3j,profile,BigInteger.valueOf(mpc.plan(operation).orElseThrow().chainId()));
                    if (rpc.isTransactionKnown(web3j, attempt.txHash())) {
                        coordinator.markSubmitted(attempt.tenantId(), attempt.batchId(), attempt.txHash());
                        continue;
                    }
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
        var batch=coordinator.claimNextBatch(profile.getChain(),profile.getNetwork()).orElse(null);
        return batch==null?Optional.empty():Optional.of(execute(profile,batch));
    }

    public void recoverSigning(AccountChainProfile profile) {
        for(var batch:coordinator.listSigning(profile.getChain(),profile.getNetwork(),20)) {
            try { execute(profile,batch); }
            catch(RuntimeException pending) { log.warn("Original EIP-7702 batch remains pending: tenant={} batch={} reason={}",batch.tenantId(),batch.id(),pending.getMessage()); }
        }
    }

    private String execute(AccountChainProfile profile,Evm7702CollectionCoordinator.Batch batch) {
        var operation=Evm7702CollectionCoordinator.operation(batch);
        boolean rawCommitted=false;
        try {
            HttpService http=rpc.http(profile);Web3j web3j=Web3j.build(http);
            try {
                if(mpc.plan(operation).isEmpty()) {
                    var preparation=prepare(web3j,profile,batch);
                    coordinator.reserveSigningPlan(batch,preparation,preparation.rpcPendingNonce());
                }
                var preparation=mpc.preparation(operation,Evm7702CollectionPayload.class);
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
            if(rawCommitted)coordinator.markBroadcastUnknown(batch.tenantId(),batch.id(),"BROADCAST_UNCERTAIN",error.getMessage());
            else if(mpc.plan(operation).isEmpty()) {

                coordinator.releaseUnbroadcastBatch(batch,"PREPARATION_FAILED",error.getMessage());
            }
            throw error instanceof RuntimeException runtime?runtime:new IllegalStateException("original MPC batch pending",error);
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

    private Evm7702CollectionPayload prepare(Web3j web3j,AccountChainProfile profile,Evm7702CollectionCoordinator.Batch batch) throws Exception {
        var config=batch.config();
        requireChain(web3j,profile,config.chainId());
        var latest=web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST,false).send().getBlock();
        Instant timestamp=chainBlockTimestamp(latest);
        rpc.requireCodeHash(web3j,config.delegateAddress(),config.delegateCodeHash(),"delegate");
        rpc.requireCodeHash(web3j,config.collectorAddress(),config.collectorCodeHash(),"collector");
        var prepared=new ArrayList<Evm7702CollectionPayload.Item>();
        for(int index=0;index<batch.items().size();index++) {
            var item=batch.items().get(index);var authority=item.authorityChainAddress();
            if(!batch.tenantId().equals(authority.getTenantId()) || !authority.getAddress().equalsIgnoreCase(item.fromAddress()))
                throw new IllegalStateException("collection authority differs from original owned claim");
            BigInteger balance=NATIVE_TOKEN.equalsIgnoreCase(batch.tokenContract())?web3j.ethGetBalance(item.fromAddress(),DefaultBlockParameterName.LATEST).send().getBalance()
                    :rpc.tokenBalance(web3j,batch.tokenContract(),item.fromAddress());
            if(balance.compareTo(item.amountAtomic())<0)throw new IllegalStateException("authority asset balance is lower than collection amount");
            String code=web3j.ethGetCode(item.fromAddress(),DefaultBlockParameterName.LATEST).send().getCode();
            BigInteger nonce=web3j.ethGetTransactionCount(item.fromAddress(),DefaultBlockParameterName.PENDING).send().getTransactionCount();
            MpcEvm7702Codec.Authorization authorization=null;BigInteger operationNonce;
            if(code==null || "0x".equalsIgnoreCase(code) || "0x0".equalsIgnoreCase(code)) {
                authorization=new MpcEvm7702Codec.Authorization(config.chainId(),item.fromAddress(),config.delegateAddress(),nonce);operationNonce=BigInteger.ZERO;
            } else if(code.equalsIgnoreCase(delegationCode(config.delegateAddress())))operationNonce=rpc.operationNonce(web3j,item.fromAddress());
            else throw new IllegalStateException("authority has unapproved delegation");
            var request=new Evm7702CollectionRequest(Numeric.hexStringToByteArray(batch.batchHash()),BigInteger.valueOf(index),
                    item.fromAddress(),config.collectorAddress(),batch.tokenContract(),batch.hotWallet(),item.amountAtomic(),operationNonce,
                    BigInteger.valueOf(batch.signatureDeadline().getEpochSecond()),DEFAULT_ITEM_GAS);
            request.requireNotExpired(timestamp);
            prepared.add(new Evm7702CollectionPayload.Item(authority,authorization,request));
        }
        var quote=EvmFeeSupport.quote(web3j,profile);
        BigInteger pending=web3j.ethGetTransactionCount(batch.relayerChainAddress().getAddress(),DefaultBlockParameterName.PENDING).send().getTransactionCount();
        return new Evm7702CollectionPayload(config.chainId(),pending,quote.maxPriorityFeePerGas(),quote.maxFeePerGas(),latest.getGasLimit(),prepared);
    }

    private void prepareOuter(Web3j web3j,HttpService http,AccountChainProfile profile,Evm7702CollectionCoordinator.Batch batch,Evm7702CollectionPayload payload) throws Exception {
        var operation=Evm7702CollectionCoordinator.operation(batch);var plan=mpc.plan(operation).orElseThrow();
        var authorizations=new ArrayList<AuthorizationTuple>();var requests=new ArrayList<Evm7702CollectionRequest>();var signatures=new ArrayList<byte[]>();
        for(int index=0;index<payload.items().size();index++) {
            var item=payload.items().get(index);byte[] publicKey=mpc.publicKey(item.authority(),batch.chain());
            if(item.authorization()!=null)authorizations.add(MpcEvm7702Codec.authorization(item.authorization(),publicKey,mpc.signature(operation,
                    mpc.input(item.authority(),"authorization-"+index,MpcSignCommand.Purpose.EIP7702_AUTHORIZATION,item.authorization(),MpcEvm7702Codec.digest(item.authorization())))));
            requests.add(item.request());signatures.add(MpcEvm7702Codec.collectionSignature(payload.chainId(),item.request(),publicKey,mpc.signature(operation,
                    mpc.input(item.authority(),"operation-"+index,MpcSignCommand.Purpose.EIP7702_OPERATION,item.request(),operationSigner.digest(payload.chainId(),item.request())))));
        }
        String data=contractCodec.encodeCollectBatch(requests,signatures);
        BigInteger estimated=rpc.estimateGas(http,plan.relayer().getAddress(),batch.config().collectorAddress(),data,payload.maxPriorityFeePerGas(),payload.maxFeePerGas(),authorizations);
        BigInteger gasLimit=gasLimit(estimated,payload.blockGasLimit(),batch.config(),MIN_ITEM_GAS);
        var raw=MpcEvm7702Codec.batch(plan.chainId(),plan.relayerNonce(),payload.maxPriorityFeePerGas(),payload.maxFeePerGas(),gasLimit,batch.config().collectorAddress(),data,authorizations);
        BigInteger fee=gasLimit.multiply(payload.maxFeePerGas()).add(EvmFeeSupport.estimateUnsignedSeparateL1Fee(web3j,profile,plan.relayer().getAddress(),MpcEvmTransactionCodec.unsignedPayload(raw,plan.chainId())))
                .add(EvmFeeSupport.estimateOperatorFee(web3j,profile,plan.relayer().getAddress(),gasLimit));
        EvmFeeSupport.requireBalance(web3j,plan.relayer().getAddress(),fee,"EIP-7702 relayer");
        coordinator.reserveOuter(batch,raw,estimated.longValueExact(),EvmFeeSupport.atomicToNative(fee,nativeDecimals(profile),RoundingMode.UP));
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

    private int nativeDecimals(AccountChainProfile profile) {
        var asset = chainAssetRepository.findDefinition(
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

    private final EvmCollectionBatchRepository batches;
    private final EvmCollectionBatchItemRepository items;
    private final EvmCollectionBatchAttemptRepository attempts;
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
            var matching = definitions.stream().filter(row -> chain.equals(row.chain()) && network.equals(row.network())).toList();
            boolean active = Boolean.TRUE.equals(profile.getEnabled()) && matching.stream().anyMatch(row -> "ACTIVE".equals(row.status()));
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
        return batches.listPending(chain,network,limit).stream().map(row -> {
            var config=coordinator.claimed(row.tenantId(),row.id()).orElseThrow().config();
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
