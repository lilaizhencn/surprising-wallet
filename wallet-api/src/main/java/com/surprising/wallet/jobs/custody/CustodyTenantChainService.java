package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.chain.BlockchainAdapterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CustodyTenantChainService {
    private final CustodyTenantChainRepository chains;
    private final CustodyRepository custody;
    private final BlockchainAdapterRegistry adapters;

    public CustodyTenantChainService(CustodyTenantChainRepository chains,
                                     CustodyRepository custody,
                                     BlockchainAdapterRegistry adapters) {
        this.chains = chains;
        this.custody = custody;
        this.adapters = adapters;
    }

    public List<ChainView> list(CustodyPrincipal principal) {
        requireScope(principal, "chains:read");
        Map<String, CustodyRepository.GasAccountRecord> addresses = custody
                .listGasAccounts(principal.tenantId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CustodyRepository.GasAccountRecord::chain, row -> row));
        return chains.list(principal.tenantId()).stream()
                .map(row -> toSupportedView(row, addresses.get(row.chain())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional(rollbackFor = Throwable.class)
    public ChainView setEnabled(CustodyPrincipal principal, String chainValue,
                                boolean enabled, String sourceIp) {
        requireTenantAdmin(principal);
        String chain = normalizeChain(chainValue);
        if (!chains.platformChainEnabled(chain)) {
            throw new IllegalArgumentException("chain is not enabled by the platform");
        }
        requireAdapter(chain);
        String status = enabled ? "ACTIVE" : "CLOSED";
        chains.setStatus(principal.tenantId(), chain, status, principal.actorId());
        custody.audit(principal.tenantId(), principal.actorType().name(),
                principal.actorId().toString(),
                enabled ? "TENANT_CHAIN.OPEN" : "TENANT_CHAIN.CLOSE",
                "TENANT_CHAIN", chain, sourceIp,
                "{\"chain\":\"" + chain + "\",\"status\":\"" + status + "\"}");
        Map<String, CustodyRepository.GasAccountRecord> addresses = custody
                .listGasAccounts(principal.tenantId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        CustodyRepository.GasAccountRecord::chain, row -> row));
        return chains.list(principal.tenantId()).stream()
                .filter(row -> row.chain().equals(chain))
                .map(row -> toSupportedView(row, addresses.get(row.chain())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("tenant chain status was not persisted"));
    }

    public void requireActive(UUID tenantId, String chainValue) {
        String chain = normalizeChain(chainValue);
        if (!chains.active(tenantId, chain)) {
            throw new CustodyForbiddenException(
                    "chain " + chain + " is not open for this tenant");
        }
    }

    public void requireWithdrawalEnabled(UUID tenantId, String chainValue, String symbolValue) {
        String chain = normalizeChain(chainValue);
        String symbol = normalizeSymbol(symbolValue);
        if (!chains.withdrawalEnabled(tenantId, chain, symbol)) {
            throw new CustodyForbiddenException(
                    symbol + " withdrawals are not enabled on " + chain + " for this tenant");
        }
    }

    private ChainView toSupportedView(CustodyTenantChainRepository.ChainRecord row,
                                      CustodyRepository.GasAccountRecord address) {
        BlockchainAdapter adapter;
        try {
            adapter = requireAdapter(row.chain());
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<TokenView> tokens = row.tokens().stream()
                .map(CustodyTenantChainService::tokenView)
                .toList();
        List<String> enabledAssets = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(row.nativeSymbol()),
                        tokens.stream().filter(TokenView::platformEnabled).map(TokenView::symbol))
                .distinct().toList();
        return new ChainView(
                row.chain(), row.network(), row.family(), row.nativeSymbol(),
                enabledAssets, tokens, row.status(), "ACTIVE".equals(row.status()),
                row.scanEnabled(), row.withdrawalEnabled(), row.transferEnabled(),
                adapter.capabilities(),
                address == null ? null : address.custodyAddressId(),
                address == null ? null : address.address(),
                address == null ? null : address.memo(),
                row.openedAt(), row.closedAt());
    }

    private static TokenView tokenView(CustodyTenantChainRepository.TokenRecord row) {
        return new TokenView(
                row.symbol(), row.standard(), row.contractAddress(), row.decimals(),
                row.platformEnabled());
    }

    private BlockchainAdapter requireAdapter(String chain) {
        try {
            return adapters.require(ChainType.valueOf(chain));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("chain has no executable wallet adapter: " + chain, e);
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
            List<String> assetSymbols,
            List<TokenView> tokens,
            String status,
            boolean enabled,
            boolean scanEnabled,
            boolean withdrawalEnabled,
            boolean transferEnabled,
            Set<BlockchainAdapter.Capability> capabilities,
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
            boolean platformEnabled
    ) {
    }
}
