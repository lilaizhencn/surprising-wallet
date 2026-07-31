package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 负责扫描链上区块、交易或事件，并转换为钱包领域事件。
 */
@Slf4j
@Component
public class EvmDepositScanner {
    /**
     * 定义 {@code FINALITY_AUDIT_DEPTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int FINALITY_AUDIT_DEPTH = 256;
    /**
     * 定义 {@code WEI_PER_ETH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal WEI_PER_ETH = new BigDecimal("1000000000000000000");
    /**
     * 定义 {@code TRANSFER_TOPIC} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code logScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final EvmLogScanner logScanner;
    /**
     * 保存 {@code rpcNodeService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code fixedRpcUrl}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final String fixedRpcUrl;
    /**
     * 保存 {@code fixedConfirmations}，记录开关、处理状态、确认结果或重试信息。
     */
    private final int fixedConfirmations;

    /**
     * 保存 {@code runtimeConfigService}，用于保存运行配置和策略参数。
     */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 构造 {@code EvmDepositScanner}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public EvmDepositScanner(ChainJdbcRepository repository, EvmLogScanner logScanner,
                             ChainRpcNodeService rpcNodeService) {
        this.repository = repository;
        this.logScanner = logScanner;
        this.rpcNodeService = rpcNodeService;
        this.fixedRpcUrl = null;
        this.fixedConfirmations = 0;
    }

    /**
     * 构造 {@code EvmDepositScanner}，初始化该组件运行所需的状态和依赖。
     */
    public EvmDepositScanner(ChainJdbcRepository repository,
                             String sepoliaRpcUrl,
                             int sepoliaConfirmations) {
        this(repository, new EvmLogScanner(), sepoliaRpcUrl, sepoliaConfirmations);
    }

