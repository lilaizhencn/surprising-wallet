package com.surprising.wallet.custody.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.surprising.wallet.custody.repository.CustodyAssetDashboardRepository;
import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;

/**
 * 托管资产仪表盘服务，提供租户资产概览数据。
 *
 * <p>汇总各链的原生币余额、代币余额、充值/提现统计（24h/7d），
 * 用于 Console 首页仪表盘展示。
 */
@Service
public class CustodyAssetDashboardService {
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CustodyAssetDashboardRepository repository;
    /**
     * 保存 {@code custody}，用于承载当前对象的运行配置或业务数据。
     */
    private final CustodyRepository custody;
    /**
     * 构造 {@code CustodyAssetDashboardService}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyAssetDashboardService(CustodyAssetDashboardRepository repository,
                                        CustodyRepository custody) {
        this.repository = repository;
        this.custody = custody;
    }
    /**
     * 执行 {@code dashboard} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Dashboard dashboard(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        List<CustodyAssetDashboardRepository.AssetBalance> balances =
                repository.balances(principal.tenantId());
        BigDecimal totalValueUsd = BigDecimal.ZERO;
        int unpricedAssetCount = 0;
        Map<String, MutableAggregate> symbols = new LinkedHashMap<>();
        Map<String, MutableChain> chains = new LinkedHashMap<>();
        List<AssetRow> assets = new ArrayList<>();
        Instant oldestPriceAt = null;

        for (CustodyAssetDashboardRepository.AssetBalance row : balances) {
            BigDecimal valueUsd = row.usdPrice() == null
                    ? null : row.totalBalance().multiply(row.usdPrice());
            if (valueUsd == null && row.totalBalance().signum() != 0) {
                unpricedAssetCount++;
            }
            if (valueUsd != null) {
                totalValueUsd = totalValueUsd.add(valueUsd);
            }
            if (row.priceObservedAt() != null
                    && (oldestPriceAt == null || row.priceObservedAt().isBefore(oldestPriceAt))) {
                oldestPriceAt = row.priceObservedAt();
            }
            AssetRow asset = new AssetRow(
                    row.chain(), row.assetSymbol(), row.nativeAsset(),
                    row.availableBalance(), row.lockedBalance(),
                    row.totalBalance(), row.addressCount(), row.usdPrice(), valueUsd,
                    row.priceSource(), row.priceObservedAt());
            assets.add(asset);
            symbols.computeIfAbsent(row.assetSymbol(), MutableAggregate::new).add(asset);
            chains.computeIfAbsent(row.chain(), MutableChain::new).add(asset);
        }

        return new Dashboard(
                Instant.now(), "USD", totalValueUsd, unpricedAssetCount, oldestPriceAt,
                assets,
                symbols.values().stream().map(MutableAggregate::view).toList(),
                chains.values().stream().map(MutableChain::view).toList(),
                repository.openReorgDeficits(principal.tenantId()).stream()
                        .map(row -> new ReorgDeficit(
                                row.id(), row.custodyAddressId(), row.chain(), row.assetSymbol(),
                                row.deficitAmount(), row.recoveredAmount(), row.outstandingAmount(),
                                row.createdAt()))
                        .toList());
    }
    /**
     * 执行 {@code prices} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public List<CustodyAssetDashboardRepository.AssetPrice> prices(CustodyPrincipal principal) {
        requirePlatformAdmin(principal);
        return repository.prices();
    }

    /**
     * 设置或更新 {@code setPrice} 对应的状态，并保持相关业务字段一致。
     */
    @Transactional(rollbackFor = Throwable.class)
    public CustodyAssetDashboardRepository.AssetPrice setPrice(
            CustodyPrincipal principal, String symbolValue, SetPriceCommand command, String sourceIp) {
        requirePlatformAdmin(principal);
        String symbol = normalizeSymbol(symbolValue);
        if (command.usdPrice() == null || command.usdPrice().signum() <= 0
                || command.usdPrice().scale() > 18 || command.usdPrice().precision() > 38) {
            throw new IllegalArgumentException("usdPrice must be a positive decimal with at most 18 decimals");
        }
        String source = command.source() == null ? "" : command.source().trim();
        if (!source.matches("^[A-Za-z0-9][A-Za-z0-9._:/ -]{0,79}$")) {
            throw new IllegalArgumentException("price source contains unsupported characters");
        }
        Instant observedAt = command.observedAt() == null ? Instant.now() : command.observedAt();
        if (observedAt.isAfter(Instant.now().plusSeconds(60))) {
            throw new IllegalArgumentException("price observation time cannot be in the future");
        }
        CustodyAssetDashboardRepository.AssetPrice saved =
                repository.upsertPrice(symbol, command.usdPrice(), source, observedAt);
        custody.audit(null, principal.actorType().name(), principal.actorId().toString(),
                "ASSET_PRICE.UPDATE", "ASSET_PRICE", symbol, sourceIp,
                "{\"assetSymbol\":\"" + symbol + "\",\"source\":\"" + source + "\"}");
        return saved;
    }
    /**
     * 转换或计算 {@code normalizeSymbol} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeSymbol(String value) {
        String symbol = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid asset symbol is required");
        }
        return symbol;
    }
    /**
     * 校验 {@code requireScope} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }
    /**
     * 校验 {@code requirePlatformAdmin} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requirePlatformAdmin(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() != null
                || !"PLATFORM_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    private static final class MutableAggregate {
        /**
         * 保存 {@code symbol}，表示链、网络、资产或代币配置。
         */
        private final String symbol;
        /**
         * 保存 {@code available}，用于承载当前对象的运行配置或业务数据。
         */
        private BigDecimal available = BigDecimal.ZERO;
        /**
         * 保存 {@code locked}，用于承载当前对象的运行配置或业务数据。
         */
        private BigDecimal locked = BigDecimal.ZERO;
        /**
         * 保存 {@code total}，用于承载当前对象的运行配置或业务数据。
         */
        private BigDecimal total = BigDecimal.ZERO;
        /**
         * 保存 {@code valueUsd}，用于保存金额、费用或链上执行状态。
         */
        private BigDecimal valueUsd = BigDecimal.ZERO;
        /**
         * 保存 {@code priced}，表示金额、余额、手续费、Gas 或精度相关参数。
         */
        private boolean priced;
        /**
         * 保存 {@code chains}，表示链、网络、资产或代币配置。
         */
        private final List<String> chains = new ArrayList<>();

