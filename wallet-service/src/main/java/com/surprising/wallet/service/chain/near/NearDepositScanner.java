package com.surprising.wallet.service.chain.near;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.NearTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public
class NearDepositScanner {
    private static final String CHAIN = "NEAR";
    private static final String SYMBOL = "NEAR";
    private static final String SCANNER = "near-block-scanner";
    private static final String EMPTY_MERKLE_ROOT = "11111111111111111111111111111111";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final NearRpcClient rpc;
    private final ChainJdbcRepository repository;
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    public List<DepositEvent> scanAndCredit() {
        if (!scanning.compareAndSet(false, true)) {
            return List.of();
        }
        try {
            return doScanAndCredit();
        } finally {
            scanning.set(false);
        }
    }
    private List<DepositEvent> doScanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "near scanAndCredit");
        AccountChainProfile profile = profile();
        int requiredConfirmations = requiredConfirmations(profile);
        long latest = rpc.latestFinalBlockHeight();
        long safeHeight = Math.max(0L, latest - requiredConfirmations + 1L);
        long start = scanStart(profile, safeHeight);
        if (start > safeHeight) {
            repository.updateScanHeight(CHAIN, SCANNER, latest, safeHeight);
            return List.of();
        }
        long end = Math.min(safeHeight, start + maxBlocksPerRun(profile) - 1L);
        Map<String, ChainAddressRecord> nativeAddresses = trackedNativeDepositAddresses();
        Map<String, TokenDefinition> tokensByContract = tokensByContract();
        Map<String, Map<String, ChainAddressRecord>> tokenAddresses = trackedTokenDepositAddresses(tokensByContract);
        List<DepositEvent> events = new ArrayList<>();
        if (!nativeAddresses.isEmpty() || !tokenAddresses.isEmpty()) {
            for (long height = start; height <= end; height++) {
                scanBlock(height, latest, requiredConfirmations,
                        nativeAddresses, tokensByContract, tokenAddresses, events);
                repository.updateScanHeight(CHAIN, SCANNER, latest, height);
            }
        }
        repository.updateScanHeight(CHAIN, SCANNER, latest, end);
        return events;
    }

    private void scanBlock(long height, long latest, int requiredConfirmations,
                           Map<String, ChainAddressRecord> nativeAddresses,
                           Map<String, TokenDefinition> tokensByContract,
                           Map<String, Map<String, ChainAddressRecord>> tokenAddresses,
                           List<DepositEvent> events) {
        JsonNode block;
        try {
            block = rpc.block(height);
        } catch (IllegalStateException e) {
            if (NearRpcClient.isUnknownBlockError(e.getMessage())) {
                return;
            }
            throw e;
        }
        for (JsonNode chunkHeader : block.path("chunks")) {
            if (EMPTY_MERKLE_ROOT.equals(chunkHeader.path("tx_root").asText())) {
                continue;
            }
            String chunkHash = chunkHeader.path("chunk_hash").asText();
            if (chunkHash.isBlank()) {
                continue;
            }
            JsonNode chunk = rpc.chunk(chunkHash);
            for (NativeTransfer transfer : nativeTransfers(chunk, height, latest)) {
                ChainAddressRecord address = nativeAddresses.get(normalize(transfer.receiver()));
                if (address == null || !transactionSucceeded(transfer)) {
                    continue;
                }
                DepositEvent event = new DepositEvent(ChainType.NEAR, SYMBOL, transfer.txHash(),
                        transfer.sender(), address.getAddress(), transfer.amount(), transfer.blockHeight(),
                        transfer.txHash(), transfer.confirmations(), null, transfer.rawPayload());
                repository.recordNearTransaction(NearTransactionRecord.builder()
                        .chain(CHAIN)
                        .txHash(transfer.txHash())
                        .actionIndex((long) transfer.actionIndex())
                        .sender(transfer.sender())
                        .receiver(address.getAddress())
                        .assetSymbol(SYMBOL)
                        .amount(transfer.amount())
                        .gasBurnt(transfer.gasBurnt())
                        .blockHeight(transfer.blockHeight())
                        .status(transfer.confirmations() >= requiredConfirmations ? "CONFIRMED" : "CONFIRMING")
                        .rawPayload(transfer.rawPayload())
                        .build());
                repository.recordAndCreditDeposit(event, transfer.actionIndex(), requiredConfirmations,
                        address.getAccountId());
                events.add(event);
            }
            for (TokenTransfer transfer : tokenTransfers(chunk, tokensByContract, height, latest)) {
                Map<String, ChainAddressRecord> byReceiver = tokenAddresses.get(normalize(transfer.contractId()));
                if (byReceiver == null) {
                    continue;
                }
                ChainAddressRecord address = byReceiver.get(normalize(transfer.receiver()));
                if (address == null || !transactionSucceeded(transfer)) {
                    continue;
                }
                DepositEvent event = new DepositEvent(ChainType.NEAR, transfer.symbol(), transfer.txHash(),
                        transfer.sender(), address.getAddress(), transfer.amount(), transfer.blockHeight(),
                        transfer.txHash(), transfer.confirmations(), transfer.contractId(), transfer.rawPayload());
                repository.recordNearTransaction(NearTransactionRecord.builder()
                        .chain(CHAIN)
                        .txHash(transfer.txHash())
                        .actionIndex((long) transfer.actionIndex())
                        .sender(transfer.sender())
                        .receiver(address.getAddress())
                        .assetSymbol(transfer.symbol())
                        .amount(transfer.amount())
                        .gasBurnt(transfer.gasBurnt())
                        .blockHeight(transfer.blockHeight())
                        .status(transfer.confirmations() >= requiredConfirmations ? "CONFIRMED" : "CONFIRMING")
                        .rawPayload(transfer.rawPayload())
                        .build());
                repository.recordAndCreditDeposit(event, transfer.actionIndex(), requiredConfirmations,
                        address.getAccountId());
                events.add(event);
            }
        }
    }
    private boolean transactionSucceeded(ScannedTransfer transfer) {
        JsonNode result = rpc.transactionStatus(transfer.txHash(), transfer.sender());
        JsonNode status = result.path("status");
        return status.has("SuccessValue") || status.has("SuccessReceiptId");
    }
    static List<NativeTransfer> nativeTransfers(JsonNode chunk, long blockHeight, long latestHeight) {
        List<NativeTransfer> transfers = new ArrayList<>();
        for (JsonNode transaction : chunk.path("transactions")) {
            String sender = transaction.path("signer_id").asText();
            String receiver = transaction.path("receiver_id").asText();
            String hash = transaction.path("hash").asText();
            if (sender.isBlank() || receiver.isBlank() || hash.isBlank()) {
                continue;
            }
            JsonNode actions = transaction.path("actions");
            for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                JsonNode transfer = actions.get(actionIndex).path("Transfer");
                if (transfer.isMissingNode()) {
                    continue;
                }
                BigInteger yocto = new BigInteger(transfer.path("deposit").asText("0"));
                if (yocto.signum() <= 0) {
                    continue;
                }
                transfers.add(new NativeTransfer(hash, sender, receiver,
                        NearTransactionService.fromYocto(yocto), blockHeight,
                        confirmations(latestHeight, blockHeight), actionIndex, 0L, transaction.toString()));
            }
        }
        return transfers;
    }

    static List<TokenTransfer> tokenTransfers(JsonNode chunk, Map<String, TokenDefinition> tokensByContract,
                                              long blockHeight, long latestHeight) {
        List<TokenTransfer> transfers = new ArrayList<>();
        for (JsonNode transaction : chunk.path("transactions")) {
            String sender = transaction.path("signer_id").asText();
            String contractId = transaction.path("receiver_id").asText();
            String hash = transaction.path("hash").asText();
            TokenDefinition token = tokensByContract.get(normalize(contractId));
            if (token == null || sender.isBlank() || hash.isBlank()) {
                continue;
            }
            JsonNode actions = transaction.path("actions");
            for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                JsonNode functionCall = actions.get(actionIndex).path("FunctionCall");
                String methodName = functionCall.path("method_name").asText();
                if (functionCall.isMissingNode()
                        || (!"ft_transfer".equals(methodName) && !"ft_transfer_call".equals(methodName))) {
                    continue;
                }
                JsonNode args = decodeFunctionArgs(functionCall.path("args").asText());
                String receiver = args.path("receiver_id").asText();
                BigInteger atomicAmount = new BigInteger(args.path("amount").asText("0"));
                if (receiver.isBlank() || atomicAmount.signum() <= 0) {
                    continue;
                }
                int decimals = token.getDecimals() == null ? 24 : token.getDecimals();
                BigDecimal amount = new BigDecimal(atomicAmount).movePointLeft(decimals)
                        .stripTrailingZeros();
                transfers.add(new TokenTransfer(hash, sender, contractId, receiver, token.getSymbol(),
                        contractId, amount, blockHeight, confirmations(latestHeight, blockHeight),
                        actionIndex, 0L, transaction.toString()));
            }
        }
        return transfers;
    }
    private Map<String, ChainAddressRecord> trackedNativeDepositAddresses() {
        Map<String, ChainAddressRecord> addresses = new HashMap<>();
        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, SYMBOL)) {
            if ("DEPOSIT".equals(address.getWalletRole())) {
                addresses.put(normalize(address.getAddress()), address);
            }
        }
        return addresses;
    }
    private Map<String, TokenDefinition> tokensByContract() {
        Map<String, TokenDefinition> tokens = new HashMap<>();
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            if (token.getContractAddress() != null && Boolean.TRUE.equals(token.getActive())) {
                tokens.put(normalize(token.getContractAddress()), token);
            }
        }
        return tokens;
    }

    private Map<String, Map<String, ChainAddressRecord>> trackedTokenDepositAddresses(
            Map<String, TokenDefinition> tokensByContract) {
        Map<String, Map<String, ChainAddressRecord>> addresses = new HashMap<>();
        for (TokenDefinition token : tokensByContract.values()) {
            Map<String, ChainAddressRecord> byReceiver = new HashMap<>();
            for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, token.getSymbol())) {
                if ("DEPOSIT".equals(address.getWalletRole())) {
                    byReceiver.put(normalize(address.getAddress()), address);
                }
            }
            if (!byReceiver.isEmpty()) {
                addresses.put(normalize(token.getContractAddress()), byReceiver);
            }
        }
        return addresses;
    }
    private long scanStart(AccountChainProfile profile, long safeHeight) {
        return repository.findScanSafeHeight(CHAIN, SCANNER)
                .map(height -> Math.min(height + 1L, safeHeight + 1L))
                .orElseGet(() -> {
                    Long configured = profile.getScanStartHeight();
                    if (configured != null && configured > 0) {
                        return Math.min(configured, safeHeight + 1L);
                    }
                    return Math.max(0L, safeHeight - maxBlocksPerRun(profile) + 1L);
                });
    }
    private int maxBlocksPerRun(AccountChainProfile profile) {
        Long configured = profile.getScanMaxBlocksPerRun();
        if (configured != null && configured > 0) {
            return Math.toIntExact(Math.min(configured, 500L));
        }
        Integer batchSize = profile.getScanBatchSize();
        if (batchSize != null && batchSize > 0) {
            return Math.min(batchSize, 200);
        }
        return 50;
    }
    private int requiredConfirmations(AccountChainProfile profile) {
        Integer configured = profile.getDepositConfirmations();
        return configured == null || configured <= 0 ? 1 : configured;
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    private static int confirmations(long latestHeight, long blockHeight) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, latestHeight - blockHeight + 1L));
    }
    private static String normalize(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }
    private static JsonNode decodeFunctionArgs(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return MAPPER.nullNode();
        }
        try {
            return MAPPER.readTree(Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            return MAPPER.nullNode();
        }
    }
    interface ScannedTransfer {
        String txHash();

        String sender();
    }

    record NativeTransfer(String txHash, String sender, String receiver, BigDecimal amount,
                          long blockHeight, int confirmations, int actionIndex,
                          long gasBurnt, String rawPayload) implements ScannedTransfer {
    }

    record TokenTransfer(String txHash, String sender, String contractId, String receiver,
                         String symbol, String tokenAddress, BigDecimal amount, long blockHeight,
                         int confirmations, int actionIndex, long gasBurnt,
                         String rawPayload) implements ScannedTransfer {
    }
}
