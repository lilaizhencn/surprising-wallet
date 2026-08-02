package com.surprising.wallet.chain.starknet;

import com.swmansion.starknet.account.StandardAccount;
import com.swmansion.starknet.data.types.Call;
import com.swmansion.starknet.data.types.DeployAccountTransactionV3;
import com.swmansion.starknet.data.types.EstimateFeeResponse;
import com.swmansion.starknet.data.types.EstimateFeeResponseList;
import com.swmansion.starknet.data.types.Felt;
import com.swmansion.starknet.data.types.InvokeFunctionResponse;
import com.swmansion.starknet.data.types.ResourceBoundsMapping;
import com.swmansion.starknet.data.types.StarknetChainId;
import com.swmansion.starknet.data.types.TransactionExecutionStatus;
import com.swmansion.starknet.data.types.TransactionReceipt;
import com.swmansion.starknet.data.types.Uint256;
import com.swmansion.starknet.provider.exceptions.RpcRequestFailedException;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.StarknetTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Starknet 交易服务，负责账户部署、STRK/ERC-20 转账、归集和链上确认。
 *
 * <p>Starknet 账户是合约账户。首次发送前按链配置的 class hash、salt 和构造参数部署
 * counterfactual 地址，随后所有转账均通过账户合约的 {@code execute_v3} 执行。</p>
 */
@Service
public class StarknetTransactionService {
    /** 链标识。 */
    private static final String CHAIN = "STARKNET";
    /** Starknet 手续费精度。 */
    private static final int STRK_DECIMALS = 18;
    /** 部署回执最大等待时间。 */
    private static final Duration DEPLOYMENT_WAIT_TIMEOUT = Duration.ofSeconds(60);
    /** 部署回执轮询间隔。 */
    private static final Duration RECEIPT_POLL_INTERVAL = Duration.ofSeconds(1);
    /** Starknet RPC 的“交易哈希不存在”错误码。 */
    private static final int TRANSACTION_NOT_FOUND_ERROR = 29;
    /** 每个 counterfactual 账户的本机锁，避免单实例重复部署。 */
    private final ConcurrentMap<String, Object> deploymentLocks = new ConcurrentHashMap<>();

    /** Starknet RPC 客户端。 */
    private final StarknetRpcClient rpc;
    /** Starknet 密钥服务。 */
    private final StarknetKeyService keyService;
    /** 数据库仓储。 */
    private final ChainJdbcRepository repository;
    /** 运行时任务开关。 */
    private final WalletRuntimeConfigService runtimeConfigService;

    /** 构造 Starknet 交易服务。 */
    public StarknetTransactionService(StarknetRpcClient rpc, StarknetKeyService keyService,
                                      ChainJdbcRepository repository,
                                      WalletRuntimeConfigService runtimeConfigService) {
        this.rpc = rpc;
        this.keyService = keyService;
        this.repository = repository;
        this.runtimeConfigService = runtimeConfigService;
    }

    /** 发送 STRK 原生资产。 */
    public String sendNative(AccountChainProfile profile, ChainAddressRecord from,
                             String toAddress, BigDecimal amount) {
        ChainAsset asset = repository.findAsset(CHAIN, profile.getNativeSymbol())
                .filter(candidate -> Boolean.TRUE.equals(candidate.getActive()))
                .orElseThrow(() -> new IllegalStateException("enabled Starknet native asset is required"));
        return send(profile, from, toAddress, asset.getContractAddress(), profile.getNativeSymbol(),
                asset.getDecimals(), amount);
    }

    /** 发送 Starknet ERC-20 Token。 */
    public String sendToken(AccountChainProfile profile, ChainAddressRecord from,
                            TokenDefinition token, String toAddress, BigDecimal amount) {
        requireToken(token);
        return send(profile, from, toAddress, token.getContractAddress(), token.getSymbol(),
                token.getDecimals(), amount);
    }

    /** 归集 STRK 原生资产。 */
    public String collectNative(UUID tenantId, String collectionNo, AccountChainProfile profile,
                                ChainAddressRecord from, String hotAddress, BigDecimal amount) {
        return collect(tenantId, collectionNo, profile, from, hotAddress,
                repository.findAsset(CHAIN, profile.getNativeSymbol())
                        .orElseThrow(() -> new IllegalStateException("missing Starknet native asset"))
                        .getContractAddress(), profile.getNativeSymbol(), 18, amount);
    }

