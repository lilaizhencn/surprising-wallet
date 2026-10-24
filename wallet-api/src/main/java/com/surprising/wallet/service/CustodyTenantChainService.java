package com.surprising.wallet.service;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.Evm7702ConfigRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户链开关、资产权限和链列表；所有组合均使用对应单表仓储。 */
@Service
@RequiredArgsConstructor
public class CustodyTenantChainService {
    private final CustodyTenantChainTableRepository chains;
    private final ChainProfileRepository profiles;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokenConfigs;
    private final Evm7702ConfigRepository evmConfigs;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyAddressRepository addresses;
    private final CustodyAuditRepository audits;
    private final BlockchainRuntimeService runtime;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<ChainView> list(CustodyPrincipal principal) {
        requireScope(principal, "chains:read");
        return listForTenant(principal.tenantId());
    }

    @Transactional(rollbackFor = Throwable.class)
    public ChainView setEnabled(CustodyPrincipal principal, String chainValue,
                                boolean enabled, String sourceIp) {
        requireTenantAdmin(principal);
        String chain = normalizeChain(chainValue);
        if (profiles.findAccountByChain(chain).isEmpty()) {
            throw new IllegalArgumentException("chain is not enabled by the platform");
        }
        runtime.requireRuntime(chain);
        String status = enabled ? "ACTIVE" : "CLOSED";
        chains.setStatus(principal.tenantId(), chain, status, principal.actorId());
        audits.insert(UUID.randomUUID(), principal.tenantId(), principal.actorType().name(),
                principal.actorId().toString(),
                enabled ? "TENANT_CHAIN.OPEN" : "TENANT_CHAIN.CLOSE",
                "TENANT_CHAIN", chain, sourceIp,
                "{\"chain\":\"" + chain + "\",\"status\":\"" + status + "\"}");
        return listForTenant(principal.tenantId()).stream()
                .filter(row -> row.chain().equals(chain)).findFirst()
                .orElseThrow(() -> new IllegalStateException("tenant chain status was not persisted"));
    }

    public void requireActive(UUID tenantId, String chainValue) {
        String chain = normalizeChain(chainValue);
        if (profiles.findAccountByChain(chain).isEmpty() || !chains.active(tenantId, chain)) {
            throw new CustodyForbiddenException("chain " + chain + " is not open for this tenant");
        }
    }

    public void requireWithdrawalEnabled(UUID tenantId, String chainValue, String symbolValue) {
        String chain = normalizeChain(chainValue);
        String symbol = normalizeSymbol(symbolValue);
        AccountChainProfile profile = profiles.findAccountByChain(chain).orElse(null);
        if (profile != null && chains.active(tenantId, chain)
                && Boolean.TRUE.equals(profile.getWithdrawEnabled())
                && Boolean.TRUE.equals(profile.getTransferEnabled())) {
            ChainAsset asset = assets.findActiveDefinition(chain, symbol).orElse(null);
            if (asset != null && (Boolean.TRUE.equals(asset.getNativeAsset())
                    || tokenConfigs.findEnabled(chain, profile.getNetwork(), symbol).isPresent())) {
                return;
            }
        }
        throw new CustodyForbiddenException(
                symbol + " withdrawals are not enabled on " + chain + " for this tenant");
    }