        /**
         * 构造 {@code MutableAggregate}，初始化该组件运行所需的状态和依赖。
         */
        private MutableAggregate(String symbol) {
            this.symbol = symbol;
        }

        /**
         * 添加 {@code add} 对应的业务对象，并更新当前组件的集合或索引。
         */
        private void add(AssetRow row) {
            available = available.add(row.availableBalance());
            locked = locked.add(row.lockedBalance());
            total = total.add(row.totalBalance());
            chains.add(row.chain());
            if (row.valueUsd() != null) {
                valueUsd = valueUsd.add(row.valueUsd());
                priced = true;
            }
        }

        /**
         * 获取或查询 {@code view} 对应的数据，并向调用方返回当前业务状态。
         */
        private SymbolAggregate view() {
            return new SymbolAggregate(symbol, available, locked, total,
                    priced ? valueUsd : null, List.copyOf(chains));
        }
    }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    private static final class MutableChain {
        /**
         * 保存 {@code chain}，表示链、网络、资产或代币配置。
         */
        private final String chain;
        /**
         * 保存 {@code valueUsd}，用于保存金额、费用或链上执行状态。
         */
        private BigDecimal valueUsd = BigDecimal.ZERO;
        /**
         * 保存 {@code priced}，表示金额、余额、手续费、Gas 或精度相关参数。
         */
        private boolean priced;
        /**
         * 保存 {@code assets}，表示链、网络、资产或代币配置。
         */
        private final List<AssetRow> assets = new ArrayList<>();

        /**
         * 构造 {@code MutableChain}，初始化该组件运行所需的状态和依赖。
         */
        private MutableChain(String chain) {
            this.chain = chain;
        }

        /**
         * 添加 {@code add} 对应的业务对象，并更新当前组件的集合或索引。
         */
        private void add(AssetRow row) {
            assets.add(row);
            if (row.valueUsd() != null) {
                valueUsd = valueUsd.add(row.valueUsd());
                priced = true;
            }
        }

        /**
         * 获取或查询 {@code view} 对应的数据，并向调用方返回当前业务状态。
         */
        private ChainAggregate view() {
            return new ChainAggregate(chain, priced ? valueUsd : null, List.copyOf(assets));
        }
    }

    public record Dashboard(
            Instant asOf,
            String displayCurrency,
            BigDecimal totalValueUsd,
            int unpricedAssetCount,
            Instant oldestPriceObservedAt,
            List<AssetRow> assets,
            List<SymbolAggregate> bySymbol,
            List<ChainAggregate> byChain,
            List<ReorgDeficit> reorgDeficits
    ) {
    }

    public record AssetRow(
            String chain,
            String assetSymbol,
            boolean nativeAsset,
            BigDecimal availableBalance,
            BigDecimal lockedBalance,
            BigDecimal totalBalance,
            long addressCount,
            BigDecimal usdPrice,
            BigDecimal valueUsd,
            String priceSource,
            Instant priceObservedAt
    ) {
    }

    public record SymbolAggregate(
            String assetSymbol,
            BigDecimal availableBalance,
            BigDecimal lockedBalance,
            BigDecimal totalBalance,
            BigDecimal valueUsd,
            List<String> chains
    ) {
    }
    public record ChainAggregate(String chain, BigDecimal valueUsd, List<AssetRow> assets) {
    }

    public record ReorgDeficit(
            java.util.UUID id,
            java.util.UUID custodyAddressId,
            String chain,
            String assetSymbol,
            BigDecimal deficitAmount,
            BigDecimal recoveredAmount,
            BigDecimal outstandingAmount,
            Instant createdAt
    ) {
    }
    public record SetPriceCommand(BigDecimal usdPrice, String source, Instant observedAt) {
    }
}
