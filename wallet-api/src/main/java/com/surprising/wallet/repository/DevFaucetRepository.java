package com.surprising.wallet.repository;

import com.surprising.wallet.devfaucet.model.DevFaucetFunding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 开发水龙头数据访问门面，实际 SQL 由单表仓储执行。 */
@Repository
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
public class DevFaucetRepository {
    /** 水龙头资金单表仓储。 */
    private final DevFaucetFundingRepository fundings;
    /** 托管地址单表仓储。 */
    private final CustodyAddressRepository custodyAddresses;
    /** 托管租户单表仓储。 */
    private final CustodyTenantTableRepository tenants;
    /** 租户链单表仓储。 */
    private final CustodyTenantChainRepository tenantChains;
    /** Gas 账户单表仓储。 */
    private final CustodyGasAccountRepository gasAccounts;
    /** 链配置单表仓储。 */
    private final ChainProfileRepository chainProfiles;
    /** 链资产单表仓储。 */
    private final ChainAssetRepository chainAssets;
    /** 代币配置单表仓储。 */
    private final TokenConfigRepository tokenConfigs;
    /** 托管充值单表仓储。 */
    private final CustodyDepositRepository deposits;

    /** 兼容测试和手工构造。 */
    public DevFaucetRepository(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this(new DevFaucetFundingRepository(jdbc), new CustodyAddressRepository(jdbc),
                new CustodyTenantTableRepository(jdbc), new CustodyTenantChainRepository(jdbc),
                new CustodyGasAccountRepository(jdbc), new ChainProfileRepository(jdbc),
                new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                new CustodyDepositRepository(jdbc));
    }

    /** Spring 构造器，注入各自负责单表的数据访问组件。 */
    @Autowired
    public DevFaucetRepository(
            DevFaucetFundingRepository fundings,
            CustodyAddressRepository custodyAddresses,
            CustodyTenantTableRepository tenants,
            CustodyTenantChainRepository tenantChains,
            CustodyGasAccountRepository gasAccounts,
            ChainProfileRepository chainProfiles,
            ChainAssetRepository chainAssets,
            TokenConfigRepository tokenConfigs,
            CustodyDepositRepository deposits) {
        this.fundings = fundings;
        this.custodyAddresses = custodyAddresses;
        this.tenants = tenants;
        this.tenantChains = tenantChains;
        this.gasAccounts = gasAccounts;
        this.chainProfiles = chainProfiles;
        this.chainAssets = chainAssets;
        this.tokenConfigs = tokenConfigs;
        this.deposits = deposits;
    }