    /**
     * 构造 {@code EvmDepositScanner}，初始化该组件运行所需的状态和依赖。
     */
    public EvmDepositScanner(ChainJdbcRepository repository, EvmLogScanner logScanner,
                             String sepoliaRpcUrl, int sepoliaConfirmations) {
        this.repository = repository;
        this.logScanner = logScanner;
        this.rpcNodeService = null;
        this.fixedRpcUrl = sepoliaRpcUrl;
        this.fixedConfirmations = sepoliaConfirmations;
    }
    /**
     * 获取或查询 {@code getNativeBalance} 对应的数据，供调用方读取当前状态。
     */
    public BigDecimal getNativeBalance(String address) throws IOException {
        return withDefaultRpc(rpcUrl -> getNativeBalance(rpcUrl, address));
    }
    /**
     * 获取或查询 {@code getNativeBalance} 对应的数据，供调用方读取当前状态。
     */
    public BigDecimal getNativeBalance(String rpcUrl, String address) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            BigInteger wei = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
            return weiToEth(wei);
        } finally {
            web3j.shutdown();
        }
    }
    /**
     * 获取或查询 {@code getPendingNonce} 对应的数据，供调用方读取当前状态。
     */
    public BigInteger getPendingNonce(String address) throws IOException {
        return withDefaultRpc(rpcUrl -> getPendingNonce(rpcUrl, address));
    }
    /**
     * 获取或查询 {@code getPendingNonce} 对应的数据，供调用方读取当前状态。
     */
    public BigInteger getPendingNonce(String rpcUrl, String address) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        } finally {
            web3j.shutdown();
        }
    }
    /**
     * 获取或查询 {@code getGasPriceGwei} 对应的数据，供调用方读取当前状态。
     */
    public BigDecimal getGasPriceGwei() throws IOException {
        return withDefaultRpc(this::getGasPriceGwei);
    }
    /**
     * 获取或查询 {@code getGasPriceGwei} 对应的数据，供调用方读取当前状态。
     */
    public BigDecimal getGasPriceGwei(String rpcUrl) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            BigInteger wei = web3j.ethGasPrice().send().getGasPrice();
            return new BigDecimal(wei).divide(new BigDecimal("1000000000"), 9, RoundingMode.DOWN);
        } finally {
            web3j.shutdown();
        }
    }
    /**
     * 获取或查询 {@code getLatestBlockNumber} 对应的数据，供调用方读取当前状态。
     */
    public BigInteger getLatestBlockNumber() throws IOException {
        return withDefaultRpc(this::getLatestBlockNumber);
    }
    /**
     * 获取或查询 {@code getLatestBlockNumber} 对应的数据，供调用方读取当前状态。
     */
    public BigInteger getLatestBlockNumber(String rpcUrl) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            return web3j.ethBlockNumber().send().getBlockNumber();
        } finally {
            web3j.shutdown();
        }
    }
    /**
     * 获取或查询 {@code getLatestBlockNumber} 对应的数据，供调用方读取当前状态。
     */
    public BigInteger getLatestBlockNumber(ChainType chainType) throws IOException {
        return withDefaultRpc(chainType, this::getLatestBlockNumber);
    }
    /**
     * 扫描或观察 {@code scanNativeEthDeposits} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanNativeEthDeposits(long blockHeight) throws IOException {
        return withDefaultRpc(rpcUrl -> scanNativeDeposits(ChainType.ETH, profile(ChainType.ETH).getNativeSymbol(),
                rpcUrl, blockHeight));
    }
    /**
     * 扫描或观察 {@code scanAndCreditNativeEth} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCreditNativeEth(long blockHeight) throws IOException {
        AccountChainProfile profile = profile(ChainType.ETH);
        int confirmations = fixedRpcUrl != null ? fixedConfirmations : profile.getDepositConfirmations();
        return withDefaultRpc(ChainType.ETH, rpcUrl -> scanAndCreditNative(ChainType.ETH, profile.getNativeSymbol(),
                rpcUrl, confirmations, blockHeight));
    }
    /**
     * 扫描或观察 {@code scanAndCreditNative} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCreditNative(ChainType chainType, long blockHeight) throws IOException {
        AccountChainProfile profile = profile(chainType);
        int confirmations = fixedRpcUrl != null ? fixedConfirmations : profile.getDepositConfirmations();
        return withDefaultRpc(chainType, rpcUrl -> scanAndCreditNative(chainType, profile.getNativeSymbol(),
                rpcUrl, confirmations, blockHeight));
    }
    /**
     * 扫描或观察 {@code scanAndCreditErc20} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCreditErc20(ChainType chainType, long blockHeight) throws IOException {
        AccountChainProfile profile = profile(chainType);
        int confirmations = fixedRpcUrl != null ? fixedConfirmations : profile.getDepositConfirmations();
        return withDefaultRpc(chainType, rpcUrl -> scanAndCreditErc20(chainType, rpcUrl, confirmations, blockHeight));
    }
    /**
     * 处理 {@code reconcileCreditedDeposits} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public void reconcileCreditedDeposits(ChainType chainType, long latestHeight) throws IOException {
        long minimumHeight = Math.max(0L, latestHeight - FINALITY_AUDIT_DEPTH + 1L);
        List<Long> heights = repository.listCanonicalDepositBlockHeights(
                chainType.name(), minimumHeight);
        if (heights.isEmpty()) {
            return;
        }
        List<Long> reorgedHeights = withDefaultRpc(chainType, rpcUrl -> {
            Web3j web3j = web3j(rpcUrl);
            try {
                List<Long> reorged = new ArrayList<>();
                for (Long height : heights) {
                    if (observeBlock(web3j, chainType, height).reorg()) {
                        reorged.add(height);
                    }
                }
                return reorged;
            } finally {
                web3j.shutdown();
            }
        });
        for (Long height : reorgedHeights) {
            // The normal scan cursor is monotonic, so replacement blocks must be processed here.
            scanAndCreditNative(chainType, height);
            scanAndCreditErc20(chainType, height);
        }
    }

    /**
     * 扫描或观察 {@code scanNativeDeposits} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanNativeDeposits(ChainType chainType, String nativeSymbol,
                                                 String rpcUrl, long blockHeight) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            return scanNativeDeposits(web3j, chainType, nativeSymbol, blockHeight);
        } finally {
            web3j.shutdown();
        }
    }

    /**
     * 扫描或观察 {@code scanAndCreditNative} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCreditNative(ChainType chainType, String nativeSymbol, String rpcUrl,
                                                  int requiredConfirmations, long blockHeight) throws IOException {
        requireTaskEnabled(chainType, WalletRuntimeConfigService.TASK_SCAN, "evm scanAndCreditNative");
        List<DepositEvent> events = scanNativeDeposits(chainType, nativeSymbol, rpcUrl, blockHeight);
        for (DepositEvent event : events) {
            repository.recordAndCreditDeposit(event, requiredConfirmations);
            repository.recordEvmTransaction(EvmTransactionRecord.builder()
                    .chain(event.chainType().name())
                    .txHash(event.txId())
                    .fromAddress(event.fromAddress())
                    .toAddress(event.toAddress())
                    .assetSymbol(event.assetSymbol())
                    .contractAddress(event.tokenAddress())
                    .amount(event.amount())
                    .fee(BigDecimal.ZERO)
                    .nonce(null)
                    .blockHeight(event.blockHeight())
                    .confirmations(event.confirmations())
                    .status(event.confirmations() >= requiredConfirmations ? "CREDITED" : "CONFIRMING")
                    .rawPayload(event.rawPayload())
                    .build());
        }
        long safeHeight = Math.max(0L, blockHeight - requiredConfirmations + 1L);
        repository.updateScanHeight(chainType.name(), "native-evm", blockHeight, safeHeight);
        return events;
    }

    /**
     * 扫描或观察 {@code scanAndCreditErc20} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCreditErc20(ChainType chainType, String rpcUrl,
                                                 int requiredConfirmations, long blockHeight) throws IOException {
        requireTaskEnabled(chainType, WalletRuntimeConfigService.TASK_SCAN, "evm scanAndCreditErc20");
        Web3j web3j = web3j(rpcUrl);
        try {
            observeBlock(web3j, chainType, blockHeight);
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            int confirmations = confirmations(latest, blockHeight);
            Set<String> trackedAddresses = repository.listEnabledChainScanAddresses(chainType.name());
            if (trackedAddresses.isEmpty()) {
                log.warn("EVM token scanner skipped: no enabled {} chain_address rows", chainType.name());
                return List.of();
            }

            ArrayList<DepositEvent> events = new ArrayList<>();
            for (var token : repository.listTokens(chainType.name())) {
                EthFilter filter = new EthFilter(
                        DefaultBlockParameter.valueOf(BigInteger.valueOf(blockHeight)),
                        DefaultBlockParameter.valueOf(BigInteger.valueOf(blockHeight)),
                        token.getContractAddress());
                filter.addSingleTopic(TRANSFER_TOPIC);
                EthLog logs = web3j.ethGetLogs(filter).send();
                for (EthLog.LogResult<?> result : logs.getLogs()) {
                    if (!(result.get() instanceof Log log)) {
                        continue;
                    }
                    List<DepositEvent> decoded = logScanner.scanTransfers(chainType, token, blockHeight, confirmations, List.of(log));
                    for (DepositEvent event : decoded) {
                        if (!trackedAddresses.contains(lower(event.toAddress()))) {
                            continue;
                        }
                        long logIndex = log.getLogIndex() == null ? 0L : log.getLogIndex().longValue();
                        repository.recordAndCreditDeposit(event, logIndex, requiredConfirmations);
                        String status = event.confirmations() >= requiredConfirmations ? "CREDITED" : "CONFIRMING";
                        repository.recordEvmTokenTransfer(event, logIndex, status);
                        events.add(event);
                    }
                }
            }
            long safeHeight = Math.max(0L, blockHeight - requiredConfirmations + 1L);
            repository.updateScanHeight(chainType.name(), "erc20-evm", blockHeight, safeHeight);
            return events;
        } finally {
            web3j.shutdown();
        }
    }

    /**
     * 扫描或观察 {@code scanNativeDeposits} 对应的链上状态，并转换为业务可用结果。
     */
    private List<DepositEvent> scanNativeDeposits(Web3j web3j, ChainType chainType,
                                                  String nativeSymbol, long blockHeight) throws IOException {
        observeBlock(web3j, chainType, blockHeight);
        Set<String> trackedAddresses = repository.listEnabledChainScanAddresses(chainType.name());
        if (trackedAddresses.isEmpty()) {
            log.warn("EVM native scanner skipped: no enabled {} chain_address rows", chainType.name());
            return List.of();
        }

        BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
        int confirmations = confirmations(latest, blockHeight);
        EthBlock.Block block = web3j.ethGetBlockByNumber(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(blockHeight)), true).send().getBlock();
        if (block == null) {
            throw new IllegalStateException("ETH block not found: " + blockHeight);
        }
        ArrayList<DepositEvent> events = new ArrayList<>();
        for (EthBlock.TransactionResult<?> result : block.getTransactions()) {
            Object value = result.get();
            if (!(value instanceof Transaction tx)) {
                continue;
            }
            String to = lower(tx.getTo());
            if (to == null || !trackedAddresses.contains(to)) {
                continue;
            }
            String from = lower(tx.getFrom());
            if (tx.getValue() == null || tx.getValue().signum() <= 0) {
                continue;
            }
            events.add(new DepositEvent(chainType, nativeSymbol, tx.getHash(), from, to,
                    weiToEth(tx.getValue()), blockHeight, block.getHash(), confirmations, null, tx.toString()));
        }
        return events;
    }

    /**
     * 设置或更新 {@code observeBlock} 对应的状态，并保持相关业务字段一致。
     */
    private ChainJdbcRepository.BlockObservation observeBlock(
            Web3j web3j, ChainType chainType, long blockHeight) throws IOException {
        EthBlock.Block block = web3j.ethGetBlockByNumber(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(blockHeight)), false).send().getBlock();
        if (block == null) {
            throw new IllegalStateException("EVM block not found: " + blockHeight);
        }
        return repository.observeCanonicalBlock(chainType.name(), "evm-canonical", blockHeight,
                block.getHash(), block.getParentHash());
    }
    /**
     * 执行 {@code web3j} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Web3j web3j(String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl));
    }
    /**
     * 获取或查询 {@code profile} 对应的数据，并向调用方返回当前业务状态。
     */
    private AccountChainProfile profile(ChainType chainType) {
        return repository.findProfileByChain(chainType.name())
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chainType.name()));
    }
    /**
     * 执行 {@code withDefaultRpc} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private <T> T withDefaultRpc(IoRpcRequest<T> request) throws IOException {
        return withDefaultRpc(ChainType.ETH, request);
    }
    /**
     * 执行 {@code withDefaultRpc} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private <T> T withDefaultRpc(ChainType chainType, IoRpcRequest<T> request) throws IOException {
        if (fixedRpcUrl != null && !fixedRpcUrl.isBlank()) {
            return request.apply(fixedRpcUrl);
        }
        AccountChainProfile profile = profile(chainType);
        try {
            return rpcNodeService.withFailover(chainType.name(), profile.getNetwork(), node -> {
                try {
                    return request.apply(node.getRpcUrl());
                } catch (IOException e) {
                    throw new RpcIoException(e);
                }
            });
        } catch (RpcIoException e) {
            throw e.getCause();
        }
    }

    /**
     * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
     */
    @FunctionalInterface
    private interface IoRpcRequest<T> {
        /**
         * 设置或更新 {@code apply} 对应的状态，并保持相关业务字段一致。
         */
        T apply(String rpcUrl) throws IOException;
    }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    private static class RpcIoException extends RuntimeException {
        /**
         * 构造 {@code RpcIoException}，初始化该组件运行所需的状态和依赖。
         */
        RpcIoException(IOException cause) {
            super(cause);
        }

        /**
         * 获取或查询 {@code getCause} 对应的数据，供调用方读取当前状态。
         */
        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
    /**
     * 处理 {@code confirmations} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private static int confirmations(BigInteger latest, long blockHeight) {
        BigInteger confirmations = latest.subtract(BigInteger.valueOf(blockHeight)).add(BigInteger.ONE);
        return confirmations.signum() < 0 ? 0 : confirmations.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }
    /**
     * 执行 {@code weiToEth} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static BigDecimal weiToEth(BigInteger wei) {
        return new BigDecimal(wei).divide(WEI_PER_ETH, 18, RoundingMode.DOWN);
    }
    /**
     * 校验 {@code requireTaskEnabled} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requireTaskEnabled(ChainType chainType, String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(chainType.name(), task, operation);
        }
    }
    /**
     * 转换或计算 {@code lower} 对应的值，统一金额、格式和边界规则。
     */
    private static String lower(String address) {
        return address == null ? null : address.toLowerCase(Locale.ROOT);
    }
}