    /** 归集 Starknet ERC-20 Token。 */
    public String collectToken(UUID tenantId, String collectionNo, AccountChainProfile profile,
                               ChainAddressRecord from, TokenDefinition token,
                               String hotAddress, BigDecimal amount) {
        requireToken(token);
        return collect(tenantId, collectionNo, profile, from, hotAddress, token.getContractAddress(),
                token.getSymbol(), token.getDecimals(), amount);
    }

    /** 确认提现交易并结算冻结余额。 */
    public boolean confirmWithdrawal(UUID tenantId, AccountChainProfile profile, String orderNo,
                                     String assetSymbol, String debitAccountId, BigDecimal debitAmount) {
        String txHash = repository.findWithdrawalTxHash(tenantId, CHAIN, orderNo).orElseThrow();
        TransactionReceipt receipt = acceptedReceipt(profile, txHash);
        if (receipt == null) {
            return false;
        }
        markConfirmed(profile, txHash, receipt);
        return repository.confirmWithdrawalAndSettle(tenantId, CHAIN, orderNo, txHash,
                assetSymbol, debitAccountId, debitAmount);
    }

    /** 确认归集交易。 */
    public boolean confirmCollection(UUID tenantId, AccountChainProfile profile,
                                     String collectionNo, String assetSymbol) {
        String txHash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        TransactionReceipt receipt = acceptedReceipt(profile, txHash);
        if (receipt == null) {
            return false;
        }
        markConfirmed(profile, txHash, receipt);
        return repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1;
    }

    /** 返回 Starknet 归集安全手续费预留。金额单位为 STRK。 */
    public BigDecimal estimateCollectionFeeReserve(AccountChainProfile profile, int tokenCount) {
        BigDecimal configured = profile.getDefaultFee() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(profile.getDefaultFee()).movePointLeft(STRK_DECIMALS);
        return configured.max(new BigDecimal("0.001"))
                .multiply(BigDecimal.valueOf(Math.max(1, tokenCount + 1L)));
    }

    /** 构造、部署账户并发送一笔转账。 */
    private String send(AccountChainProfile profile, ChainAddressRecord from, String toAddress,
                        String contractAddress, String assetSymbol, int decimals, BigDecimal amount) {
        requireTransferEnabled(profile);
        BigInteger atomicAmount = toAtomic(amount, decimals);
        String normalizedTo = StarknetKeyService.normalizeAddress(toAddress);
        String normalizedContract = StarknetKeyService.normalizeAddress(contractAddress);
        ensureDeployed(profile, from);
        BigInteger balance = rpc.balance(profile, normalizedContract, from.getAddress());
        if (balance.compareTo(atomicAmount) < 0) {
            throw new IllegalStateException("insufficient Starknet " + assetSymbol + " balance for "
                    + from.getAddress());
        }
        return rpc.withProvider(profile, "broadcast", provider -> {
            StarknetKeyService.DerivedKey derived = keyService.derive(
                    profile, from.getUserId(), from.getBiz(), from.getAddressIndex());
            StandardAccount account = account(profile, from, derived, provider);
            Call call = new Call(Felt.fromHex(normalizedContract), "transfer", List.of(
                    Felt.fromHex(normalizedTo),
                    new Uint256(atomicAmount).getLow(),
                    new Uint256(atomicAmount).getHigh()));
            InvokeFunctionResponse response = account.executeV3(call).send();
            String txHash = response.getTransactionHash().hexString().toLowerCase(Locale.ROOT);
            repository.recordStarknetTransaction(StarknetTransactionRecord.builder()
                    .chain(CHAIN)
                    .txHash(txHash)
                    .fromAddress(from.getAddress())
                    .toAddress(normalizedTo)
                    .assetSymbol(assetSymbol)
                    .contractAddress(normalizedContract)
                    .amount(amount)
                    .fee(BigDecimal.ZERO)
                    .confirmations(0)
                    .status("SENT")
                    .rawPayload(String.valueOf(response))
                    .build());
            return txHash;
        });
    }

