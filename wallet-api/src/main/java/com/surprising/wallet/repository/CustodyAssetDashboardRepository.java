package com.surprising.wallet.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** custody_asset_price 单表仓储，并组合单表仓储生成资产看板数据。 */
@Component
public class CustodyAssetDashboardRepository {
    /** JDBC 模板，仅用于访问 custody_asset_price。 */
    private final JdbcTemplate jdbc;
    /** 托管地址单表仓储。 */
    private final CustodyAddressRepository custodyAddresses;
    /** Gas 账户单表仓储。 */
    private final CustodyGasAccountRepository gasAccounts;
    /** 链地址单表仓储。 */
    private final ChainAddressRepository chainAddresses;
    /** 链配置单表仓储。 */
    private final ChainProfileRepository chainProfiles;
    /** 链资产单表仓储。 */
    private final ChainAssetRepository chainAssets;
    /** 代币配置单表仓储。 */
    private final TokenConfigRepository tokenConfigs;
    /** 租户链单表仓储。 */
    private final CustodyTenantChainRepository tenantChains;
    /** 账本余额单表仓储。 */
    private final LedgerBalanceRepository ledgerBalances;
    /** 重组赤字单表仓储。 */
    private final CustodyReorgDeficitRepository reorgDeficits;

    /** 兼容测试和手工构造。 */
    public CustodyAssetDashboardRepository(JdbcTemplate jdbc) {
        this(jdbc, new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new ChainAddressRepository(jdbc), new ChainProfileRepository(jdbc),
                new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                new CustodyTenantChainRepository(jdbc), new LedgerBalanceRepository(jdbc),
                new CustodyReorgDeficitRepository(jdbc));
    }

    /** 构造资产看板仓储，注入各自负责单表的数据访问组件。 */
    @Autowired
    public CustodyAssetDashboardRepository(
            JdbcTemplate jdbc,
            CustodyAddressRepository custodyAddresses,
            CustodyGasAccountRepository gasAccounts,
            ChainAddressRepository chainAddresses,
            ChainProfileRepository chainProfiles,
            ChainAssetRepository chainAssets,
            TokenConfigRepository tokenConfigs,
            CustodyTenantChainRepository tenantChains,
            LedgerBalanceRepository ledgerBalances,
            CustodyReorgDeficitRepository reorgDeficits) {
        this.jdbc = jdbc;
        this.custodyAddresses = custodyAddresses;
        this.gasAccounts = gasAccounts;
        this.chainAddresses = chainAddresses;
        this.chainProfiles = chainProfiles;
        this.chainAssets = chainAssets;
        this.tokenConfigs = tokenConfigs;
        this.tenantChains = tenantChains;
        this.ledgerBalances = ledgerBalances;
        this.reorgDeficits = reorgDeficits;
    }