    /** 查询待补充的原生币和代币地址，关联逻辑在 Java 中完成。 */
    public List<Candidate> discover(int limit) {
        Set<UUID> activeTenants = new HashSet<>(tenants.listActiveIds());
        Set<UUID> activeGasAddresses = new HashSet<>(gasAccounts.listActive().stream()
                .map(row -> uuid(row.get("custody_address_id"))).toList());
        List<Map<String, Object>> profiles = chainProfiles.listAll();
        List<Map<String, Object>> assets = chainAssets.listActive();
        List<Map<String, Object>> tokens = tokenConfigs.listAll();
        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> address : custodyAddresses.listAll()) {
            UUID tenantId = uuid(address.get("tenant_id"));
            UUID addressId = uuid(address.get("id"));
            String chain = text(address.get("chain"));
            if (!activeTenants.contains(tenantId) || !Set.of("BTC", "ETH").contains(chain)
                    || !"ACTIVE".equalsIgnoreCase(text(address.get("status")))) {
                continue;
            }
            boolean gas = activeGasAddresses.contains(addressId);
            if ((!gas && !"API".equalsIgnoreCase(text(address.get("source"))))) {
                continue;
            }
            if (!tenantChains.listActiveChains(tenantId).stream().anyMatch(value -> same(value, chain))) {
                continue;
            }
            Map<String, Object> profile = profiles.stream()
                    .filter(row -> same(row.get("chain"), chain))
                    .filter(row -> same(row.get("network"), address.get("network")))
                    .filter(row -> bool(row.get("enabled")) && bool(row.get("scan_enabled")))
                    .findFirst().orElse(null);
            if (profile == null) {
                continue;
            }
            for (Map<String, Object> asset : assets) {
                if (!same(asset.get("chain"), chain) || !bool(asset.get("native_asset"))) {
                    continue;
                }
                String purpose = gas ? "TENANT_GAS" : "CUSTOMER_DEPOSIT";
                addIfNew(candidates, new Candidate(
                        tenantId, addressId, chain, text(address.get("network")),
                        text(asset.get("symbol")), purpose, text(address.get("address")),
                        null, number(asset.get("decimals"))));
            }
            if ("ETH".equals(chain) && !gas) {
                for (Map<String, Object> token : tokens) {
                    if (!Set.of("USDT", "USDC").contains(text(token.get("symbol")))
                            || !same(token.get("chain"), chain)
                            || !bool(token.get("enabled"))
                            || (token.get("network") != null
                            && !same(token.get("network"), profile.get("network")))) {
                        continue;
                    }
                    boolean assetMatches = assets.stream().anyMatch(asset ->
                            same(asset.get("chain"), chain)
                                    && same(asset.get("symbol"), token.get("symbol"))
                                    && !bool(asset.get("native_asset"))
                                    && bool(asset.get("active"))
                                    && same(asset.get("contract_address"), token.get("contract_address")));
                    if (!assetMatches) {
                        continue;
                    }
                    addIfNew(candidates, new Candidate(
                            tenantId, addressId, chain, text(address.get("network")),
                            text(token.get("symbol")), "CUSTOMER_DEPOSIT",
                            text(address.get("address")), text(token.get("contract_address")),
                            number(token.get("decimals"))));
                }
            }
        }
        return candidates.stream()
                .sorted(java.util.Comparator.comparing(Candidate::custodyAddressId)
                        .thenComparing(Candidate::assetSymbol))
                .limit(Math.max(limit, 0))
                .toList();
    }

    /** 创建补币资金任务。 */
    public boolean create(Candidate candidate, BigDecimal amount) {
        return fundings.create(candidate.tenantId(), candidate.custodyAddressId(), candidate.chain(),
                candidate.network(), candidate.assetSymbol(), candidate.purpose(), candidate.address(),
                candidate.contractAddress(), candidate.decimals(), amount);
    }

    /** 查询待发送资金任务。 */
    public List<DevFaucetFunding> due(int limit, int maxAttempts) {
        return fundings.due(limit, maxAttempts);
    }

    /** 标记资金任务进入发送状态。 */
    public boolean markSending(UUID id) {
        return fundings.markSending(id);
    }

    /** 标记资金任务已发送。 */
    public void markSent(UUID id, String txHash) {
        fundings.markSent(id, txHash);
    }

    /** 标记资金任务失败。 */
    public void markFailed(UUID id, String error, Duration retryDelay) {
        fundings.markFailed(id, error, retryDelay);
    }

    /** 标记资金任务结果未知。 */
    public void markUnknown(UUID id, String error) {
        fundings.markUnknown(id, error);
    }

    /** 恢复超时发送任务。 */
    public int recoverStaleSending(Duration age) {
        return fundings.recoverStaleSending(age);
    }

    /** 对账已确认资金任务。 */
    public int reconcileConfirmed() {
        int updated = 0;
        for (DevFaucetFundingRepository.SentFunding funding : fundings.sent()) {
            Timestamp creditedAt = deposits.findConfirmedAt(
                    funding.tenantId(), funding.custodyAddressId(), funding.chain(),
                    funding.assetSymbol(), funding.txHash());
            if (creditedAt != null) {
                updated += fundings.markConfirmed(funding.id(), creditedAt);
            }
        }
        return updated;
    }

    /** 保持候选去重并排除已经创建的资金任务。 */
    private void addIfNew(List<Candidate> candidates, Candidate candidate) {
        if (!fundings.exists(candidate.custodyAddressId(), candidate.assetSymbol(), candidate.purpose())) {
            candidates.add(candidate);
        }
    }

    /** 比较业务文本。 */
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
    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
    }

    /** 读取 UUID 字段。 */
    private static UUID uuid(Object value) {
        return value instanceof UUID result ? result : value == null ? null : UUID.fromString(value.toString());
    }

    /** 开发水龙头候选地址。 */
    public record Candidate(
            UUID tenantId,
            UUID custodyAddressId,
            String chain,
            String network,
            String assetSymbol,
            String purpose,
            String address,
            String contractAddress,
            int decimals
    ) {
    }
}
