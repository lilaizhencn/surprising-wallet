package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.chain.BlockchainAdapterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
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
        return chains.list(principal.tenantId()).stream()
                .map(this::toSupportedView)
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
        return chains.list(principal.tenantId()).stream()
                .filter(row -> row.chain().equals(chain))
                .map(this::toSupportedView)
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

    private ChainView toSupportedView(CustodyTenantChainRepository.ChainRecord row) {
        BlockchainAdapter adapter;
        try {
            adapter = requireAdapter(row.chain());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new ChainView(
                row.chain(), row.network(), row.family(), row.nativeSymbol(),
                row.assetSymbols(), row.status(), "ACTIVE".equals(row.status()),
                row.scanEnabled(), row.withdrawalEnabled(), row.transferEnabled(),
                adapter.capabilities(), row.openedAt(), row.closedAt());
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
            String status,
            boolean enabled,
            boolean scanEnabled,
            boolean withdrawalEnabled,
            boolean transferEnabled,
            Set<BlockchainAdapter.Capability> capabilities,
            java.time.Instant openedAt,
            java.time.Instant closedAt
    ) {
    }
}
