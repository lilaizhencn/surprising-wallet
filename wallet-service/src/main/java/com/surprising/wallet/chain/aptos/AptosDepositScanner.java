package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AptosTransactionRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Aptos 链充值扫描器，通过扫描链上交易事件识别 Fungible Asset Deposit 事件。
 *
 * <p>扫描策略：从上次扫描高度开始，按版本号递增拉取交易列表，
 * 遍历每条成功交易的 events，匹配 {@code 0x1::fungible_asset::Deposit} 事件类型。
 * 通过解析 event 中的 store -> owner 和 store -> metadata 关联，
 * 判断充值目标是 APT（metadata=0xa）还是 FA 同质化资产代币。</p>
 *
 * <p>同时从交易的 changes 中预提取 store 的 owner 和 metadata 信息，
 * 减少后续 RPC 查询次数。平台内部地址之间的转账会被过滤掉。</p>
 *
 * @see AptosRpcClient
 */
@Service
@RequiredArgsConstructor
public class AptosDepositScanner {

    /** 链标识常量 */
    private static final String CHAIN = "APTOS";

    /** 扫描器名称，用于数据库中持久化扫描高度 */
    static final String SCANNER = "aptos-fa-event-scanner";

    /** Fa Deposit 事件类型 */
    private static final String FUNGIBLE_DEPOSIT = "0x1::fungible_asset::Deposit";

    /** FungibleStore 资源类型 */
    private static final String FUNGIBLE_STORE = "0x1::fungible_asset::FungibleStore";

    /** ObjectCore 资源类型 */
    private static final String OBJECT_CORE = "0x1::object::ObjectCore";

    /** APT 的 metadata 地址（0xa 规范化后），用于区分 APT 和其他 FA 代币 */
    private static final String APT_METADATA = AptosHex.normalizeAddress("0xa");