    private List<ChainView> listForTenant(UUID tenant) {
        var statuses = chains.list(tenant).stream().collect(Collectors.toMap(
                row -> row.chain().toUpperCase(Locale.ROOT), row -> row));
        var gas = gasAddresses(tenant);
        Map<String, Map<String, ChainAsset>> assetsByChain = new HashMap<>();
        for (var asset : assets.listActiveDefinitions()) {
            if (!Boolean.TRUE.equals(asset.getNativeAsset())) {
                assetsByChain.computeIfAbsent(asset.getChain().toUpperCase(Locale.ROOT),
                        ignored -> new HashMap<>()).put(asset.getSymbol().toUpperCase(Locale.ROOT), asset);
            }
        }
        Map<ChainNetwork, List<TokenView>> tokens = new HashMap<>();
        for (var token : tokenConfigs.listAll()) {
            var asset = assetsByChain.getOrDefault(token.chain().toUpperCase(Locale.ROOT), Map.of())
                    .get(token.symbol().toUpperCase(Locale.ROOT));
            if (asset != null) {
                tokens.computeIfAbsent(new ChainNetwork(token.chain(), token.network()),
                        ignored -> new ArrayList<>()).add(new TokenView(asset.getSymbol(), token.standard(),
                        token.contractAddress(), token.decimals(), token.enabled(), token.minDeposit()));
            }
        }
        tokens.values().forEach(rows -> rows.sort(Comparator.comparing(TokenView::symbol)));
        Set<ChainNetwork> evm = new HashSet<>();
        for (var config : evmConfigs.listAll()) {
            if ("ACTIVE".equals(config.status())) evm.add(new ChainNetwork(config.chain(), config.network()));
        }
        return profiles.listProfiles(true).stream().map(profile -> {
            String chain = profile.getChain();
            ChainNetwork network = new ChainNetwork(chain, profile.getNetwork());
            return toView(profile, statuses.get(chain.toUpperCase(Locale.ROOT)), gas.get(chain),
                    tokens.getOrDefault(network, List.of()), evm.contains(network));
        }).toList();
    }

    // 只需归集地址元数据；避免依赖 GasService 形成 Gas → Address → TenantChain 的循环。
    private Map<String, CustodyAddressRecord> gasAddresses(UUID tenant) {
        var gas = gasAccounts.listByTenant(tenant);
        var rows = addresses.findByIds(tenant, gas.stream()
                .map(CustodyGasAccountRepository.Definition::custodyAddressId).toList()).stream()
                .collect(Collectors.toMap(CustodyAddressRecord::id, row -> row));
        Map<String, CustodyAddressRecord> result = new HashMap<>();
        for (var account : gas) {
            var address = rows.get(account.custodyAddressId());
            if (address == null) throw new IllegalStateException("gas custody address is missing");
            result.put(account.chain(), address);
        }
        return result;
    }

    private static ChainView toView(AccountChainProfile profile,
            CustodyTenantChainTableRepository.ChainRow row, CustodyAddressRecord address,
            List<TokenView> tokens, boolean evm) {
        tokens = List.copyOf(tokens);
        List<String> enabledAssets = java.util.stream.Stream.concat(
                java.util.stream.Stream.of(profile.getNativeSymbol()),
                tokens.stream().filter(TokenView::platformEnabled).map(TokenView::symbol)).distinct().toList();
        String status = row == null ? "CLOSED" : row.status();
        return new ChainView(profile.getChain(), profile.getNetwork(), profile.getFamily(), profile.getNativeSymbol(),
                evm, enabledAssets, tokens, status, "ACTIVE".equals(status),
                Boolean.TRUE.equals(profile.getScanEnabled()), Boolean.TRUE.equals(profile.getWithdrawEnabled()),
                Boolean.TRUE.equals(profile.getTransferEnabled()),
                address == null ? null : address.id(), address == null ? null : address.address(),
                address == null ? null : address.memo(), row == null ? null : row.openedAt(),
                row == null ? null : row.closedAt());
    }

    private record ChainNetwork(String chain, String network) {
        private ChainNetwork {
            chain = chain.toUpperCase(Locale.ROOT);
            network = network.toLowerCase(Locale.ROOT);
        }
    }

    private static String normalizeChain(String value) {
        String chain = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!chain.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid chain is required");
        }
        return chain;
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

    private static void requireTenantAdmin(CustodyPrincipal principal) {
        requireScope(principal, "chains:write");
        if (!"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
    }

    public record ChainView(
            String chain,
            String network,
            String family,
            String nativeSymbol,
            boolean eip7702Enabled,
            List<String> assetSymbols,
            List<TokenView> tokens,
            String status,
            boolean enabled,
            boolean scanEnabled,
            boolean withdrawalEnabled,
            boolean transferEnabled,
            UUID collectionAddressId,
            String collectionAddress,
            String memo,
            java.time.Instant openedAt,
            java.time.Instant closedAt
    ) {
    }

    public record TokenView(
            String symbol,
            String standard,
            String contractAddress,
            int decimals,
            boolean platformEnabled,
            BigDecimal minDeposit
    ) {
    }
}
