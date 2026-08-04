package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
    private final CustodySecurityRepository securityRepository;
    /** 托管地址单表仓储。 */
    private final CustodyAddressRepository custodyAddresses;
    /** 链地址单表仓储。 */
    private final ChainAddressRepository chainAddresses;
    /** 派生主题单表仓储。 */
    private final CustodyDerivationSubjectRepository derivationSubjects;
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

    /** 兼容已有手工构造方式。 */
    public CustodyRepository(JdbcTemplate jdbc) {
        this(jdbc, new CustodySecurityRepository(jdbc), new CustodyTenantTableRepository(jdbc),
                new CustodyTenantUserRepository(jdbc));
    }

    /** 兼容已有手工构造方式。 */
    public CustodyRepository(JdbcTemplate jdbc, CustodySecurityRepository securityRepository) {
        this(jdbc, securityRepository, new CustodyTenantTableRepository(jdbc),
                new CustodyTenantUserRepository(jdbc));
    }

    /** 构造托管数据编排门面。 */
    @Autowired
    public CustodyRepository(JdbcTemplate jdbc, CustodySecurityRepository securityRepository,
                             CustodyTenantTableRepository tenantTable,
                             CustodyTenantUserRepository tenantUsers) {
        this.tenantTable = tenantTable;
        this.tenantUsers = tenantUsers;
        this.securityRepository = securityRepository;
        this.custodyAddresses = new CustodyAddressRepository(jdbc);
        this.chainAddresses = new ChainAddressRepository(jdbc);
        this.derivationSubjects = new CustodyDerivationSubjectRepository(jdbc);
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
        return Optional.ofNullable(tenantTable.findBySlug(slug)).map(CustodyRepository::mapTenant);
    }

    /** 查询并校验租户。 */
    public TenantRecord requireTenant(UUID tenantId) {
        Map<String, Object> row = tenantTable.findFullById(tenantId);
        if (row == null) throw new IllegalArgumentException("tenant not found");
        return mapTenant(row);
    }

    /** 查询租户列表，统计字段在 Java 中组合。 */
    public List<Map<String, Object>> listTenants(String search, String status, int limit, int offset) {
        String value = search == null ? "" : search.trim().toLowerCase();
        return tenantTable.listAll().stream()
                .filter(row -> value.isEmpty() || text(row.get("slug")).toLowerCase().contains(value)
                        || text(row.get("name")).toLowerCase().contains(value))
                .filter(row -> status == null || status.isBlank() || status.equals(row.get("status")))
                .skip(Math.max(offset, 0)).limit(Math.min(Math.max(limit, 1), 500))
                .map(row -> {
                    Map<String, Object> result = new LinkedHashMap<>(row);
                    UUID tenantId = uuid(row.get("id"));
                    result.put("addressCount", custodyAddresses.listByTenant(tenantId).size());
                    result.put("gasAccountCount", gasAccounts.listByTenant(tenantId).size());
                    result.put("userCount", tenantUsers.listByTenant(tenantId).size());
                    return result;
                }).toList();
    }

    /** 统计租户数量。 */
    public long countTenants(String search, String status) {
        return tenantTable.listAll().stream()
                .filter(row -> search == null || search.isBlank()
                        || text(row.get("slug")).toLowerCase().contains(search.toLowerCase())
                        || text(row.get("name")).toLowerCase().contains(search.toLowerCase()))
                .filter(row -> status == null || status.isBlank() || status.equals(row.get("status"))).count();
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
        result.put("withdrawalCount", (long) custodyWithdrawals.listByTenant(tenantId, "", "", "", 500, 0).size());
        result.put("depositCount", custodyDeposits.listByTenant(tenantId, "", "", "", 500, 0).stream()
                .filter(row -> !gasAddressIds.contains(uuid(row.get("custody_address_id")))).count());
        result.put("activeApiKeyCount", securityRepository.countActiveApiKeys(tenantId));
        result.put("activeWebhookCount", webhookEndpoints.countActive(tenantId));
        result.put("webhookEndpointCount", webhookEndpoints.list(tenantId).size());
        result.put("failedWebhookDeliveryCount", webhookDeliveries.countFailed(tenantId));
        result.put("activeSessionCount", securityRepository.countActiveSessions(tenantId));
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

    /** 撤销租户会话。 */
    public int revokeTenantSessions(UUID tenantId) { return securityRepository.revokeTenantSessions(tenantId); }

    /** 查询租户用户。 */
    public Optional<AuthUser> findTenantUser(String email) { return securityRepository.findTenantUser(email); }

    /** 查询平台用户。 */
    public Optional<AuthUser> findPlatformUser(String email) { return securityRepository.findPlatformUser(email); }

    /** 判断平台管理员是否存在。 */
    public boolean platformAdminExists() { return securityRepository.platformAdminExists(); }

    /** 创建平台管理员。 */
    public void insertPlatformAdmin(UUID userId, String email, String passwordHash) {
        securityRepository.insertPlatformAdmin(userId, email, passwordHash);
    }

    /** 记录登录失败。 */
    public void recordLoginFailure(UUID userId, Instant lockedUntil) {
        securityRepository.recordLoginFailure(userId, lockedUntil);
    }

    /** 记录登录成功。 */
    public void recordLoginSuccess(UUID userId) { securityRepository.recordLoginSuccess(userId); }

    /** 创建会话。 */
    public void insertSession(UUID sessionId, UUID userId, UUID tenantId, String tokenHash,
                              Instant expiresAt, String sourceIp, String userAgent) {
        securityRepository.insertSession(sessionId, userId, tenantId, tokenHash, sourceIp, userAgent, expiresAt);
    }

    /** 兼容认证服务使用的会话参数顺序。 */
    public void insertSession(UUID sessionId, UUID userId, UUID tenantId, String tokenHash,
                              String sourceIp, String userAgent, Instant expiresAt) {
        securityRepository.insertSession(sessionId, userId, tenantId, tokenHash, sourceIp, userAgent, expiresAt);
    }

    /** 查询有效会话。 */
    public Optional<SessionRecord> findActiveSession(String tokenHash) {
        return securityRepository.findActiveSession(tokenHash);
    }

    /** 查询租户用户列表。 */
    public List<Map<String, Object>> listTenantUsers(UUID tenantId) { return securityRepository.listTenantUsers(tenantId); }

    /** 解锁租户管理员。 */
    public Map<String, Object> unlockTenantAdministrator(UUID tenantId, UUID userId) {
        return securityRepository.unlockTenantAdministrator(tenantId, userId);
    }

    /** 更新会话访问时间。 */
    public void touchSession(UUID sessionId) { securityRepository.touchSession(sessionId); }

    /** 撤销会话。 */
    public void revokeSession(String tokenHash) { securityRepository.revokeSession(tokenHash); }

    /** 创建 API 密钥。 */
    public ApiKeyRecord insertApiKey(UUID id, UUID tenantId, String keyId, String name,
                                     String encryptedSecret, UUID createdBy) {
        return securityRepository.insertApiKey(id, tenantId, keyId, name, encryptedSecret, createdBy);
    }

    /** 兼容包含密钥版本和过期时间的调用方。 */
    public ApiKeyRecord insertApiKey(UUID id, UUID tenantId, String keyId, String name,
                                     String encryptedSecret, int secretVersion, Instant expiresAt,
                                     UUID createdBy) {
        return securityRepository.insertApiKey(id, tenantId, keyId, name, encryptedSecret, createdBy);
    }

    /** 查询有效 API 密钥。 */
    public Optional<ApiKeyRecord> findActiveApiKey(String keyId) { return securityRepository.findActiveApiKey(keyId); }

    /** 查询并校验 API 密钥。 */
    public ApiKeyRecord requireApiKey(String keyId) { return securityRepository.requireApiKey(keyId); }

    /** 查询租户 API 密钥。 */
    public List<Map<String, Object>> listApiKeys(UUID tenantId) { return securityRepository.listApiKeys(tenantId); }

    /** 撤销 API 密钥。 */
    public void revokeApiKey(UUID tenantId, UUID keyId) { securityRepository.revokeApiKey(tenantId, keyId); }

    /** 更新 API 密钥访问信息。 */
    public void touchApiKey(UUID keyId, String sourceIp) { securityRepository.touchApiKey(keyId, sourceIp); }

    /** 占用 API nonce。 */
    public boolean reserveNonce(String keyId, String nonce, Instant expiresAt) {
        return securityRepository.reserveNonce(keyId, nonce, expiresAt);
    }

    /** 查询启用的 IP 规则。 */
    public List<String> activeIpRules(UUID tenantId) { return securityRepository.activeIpRules(tenantId); }

    /** 查询租户 IP 规则。 */
    public List<Map<String, Object>> listIpRules(UUID tenantId) { return securityRepository.listIpRules(tenantId); }

    /** 创建 IP 规则。 */
    public Map<String, Object> insertIpRule(UUID tenantId, UUID ruleId, String label, String cidr, UUID createdBy) {
        return securityRepository.insertIpRule(tenantId, ruleId, label, cidr, createdBy);
    }

    /** 删除 IP 规则。 */
    public void deleteIpRule(UUID tenantId, UUID ruleId) { securityRepository.deleteIpRule(tenantId, ruleId); }

    /** 更新租户 IP 白名单开关。 */
    public void setIpAllowlistEnabled(UUID tenantId, boolean enabled) {
        securityRepository.setIpAllowlistEnabled(tenantId, enabled);
    }

    /** 获取租户主题派生编号。 */
    public int resolveDerivationSubject(UUID tenantId, String subject) {
        return derivationSubjects.resolve(tenantId, subject);
    }

    /** 保留地址分配的事务边界。 */
    @Transactional(rollbackFor = Throwable.class)
    public void lockSubjectAddressAllocation(UUID tenantId, String chain, String subject) {
        derivationSubjects.resolve(tenantId, subject);
    }

    /** 创建托管地址。 */
    public AddressRecord insertAddress(UUID id, UUID tenantId, long chainAddressId, String chain,
                                       String network, String address, String memo, String subject,
                                       String label, String metadataJson, String source,
                                       int derivationSubject, long addressVersion, long derivationChild,
                                       UUID createdBy) {
        if (custodyAddresses.insert(id, tenantId, chainAddressId, chain, network, address, memo, subject,
                label, metadataJson, source, derivationSubject, addressVersion, derivationChild, createdBy) != 1) {
            throw new IllegalStateException("failed to create custody address");
        }
        return requireAddress(tenantId, id);
    }

    /** 设置链地址租户归属。 */
    public void assignChainAddressTenant(UUID tenantId, long chainAddressId) {
        if (chainAddresses.assignTenant(tenantId, chainAddressId) != 1) {
            throw new IllegalArgumentException("chain address not found");
        }
    }

    /** 查询并校验托管地址。 */
    public AddressRecord requireAddress(UUID tenantId, UUID addressId) {
        return custodyAddresses.findFullByTenantAndId(tenantId, addressId)
                .map(CustodyRepository::mapAddress)
                .orElseThrow(() -> new IllegalArgumentException("custody address not found"));
    }

    /** 按主题和版本查询托管地址。 */
    public Optional<AddressRecord> findAddressBySubjectAndVersion(UUID tenantId, String chain,
                                                                   String subject, long version) {
        return custodyAddresses.findBySubjectAndVersion(tenantId, chain, subject, version)
                .map(CustodyRepository::mapAddress);
    }

    /** 判断托管地址是否为 Gas 地址。 */
    public boolean isGasAddress(UUID tenantId, UUID addressId) {
        return gasAccounts.listCustodyAddressIds(tenantId).contains(addressId);
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

    /** 更新托管地址。 */
    public AddressRecord updateAddress(UUID tenantId, UUID addressId, String label, String memo,
                                       String status, String metadataJson) {
        if (custodyAddresses.update(tenantId, addressId, label, memo, status, metadataJson) != 1) {
            throw new IllegalArgumentException("custody address not found");
        }
        return requireAddress(tenantId, addressId);
    }

    /** 更新托管地址元数据的兼容入口。 */
    public AddressRecord updateAddress(UUID tenantId, UUID addressId, String label,
                                       String metadataJson, String status) {
        return updateAddress(tenantId, addressId, label, null, status, metadataJson);
    }

    /** 分页查询托管地址。 */
    public List<AddressRecord> listAddresses(UUID tenantId, String chain, String source,
                                              String status, int limit, int offset) {
        return custodyAddresses.list(tenantId, blank(chain), blank(source), blank(status), limit, offset)
                .stream().map(CustodyRepository::mapAddress).toList();
    }

    /** 分页查询托管地址并支持地址文本搜索。 */
    public List<AddressRecord> listAddresses(UUID tenantId, String chain, String source,
                                              String status, String search, int limit, int offset) {
        String value = search == null ? "" : search.toLowerCase();
        return custodyAddresses.list(tenantId, optionalBlank(chain), optionalBlank(source), optionalBlank(status), 500, 0).stream()
                .filter(row -> !isGasAddress(tenantId, uuid(row.get("id"))))
                .filter(row -> value.isEmpty() || (text(row.get("address")) + " " + text(row.get("subject")) + " " + text(row.get("label"))).toLowerCase().contains(value))
                .skip(Math.max(offset, 0)).limit(Math.min(Math.max(limit, 1), 200))
                .map(CustodyRepository::mapAddress).toList();
    }

    /** 统计托管地址。 */
    public long countAddresses(UUID tenantId, String chain, String source, String status) {
        return custodyAddresses.count(tenantId, blank(chain), blank(source), blank(status));
    }

    /** 统计托管地址并支持地址文本搜索。 */
    public long countAddresses(UUID tenantId, String chain, String source, String status, String search) {
        return listAddresses(tenantId, chain, source, status, search, Integer.MAX_VALUE, 0).size();
    }

    /** 查询租户资产概览，跨表数据在 Java 中组合。 */
    public List<Map<String, Object>> tenantAssetOverview(UUID tenantId) {
        List<UUID> gasAddressIds = gasAccounts.listByTenant(tenantId).stream()
                .map(row -> uuid(row.get("custody_address_id"))).toList();
        List<String> gasAccountIds = listGasAccounts(tenantId).stream()
                .map(GasAccountRecord::accountId).toList();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        Map<String, Long> addressCounts = new LinkedHashMap<>();
        for (Map<String, Object> balance : ledgerBalances.listByTenant(tenantId)) {
            if (gasAccountIds.stream().anyMatch(account -> account.equalsIgnoreCase(text(balance.get("account_id"))))) {
                continue;
            }
            String key = text(balance.get("chain")).toUpperCase(Locale.ROOT)
                    + "\u0000" + text(balance.get("asset_symbol")).toUpperCase(Locale.ROOT);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(balance);
        }
        for (Map<String, Object> address : custodyAddresses.listByTenant(tenantId)) {
            if (gasAddressIds.contains(uuid(address.get("id")))) continue;
            Map<String, Object> base = chainAddresses.findByTenantAndId(
                    tenantId, longValue(address.get("chain_address_id"), 0)).orElse(Map.of());
            String chain = text(address.get("chain"));
            String accountId = text(base.get("account_id"));
            for (Map<String, Object> balance : ledgerBalances.listByTenant(tenantId)) {
                if (chain.equalsIgnoreCase(text(balance.get("chain")))
                        && accountId.equalsIgnoreCase(text(balance.get("account_id")))) {
                    String key = text(balance.get("chain")).toUpperCase(Locale.ROOT)
                            + "\u0000" + text(balance.get("asset_symbol")).toUpperCase(Locale.ROOT);
                    addressCounts.merge(key, 1L, Long::sum);
                }
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

    /** 查询 Gas 充值记录，关联逻辑在 Java 中完成。 */
    public List<Map<String, Object>> listGasTopups(UUID tenantId, UUID gasAccountId, int limit, int offset) {
        GasAccountRecord account = requireGasAccount(tenantId, gasAccountId);
        return custodyDeposits.listByTenant(tenantId, account.chain(), account.nativeSymbol(), "", limit, offset)
                .stream().filter(row -> account.custodyAddressId().equals(row.get("custody_address_id")))
                .toList();
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

    /** 查询指定 Gas 账户的使用记录。 */
    public List<Map<String, Object>> listGasUsage(UUID tenantId, UUID gasAccountId, int limit, int offset) {
        return gasUsages.list(tenantId, null, null, 500, 0).stream()
                .filter(row -> gasAccountId.equals(row.get("gas_account_id")))
                .skip(Math.max(offset, 0)).limit(Math.min(Math.max(limit, 1), 200)).map(row -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", row.get("id")); result.put("operationType", row.get("operation_type"));
                    result.put("operationId", row.get("operation_id")); result.put("referenceNo", row.get("reference_no"));
                    result.put("chain", row.get("chain")); result.put("nativeSymbol", row.get("native_symbol"));
                    result.put("reservedAmount", row.get("reserved_amount")); result.put("actualAmount", row.get("actual_amount"));
                    result.put("status", row.get("status")); result.put("pricingSource", row.get("pricing_source"));
                    result.put("txHash", row.get("tx_hash")); result.put("errorMessage", row.get("error_message"));
                    result.put("createdAt", instant(row.get("created_at"))); result.put("updatedAt", instant(row.get("updated_at")));
                    result.put("settledAt", instant(row.get("settled_at"))); return result;
                }).toList();
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
        boolean apiKey = securityRepository.countActiveApiKeys(tenantId) > 0;
        boolean webhook = webhookEndpoints.list(tenantId).stream()
                .anyMatch(row -> "ACTIVE".equals(row.get("status")) && row.get("verified_at") != null);
        boolean allowlist = tenant.ipAllowlistEnabled() && !securityRepository.activeIpRules(tenantId).isEmpty();
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
        return webhookDeliveries.list(tenantId, endpointId, blank(status), limit, offset);
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

    /** 查询托管提现记录并组合订单与地址字段。 */
    public List<Map<String, Object>> listCustodyWithdrawals(UUID tenantId, String chain, String assetSymbol,
                                                             String status, String search, int limit, int offset) {
        String value = search == null ? "" : search.toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : custodyWithdrawals.listByTenant(tenantId, blank(chain), blank(assetSymbol), blank(status), 500, 0)) {
            Map<String, Object> order = withdrawalOrders.findById(tenantId, ((Number) row.get("withdrawal_order_id")).longValue()).orElse(Map.of());
            Map<String, Object> address = custodyAddresses.findFullByTenantAndId(
                    tenantId, uuid(row.get("custody_address_id"))).orElse(Map.of());
            String haystack = (text(row.get("order_no")) + " " + text(row.get("external_reference")) + " "
                    + text(row.get("to_address")) + " " + text(order.get("tx_hash")) + " " + text(address.get("address"))).toLowerCase();
            if (!value.isEmpty() && !haystack.contains(value)) continue;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.get("id")); view.put("custodyAddressId", row.get("custody_address_id"));
            view.put("orderNo", row.get("order_no")); view.put("externalReference", row.get("external_reference"));
            view.put("chain", row.get("chain")); view.put("assetSymbol", row.get("asset_symbol"));
            view.put("toAddress", row.get("to_address")); view.put("amount", row.get("amount")); view.put("fee", row.get("fee"));
            view.put("txHash", order.get("tx_hash")); view.put("status", order.getOrDefault("status", row.get("status")));
            view.put("errorMessage", order.get("error_message")); view.put("createdByType", row.get("created_by_type"));
            view.put("sourceAddress", address.get("address")); view.put("subject", address.get("subject"));
            view.put("createdAt", instant(row.get("created_at"))); view.put("updatedAt", instant(row.get("updated_at")));
            result.add(view);
        }
        return result.stream().skip(Math.max(offset, 0)).limit(Math.min(Math.max(limit, 1), 200)).toList();
    }

    /** 统计托管提现记录。 */
    public long countCustodyWithdrawals(UUID tenantId, String chain, String assetSymbol, String status, String search) {
        return listCustodyWithdrawals(tenantId, chain, assetSymbol, status, search, Integer.MAX_VALUE, 0).size();
    }

    /** 查询托管充值记录并组合地址字段。 */
    public List<Map<String, Object>> listCustodyDeposits(UUID tenantId, String chain, String assetSymbol,
                                                         String status, String search, int limit, int offset) {
        String value = search == null ? "" : search.toLowerCase();
        return custodyDeposits.listByTenant(tenantId, blank(chain), blank(assetSymbol), blank(status), 500, 0).stream()
                .map(row -> {
            Map<String, Object> address = custodyAddresses.findFullByTenantAndId(
                    tenantId, uuid(row.get("custody_address_id"))).orElse(Map.of());
            Map<String, Object> view = new LinkedHashMap<>(row);
            view.put("address", address.get("address")); view.put("subject", address.get("subject"));
            view.put("id", row.get("id")); view.put("custodyAddressId", row.get("custody_address_id"));
            view.put("txHash", row.get("tx_hash")); view.put("assetSymbol", row.get("asset_symbol"));
            view.put("logIndex", row.get("log_index"));
                    view.put("creditedAt", instant(row.get("credited_at"))); view.put("createdAt", instant(row.get("created_at")));
                    view.put("updatedAt", instant(row.get("updated_at")));
                    return view;
                }).filter(row -> value.isEmpty() || (text(row.get("tx_hash")) + " " + text(row.get("address")) + " " + text(row.get("subject"))).toLowerCase().contains(value))
                .skip(Math.max(offset, 0)).limit(Math.min(Math.max(limit, 1), 200)).toList();
    }

    /** 统计托管充值记录。 */
    public long countCustodyDeposits(UUID tenantId, String chain, String assetSymbol, String status, String search) {
        return listCustodyDeposits(tenantId, chain, assetSymbol, status, search, Integer.MAX_VALUE, 0).size();
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
            ledgerEntries.insertIfAbsent(UUID.randomUUID(), change.tenantId(), change.custodyAddressId(),
                    change.chain(), change.assetSymbol(), change.debitAccountId(), "WITHDRAWAL", "DEBIT",
                    change.amount().add(change.fee()), "WITHDRAWAL", change.orderNo());
            findGasUsage(change.id()).ifPresent(usage -> {
                GasPricingMetadata metadata = gasPricingMetadata(change.chain(), change.assetSymbol());
                NetworkFee networkFee = confirmedNetworkFee(change.chain(), change.orderNo(), change.txHash(),
                                metadata.decimals())
                        .orElse(new NetworkFee(usage.reservedAmount(), "CONFIGURED_RESERVE"));
                settleGasUsage(change.id(), networkFee.amount(), networkFee.pricingSource(), change.txHash());
            });
        } else if (!Set.of("FROZEN", "SIGNING", "SENT", "CONFIRMING", "RETRYING", "BROADCAST_UNKNOWN")
                .contains(change.nextStatus())) {
            findGasUsage(change.id()).ifPresent(usage -> releaseGasUsage(change.id(),
                    "withdrawal ended as " + change.nextStatus()));
        }
        if (eventType != null) insertEventWithDeliveries(eventId, change.tenantId(), eventType,
                "WITHDRAWAL", change.orderNo(), payloadJson, "API".equals(change.addressSource()));
        return true;
    }

    /** 创建领域事件及其 Webhook 投递记录。 */
    @Transactional(rollbackFor = Throwable.class)
    public UUID insertEventWithDeliveries(UUID eventId, UUID tenantId, String eventType,
                                          String aggregateType, String aggregateId, String payload) {
        return insertEventWithDeliveries(eventId, tenantId, eventType, aggregateType, aggregateId, payload, true);
    }

    /** 创建领域事件的兼容入口，保留地址来源参数供调用方审计。 */
    public UUID insertEventWithDeliveries(UUID eventId, UUID tenantId, String eventType,
                                          String aggregateType, String aggregateId, String payload,
                                          boolean apiSource) {
        UUID persisted = events.insertIfAbsent(eventId, tenantId, eventType, aggregateType, aggregateId, payload)
                .or(() -> events.findIdByBusinessKey(tenantId, eventType, aggregateType, aggregateId))
                .orElseThrow(() -> new IllegalStateException("failed to persist custody event"));
        if (apiSource) {
            for (Map<String, Object> endpoint : webhookEndpoints.list(tenantId)) {
                if ("ACTIVE".equals(endpoint.get("status"))) {
                    webhookDeliveries.insert(UUID.randomUUID(), tenantId, uuid(endpoint.get("id")), persisted);
                }
            }
        }
        events.markPublished(persisted);
        return persisted;
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

    /** 清理过期安全数据。 */
    public int cleanupExpiredSecurityRows() {
        return securityRepository.cleanupExpiredRows() + idempotencies.deleteExpired();
    }

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

    /** 将数据库租户字段转换为模型。 */
    private static TenantRecord mapTenant(Map<String, Object> row) {
        return new TenantRecord(uuid(row.get("id")), text(row.get("slug")), text(row.get("name")), text(row.get("status")),
                number(row.get("derivation_namespace")), Boolean.TRUE.equals(row.get("ip_allowlist_enabled")),
                text(row.get("display_currency")), instant(row.get("created_at")), instant(row.get("updated_at")));
    }

    /** 将数据库地址字段转换为模型。 */
    private static AddressRecord mapAddress(Map<String, Object> row) {
        return new AddressRecord(uuid(row.get("id")), uuid(row.get("tenant_id")), longValue(row.get("chain_address_id"), 0),
                text(row.get("chain")), text(row.get("network")), text(row.get("address")), text(row.get("memo")),
                text(row.get("subject")), text(row.get("label")), text(row.get("metadata_json")), text(row.get("source")),
                text(row.get("status")), number(row.get("derivation_subject")), longValue(row.get("address_version"), 0),
                longValue(row.get("derivation_child"), 0), instant(row.get("created_at")), instant(row.get("updated_at")));
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
    /** 将地址查询的空筛选参数转换为无筛选语义。 */
    private static String optionalBlank(String value) {
        String normalized = blank(value);
        return normalized.isEmpty() ? null : normalized;
    }

    /** 租户记录。 */
    public record TenantRecord(UUID id, String slug, String name, String status, int derivationNamespace,
                               boolean ipAllowlistEnabled, String displayCurrency, Instant createdAt, Instant updatedAt) { }
    /** 认证用户记录。 */
    public record AuthUser(UUID id, UUID tenantId, String tenantSlug, String tenantStatus, String email,
                           String displayName, String passwordHash, String role, String status,
                           int failedLoginCount, Instant lockedUntil) { }
    /** 会话记录。 */
    public record SessionRecord(UUID sessionId, UUID userId, UUID tenantId, String tenantSlug, String email,
                               String displayName, String role, String userStatus, String tenantStatus, Instant expiresAt) { }
    /** API 密钥记录。 */
    public record ApiKeyRecord(UUID id, UUID tenantId, String tenantSlug, String tenantStatus,
                               boolean ipAllowlistEnabled, String keyId, String name, String secretCiphertext,
                               String status, Instant expiresAt, Instant createdAt) { }
    /** 托管地址记录。 */
    public record AddressRecord(UUID id, UUID tenantId, long chainAddressId, String chain, String network,
                                String address, String memo, String subject, String label, String metadataJson,
                                String source, String status, int derivationSubject, long addressVersion,
                                long derivationChild, Instant createdAt, Instant updatedAt) { }
    /** Gas 账户记录。 */
    public record GasAccountRecord(UUID id, UUID tenantId, UUID custodyAddressId, String chain, String network,
                                   String nativeSymbol, String address, String memo, long childIndex, String accountId,
                                   BigDecimal availableBalance, BigDecimal lockedBalance, BigDecimal totalBalance,
                                   BigDecimal lowBalanceThreshold, String status, Instant createdAt, Instant updatedAt) {
        /** 判断 Gas 余额是否低于阈值。 */
        public boolean lowBalance() { return "ACTIVE".equals(status) && availableBalance.compareTo(lowBalanceThreshold) < 0; }
    }
    /** 幂等记录。 */
    public record IdempotencyRecord(String requestHash, Integer responseStatus, String responseJson, Instant expiresAt) { }
    /** Webhook 端点记录。 */
    public record WebhookEndpointRecord(UUID id, UUID tenantId, String name, String url, String secretCiphertext,
                                        String status, String verificationTokenHash, Instant verifiedAt,
                                        Instant lastDeliveryAt, Instant createdAt, Instant updatedAt) { }
    /** Webhook 投递任务。 */
    public record WebhookDeliveryTask(UUID id, UUID tenantId, UUID endpointId, UUID eventId, int attemptCount,
                                      int totalAttemptCount, int manualRetryCount, String attemptTrigger,
                                      UUID attemptId, String workerId, String url, String secretCiphertext,
                                      String eventType, String payload) { }
    /** 提现状态变化记录。 */
    public record WithdrawalStatusChange(UUID id, UUID tenantId, UUID custodyAddressId, String orderNo,
                                         String externalReference, String chain, String assetSymbol, String toAddress,
                                         BigDecimal amount, BigDecimal fee, String previousStatus, String nextStatus,
                                         String txHash, String errorMessage, String debitAccountId, String addressSource) { }
    /** Gas 定价元数据。 */
    public record GasPricingMetadata(String family, String nativeSymbol, long defaultFeeRate, int decimals,
                                     boolean requestedNative) { }
    /** Gas 使用记录。 */
    public record GasUsageRecord(UUID id, UUID tenantId, UUID gasAccountId, String operationType, UUID operationId,
                                 String referenceNo, String chain, String nativeSymbol, BigDecimal reservedAmount,
                                 BigDecimal actualAmount, String status, String pricingSource, String txHash,
                                 String errorMessage, Instant createdAt, Instant updatedAt, Instant settledAt) { }
    /** 网络费用记录。 */
    public record NetworkFee(BigDecimal amount, String pricingSource) { }
}
