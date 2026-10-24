package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.WithdrawalStatusChange;
import com.surprising.wallet.model.custody.WebhookEndpointRecord;
import com.surprising.wallet.model.custody.WebhookDeliveryTask;
import com.surprising.wallet.model.custody.TenantRecord;
import com.surprising.wallet.model.custody.NetworkFee;
import com.surprising.wallet.model.custody.IdempotencyRecord;
import com.surprising.wallet.model.custody.GasUsageRecord;
import com.surprising.wallet.model.custody.GasPricingMetadata;
import com.surprising.wallet.model.custody.GasAccountRecord;
import com.surprising.wallet.chain.model.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.service.CustodyEventService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 托管数据业务编排门面。
 *
 * <p>本类不执行 SQL，只组合各个单表仓储的结果并维护跨表事务边界。</p>
 */
@Component
public class CustodyRepository {
    /** 租户单表仓储。 */
    private final CustodyTenantTableRepository tenantTable;
    /** 租户用户单表仓储。 */
    private final CustodyTenantUserRepository tenantUsers;
    /** 安全单表仓储编排。 */
    private final CustodyApiKeyRepository apiKeys;
    private final CustodySessionRepository sessions;
    private final CustodyIpRuleRepository ipRules;
    /** 托管地址单表仓储。 */
    private final CustodyAddressRepository custodyAddresses;
    /** 链地址单表仓储。 */
    private final ChainAddressRepository chainAddresses;
    /** Gas 账户单表仓储。 */
    private final CustodyGasAccountRepository gasAccounts;
    /** Gas 使用单表仓储。 */
    private final CustodyGasUsageRepository gasUsages;
    /** 账本单表仓储。 */
    private final LedgerBalanceRepository ledgerBalances;
    /** 链配置单表仓储。 */
    private final ChainProfileRepository chainProfiles;
    /** 链资产单表仓储。 */
    private final ChainAssetRepository chainAssets;
    /** 代币配置单表仓储。 */
    private final TokenConfigRepository tokenConfigs;
    /** 幂等键单表仓储。 */
    private final CustodyIdempotencyRepository idempotencies;
    /** Webhook 端点单表仓储。 */
    private final CustodyWebhookEndpointRepository webhookEndpoints;
    /** Webhook 投递单表仓储。 */
    private final CustodyWebhookDeliveryRepository webhookDeliveries;
    /** Webhook 尝试单表仓储。 */
    private final CustodyWebhookDeliveryAttemptRepository webhookAttempts;
    /** 领域事件单表仓储。 */
    private final CustodyEventRepository events;
    /** 审计单表仓储。 */
    private final CustodyAuditRepository audits;
    /** 托管提现单表仓储。 */
    private final CustodyWithdrawalRepository custodyWithdrawals;
    /** 提现订单单表仓储。 */
    private final WithdrawalOrderRepository withdrawalOrders;
    /** 托管充值单表仓储。 */
    private final CustodyDepositRepository custodyDeposits;
    /** 重组赤字单表仓储。 */
    private final CustodyReorgDeficitRepository reorgDeficits;
    /** EVM 归集批次单表仓储。 */
    private final EvmCollectionBatchRepository collectionBatches;
    /** EVM 提现批次单表仓储。 */
    private final EvmWithdrawalBatchRepository withdrawalBatches;
    /** 租户链配置单表编排。 */
    private final CustodyTenantChainRepository tenantChains;
    /** EVM 交易单表仓储。 */
    private final EvmTransactionRepository evmTransactions;
    /** Solana 交易单表仓储。 */
    private final SolanaTransactionRepository solanaTransactions;
    /** Aptos 交易单表仓储。 */
    private final AptosTransactionRepository aptosTransactions;
    /** Sui 交易单表仓储。 */
    private final SuiTransactionRepository suiTransactions;
    /** TON 交易单表仓储。 */
    private final TonTransactionRepository tonTransactions;
    /** XRP 交易单表仓储。 */
    private final XrpTransactionRepository xrpTransactions;
    /** Monero 交易单表仓储。 */
    private final MoneroTransactionRepository moneroTransactions;
    /** TRON 交易单表仓储。 */
    private final TronTransactionRepository tronTransactions;
    /** 托管流水单表仓储。 */
    private final CustodyLedgerEntryRepository ledgerEntries;

    private final CustodyEventService eventService;