    /** Aptos RPC 客户端 */
    private final AptosRpcClient rpc;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选，用于任务开关控制） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 执行一次完整的充值扫描并触发入账。
     *
     * @return 发现的充值事件列表
     */
    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "aptos scanAndCredit");
        AccountChainProfile profile = profile();
        int scanLimit = scanLimit(profile);
        long ledgerVersion = rpc.ledgerVersion();
        List<DepositEvent> events = new ArrayList<>();
        Map<String, ChainAddressRecord> nativeAddresses = repository.listChainAddresses(CHAIN, "APT").stream()
                .filter(address -> "DEPOSIT".equals(address.getWalletRole()))
                .collect(Collectors.toMap(address -> AptosHex.normalizeAddress(address.getAddress()),
                        address -> address, (a, b) -> a));
        Map<String, TokenDefinition> metadataTokens = repository.listTokens(CHAIN).stream()
                .filter(AptosFungibleAsset::supports)
                .collect(Collectors.toMap(token -> AptosHex.normalizeAddress(token.getContractAddress()),
                        token -> token, (a, b) -> a));

        long start = repository.findScanSafeHeight(CHAIN, SCANNER)
                .map(height -> Math.min(height + 1L, ledgerVersion))
                .orElse(Math.max(0L, ledgerVersion - scanLimit + 1L));
        while (start <= ledgerVersion) {
            int limit = (int) Math.min(scanLimit, ledgerVersion - start + 1L);
            JsonNode transactions = rpc.transactions(start, limit);
            for (JsonNode transaction : transactions) {
                scanTransaction(transaction, nativeAddresses, metadataTokens, profile, ledgerVersion, events);
            }
            start += limit;
        }
        long safeVersion = Math.max(0, ledgerVersion - profile.getDepositConfirmations() + 1L);
        repository.updateScanHeight(CHAIN, SCANNER, ledgerVersion, safeVersion);
        return events;
    }

    private void scanTransaction(JsonNode transaction, Map<String, ChainAddressRecord> nativeAddresses,
                                 Map<String, TokenDefinition> metadataTokens, AccountChainProfile profile,
                                 long ledgerVersion, List<DepositEvent> events) {
        if (!"user_transaction".equals(transaction.path("type").asText())
                || !transaction.path("success").asBoolean(false)) {
            return;
        }
        String sender = AptosHex.normalizeAddress(transaction.path("sender").asText("0x0"));
        if (isPlatformAddress(sender)) {
            return;
        }
        Map<String, String> storeOwners = storeOwners(transaction);
        Map<String, String> storeMetadata = storeMetadata(transaction);
        JsonNode eventNodes = transaction.path("events");
        for (int eventIndex = 0; eventIndex < eventNodes.size(); eventIndex++) {
            JsonNode eventNode = eventNodes.get(eventIndex);
            if (!FUNGIBLE_DEPOSIT.equals(eventNode.path("type").asText())) {
                continue;
            }
            BigDecimal rawAmount = new BigDecimal(eventNode.path("data").path("amount").asText("0"));
            if (rawAmount.signum() <= 0) {
                continue;
            }
            String store = AptosHex.normalizeAddress(eventNode.path("data").path("store").asText("0x0"));
            String owner = Optional.ofNullable(storeOwners.get(store))
                    .or(() -> rpc.fungibleStoreOwner(store))
                    .orElse(null);
            String metadata = Optional.ofNullable(storeMetadata.get(store))
                    .or(() -> rpc.fungibleStoreMetadata(store))
                    .orElse(null);
            if (owner == null || metadata == null) {
                continue;
            }
            ResolvedAsset asset = resolveAsset(owner, metadata, nativeAddresses, metadataTokens);
            if (asset == null) {
                continue;
            }
            BigDecimal amount = rawAmount.movePointLeft(asset.decimals());
            long version = transaction.path("version").asLong();
            String hash = transaction.path("hash").asText(Long.toString(version));
            int confirmations = (int) Math.min(Integer.MAX_VALUE, Math.max(1, ledgerVersion - version + 1));
            DepositEvent event = new DepositEvent(ChainType.APTOS, asset.symbol(), hash, sender,
                    asset.address().getAddress(), amount, version, hash, confirmations,
                    asset.tokenAddress(), transaction.toString());
            repository.recordAptosTransaction(AptosTransactionRecord.builder()
                    .chain(CHAIN)
                    .txHash(hash)
                    .sender(sender)
                    .receiver(asset.address().getAddress())
                    .assetSymbol(asset.symbol())
                    .coinType(asset.assetType())
                    .amount(amount)
                    .gasUsed(transaction.path("gas_used").asLong(0))
                    .gasUnitPrice(transaction.path("gas_unit_price").asLong(0))
                    .version(version)
                    .sequenceNumber(transaction.path("sequence_number").asLong(0))
                    .confirmations(confirmations)
                    .status(confirmations >= profile.getDepositConfirmations() ? "CONFIRMED" : "CONFIRMING")
                    .rawPayload(transaction.toString())
                    .build());
            repository.recordAndCreditDeposit(event, eventIndex, profile.getDepositConfirmations(),
                    asset.address().getAccountId());
            events.add(event);
        }
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private int scanLimit(AccountChainProfile profile) {
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 100 : batchSize;
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    private ResolvedAsset resolveAsset(String owner, String metadata,
                                       Map<String, ChainAddressRecord> nativeAddresses,
                                       Map<String, TokenDefinition> metadataTokens) {
        if (APT_METADATA.equals(metadata) && nativeAddresses.containsKey(owner)) {
            int decimals = repository.findAsset(CHAIN, "APT").map(asset -> asset.getDecimals()).orElse(8);
            return new ResolvedAsset("APT", AptosRpcClient.aptCoinType(), null, nativeAddresses.get(owner),
                    decimals);
        }
        TokenDefinition token = metadataTokens.get(metadata);
        if (token == null) {
            return null;
        }
        ChainAddressRecord address = nativeAddresses.get(owner);
        if (address == null) {
            return null;
        }
        return new ResolvedAsset(token.getSymbol(), token.getContractAddress(),
                metadata, address, token.getDecimals());
    }
    private Map<String, String> storeOwners(JsonNode transaction) {
        Map<String, String> owners = new HashMap<>();
        for (JsonNode change : transaction.path("changes")) {
            if ("write_resource".equals(change.path("type").asText())
                    && OBJECT_CORE.equals(change.path("data").path("type").asText())) {
                owners.put(AptosHex.normalizeAddress(change.path("address").asText("0x0")),
                        AptosHex.normalizeAddress(change.path("data").path("data").path("owner").asText("0x0")));
            }
        }
        return owners;
    }
    private Map<String, String> storeMetadata(JsonNode transaction) {
        Map<String, String> metadata = new HashMap<>();
        for (JsonNode change : transaction.path("changes")) {
            if ("write_resource".equals(change.path("type").asText())
                    && FUNGIBLE_STORE.equals(change.path("data").path("type").asText())) {
                metadata.put(AptosHex.normalizeAddress(change.path("address").asText("0x0")),
                        AptosHex.normalizeAddress(change.path("data").path("data").path("metadata")
                                .path("inner").asText("0x0")));
            }
        }
        return metadata;
    }
    private boolean isPlatformAddress(String address) {
        for (ChainAddressRecord tracked : repository.listChainAddresses(CHAIN)) {
            if (sameAddress(address, tracked.getAddress()) || sameAddress(address, tracked.getOwnerAddress())) {
                return true;
            }
        }
        return false;
    }
    private boolean sameAddress(String first, String second) {
        if (first == null || first.isBlank() || second == null || second.isBlank()) {
            return false;
        }
        try {
            return AptosHex.normalizeAddress(first).equals(AptosHex.normalizeAddress(second));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private record ResolvedAsset(String symbol, String assetType, String tokenAddress, ChainAddressRecord address,
                                 int decimals) {
    }
}
