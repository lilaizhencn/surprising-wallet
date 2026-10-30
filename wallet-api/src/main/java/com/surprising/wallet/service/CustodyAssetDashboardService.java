package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.chain.model.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.custody.AssetPriceRecord;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Asset holdings, configured dashboard valuation and platform price maintenance.
 */
@Service
@RequiredArgsConstructor
public class CustodyAssetDashboardService {

    private final CustodyAssetPriceRepository prices;

    private final CustodyAddressRepository addresses;

    private final CustodyGasAccountRepository gasAccounts;

    private final ChainAddressRepository chainAddresses;

    private final ChainProfileRepository profiles;

    private final ChainAssetRepository chainAssets;

    private final TokenConfigRepository tokens;

    private final CustodyTenantChainTableRepository tenantChains;

    private final LedgerBalanceRepository balances;

    private final CustodyReorgDeficitRepository deficits;

    private final CustodyAuditRepository audits;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CustomerAssetBalance> assets(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return observedBalancesForTenant(principal.tenantId());
    }

    /**
     * Observed holdings remain visible after a chain or asset closes to new work.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CustomerAssetBalance> observedBalancesForTenant(UUID tenantId) {
        Directory directory = directory(tenantId);
        return aggregate(tenantId, directory.gasAccounts(), directory.directCustomers(), false).entrySet().stream().map(entry -> entry.getValue().view(entry.getKey())).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dashboard dashboard(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        UUID tenant = principal.tenantId();
        Directory directory = directory(tenant);
        var holdings = aggregate(tenant, directory.gasAccounts(), directory.relatedCustomers(), true);
        var priceBySymbol = prices.list().stream().collect(Collectors.toMap(row -> upper(row.assetSymbol()), row -> row));
        var activeChains = tenantChains.listActiveChains(tenant).stream().map(CustodyAssetDashboardService::upper).collect(Collectors.toSet());
        var assetsByChain = chainAssets.listActiveDefinitions().stream().collect(Collectors.groupingBy(row -> upper(row.getChain())));
        var configuredTokens = tokens.listAll().stream().map(row -> new ConfiguredToken(row.chain(), row.network(), row.symbol())).collect(Collectors.toSet());
        Map<AssetKey, Boolean> configured = new LinkedHashMap<>();
        for (var profile : profiles.listProfiles(true)) {
            if (!activeChains.contains(upper(profile.getChain()))) {
                continue;
            }
            for (var asset : assetsByChain.getOrDefault(upper(profile.getChain()), List.of())) {
                boolean nativeAsset = Boolean.TRUE.equals(asset.getNativeAsset());
                if (nativeAsset || configuredTokens.contains(new ConfiguredToken(profile.getChain(), profile.getNetwork(), asset.getSymbol()))) {
                    configured.putIfAbsent(new AssetKey(profile.getChain(), asset.getSymbol()), nativeAsset);
                }
            }
        }
        BigDecimal totalValueUsd = BigDecimal.ZERO;
        int unpricedAssetCount = 0;
        Instant oldestPriceAt = null;
        List<AssetRow> rows = new ArrayList<>();
        Map<String, MutableAggregate> symbols = new LinkedHashMap<>();
        Map<String, MutableChain> chains = new LinkedHashMap<>();
        for (var configuredAsset : configured.entrySet()) {
            AssetKey key = configuredAsset.getKey();
            Amounts amount = holdings.getOrDefault(key, new Amounts());
            var price = priceBySymbol.get(key.symbol());
            BigDecimal value = price == null ? null : amount.total.multiply(price.usdPrice());
            if (value == null && amount.total.signum() != 0) {
                unpricedAssetCount++;
            }
            if (value != null) {
                totalValueUsd = totalValueUsd.add(value);
            }
            if (price != null && price.observedAt() != null && (oldestPriceAt == null || price.observedAt().isBefore(oldestPriceAt))) {
                oldestPriceAt = price.observedAt();
            }
            var row = new AssetRow(key.chain(), key.symbol(), configuredAsset.getValue(), amount.available, amount.locked, amount.total, amount.addresses.size(), price == null ? null : price.usdPrice(), value, price == null ? null : price.source(), price == null ? null : price.observedAt());
            rows.add(row);
            symbols.computeIfAbsent(key.symbol(), MutableAggregate::new).add(row);
            chains.computeIfAbsent(key.chain(), MutableChain::new).add(row);
        }
        return new Dashboard(Instant.now(), "USD", totalValueUsd, unpricedAssetCount, oldestPriceAt, rows, symbols.values().stream().map(MutableAggregate::view).toList(), chains.values().stream().map(MutableChain::view).toList(), deficits.listOpen(tenant).stream().map(row -> new ReorgDeficit(row.id(), row.custodyAddressId(), row.chain(), row.assetSymbol(), row.deficitAmount(), row.recoveredAmount(), row.outstandingAmount(), row.createdAt())).toList());
    }

    private Directory directory(UUID tenant) {
        Set<UUID> gasIds = new HashSet<>(gasAccounts.listCustodyAddressIds(tenant));
        var custody = addresses.listOwned(tenant);
        var chain = chainAddresses.listOwned(tenant);
        var byId = chain.stream().collect(Collectors.toMap(row -> row.getId(), row -> row));
        Map<WalletKey, List<ChainAddressRecord>> related = new HashMap<>();
        for (var row : chain) {
            if (Boolean.TRUE.equals(row.getEnabled())) {
                related.computeIfAbsent(WalletKey.of(row), ignored -> new ArrayList<>()).add(row);
            }
        }
        Set<AccountKey> gas = new HashSet<>();
        Map<AccountKey, Set<UUID>> direct = new HashMap<>();
        Map<AccountKey, Set<UUID>> customerRelated = new HashMap<>();
        for (CustodyAddressRecord row : custody) {
            var base = byId.get(row.chainAddressId());
            if (base == null) {
                continue;
            }
            AccountKey key = new AccountKey(base.getChain(), base.getAccountId());
            if (gasIds.contains(row.id())) {
                gas.add(key);
                continue;
            }
            direct.computeIfAbsent(key, ignored -> new HashSet<>()).add(row.id());
            for (var wallet : related.getOrDefault(WalletKey.of(base), List.of(base))) {
                customerRelated.computeIfAbsent(new AccountKey(wallet.getChain(), wallet.getAccountId()), ignored -> new HashSet<>()).add(row.id());
            }
        }
        return new Directory(gas, direct, customerRelated);
    }

    private Map<AssetKey, Amounts> aggregate(UUID tenant, Set<AccountKey> gas, Map<AccountKey, Set<UUID>> owners, boolean requireOwner) {
        Map<AssetKey, Amounts> result = new LinkedHashMap<>();
        for (LedgerBalanceRecord balance : balances.listOwned(tenant)) {
            AccountKey account = new AccountKey(balance.getChain(), balance.getAccountId());
            boolean tenantPool = CustodyPoolFundingService.accountId(tenant, balance.getChain(), balance.getAssetSymbol()).equals(balance.getAccountId());
            if (gas.contains(account) || requireOwner && !owners.containsKey(account) && !tenantPool) {
                continue;
            }
            var amount = result.computeIfAbsent(new AssetKey(balance.getChain(), balance.getAssetSymbol()), ignored -> new Amounts());
            amount.available = amount.available.add(balance.getAvailableBalance());
            amount.locked = amount.locked.add(balance.getLockedBalance());
            amount.total = amount.total.add(balance.getTotalBalance());
            amount.addresses.addAll(owners.getOrDefault(account, Set.of()));
        }
        return result;
    }

    public List<AssetPriceRecord> prices(CustodyPrincipal principal) {
        requirePlatformAdmin(principal);
        return prices.list();
    }

    @Transactional(rollbackFor = Throwable.class)
    public AssetPriceRecord setPrice(CustodyPrincipal principal, String symbolValue, SetPriceCommand command, String sourceIp) {
        requirePlatformAdmin(principal);
        String symbol = upper(symbolValue == null ? "" : symbolValue.trim());
        if (!symbol.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid asset symbol is required");
        }
        if (command.usdPrice() == null || command.usdPrice().signum() <= 0 || command.usdPrice().scale() > 18 || command.usdPrice().precision() > 38) {
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
        var saved = prices.upsert(symbol, command.usdPrice(), source, observedAt);
        audits.insert(UUID.randomUUID(), null, principal.actorType().name(), principal.actorId().toString(), "ASSET_PRICE.UPDATE", "ASSET_PRICE", symbol, sourceIp, "{\"assetSymbol\":\"" + symbol + "\",\"source\":\"" + source + "\"}");
        return saved;
    }

    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }

    private static void requirePlatformAdmin(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() != null || !"PLATFORM_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private record AccountKey(String chain, String accountId) {

        AccountKey {
            chain = upper(chain);
        }
    }

    private record AssetKey(String chain, String symbol) {

        AssetKey {
            chain = upper(chain);
            symbol = upper(symbol);
        }
    }

    private record ConfiguredToken(
            String chain,
            String network,
            String symbol) {

        ConfiguredToken {
            chain = upper(chain);
            network = network.toLowerCase(Locale.ROOT);
            symbol = upper(symbol);
        }
    }

    private record WalletKey(
            String chain,
            long userId,
            int biz,
            long child,
            String role) {

        static WalletKey of(ChainAddressRecord row) {
            return new WalletKey(upper(row.getChain()), row.getUserId(), row.getBiz(), row.getAddressIndex(), row.getWalletRole());
        }
    }

    private record Directory(
            Set<AccountKey> gasAccounts,
            Map<AccountKey, Set<UUID>> directCustomers,
            Map<AccountKey, Set<UUID>> relatedCustomers) {
    }

    private static final class Amounts {

        BigDecimal available = BigDecimal.ZERO, locked = BigDecimal.ZERO, total = BigDecimal.ZERO;

        Set<UUID> addresses = new HashSet<>();

        CustomerAssetBalance view(AssetKey key) {
            return new CustomerAssetBalance(key.chain(), key.symbol(), available, locked, total, addresses.size());
        }
    }

    private static final class MutableAggregate {

        final String symbol;

        BigDecimal available = BigDecimal.ZERO, locked = BigDecimal.ZERO, total = BigDecimal.ZERO, value = BigDecimal.ZERO;

        boolean priced;

        final List<String> chains = new ArrayList<>();

        MutableAggregate(String symbol) {
            this.symbol = symbol;
        }

        void add(AssetRow row) {
            available = available.add(row.availableBalance());
            locked = locked.add(row.lockedBalance());
            total = total.add(row.totalBalance());
            chains.add(row.chain());
            if (row.valueUsd() != null) {
                value = value.add(row.valueUsd());
                priced = true;
            }
        }

        SymbolAggregate view() {
            return new SymbolAggregate(symbol, available, locked, total, priced ? value : null, List.copyOf(chains));
        }
    }

    private static final class MutableChain {

        final String chain;

        BigDecimal value = BigDecimal.ZERO;

        boolean priced;

        final List<AssetRow> assets = new ArrayList<>();

        MutableChain(String chain) {
            this.chain = chain;
        }

        void add(AssetRow row) {
            assets.add(row);
            if (row.valueUsd() != null) {
                value = value.add(row.valueUsd());
                priced = true;
            }
        }

        ChainAggregate view() {
            return new ChainAggregate(chain, priced ? value : null, List.copyOf(assets));
        }
    }

    public record CustomerAssetBalance(
            String chain,
            String assetSymbol,
            BigDecimal availableBalance,
            BigDecimal lockedBalance,
            BigDecimal totalBalance,
            long addressCount) {
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
            List<ReorgDeficit> reorgDeficits) {
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
            Instant priceObservedAt) {
    }

    public record SymbolAggregate(
            String assetSymbol,
            BigDecimal availableBalance,
            BigDecimal lockedBalance,
            BigDecimal totalBalance,
            BigDecimal valueUsd,
            List<String> chains) {
    }

    public record ChainAggregate(
            String chain,
            BigDecimal valueUsd,
            List<AssetRow> assets) {
    }

    public record ReorgDeficit(
            UUID id,
            UUID custodyAddressId,
            String chain,
            String assetSymbol,
            BigDecimal deficitAmount,
            BigDecimal recoveredAmount,
            BigDecimal outstandingAmount,
            Instant createdAt) {
    }

    public record SetPriceCommand(
            BigDecimal usdPrice,
            String source,
            Instant observedAt) {
    }
}
