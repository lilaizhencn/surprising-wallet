package com.surprising.wallet.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** custody_tenant_chain 单表仓储。 */
@Repository
public class CustodyTenantChainRepository {
    /** JDBC 模板，仅用于访问 custody_tenant_chain。 */
    private final JdbcTemplate jdbc;
    /** 链配置单表仓储。 */
    private final ChainProfileRepository chainProfiles;
    /** 链资产单表仓储。 */
    private final ChainAssetRepository chainAssets;
    /** 代币配置单表仓储。 */
    private final TokenConfigRepository tokenConfigs;
    /** EIP-7702 配置单表仓储。 */
    private final Evm7702ConfigRepository evm7702Configs;

    /** 兼容测试和手工构造，初始化全部单表仓储。 */
    public CustodyTenantChainRepository(JdbcTemplate jdbc) {
        this(jdbc, new ChainProfileRepository(jdbc), new ChainAssetRepository(jdbc),
                new TokenConfigRepository(jdbc), new Evm7702ConfigRepository(jdbc));
    }

    /** 构造租户链仓储，注入各自负责单表的数据访问组件。 */
    @Autowired
    public CustodyTenantChainRepository(
            JdbcTemplate jdbc,
            ChainProfileRepository chainProfiles,
            ChainAssetRepository chainAssets,
            TokenConfigRepository tokenConfigs,
            Evm7702ConfigRepository evm7702Configs) {
        this.jdbc = jdbc;
        this.chainProfiles = chainProfiles;
        this.chainAssets = chainAssets;
        this.tokenConfigs = tokenConfigs;
        this.evm7702Configs = evm7702Configs;
    }