    public CustodyRepository(JdbcTemplate jdbc, CustodyTenantTableRepository tenantTable,
                             CustodyTenantUserRepository tenantUsers, CustodyEventService eventService,
                             CustodyApiKeyRepository apiKeys, CustodySessionRepository sessions,
                             CustodyIpRuleRepository ipRules) {
        this.tenantTable = tenantTable;
        this.tenantUsers = tenantUsers;
        this.eventService = eventService;
        this.apiKeys = apiKeys;
        this.sessions = sessions;
        this.ipRules = ipRules;
        this.custodyAddresses = new CustodyAddressRepository(jdbc);
        this.chainAddresses = new ChainAddressRepository(jdbc);
        this.gasAccounts = new CustodyGasAccountRepository(jdbc);
        this.gasUsages = new CustodyGasUsageRepository(jdbc);
        this.ledgerBalances = new LedgerBalanceRepository(jdbc);
        this.chainProfiles = new ChainProfileRepository(jdbc);
        this.chainAssets = new ChainAssetRepository(jdbc);
        this.tokenConfigs = new TokenConfigRepository(jdbc);
        this.idempotencies = new CustodyIdempotencyRepository(jdbc);
        this.webhookEndpoints = new CustodyWebhookEndpointRepository(jdbc);
        this.webhookDeliveries = new CustodyWebhookDeliveryRepository(jdbc);
        this.webhookAttempts = new CustodyWebhookDeliveryAttemptRepository(jdbc);
        this.events = new CustodyEventRepository(jdbc);
        this.audits = new CustodyAuditRepository(jdbc);
        this.custodyWithdrawals = new CustodyWithdrawalRepository(jdbc);
        this.withdrawalOrders = new WithdrawalOrderRepository(jdbc);
        this.custodyDeposits = new CustodyDepositRepository(jdbc);
        this.reorgDeficits = new CustodyReorgDeficitRepository(jdbc);
        this.collectionBatches = new EvmCollectionBatchRepository(jdbc);
        this.withdrawalBatches = new EvmWithdrawalBatchRepository(jdbc);
        this.tenantChains = new CustodyTenantChainRepository(jdbc);
        this.evmTransactions = new EvmTransactionRepository(jdbc);
        this.solanaTransactions = new SolanaTransactionRepository(jdbc);
        this.aptosTransactions = new AptosTransactionRepository(jdbc);
        this.suiTransactions = new SuiTransactionRepository(jdbc);
        this.tonTransactions = new TonTransactionRepository(jdbc);
        this.xrpTransactions = new XrpTransactionRepository(jdbc);
        this.moneroTransactions = new MoneroTransactionRepository(jdbc);
        this.tronTransactions = new TronTransactionRepository(jdbc);
        this.ledgerEntries = new CustodyLedgerEntryRepository(jdbc);
    }

    /** 创建租户及其管理员。 */
    @Transactional(rollbackFor = Throwable.class)
    public TenantRecord createTenant(UUID tenantId, String slug, String name, UUID adminId,
                                     String adminEmail, String adminDisplayName, String passwordHash) {
        tenantTable.insert(tenantId, slug, name);
        tenantUsers.insertTenantAdmin(adminId, tenantId, adminEmail, adminDisplayName, passwordHash);
        return requireTenant(tenantId);
    }

    /** 按 slug 查询租户。 */
    public Optional<TenantRecord> findTenantBySlug(String slug) {
        return tenantTable.findBySlug(slug);
    }

    /** 查询并校验租户。 */
    public TenantRecord requireTenant(UUID tenantId) {
        return tenantTable.findById(tenantId).orElseThrow(() -> new IllegalArgumentException("tenant not found"));
    }

