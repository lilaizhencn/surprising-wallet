package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * RPC-backed native ETH scanner for Sepolia validation and production-safe EVM deposit flow.
 * It only credits addresses already known to the wallet database and skips internal sends.
 */
@Slf4j
@Component
public class EvmDepositScanner {
    private static final BigDecimal WEI_PER_ETH = new BigDecimal("1000000000000000000");
    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final ChainJdbcRepository repository;
    private final EvmLogScanner logScanner;
    private final String sepoliaRpcUrl;
    private final int sepoliaConfirmations;

    @Autowired
    public EvmDepositScanner(ChainJdbcRepository repository, EvmLogScanner logScanner,
                             @Value("${atomex.chains.ethereum-sepolia.rpcUrl:https://ethereum-sepolia-rpc.publicnode.com}")
                             String sepoliaRpcUrl,
                             @Value("${atomex.chains.ethereum-sepolia.confirmations:12}") int sepoliaConfirmations) {
        this.repository = repository;
        this.logScanner = logScanner;
        this.sepoliaRpcUrl = sepoliaRpcUrl;
        this.sepoliaConfirmations = sepoliaConfirmations;
    }

    public EvmDepositScanner(ChainJdbcRepository repository,
                             String sepoliaRpcUrl,
                             int sepoliaConfirmations) {
        this(repository, new EvmLogScanner(), sepoliaRpcUrl, sepoliaConfirmations);
    }

    public BigDecimal getNativeBalance(String address) throws IOException {
        return getNativeBalance(sepoliaRpcUrl, address);
    }

    public BigDecimal getNativeBalance(String rpcUrl, String address) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            BigInteger wei = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
            return weiToEth(wei);
        } finally {
            web3j.shutdown();
        }
    }

    public BigInteger getPendingNonce(String address) throws IOException {
        return getPendingNonce(sepoliaRpcUrl, address);
    }

    public BigInteger getPendingNonce(String rpcUrl, String address) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        } finally {
            web3j.shutdown();
        }
    }

    public BigDecimal getGasPriceGwei() throws IOException {
        return getGasPriceGwei(sepoliaRpcUrl);
    }

    public BigDecimal getGasPriceGwei(String rpcUrl) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            BigInteger wei = web3j.ethGasPrice().send().getGasPrice();
            return new BigDecimal(wei).divide(new BigDecimal("1000000000"), 9, RoundingMode.DOWN);
        } finally {
            web3j.shutdown();
        }
    }

    public BigInteger getLatestBlockNumber() throws IOException {
        return getLatestBlockNumber(sepoliaRpcUrl);
    }

    public BigInteger getLatestBlockNumber(String rpcUrl) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            return web3j.ethBlockNumber().send().getBlockNumber();
        } finally {
            web3j.shutdown();
        }
    }

    public List<DepositEvent> scanNativeEthDeposits(long blockHeight) throws IOException {
        return scanNativeDeposits(ChainType.ETH, "ETH", sepoliaRpcUrl, blockHeight);
    }

    public List<DepositEvent> scanAndCreditNativeEth(long blockHeight) throws IOException {
        return scanAndCreditNative(ChainType.ETH, "ETH", sepoliaRpcUrl, sepoliaConfirmations, blockHeight);
    }

    public List<DepositEvent> scanNativeDeposits(ChainType chainType, String nativeSymbol,
                                                 String rpcUrl, long blockHeight) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            return scanNativeDeposits(web3j, chainType, nativeSymbol, blockHeight);
        } finally {
            web3j.shutdown();
        }
    }

    public List<DepositEvent> scanAndCreditNative(ChainType chainType, String nativeSymbol, String rpcUrl,
                                                  int requiredConfirmations, long blockHeight) throws IOException {
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

    public List<DepositEvent> scanAndCreditErc20(ChainType chainType, String rpcUrl,
                                                 int requiredConfirmations, long blockHeight) throws IOException {
        Web3j web3j = web3j(rpcUrl);
        try {
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            int confirmations = confirmations(latest, blockHeight);
            Set<String> trackedAddresses = repository.listEnabledHotWalletAddresses(chainType.name());
            if (trackedAddresses.isEmpty()) {
                log.warn("EVM token scanner skipped: no enabled {} hot_wallet_address rows", chainType.name());
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
                        if (trackedAddresses.contains(lower(event.fromAddress()))) {
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

    private List<DepositEvent> scanNativeDeposits(Web3j web3j, ChainType chainType,
                                                  String nativeSymbol, long blockHeight) throws IOException {
        Set<String> trackedAddresses = repository.listEnabledHotWalletAddresses(chainType.name());
        if (trackedAddresses.isEmpty()) {
            log.warn("EVM native scanner skipped: no enabled {} hot_wallet_address rows", chainType.name());
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
            if (from != null && trackedAddresses.contains(from)) {
                continue;
            }
            if (tx.getValue() == null || tx.getValue().signum() <= 0) {
                continue;
            }
            events.add(new DepositEvent(chainType, nativeSymbol, tx.getHash(), from, to,
                    weiToEth(tx.getValue()), blockHeight, confirmations, null, tx.toString()));
        }
        return events;
    }

    private Web3j web3j() {
        return web3j(sepoliaRpcUrl);
    }

    private Web3j web3j(String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl));
    }

    private static int confirmations(BigInteger latest, long blockHeight) {
        BigInteger confirmations = latest.subtract(BigInteger.valueOf(blockHeight)).add(BigInteger.ONE);
        return confirmations.signum() < 0 ? 0 : confirmations.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }

    private static BigDecimal weiToEth(BigInteger wei) {
        return new BigDecimal(wei).divide(WEI_PER_ETH, 18, RoundingMode.DOWN);
    }

    private static String lower(String address) {
        return address == null ? null : address.toLowerCase(Locale.ROOT);
    }
}