    /** 查询平台已启用链及租户在该链上的状态。 */
    public List<ChainRecord> list(UUID tenantId) {
        Map<String, Map<String, Object>> tenantChains = jdbc.queryForList("""
                        select chain, status, opened_at, closed_at
                          from custody_tenant_chain
                         where tenant_id = ?
                        """, tenantId).stream()
                .collect(Collectors.toMap(
                        row -> text(row.get("chain")).toUpperCase(),
                        row -> row,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<TokenRecord>> tokensByChain = tokens().stream()
                .collect(Collectors.groupingBy(
                        TokenRecord::chain, LinkedHashMap::new, Collectors.toList()));
        return chainProfiles.listAll().stream()
                .filter(row -> bool(row.get("enabled")))
                .map(profile -> {
                    String chain = text(profile.get("chain"));
                    Map<String, Object> tenantChain = tenantChains.get(chain.toUpperCase());
                    return new ChainRecord(
                            chain,
                            text(profile.get("network")),
                            text(profile.get("family")),
                            text(profile.get("native_symbol")),
                            evm7702Configs.existsActive(chain, text(profile.get("network"))),
                            bool(profile.get("scan_enabled")),
                            bool(profile.get("withdraw_enabled")),
                            bool(profile.get("transfer_enabled")),
                            tenantChain == null ? "CLOSED" : text(tenantChain.get("status")),
                            tenantChain == null ? null : instant(tenantChain.get("opened_at")),
                            tenantChain == null ? null : instant(tenantChain.get("closed_at")),
                            tokensByChain.getOrDefault(chain, List.of()));
                })
                .toList();
    }

    /** 查询平台启用的非原生代币配置，并在 Java 中组合链资产和代币表。 */
    public List<TokenRecord> tokens() {
        List<Map<String, Object>> profiles = chainProfiles.listAll().stream()
                .filter(row -> bool(row.get("enabled")))
                .toList();
        List<Map<String, Object>> assets = chainAssets.listActive().stream()
                .filter(row -> !bool(row.get("native_asset")))
                .toList();
        List<Map<String, Object>> configs = tokenConfigs.listAll();
        return profiles.stream()
                .flatMap(profile -> assets.stream()
                        .filter(asset -> same(profile.get("chain"), asset.get("chain")))
                        .map(asset -> configs.stream()
                                .filter(token -> same(token.get("chain"), asset.get("chain")))
                                .filter(token -> same(token.get("symbol"), asset.get("symbol")))
                                .filter(token -> same(token.get("network"), profile.get("network")))
                                .findFirst()
                                .map(token -> new TokenRecord(
                                        text(asset.get("chain")), text(asset.get("symbol")),
                                        text(token.get("standard")), text(token.get("contract_address")),
                                        number(token.get("decimals")), bool(token.get("enabled")))))
                        .flatMap(java.util.Optional::stream))
                .sorted(java.util.Comparator.comparing(TokenRecord::chain)
                        .thenComparing(TokenRecord::symbol))
                .toList();
    }

    /** 判断平台链配置是否启用。 */
    public boolean platformChainEnabled(String chain) {
        return chainProfiles.listAll().stream()
                .anyMatch(row -> same(row.get("chain"), chain) && bool(row.get("enabled")));
    }

    /** 判断租户是否已启用指定链，链平台配置由链配置仓储负责校验。 */
    public boolean active(UUID tenantId, String chain) {
        return platformChainEnabled(chain) && !jdbc.queryForList("""
                select chain
                  from custody_tenant_chain
                 where tenant_id = ? and upper(chain) = upper(?) and status = 'ACTIVE'
                """, tenantId, chain).isEmpty();
    }

    /** 查询租户当前已启用的链名称。 */
    public List<String> listActiveChains(UUID tenantId) {
        return jdbc.queryForList("""
                select chain
                  from custody_tenant_chain
                 where tenant_id = ? and status = 'ACTIVE'
                """, String.class, tenantId);
    }

    /** 更新租户链启用状态。 */
    public void setStatus(UUID tenantId, String chain, String status, UUID actorId) {
        jdbc.update("""
                insert into custody_tenant_chain(
                    tenant_id, chain, status, opened_by, opened_at,
                    closed_by, closed_at, updated_at)
                values (?, ?, ?,
                        case when ? = 'ACTIVE' then ?::uuid end,
                        case when ? = 'ACTIVE' then now() end,
                        case when ? = 'CLOSED' then ?::uuid end,
                        case when ? = 'CLOSED' then now() end,
                        now())
                on conflict (tenant_id, chain) do update set
                    status = excluded.status,
                    opened_by = case when excluded.status = 'ACTIVE' then excluded.opened_by
                                     else custody_tenant_chain.opened_by end,
                    opened_at = case when excluded.status = 'ACTIVE' then now()
                                     else custody_tenant_chain.opened_at end,
                    closed_by = case when excluded.status = 'CLOSED' then excluded.closed_by
                                     else null end,
                    closed_at = case when excluded.status = 'CLOSED' then now() else null end,
                    updated_at = now()
                """, tenantId, chain, status,
                status, actorId, status, status, actorId, status);
    }

    /** 判断租户指定链和资产是否允许充值。 */
    public boolean depositEnabled(UUID tenantId, String chain, String symbol) {
        return assetOperationEnabled(tenantId, chain, symbol, true);
    }

    /** 判断租户指定链和资产是否允许提现。 */
    public boolean withdrawalEnabled(UUID tenantId, String chain, String symbol) {
        return assetOperationEnabled(tenantId, chain, symbol, false);
    }

    /** 在 Java 中组合四张单表的开关和资产配置。 */
    private boolean assetOperationEnabled(UUID tenantId, String chain, String symbol,
                                          boolean deposit) {
        Map<String, Object> profile = chainProfiles.listAll().stream()
                .filter(row -> same(row.get("chain"), chain) && bool(row.get("enabled")))
                .findFirst().orElse(null);
        if (profile == null || !active(tenantId, chain)) {
            return false;
        }
        boolean operationEnabled = deposit
                ? bool(profile.get("scan_enabled"))
                : bool(profile.get("withdraw_enabled")) && bool(profile.get("transfer_enabled"));
        if (!operationEnabled) {
            return false;
        }
        Map<String, Object> asset = chainAssets.findActive(chain, symbol).orElse(null);
        if (asset == null) {
            return false;
        }
        return bool(asset.get("native_asset")) || tokenConfigs.listAll().stream()
                .anyMatch(token -> same(token.get("chain"), chain)
                        && same(token.get("symbol"), symbol)
                        && same(token.get("network"), profile.get("network"))
                        && bool(token.get("enabled")));
    }

    /** 比较两个链配置文本。 */
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

    /** 读取数字字段。 */
    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
    }

    /** 转换时间字段。 */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return value instanceof Instant result ? result : null;
    }

    /** 租户链展示记录。 */
    public record ChainRecord(
            String chain,
            String network,
            String family,
            String nativeSymbol,
            boolean eip7702Enabled,
            boolean scanEnabled,
            boolean withdrawalEnabled,
            boolean transferEnabled,
            String status,
            Instant openedAt,
            Instant closedAt,
            List<TokenRecord> tokens
    ) {
    }

    /** 租户链上的代币展示记录。 */
    public record TokenRecord(
            String chain,
            String symbol,
            String standard,
            String contractAddress,
            int decimals,
            boolean platformEnabled
    ) {
    }
}
