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

@Service
public class CustodyAssetDashboardService {
    private final CustodyAssetDashboardRepository repository;
    private final CustodyRepository custody;
    public CustodyAssetDashboardService(CustodyAssetDashboardRepository repository,
                                        CustodyRepository custody) {
        this.repository = repository;
        this.custody = custody;
    }
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
    public List<CustodyAssetDashboardRepository.AssetPrice> prices(CustodyPrincipal principal) {
        requirePlatformAdmin(principal);
        return repository.prices();
    }

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
    private static String normalizeSymbol(String value) {
        String symbol = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid asset symbol is required");
        }
        return symbol;
    }
    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }
    private static void requirePlatformAdmin(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() != null
                || !"PLATFORM_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }
    private static final class MutableAggregate {
        private final String symbol;
        private BigDecimal available = BigDecimal.ZERO;
        private BigDecimal locked = BigDecimal.ZERO;
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal valueUsd = BigDecimal.ZERO;
        private boolean priced;
        private final List<String> chains = new ArrayList<>();

        private MutableAggregate(String symbol) {
            this.symbol = symbol;
        }

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

        private SymbolAggregate view() {
            return new SymbolAggregate(symbol, available, locked, total,
                    priced ? valueUsd : null, List.copyOf(chains));
        }
    }
    private static final class MutableChain {
        private final String chain;
        private BigDecimal valueUsd = BigDecimal.ZERO;
        private boolean priced;
        private final List<AssetRow> assets = new ArrayList<>();

        private MutableChain(String chain) {
            this.chain = chain;
        }

        private void add(AssetRow row) {
            assets.add(row);
            if (row.valueUsd() != null) {
                valueUsd = valueUsd.add(row.valueUsd());
                priced = true;
            }
        }

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