    /** 归集并维护归集记录状态。 */
    private String collect(UUID tenantId, String collectionNo, AccountChainProfile profile,
                           ChainAddressRecord from, String hotAddress, String contractAddress,
                           String assetSymbol, int decimals, BigDecimal amount) {
        requireCollectionEnabled(profile);
        if (repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).isPresent()) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("Starknet collection is not retryable"));
        }
        try {
            String txHash = send(profile, from, hotAddress, contractAddress, assetSymbol, decimals, amount);
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException error) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, error.getMessage(), null);
            throw error;
        }
    }

    /** 确保 counterfactual 账户已经部署。 */
    private void ensureDeployed(AccountChainProfile profile, ChainAddressRecord from) {
        String address = StarknetKeyService.normalizeAddress(from.getAddress());
        if (rpc.isDeployed(profile, address)) {
            return;
        }
        Object lock = deploymentLocks.computeIfAbsent(address, ignored -> new Object());
        synchronized (lock) {
            if (rpc.isDeployed(profile, address)) {
                return;
            }
            StarknetKeyService.DerivedKey derived = keyService.derive(
                    profile, from.getUserId(), from.getBiz(), from.getAddressIndex());
            String classHash = StarknetKeyService.parseFelt(profile.getAccountClassHash(), "accountClassHash")
                    .hexString();
            StarknetChainId chainId = chainId(profile);
            rpc.withProvider(profile, "broadcast", provider -> {
                StandardAccount account = account(profile, from, derived, provider, chainId);
                List<Felt> calldata = List.of(derived.publicKey());
                DeployAccountTransactionV3 provisional = account.signDeployAccountV3(
                        Felt.fromHex(classHash), calldata, derived.publicKey(), ResourceBoundsMapping.ZERO, true);
                EstimateFeeResponseList estimates = provider.getEstimateFee(List.of(provisional)).send();
                List<? extends EstimateFeeResponse> values = estimates.getValues();
                if (values == null || values.isEmpty()) {
                    throw new IllegalStateException("Starknet deploy account fee estimate is empty");
                }
                ResourceBoundsMapping bounds = values.getFirst().toResourceBounds(1.2);
                DeployAccountTransactionV3 signed = account.signDeployAccountV3(
                        Felt.fromHex(classHash), calldata, derived.publicKey(), bounds, false);
                var response = provider.deployAccount(signed).send();
                String txHash = response.getTransactionHash().hexString().toLowerCase(Locale.ROOT);
                repository.recordStarknetTransaction(StarknetTransactionRecord.builder()
                        .chain(CHAIN)
                        .txHash(txHash)
                        .fromAddress(address)
                        .toAddress(address)
                        .assetSymbol(profile.getNativeSymbol())
                        .contractAddress(repository.findAsset(CHAIN, profile.getNativeSymbol())
                                .map(ChainAsset::getContractAddress).orElse(null))
                        .amount(BigDecimal.ZERO)
                        .fee(BigDecimal.ZERO)
                        .confirmations(0)
                        .status("DEPLOYMENT_SENT")
                        .rawPayload(String.valueOf(response))
                        .build());
                waitForAccepted(profile, txHash);
                return null;
            });
        }
    }

    /** 等待账户部署交易进入已接受状态。 */
    private void waitForAccepted(AccountChainProfile profile, String txHash) {
        long deadline = System.nanoTime() + DEPLOYMENT_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                TransactionReceipt receipt = rpc.receipt(profile, txHash);
                if (receipt.getExecutionStatus() == TransactionExecutionStatus.SUCCEEDED) {
                    BigDecimal fee = actualFee(receipt);
                    int confirmations = confirmations(profile, receipt);
                    repository.markStarknetTransactionConfirmed(CHAIN, txHash, fee,
                            receipt.getBlockNumber() == null ? null : receipt.getBlockNumber().longValue(),
                            confirmations, String.valueOf(receipt));
                    return;
                }
                if (receipt.getExecutionStatus() == TransactionExecutionStatus.REVERTED
                        || receipt.getExecutionStatus() == TransactionExecutionStatus.REJECTED) {
                    throw new IllegalStateException("Starknet account deployment failed: " + txHash);
                }
            } catch (RpcRequestFailedException error) {
                if (error.getCode() != TRANSACTION_NOT_FOUND_ERROR) {
                    throw error;
                }
                // 交易尚未被节点索引，继续轮询并在超时后明确失败。
            }
            sleepForReceipt();
        }
        throw new IllegalStateException("timed out waiting for Starknet account deployment: " + txHash);
    }

    /** 查询已经接受的交易回执，未达到确认数时返回空。 */
    private TransactionReceipt acceptedReceipt(AccountChainProfile profile, String txHash) {
        try {
            TransactionReceipt receipt = rpc.receipt(profile, txHash);
            if (receipt.getExecutionStatus() == TransactionExecutionStatus.REVERTED
                    || receipt.getExecutionStatus() == TransactionExecutionStatus.REJECTED) {
                throw new IllegalStateException("Starknet transaction failed: " + txHash);
            }
            if (!receipt.isAccepted() || confirmations(profile, receipt) < requiredConfirmations(profile)) {
                return null;
            }
            return receipt;
        } catch (RpcRequestFailedException error) {
            if (error.getCode() != TRANSACTION_NOT_FOUND_ERROR) {
                throw error;
            }
            return null;
        }
    }

    /** 将链上回执保存为已确认交易。 */
    private void markConfirmed(AccountChainProfile profile, String txHash, TransactionReceipt receipt) {
        repository.markStarknetTransactionConfirmed(CHAIN, txHash, actualFee(receipt),
                receipt.getBlockNumber() == null ? null : receipt.getBlockNumber().longValue(),
                confirmations(profile, receipt), String.valueOf(receipt));
    }

    /** 构造 SDK 账户对象。 */
    private StandardAccount account(AccountChainProfile profile, ChainAddressRecord from,
                                    StarknetKeyService.DerivedKey derived,
                                    com.swmansion.starknet.provider.Provider provider) {
        return account(profile, from, derived, provider, chainId(profile));
    }

    /** 构造指定链 ID 的 SDK 账户对象。 */
    private StandardAccount account(AccountChainProfile profile, ChainAddressRecord from,
                                    StarknetKeyService.DerivedKey derived,
                                    com.swmansion.starknet.provider.Provider provider,
                                    StarknetChainId chainId) {
        return new StandardAccount(Felt.fromHex(from.getAddress()), derived.privateKey(), provider, chainId);
    }

    /** 根据数据库网络名称选择 Starknet 链 ID。 */
    private StarknetChainId chainId(AccountChainProfile profile) {
        String network = profile.getNetwork() == null ? "" : profile.getNetwork().toLowerCase(Locale.ROOT);
        if (network.contains("main")) {
            return StarknetChainId.MAIN;
        }
        if (network.contains("integration")) {
            return StarknetChainId.INTEGRATION_SEPOLIA;
        }
        return StarknetChainId.SEPOLIA;
    }

    /** 计算 Starknet 交易的 STRK 实际手续费。 */
    private BigDecimal actualFee(TransactionReceipt receipt) {
        if (receipt.getActualFee() == null || receipt.getActualFee().getAmount() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(receipt.getActualFee().getAmount().getValue()).movePointLeft(STRK_DECIMALS);
    }

    /** 计算交易当前确认数。 */
    private int confirmations(AccountChainProfile profile, TransactionReceipt receipt) {
        if (receipt.getBlockNumber() == null) {
            return 0;
        }
        long latest = rpc.latest(profile).number();
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, latest - receipt.getBlockNumber() + 1L));
    }

    /** 将业务金额精确转换为 uint256 原子单位。 */
    private BigInteger toAtomic(BigDecimal amount, int decimals) {
        if (amount == null || amount.signum() <= 0 || decimals < 0 || decimals > 78) {
            throw new IllegalArgumentException("Starknet transfer amount must be positive");
        }
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    /** 校验发送或归集使用的 Token 必须来自当前链且仍处于启用状态。 */
    private void requireToken(TokenDefinition token) {
        if (token == null || !CHAIN.equalsIgnoreCase(token.getChain())
                || !Boolean.TRUE.equals(token.getActive())
                || token.getContractAddress() == null || token.getContractAddress().isBlank()
                || token.getDecimals() == null || token.getDecimals() < 0 || token.getDecimals() > 78) {
            throw new IllegalArgumentException("enabled Starknet token is required");
        }
        StarknetKeyService.normalizeAddress(token.getContractAddress());
    }

    /** 返回配置的提现确认数。 */
    private int requiredConfirmations(AccountChainProfile profile) {
        return profile.getWithdrawConfirmations() == null
                ? 1 : Math.max(1, profile.getWithdrawConfirmations());
    }

    /** 校验提现任务开关。 */
    private void requireTransferEnabled(AccountChainProfile profile) {
        if (runtimeConfigService != null
                && !runtimeConfigService.isTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_WITHDRAW)) {
            throw new IllegalStateException("Starknet withdrawal task is disabled");
        }
        if (!Boolean.TRUE.equals(profile.getTransferEnabled()) || !Boolean.TRUE.equals(profile.getWithdrawEnabled())) {
            throw new IllegalStateException("Starknet transfer and withdrawal must be enabled");
        }
    }

    /** 校验归集任务开关。 */
    private void requireCollectionEnabled(AccountChainProfile profile) {
        if (runtimeConfigService != null
                && !runtimeConfigService.isTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_COLLECTION)) {
            throw new IllegalStateException("Starknet collection task is disabled");
        }
        if (!Boolean.TRUE.equals(profile.getCollectionEnabled())) {
            throw new IllegalStateException("Starknet collection must be enabled");
        }
    }

    /** 等待下一次回执轮询。 */
    private void sleepForReceipt() {
        try {
            Thread.sleep(RECEIPT_POLL_INTERVAL.toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Starknet receipt", error);
        }
    }
}