    /** 查询租户列表，统计字段在 Java 中组合。 */
    public List<Map<String, Object>> listTenants(String search, String status, int limit, int offset) {
        String value = search == null ? "" : search.trim().toLowerCase();
        return tenantTable.listAll().stream()
                .filter(row -> value.isEmpty() || row.slug().toLowerCase().contains(value)
                        || row.name().toLowerCase().contains(value))
                .filter(row -> status == null || status.isBlank() || status.equals(row.status()))
                .skip(Math.max(offset, 0)).limit(Math.min(Math.max(limit, 1), 500))
                .map(row -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", row.id());
                    result.put("slug", row.slug());
                    result.put("name", row.name());
                    result.put("status", row.status());
                    result.put("derivation_namespace", row.derivationNamespace());
                    result.put("ip_allowlist_enabled", row.ipAllowlistEnabled());
                    result.put("display_currency", row.displayCurrency());
                    result.put("created_at", row.createdAt());
                    result.put("updated_at", row.updatedAt());
                    result.put("addressCount", custodyAddresses.listByTenant(row.id()).size());
                    result.put("gasAccountCount", gasAccounts.listByTenant(row.id()).size());
                    result.put("userCount", tenantUsers.listByTenant(row.id()).size());
                    return result;
                }).toList();
    }

    /** 统计租户数量。 */
    public long countTenants(String search, String status) {
        return tenantTable.listAll().stream()
                .filter(row -> search == null || search.isBlank()
                        || row.slug().toLowerCase().contains(search.toLowerCase())
                        || row.name().toLowerCase().contains(search.toLowerCase()))
                .filter(row -> status == null || status.isBlank() || status.equals(row.status())).count();
    }

    /** 查询租户运营摘要。 */
    public Map<String, Object> tenantOperationsSummary(UUID tenantId) {
        requireTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        List<UUID> gasAddressIds = gasAccounts.listByTenant(tenantId).stream()
                .map(row -> uuid(row.get("custody_address_id"))).toList();
        result.put("addressCount", custodyAddresses.listByTenant(tenantId).stream()
                .filter(row -> !gasAddressIds.contains(uuid(row.get("id")))).count());
        result.put("gasAccountCount", gasAccounts.listByTenant(tenantId).stream()
                .filter(row -> "ACTIVE".equals(row.get("status"))).count());
        result.put("userCount", (long) tenantUsers.listByTenant(tenantId).size());
        result.put("withdrawalCount", custodyWithdrawals.countByTenant(tenantId, "", "", ""));
        result.put("depositCount", custodyDeposits.countCustomerDeposits(tenantId, gasAddressIds));
        result.put("activeApiKeyCount", apiKeys.countActive(tenantId));
        result.put("activeWebhookCount", webhookEndpoints.countActive(tenantId));
        result.put("webhookEndpointCount", webhookEndpoints.list(tenantId).size());
        result.put("failedWebhookDeliveryCount", webhookDeliveries.countFailed(tenantId));
        result.put("activeSessionCount", sessions.countActive(tenantId));
        return result;
    }

    /** 更新租户资料。 */
    public void updateTenantProfile(UUID tenantId, String name, String displayCurrency) {
        if (tenantTable.updateProfile(tenantId, name, displayCurrency) != 1) {
            throw new IllegalArgumentException("tenant not found");
        }
    }

    /** 更新租户状态。 */
    public void updateTenantStatus(UUID tenantId, String status) {
        if (tenantTable.updateStatus(tenantId, status) != 1) throw new IllegalArgumentException("tenant not found");
    }

















    /** 判断是否存在未解决的充值重组赤字。 */
    public boolean hasOpenReorgDeficit(UUID tenantId, UUID custodyAddressId, String chain,
                                       String assetSymbol, String accountId) {
        return reorgDeficits.existsOpen(tenantId, chain, assetSymbol, accountId);
    }

    /** 按托管地址解析账户后判断是否存在重组赤字。 */
    public boolean hasOpenReorgDeficit(UUID tenantId, UUID custodyAddressId, String chain, String assetSymbol) {
        Map<String, Object> custody = custodyAddresses.findByTenantAndId(tenantId, custodyAddressId).orElse(null);
        if (custody == null) return false;
        Map<String, Object> address = chainAddresses.findByTenantAndId(tenantId,
                longValue(custody.get("chain_address_id"), 0)).orElse(null);
        return address != null && reorgDeficits.existsOpen(tenantId, chain, assetSymbol, text(address.get("account_id")));
    }

    /** 查询租户资产概览，跨表数据在 Java 中组合。 */
    public List<Map<String, Object>> tenantAssetOverview(UUID tenantId) {
        List<Map<String, Object>> gasRows = gasAccounts.listByTenant(tenantId);
        Set<UUID> gasAddressIds = new HashSet<>();
        Set<String> gasAccountIds = new HashSet<>();
        for (Map<String, Object> gasRow : gasRows) {
            UUID addressId = uuid(gasRow.get("custody_address_id"));
            if (addressId != null) gasAddressIds.add(addressId);
        }

        List<Map<String, Object>> balanceRows = ledgerBalances.listByTenant(tenantId);
        Map<Long, Map<String, Object>> chainRowsById = new HashMap<>();
        for (Map<String, Object> row : chainAddresses.listByTenant(tenantId)) {
            chainRowsById.put(longValue(row.get("id"), 0), row);
        }
        List<Map<String, Object>> custodyRows = custodyAddresses.listByTenant(tenantId);
        Map<UUID, Map<String, Object>> custodyRowsById = new HashMap<>();
        for (Map<String, Object> row : custodyRows) {
            UUID addressId = uuid(row.get("id"));
            if (addressId != null) custodyRowsById.put(addressId, row);
        }
        for (UUID gasAddressId : gasAddressIds) {
            Map<String, Object> custody = custodyRowsById.get(gasAddressId);
            if (custody == null) continue;
            Map<String, Object> chain = chainRowsById.get(longValue(custody.get("chain_address_id"), 0));
            if (chain != null) {
                gasAccountIds.add(text(chain.get("account_id")).toLowerCase(Locale.ROOT));
            }
        }

        Map<String, Set<UUID>> customerAddressIdsByPair = new HashMap<>();
        for (Map<String, Object> address : custodyRows) {
            UUID addressId = uuid(address.get("id"));
            if (addressId == null || gasAddressIds.contains(addressId)) continue;
            Map<String, Object> base = chainRowsById.get(longValue(address.get("chain_address_id"), 0));
            if (base == null) continue;
            String pair = pairKey(text(address.get("chain")), text(base.get("account_id")));
            customerAddressIdsByPair.computeIfAbsent(pair, ignored -> new HashSet<>()).add(addressId);
        }

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Map<String, Long> addressCounts = new LinkedHashMap<>();
        for (Map<String, Object> balance : balanceRows) {
            if (gasAccountIds.contains(text(balance.get("account_id")).toLowerCase(Locale.ROOT))) {
                continue;
            }
            String key = text(balance.get("chain")).toUpperCase(Locale.ROOT)
                    + "\u0000" + text(balance.get("asset_symbol")).toUpperCase(Locale.ROOT);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(balance);
            Set<UUID> addressIds = customerAddressIdsByPair.get(
                    pairKey(text(balance.get("chain")), text(balance.get("account_id"))));
            if (addressIds != null) {
                addressCounts.merge(key, (long) addressIds.size(), Long::sum);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> balances = entry.getValue();
            Map<String, Object> first = balances.getFirst();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chain", first.get("chain"));
            row.put("assetSymbol", first.get("asset_symbol"));
            row.put("availableBalance", balances.stream().map(item -> (BigDecimal) item.get("available_balance"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            row.put("lockedBalance", balances.stream().map(item -> (BigDecimal) item.get("locked_balance"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            row.put("totalBalance", balances.stream().map(item -> (BigDecimal) item.get("total_balance"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            row.put("addressCount", addressCounts.getOrDefault(entry.getKey(), 0L));
            result.add(row);
        }
        return result;
    }

    /** 生成链和账户的大小写不敏感组合键。 */
    private static String pairKey(String chain, String accountId) {
        return chain.toLowerCase(Locale.ROOT) + "\u0000" + accountId.toLowerCase(Locale.ROOT);
    }

    /** 按链查询 Gas 账户。 */
    public Optional<GasAccountRecord> findGasAccount(UUID tenantId, String chain) {
        return gasAccounts.findByChain(tenantId, chain).map(row -> mapGas(tenantId, row));
    }

    /** 创建 Gas 账户。 */
    public GasAccountRecord insertGasAccount(UUID id, UUID tenantId, UUID custodyAddressId,
                                             String chain, String network, String nativeSymbol,
                                             BigDecimal lowBalanceThreshold, UUID createdBy) {
        gasAccounts.insert(id, tenantId, custodyAddressId, chain, network, nativeSymbol,
                lowBalanceThreshold, createdBy);
        return findGasAccount(tenantId, chain).orElseThrow(() -> new IllegalStateException("failed to create gas account"));
    }

    /** 查询并校验 Gas 账户。 */
    public GasAccountRecord requireGasAccount(UUID tenantId, UUID gasAccountId) {
        return gasAccounts.listByTenant(tenantId).stream()
                .filter(row -> gasAccountId.equals(row.get("id"))).findFirst()
                .map(row -> mapGas(tenantId, row))
                .orElseThrow(() -> new IllegalArgumentException("gas account not found"));
    }

    /** 查询租户 Gas 账户。 */
    public List<GasAccountRecord> listGasAccounts(UUID tenantId) {
        return gasAccounts.listByTenant(tenantId).stream().map(row -> mapGas(tenantId, row)).toList();
    }

    /** 更新 Gas 账户。 */
    public GasAccountRecord updateGasAccount(UUID tenantId, UUID gasAccountId, BigDecimal threshold,
                                             String status) {
        if (gasAccounts.update(tenantId, gasAccountId, threshold, status) != 1) {
            throw new IllegalArgumentException("gas account not found");
        }
        return requireGasAccount(tenantId, gasAccountId);
    }

    /** 查询 Gas 定价元数据。 */
    public GasPricingMetadata gasPricingMetadata(String chain, String assetSymbol) {
        Map<String, Object> profile = chainProfiles.listAll().stream()
                .filter(row -> chain.equalsIgnoreCase(text(row.get("chain")))
                        && Boolean.TRUE.equals(row.get("enabled"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("enabled chain gas pricing is unavailable for " + chain));
        Map<String, Object> nativeAsset = chainAssets.findActive(chain, text(profile.get("native_symbol")))
                .orElseThrow(() -> new IllegalArgumentException("native asset is missing"));
        boolean requestedNative = chainAssets.findActive(chain, assetSymbol)
                .map(row -> Boolean.TRUE.equals(row.get("native_asset"))).orElse(false);
        return new GasPricingMetadata(text(profile.get("family")), text(profile.get("native_symbol")),
                longValue(profile.get("default_fee_rate"), 1L), ((Number) nativeAsset.get("decimals")).intValue(),
                requestedNative);
    }

    /** 预留提现 Gas。 */
    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord reserveGasUsage(UUID tenantId, UUID custodyWithdrawalId, String orderNo,
                                           String chain, BigDecimal reservedAmount) {
        return reserveGasUsage(tenantId, "WITHDRAWAL", custodyWithdrawalId, orderNo, chain, reservedAmount);
    }

    /** 预留业务操作 Gas，并同步冻结账本余额。 */
    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord reserveGasUsage(UUID tenantId, String operationType, UUID operationId,
                                          String referenceNo, String chain, BigDecimal reservedAmount) {
        if (reservedAmount == null || reservedAmount.signum() <= 0) {
            throw new IllegalArgumentException("reserved gas amount must be positive");
        }
        requireGasOperation(tenantId, operationType, operationId, chain);
        GasAccountRecord account = findGasAccount(tenantId, chain)
                .filter(row -> "ACTIVE".equals(row.status()))
                .orElseThrow(() -> new IllegalStateException("active gas account is missing"));
        if (gasUsages.existsOverdue(tenantId, account.id())) {
            throw new IllegalStateException("gas account has an overdue reservation");
        }
        if (!ledgerBalances.freeze(chain, account.nativeSymbol(), account.accountId(), reservedAmount, tenantId)) {
            throw new IllegalStateException("insufficient gas balance");
        }
        gasUsages.insert(UUID.randomUUID(), tenantId, account.id(), operationType, operationId,
                referenceNo, chain, account.nativeSymbol(), reservedAmount, "CONFIGURED_RESERVE");
        return findGasUsage(tenantId, operationType, operationId).orElseThrow();
    }

    /** 释放提现 Gas。 */
    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord releaseGasUsage(UUID custodyWithdrawalId, String reason) {
        return gasUsages.findByOperationId(custodyWithdrawalId).map(row ->
                releaseGasUsage(uuid(row.get("tenant_id")), text(row.get("operation_type")),
                        uuid(row.get("operation_id")), reason)).orElseThrow();
    }

    /** 释放业务操作 Gas。 */
    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord releaseGasUsage(UUID tenantId, String operationType, UUID operationId, String reason) {
        GasUsageRecord usage = gasUsages.findForUpdate(tenantId, operationType, operationId)
                .map(CustodyRepository::mapGasUsage).orElseThrow();
        if (!"RESERVED".equals(usage.status())) return usage;
        GasAccountRecord account = requireGasAccount(tenantId, usage.gasAccountId());
        if (!ledgerBalances.release(usage.chain(), usage.nativeSymbol(), account.accountId(),
                usage.reservedAmount(), tenantId)) throw new IllegalStateException("gas balance is inconsistent");
        gasUsages.release(tenantId, operationType, operationId, reason);
        return findGasUsage(tenantId, operationType, operationId).orElseThrow();
    }

    /** 结算提现 Gas。 */
    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord settleGasUsage(UUID custodyWithdrawalId, BigDecimal actualAmount,
                                         String pricingSource, String txHash) {
        return gasUsages.findByOperationId(custodyWithdrawalId).map(row -> settleGasUsage(
                uuid(row.get("tenant_id")), text(row.get("operation_type")), uuid(row.get("operation_id")),
                actualAmount, pricingSource, txHash)).orElseThrow();
    }

    /** 结算业务操作 Gas。 */
    @Transactional(rollbackFor = Throwable.class)
    public GasUsageRecord settleGasUsage(UUID tenantId, String operationType, UUID operationId,
                                         BigDecimal actualAmount, String pricingSource, String txHash) {
        GasUsageRecord usage = gasUsages.findForUpdate(tenantId, operationType, operationId)
                .map(CustodyRepository::mapGasUsage).orElseThrow();
        if (!Set.of("RESERVED", "OVERDUE").contains(usage.status())) return usage;
        BigDecimal actual = actualAmount == null || actualAmount.signum() <= 0
                ? usage.reservedAmount() : actualAmount.stripTrailingZeros();
        if (actual.signum() <= 0) throw new IllegalArgumentException("actual gas amount must be positive");
        GasAccountRecord account = requireGasAccount(tenantId, usage.gasAccountId());
        boolean settled = ledgerBalances.settleReserved(usage.chain(), usage.nativeSymbol(), account.accountId(),
                usage.reservedAmount(), actual, tenantId);
        if (!settled) {
            gasUsages.markOverdue(tenantId, usage.id(), actual, pricingSource, txHash,
                    "actual network fee exceeded funded gas balance");
            return findGasUsage(tenantId, operationType, operationId).orElseThrow();
        }
        gasUsages.settleReservedOrOverdue(tenantId, usage.id(), actual, pricingSource, txHash);
        ledgerEntries.insertIfAbsent(UUID.randomUUID(), usage.tenantId(), account.custodyAddressId(),
                usage.chain(), usage.nativeSymbol(), account.accountId(), "NETWORK_FEE", "DEBIT", actual,
                usage.operationType(), usage.referenceNo());
        return findGasUsage(tenantId, operationType, operationId).orElseThrow();
    }

    /** 查询 Gas 使用记录列表。 */
    public List<Map<String, Object>> listGasUsage(UUID tenantId, String chain, String status,
                                                  int limit, int offset) {
        return gasUsages.list(tenantId, blank(chain), blank(status), limit, offset);
    }

    /** 查询逾期 Gas 使用记录。 */
    public List<GasUsageRecord> listOverdueGasUsage(int limit) {
        return gasUsages.listOverdue(limit).stream().map(CustodyRepository::mapGasUsage).toList();
    }

    /** 查询提现 Gas 使用记录。 */
    public Optional<GasUsageRecord> findGasUsage(UUID custodyWithdrawalId) {
        return gasUsages.findByOperationId(custodyWithdrawalId).map(CustodyRepository::mapGasUsage);
    }

    /** 查询业务 Gas 使用记录。 */
    public Optional<GasUsageRecord> findGasUsage(UUID tenantId, String operationType, UUID operationId) {
        return gasUsages.find(tenantId, operationType, operationId).map(CustodyRepository::mapGasUsage);
    }

    /** 查询已确认网络费用。 */
    public Optional<NetworkFee> confirmedNetworkFee(String chain, String orderNo, String txHash, int nativeDecimals) {
        if (txHash == null || txHash.isBlank()) return Optional.empty();
        String normalizedChain = chain == null ? "" : chain.toUpperCase(Locale.ROOT);
        Optional<BigDecimal> amount;
        String source;
        switch (normalizedChain) {
            case "SOLANA" -> {
                amount = solanaTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(9));
                source = "CHAIN_CONFIRMED";
            }
            case "APTOS" -> {
                amount = aptosTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(8));
                source = "CHAIN_CONFIRMED";
            }
            case "SUI" -> {
                amount = suiTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(9));
                source = "CHAIN_CONFIRMED";
            }
            case "TON" -> {
                amount = tonTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(9));
                source = "CHAIN_CONFIRMED";
            }
            case "XRP" -> {
                amount = xrpTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(6));
                source = "CHAIN_CONFIRMED";
            }
            case "XMR" -> {
                amount = moneroTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(nativeDecimals));
                source = "CHAIN_CONFIRMED";
            }
            case "NEAR" -> {
                amount = Optional.empty();
                source = "CONFIGURED_RESERVE";
            }
            case "TRON" -> {
                amount = tronTransactions.findConfirmedFee(chain, txHash);
                source = "CHAIN_CONFIRMED";
            }
            default -> {
                amount = evmTransactions.findConfirmedFee(chain, txHash);
                source = amount.isPresent() ? "CHAIN_RECORDED" : "CONFIGURED_RESERVE";
                if (amount.isEmpty()) {
                    amount = withdrawalOrders.find(chain, orderNo, null).map(WithdrawalOrderRecord::getFee);
                }
            }
        }
        String pricingSource = source;
        return amount.filter(value -> value != null && value.signum() > 0)
                .map(value -> new NetworkFee(value.stripTrailingZeros(), pricingSource));
    }

    /** 查询租户初始化状态。 */
    public Map<String, Object> onboardingStatus(UUID tenantId) {
        TenantRecord tenant = requireTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        boolean openChain = !tenantChains.listActiveChains(tenantId).isEmpty();
        boolean apiKey = apiKeys.countActive(tenantId) > 0;
        boolean webhook = webhookEndpoints.list(tenantId).stream()
                .anyMatch(row -> "ACTIVE".equals(row.get("status")) && row.get("verified_at") != null);
        boolean allowlist = tenant.ipAllowlistEnabled() && !ipRules.listActive(tenantId).isEmpty();
        List<Map<String, Object>> addresses = custodyAddresses.listByTenant(tenantId);
        List<UUID> gasAddressIds = gasAccounts.listByTenant(tenantId).stream()
                .map(row -> uuid(row.get("custody_address_id"))).toList();
        boolean customerAddress = addresses.stream()
                .anyMatch(row -> !gasAddressIds.contains(uuid(row.get("id"))));
        boolean gasAccount = gasAccounts.listByTenant(tenantId).stream()
                .anyMatch(row -> "ACTIVE".equals(row.get("status")));
        boolean fundedGas = listGasAccounts(tenantId).stream()
                .anyMatch(row -> "ACTIVE".equals(row.status()) && row.availableBalance().signum() > 0);
        result.put("tenantId", tenantId);
        result.put("apiKeyConfigured", apiKey);
        result.put("chainOpened", openChain);
        result.put("webhookConfigured", webhook);
        result.put("ipAllowlistConfigured", allowlist);
        result.put("addressCreated", customerAddress);
        result.put("gasAccountConfigured", gasAccount);
        result.put("gasAccountFunded", fundedGas);
        result.put("completedSteps", List.of(openChain, apiKey, webhook, allowlist, customerAddress,
                gasAccount, fundedGas).stream().filter(Boolean::booleanValue).count());
        result.put("totalSteps", 7);
        result.put("ready", openChain && apiKey && webhook && allowlist && customerAddress
                && gasAccount && fundedGas);
        return result;
    }

    /** 查询租户幂等记录。 */
    public Optional<IdempotencyRecord> findIdempotency(UUID tenantId, String key, String operation) {
        return idempotencies.find(tenantId, key, operation).stream().findFirst()
                .map(row -> new IdempotencyRecord(text(row.get("request_hash")),
                        row.get("response_status") == null ? null : ((Number) row.get("response_status")).intValue(),
                        text(row.get("response_json")), instant(row.get("expires_at"))));
    }

    /** 开始幂等请求。 */
    public boolean beginIdempotency(UUID tenantId, String key, String operation, String requestHash,
                                    Instant expiresAt) {
        return idempotencies.begin(tenantId, key, operation, requestHash, expiresAt);
    }

    /** 完成幂等请求。 */
    public void completeIdempotency(UUID tenantId, String key, String operation, int responseStatus,
                                    String responseJson) {
        idempotencies.complete(tenantId, key, operation, responseStatus, responseJson);
    }

    /** 创建 Webhook 端点。 */
    public WebhookEndpointRecord insertWebhookEndpoint(UUID id, UUID tenantId, String name, String url,
                                                       String secretCiphertext, String verificationTokenHash,
                                                       UUID createdBy) {
        webhookEndpoints.insert(id, tenantId, name, url, secretCiphertext, verificationTokenHash);
        return requireWebhookEndpoint(tenantId, id);
    }

    /** 查询并校验 Webhook 端点。 */
    public WebhookEndpointRecord requireWebhookEndpoint(UUID tenantId, UUID endpointId) {
        return webhookEndpoints.find(tenantId, endpointId).stream().findFirst()
                .map(CustodyRepository::mapWebhookEndpoint)
                .orElseThrow(() -> new IllegalArgumentException("webhook endpoint not found"));
    }

    /** 查询 Webhook 端点。 */
    public List<Map<String, Object>> listWebhookEndpoints(UUID tenantId) { return webhookEndpoints.list(tenantId); }

    /** 标记 Webhook 已验证。 */
    public void markWebhookVerified(UUID tenantId, UUID endpointId) {
        if (webhookEndpoints.markVerified(tenantId, endpointId) != 1) throw new IllegalArgumentException("endpoint not found");
    }

    /** 更新 Webhook 状态。 */
    public void setWebhookStatus(UUID tenantId, UUID endpointId, String status) {
        webhookEndpoints.updateStatus(tenantId, endpointId, status);
    }

    /** 查询 Webhook 投递记录。 */
    public List<Map<String, Object>> listWebhookDeliveries(UUID tenantId, UUID endpointId, String status,
                                                           int limit, int offset) {
        return webhookDeliveries.list(tenantId, endpointId, status, limit, offset);
    }

    /** 领取 Webhook 投递任务并组合事件和端点数据。 */
    @Transactional(rollbackFor = Throwable.class)
    public List<WebhookDeliveryTask> claimWebhookDeliveries(String workerId, int limit) {
        List<WebhookDeliveryTask> result = new ArrayList<>();
        for (Map<String, Object> row : webhookDeliveries.claim(workerId, limit)) {
            UUID tenantId = uuid(row.get("tenant_id"));
            UUID endpointId = uuid(row.get("endpoint_id"));
            UUID eventId = uuid(row.get("event_id"));
            if ("RECOVERY".equals(text(row.get("next_attempt_trigger")))) {
                webhookAttempts.recoverStale(tenantId, uuid(row.get("id")));
            }
            WebhookEndpointRecord endpoint = requireWebhookEndpoint(tenantId, endpointId);
            Map<String, Object> event = events.find(tenantId, eventId).orElse(null);
            if (event == null) continue;
            int attemptNumber = number(row.get("attempt_count"));
            UUID attemptId = UUID.randomUUID();
            webhookAttempts.insert(attemptId, tenantId, uuid(row.get("id")), attemptNumber,
                    number(row.get("manual_retry_count")), text(row.get("next_attempt_trigger")), workerId);
            result.add(new WebhookDeliveryTask(uuid(row.get("id")), tenantId, endpointId, eventId,
                    attemptNumber, number(row.get("total_attempt_count")), number(row.get("manual_retry_count")),
                    text(row.get("next_attempt_trigger")), attemptId, workerId, endpoint.url(),
                    endpoint.secretCiphertext(), text(event.get("event_type")), text(event.get("payload"))));
        }
        return result;
    }

    /** 标记 Webhook 投递成功。 */
    public void markWebhookDelivered(WebhookDeliveryTask task, int httpStatus, String response,
                                     long durationMs) {
        webhookDeliveries.delivered(task.tenantId(), task.id(), task.workerId(), httpStatus, response);
        webhookAttempts.delivered(task.tenantId(), task.attemptId(), task.workerId(),
                httpStatus, response, durationMs);
        webhookEndpoints.touchDelivery(task.tenantId(), task.endpointId());
    }

    /** 标记 Webhook 投递失败。 */
    public void markWebhookFailed(WebhookDeliveryTask task, Integer httpStatus, String error,
                                  String response, Instant nextAttempt, boolean terminal, long durationMs) {
        webhookDeliveries.failed(task.tenantId(), task.id(), task.workerId(), httpStatus, error, response,
                terminal, nextAttempt == null ? null : java.sql.Timestamp.from(nextAttempt));
        webhookAttempts.failed(task.tenantId(), task.attemptId(), task.workerId(), httpStatus, error, response,
                terminal, nextAttempt == null ? null : java.sql.Timestamp.from(nextAttempt), durationMs);
    }

    /** 手动重试 Webhook 投递。 */
    public void retryWebhookDelivery(UUID tenantId, UUID deliveryId) {
        if (webhookDeliveries.retry(tenantId, deliveryId) != 1) throw new IllegalStateException("webhook delivery not retryable");
    }

    /** 批量重试 Webhook 投递。 */
    public int retryFailedWebhookDeliveries(UUID tenantId, UUID endpointId) {
        return webhookDeliveries.retryFailed(tenantId, endpointId);
    }

    /** 查询 Webhook 投递尝试。 */
    public List<Map<String, Object>> listWebhookDeliveryAttempts(UUID tenantId, UUID deliveryId,
                                                                  int limit, int offset) {
        return webhookAttempts.list(tenantId, deliveryId, limit, offset);
    }

    /** 创建托管提现记录。 */
    public void insertCustodyWithdrawal(UUID id, UUID tenantId, UUID custodyAddressId, String orderNo,
                                        String externalReference, String idempotencyKey, String chain,
                                        String assetSymbol, String toAddress, BigDecimal amount, BigDecimal fee,
                                        String status, String createdByType, String createdById) {
        long orderId = withdrawalOrders.find(chain, orderNo, tenantId)
                .map(WithdrawalOrderRecord::getId).map(Long::longValue)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal order not found"));
        custodyWithdrawals.insert(id, tenantId, custodyAddressId, orderId, orderNo, externalReference,
                idempotencyKey, chain, assetSymbol, toAddress, amount, fee, status, createdByType, createdById);
    }

    /** 查询状态不同步的提现记录。 */
    public List<WithdrawalStatusChange> findWithdrawalStatusChanges(int limit) {
        List<WithdrawalStatusChange> result = new ArrayList<>();
        for (Map<String, Object> row : custodyWithdrawals.listStatusChanges(limit)) {
            UUID tenantId = uuid(row.get("tenant_id"));
            Map<String, Object> order = withdrawalOrders.findById(tenantId, ((Number) row.get("withdrawal_order_id")).longValue()).orElse(Map.of());
            String next = text(order.get("status"));
            if (next == null || next.equals(row.get("status"))) continue;
            Map<String, Object> address = custodyAddresses.findByTenantAndId(tenantId, uuid(row.get("custody_address_id"))).orElse(Map.of());
            result.add(new WithdrawalStatusChange(uuid(row.get("id")), tenantId, uuid(row.get("custody_address_id")), text(row.get("order_no")),
                    text(row.get("external_reference")), text(row.get("chain")), text(row.get("asset_symbol")), text(row.get("to_address")),
                    (BigDecimal) row.get("amount"), (BigDecimal) row.get("fee"), text(row.get("status")), next,
                    (String) order.get("tx_hash"), (String) order.get("error_message"),
                    text(order.get("debit_account_id")), text(address.get("source"))));
        }
        return result;
    }

    /** 应用提现状态同步。 */
    @Transactional(rollbackFor = Throwable.class)
    public boolean applyWithdrawalStatusChange(WithdrawalStatusChange change, UUID eventId,
                                               String eventType, String payloadJson) {
        if (!change.previousStatus().equals(change.nextStatus())) {
            withdrawalOrders.updateStatus(change.tenantId(), change.chain(), change.orderNo(), change.nextStatus(),
                    null, change.txHash(), change.errorMessage());
            custodyWithdrawals.updateStatus(change.tenantId(), change.id(), change.nextStatus());
        }
        if ("CONFIRMED".equals(change.nextStatus())) {
            // The chain workflow settles the frozen customer balance when it marks the
            // withdrawal CONFIRMED. This reconciliation is only a custody projection;
            // applying the principal debit here would settle the same lock twice.
            findGasUsage(change.id()).ifPresent(usage -> {
                GasPricingMetadata metadata = gasPricingMetadata(change.chain(), change.assetSymbol());
                NetworkFee networkFee = confirmedNetworkFee(change.chain(), change.orderNo(), change.txHash(),
                                metadata.decimals())
                        .orElse(new NetworkFee(usage.reservedAmount(), "CONFIGURED_RESERVE"));
                GasAccountRecord gasAccount = requireGasAccount(change.tenantId(), usage.gasAccountId());
                ledgerEntries.insertIfAbsent(UUID.randomUUID(), change.tenantId(), change.custodyAddressId(),
                        change.chain(), change.assetSymbol(), gasAccount.accountId(), "NETWORK_FEE", "DEBIT",
                        networkFee.amount(), "WITHDRAWAL", change.orderNo());
                settleGasUsage(change.id(), networkFee.amount(), networkFee.pricingSource(), change.txHash());
            });
        } else if (Set.of("FAILED", "REJECTED", "CANCELLED").contains(change.nextStatus())) {
            // Failure handlers release the customer lock as part of the chain state
            // transition. Releasing it again here can strand status reconciliation and
            // repeatedly fail after the balance has already been returned.
            findGasUsage(change.id()).ifPresent(usage -> releaseGasUsage(change.id(),
                    "withdrawal ended as " + change.nextStatus()));
        }
        if (eventType != null) eventService.publish(eventId, change.tenantId(), eventType,
                "WITHDRAWAL", change.orderNo(), payloadJson, "API".equals(change.addressSource()));
        return true;
    }

    /** 写入审计日志。 */
    public void audit(UUID tenantId, String actorType, String actorId, String action,
                      String resourceType, String resourceId, String sourceIp, String detailsJson) {
        audits.insert(UUID.randomUUID(), tenantId, actorType, actorId, action, resourceType, resourceId,
                sourceIp, detailsJson);
    }

    /** 查询租户审计日志。 */
    public List<Map<String, Object>> listAudit(UUID tenantId, int limit, int offset) { return audits.list(tenantId, limit, offset); }

    /** 统计租户审计日志。 */
    public long countAudit(UUID tenantId) { return audits.count(tenantId); }

    /** 查询平台审计日志。 */
    public List<Map<String, Object>> listPlatformAudit(int limit, int offset) { return audits.listPlatform(limit, offset); }

    /** 统计平台审计日志。 */
    public long countPlatformAudit() { return audits.countPlatform(); }


    /** 校验 Gas 操作归属租户和链。 */
    private void requireGasOperation(UUID tenantId, String operationType, UUID operationId, String chain) {
        String type = operationType == null ? "" : operationType.trim().toUpperCase(Locale.ROOT);
        boolean valid = switch (type) {
            case "WITHDRAWAL" -> custodyWithdrawals.find(tenantId, operationId).stream()
                    .anyMatch(row -> chain.equalsIgnoreCase(text(row.get("chain"))));
            case "COLLECTION_BATCH" -> collectionBatches.find(tenantId, operationId).stream()
                    .anyMatch(row -> chain.equalsIgnoreCase(text(row.get("chain"))));
            case "WITHDRAWAL_BATCH" -> withdrawalBatches.find(tenantId, operationId).stream()
                    .anyMatch(row -> chain.equalsIgnoreCase(text(row.get("chain"))));
            default -> throw new IllegalArgumentException("unsupported gas operation type");
        };
        if (!valid) throw new IllegalArgumentException("gas operation does not belong to tenant and chain");
    }


    /** 将数据库 Gas 字段和账本余额转换为模型。 */
    private GasAccountRecord mapGas(UUID tenantId, Map<String, Object> row) {
        UUID custodyId = uuid(row.get("custody_address_id"));
        Map<String, Object> custody = custodyAddresses.findByTenantAndId(tenantId, custodyId).orElse(Map.of());
        Map<String, Object> address = chainAddresses.findByTenantAndId(tenantId, longValue(custody.get("chain_address_id"), 0)).orElse(Map.of());
        String accountId = text(address.get("account_id"));
        LedgerBalanceRecord balance = ledgerBalances.find(tenantId, text(row.get("chain")),
                text(row.get("native_symbol")), accountId).orElse(null);
        BigDecimal available = balance == null ? BigDecimal.ZERO : balance.getAvailableBalance();
        BigDecimal locked = balance == null ? BigDecimal.ZERO : balance.getLockedBalance();
        BigDecimal total = balance == null ? BigDecimal.ZERO : balance.getTotalBalance();
        return new GasAccountRecord(uuid(row.get("id")), tenantId, custodyId, text(row.get("chain")), text(row.get("network")),
                text(row.get("native_symbol")), text(custody.get("address")), text(custody.get("memo")),
                longValue(address.get("address_index"), 0), accountId, available, locked, total,
                (BigDecimal) row.get("low_balance_threshold"), text(row.get("status")), instant(row.get("created_at")), instant(row.get("updated_at")));
    }

    /** 将数据库 Gas 使用字段转换为模型。 */
    private static GasUsageRecord mapGasUsage(Map<String, Object> row) {
        return new GasUsageRecord(uuid(row.get("id")), uuid(row.get("tenant_id")), uuid(row.get("gas_account_id")),
                text(row.get("operation_type")), uuid(row.get("operation_id")), text(row.get("reference_no")),
                text(row.get("chain")), text(row.get("native_symbol")), (BigDecimal) row.get("reserved_amount"),
                (BigDecimal) row.get("actual_amount"), text(row.get("status")), text(row.get("pricing_source")),
                text(row.get("tx_hash")), text(row.get("error_message")), instant(row.get("created_at")),
                instant(row.get("updated_at")), instant(row.get("settled_at")));
    }

    /** 将数据库 Webhook 字段转换为模型。 */
    private static WebhookEndpointRecord mapWebhookEndpoint(Map<String, Object> row) {
        return new WebhookEndpointRecord(uuid(row.get("id")), uuid(row.get("tenant_id")), text(row.get("name")),
                text(row.get("url")), text(row.get("secret_ciphertext")), text(row.get("status")),
                text(row.get("verification_token_hash")), instant(row.get("verified_at")), instant(row.get("last_delivery_at")),
                instant(row.get("created_at")), instant(row.get("updated_at")));
    }

    /** 读取文本字段。 */
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    /** 读取 UUID 字段。 */
    private static UUID uuid(Object value) { return value instanceof UUID id ? id : value == null ? null : UUID.fromString(value.toString()); }
    /** 读取整数。 */
    private static int number(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
    /** 读取长整数。 */
    private static long longValue(Object value, long fallback) { return value == null ? fallback : ((Number) value).longValue(); }
    /** 读取时间字段。 */
    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        return null;
    }
    /** 规范化可选筛选参数。 */
    private static String blank(String value) { return value == null ? "" : value.trim(); }

}
