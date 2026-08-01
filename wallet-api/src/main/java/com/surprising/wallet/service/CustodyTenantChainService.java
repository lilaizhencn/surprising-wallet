package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.chain.BlockchainAdapterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyRepository;
import com.surprising.wallet.repository.CustodyTenantChainRepository;

/**
 * 租户链配置服务，管理租户启用的链和资产列表。
 *
 * <p>控制租户能访问哪些链（如 ETH、BTC、TRON），以及每条链上的资产类型。
 * 链启用状态由平台管理员配置，租户只能操作已启用的链。
 */
@Service
public class CustodyTenantChainService {
    /** 租户链配置数据访问 */
    private final CustodyTenantChainRepository chains;
    /**
     * 保存 {@code custody}，用于承载当前对象的运行配置或业务数据。
     */
    private final CustodyRepository custody;
    /**
     * 保存 {@code adapters}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final BlockchainAdapterRegistry adapters;
    /**
     * 构造 {@code CustodyTenantChainService}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyTenantChainService(CustodyTenantChainRepository chains,
                                     CustodyRepository custody,
                                     BlockchainAdapterRegistry adapters) {
        this.chains = chains;
        this.custody = custody;
        this.adapters = adapters;
    }
    /**
     * 获取或查询 {@code list} 对应的数据，供调用方读取当前状态。
     */
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

    /**
     * 设置或更新 {@code setEnabled} 对应的状态，并保持相关业务字段一致。
     */
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
    /**
     * 校验 {@code requireActive} 对应的前置条件，不满足时抛出明确异常。
     */
    public void requireActive(UUID tenantId, String chainValue) {
        String chain = normalizeChain(chainValue);
        if (!chains.active(tenantId, chain)) {
            throw new CustodyForbiddenException(
                    "chain " + chain + " is not open for this tenant");
        }
    }
    /**
     * 校验 {@code requireWithdrawalEnabled} 对应的前置条件，不满足时抛出明确异常。
     */
    public void requireWithdrawalEnabled(UUID tenantId, String chainValue, String symbolValue) {
        String chain = normalizeChain(chainValue);
        String symbol = normalizeSymbol(symbolValue);
        if (!chains.withdrawalEnabled(tenantId, chain, symbol)) {
            throw new CustodyForbiddenException(
                    symbol + " withdrawals are not enabled on " + chain + " for this tenant");
        }
    }

    /**
     * 编码 {@code toSupportedView} 对应的数据，生成链上或接口所需的表示。
     */
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
                row.eip7702Enabled(),
                enabledAssets, tokens, row.status(), "ACTIVE".equals(row.status()),
                row.scanEnabled(), row.withdrawalEnabled(), row.transferEnabled(),
                adapter.capabilities(),
                address == null ? null : address.custodyAddressId(),
                address == null ? null : address.address(),
                address == null ? null : address.memo(),
                row.openedAt(), row.closedAt());
    }
    /**
     * 编码 {@code tokenView} 对应的数据，生成链上或接口所需的表示。
     */
    private static TokenView tokenView(CustodyTenantChainRepository.TokenRecord row) {
        return new TokenView(
                row.symbol(), row.standard(), row.contractAddress(), row.decimals(),
                row.platformEnabled());
    }
    /**
     * 校验 {@code requireAdapter} 对应的前置条件，不满足时抛出明确异常。
     */
    private BlockchainAdapter requireAdapter(String chain) {
        try {
            return adapters.require(ChainType.valueOf(chain));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("chain has no executable wallet adapter: " + chain, e);
        }
    }
    /**
     * 转换或计算 {@code normalizeChain} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeChain(String value) {
        String chain = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!chain.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid chain is required");
        }
        return chain;
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
     * 校验 {@code requireTenantAdmin} 对应的前置条件，不满足时抛出明确异常。
     */
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