    /** 查询租户的资产余额，跨表关系在 Java 中按单表结果组合。 */
    public List<AssetBalance> balances(UUID tenantId) {
        List<Account> accounts = customerAccounts(tenantId);
        List<Map<String, Object>> balances = ledgerBalances.listByTenant(tenantId);
        Map<String, AssetPrice> prices = prices().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AssetPrice::assetSymbol, row -> row, (left, right) -> left));
        List<ConfiguredAsset> configuredAssets = configuredAssets(tenantId);
        List<AssetBalance> result = new ArrayList<>();
        for (ConfiguredAsset configured : configuredAssets) {
            List<Map<String, Object>> matched = balances.stream()
                    .filter(row -> same(row.get("chain"), configured.chain()))
                    .filter(row -> same(row.get("asset_symbol"), configured.symbol()))
                    .filter(row -> accounts.stream().anyMatch(account -> account.matches(row)))
                    .toList();
            BigDecimal available = sum(matched, "available_balance");
            BigDecimal locked = sum(matched, "locked_balance");
            BigDecimal total = sum(matched, "total_balance");
            long addressCount = matched.stream()
                    .map(row -> accounts.stream()
                            .filter(account -> account.matches(row))
                            .map(Account::custodyAddressId)
                            .filter(Objects::nonNull)
                            .findFirst().orElse(null))
                    .filter(Objects::nonNull)
                    .distinct().count();
            AssetPrice price = prices.get(configured.symbol());
            result.add(new AssetBalance(
                    configured.chain(), configured.symbol(), configured.nativeAsset(),
                    available, locked, total, addressCount,
                    price == null ? null : price.usdPrice(),
                    price == null ? null : price.source(),
                    price == null ? null : price.observedAt()));
        }
        return result;
    }

    /** 查询全部资产价格。 */
    public List<AssetPrice> prices() {
        return jdbc.query("""
                select asset_symbol, usd_price, source, observed_at, updated_at
                  from custody_asset_price
                 order by asset_symbol
                """, (rs, rowNum) -> new AssetPrice(
                rs.getString("asset_symbol"), rs.getBigDecimal("usd_price"),
                rs.getString("source"), rs.getTimestamp("observed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()));
    }

    /** 查询尚未弥补的重组赤字。 */
    public List<ReorgDeficit> openReorgDeficits(UUID tenantId) {
        return reorgDeficits.listOpen(tenantId);
    }

    /** 新增或更新资产价格。 */
    public AssetPrice upsertPrice(String symbol, BigDecimal price, String source, Instant observedAt) {
        return jdbc.queryForObject("""
                insert into custody_asset_price(asset_symbol, usd_price, source, observed_at, updated_at)
                values (?, ?, ?, ?, now())
                on conflict (asset_symbol) do update set
                    usd_price = excluded.usd_price,
                    source = excluded.source,
                    observed_at = excluded.observed_at,
                    updated_at = now()
                returning asset_symbol, usd_price, source, observed_at, updated_at
                """, (rs, rowNum) -> new AssetPrice(
                rs.getString("asset_symbol"), rs.getBigDecimal("usd_price"),
                rs.getString("source"), rs.getTimestamp("observed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
                symbol, price, source, Timestamp.from(observedAt));
    }

    /** 组合租户非 Gas 托管地址和其对应的链地址账户。 */
    private List<Account> customerAccounts(UUID tenantId) {
        Set<UUID> gasAddressIds = new HashSet<>(gasAccounts.listCustodyAddressIds(tenantId));
        List<Map<String, Object>> addresses = custodyAddresses.listByTenant(tenantId).stream()
                .filter(row -> !gasAddressIds.contains(uuid(row.get("id"))))
                .toList();
        List<Map<String, Object>> chainRows = chainAddresses.listByTenant(tenantId);
        List<Account> result = new ArrayList<>();
        for (Map<String, Object> address : addresses) {
            Map<String, Object> base = chainRows.stream()
                    .filter(row -> Objects.equals(longValue(row.get("id")),
                            longValue(address.get("chain_address_id"))))
                    .findFirst().orElse(null);
            if (base == null) {
                continue;
            }
            List<Map<String, Object>> related = chainRows.stream()
                    .filter(row -> bool(row.get("enabled")))
                    .filter(row -> same(row.get("chain"), base.get("chain")))
                    .filter(row -> Objects.equals(row.get("user_id"), base.get("user_id")))
                    .filter(row -> Objects.equals(row.get("biz"), base.get("biz")))
                    .filter(row -> Objects.equals(row.get("address_index"), base.get("address_index")))
                    .filter(row -> Objects.equals(row.get("wallet_role"), base.get("wallet_role")))
                    .toList();
            if (related.isEmpty()) {
                related = List.of(base);
            }
            for (Map<String, Object> row : related) {
                result.add(new Account(uuid(address.get("id")), text(row.get("chain")),
                        text(row.get("account_id"))));
            }
        }
        return result;
    }

    /** 组合租户已启用链、链资产和代币配置。 */
    private List<ConfiguredAsset> configuredAssets(UUID tenantId) {
        Set<String> activeChains = tenantChains.listActiveChains(tenantId).stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> profiles = chainProfiles.listAll().stream()
                .filter(row -> bool(row.get("enabled")))
                .filter(row -> activeChains.contains(text(row.get("chain")).toUpperCase(
                        java.util.Locale.ROOT)))
                .toList();
        List<Map<String, Object>> assets = chainAssets.listActive();
        List<Map<String, Object>> tokens = tokenConfigs.listAll();
        Map<String, ConfiguredAsset> result = new LinkedHashMap<>();
        for (Map<String, Object> profile : profiles) {
            String chain = text(profile.get("chain"));
            String network = text(profile.get("network"));
            for (Map<String, Object> asset : assets) {
                if (!same(asset.get("chain"), chain)) {
                    continue;
                }
                boolean nativeAsset = bool(asset.get("native_asset"));
                boolean configured = nativeAsset || tokens.stream().anyMatch(token ->
                        same(token.get("chain"), chain)
                                && same(token.get("symbol"), asset.get("symbol"))
                                && same(token.get("network"), network));
                if (configured) {
                    ConfiguredAsset value = new ConfiguredAsset(
                            chain, text(asset.get("symbol")), nativeAsset);
                    result.putIfAbsent(chain + "\n" + value.symbol(), value);
                }
            }
        }
        return result.values().stream().toList();
    }

    /** 对余额列求和。 */
    private static BigDecimal sum(List<Map<String, Object>> rows, String field) {
        return rows.stream().map(row -> decimal(row.get(field)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 比较两个业务文本。 */
    private static boolean same(Object left, Object right) {
        return left != null && right != null
                && left.toString().equalsIgnoreCase(right.toString());
    }

    /** 读取文本字段。 */
    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** 读取布尔字段。 */
    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value));
    }

    /** 读取数值字段。 */
    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(text(value));
    }

    /** 读取金额字段。 */
    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal
                : value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    /** 读取 UUID 字段。 */
    private static UUID uuid(Object value) {
        return value instanceof UUID result ? result : value == null ? null : UUID.fromString(value.toString());
    }

    /** 租户账户组合键。 */
    private record Account(UUID custodyAddressId, String chain, String accountId) {
        /** 判断账本余额是否属于当前账户。 */
        private boolean matches(Map<String, Object> row) {
            return same(chain, row.get("chain")) && same(accountId, row.get("account_id"));
        }
    }

    /** 已配置资产。 */
    private record ConfiguredAsset(String chain, String symbol, boolean nativeAsset) {
    }

    /** 资产余额。 */
    public record AssetBalance(
            String chain,
            String assetSymbol,
            boolean nativeAsset,
            BigDecimal availableBalance,
            BigDecimal lockedBalance,
            BigDecimal totalBalance,
            long addressCount,
            BigDecimal usdPrice,
            String priceSource,
            Instant priceObservedAt
    ) {
    }

    /** 资产价格。 */
    public record AssetPrice(
            String assetSymbol,
            BigDecimal usdPrice,
            String source,
            Instant observedAt,
            Instant updatedAt
    ) {
    }

    /** 重组赤字。 */
    public record ReorgDeficit(
            UUID id,
            UUID custodyAddressId,
            String chain,
            String assetSymbol,
            BigDecimal deficitAmount,
            BigDecimal recoveredAmount,
            Instant createdAt
    ) {
        /** 计算尚未弥补的金额。 */
        public BigDecimal outstandingAmount() {
            return deficitAmount.subtract(recoveredAmount);
        }
    }
}
