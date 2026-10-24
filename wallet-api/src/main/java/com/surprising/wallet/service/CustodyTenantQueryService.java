package com.surprising.wallet.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.custody.*;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Platform tenant pages and detail snapshots, plus the tenant's setup checklist.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustodyTenantQueryService {

    private final CustodyTenantTableRepository tenants;

    private final CustodyAddressRepository addressRows;

    private final CustodyGasAccountRepository gasAccounts;

    private final CustodyTenantUserRepository users;

    private final CustodySessionRepository sessions;

    private final CustodyApiKeyRepository apiKeys;

    private final CustodyIpRuleRepository ipRules;

    private final CustodyAuditRepository audits;

    private final CustodyWebhookEndpointRepository endpoints;

    private final CustodyWebhookDeliveryRepository deliveries;

    private final CustodyWithdrawalRepository withdrawals;

    private final CustodyDepositRepository deposits;

    private final CustodyTenantChainTableRepository tenantChains;

    private final ChainProfileRepository profiles;

    private final CustodyAddressService addresses;

    private final CustodyGasService gas;

    private final CustodyAssetDashboardService assets;

    private final CustodyWebhookService webhooks;

    private final CustodyTransactionQueryService history;

    public TenantPage list(CustodyPrincipal actor, String search, String status, int limit, int offset) {
        requirePlatformAdmin(actor);
        String value = search == null ? "" : search.trim();
        if (value.length() > 160) {
            throw new IllegalArgumentException("search must not exceed 160 characters");
        }
        String state = status == null || status.isBlank() ? "" : CustodyTenantService.requireStatus(status);
        int pageSize = Math.min(Math.max(limit, 1), 200);
        int pageOffset = Math.max(offset, 0);
        var rows = tenants.query(value, state, pageSize, pageOffset);
        var ids = rows.stream().map(TenantRecord::id).toList();
        var addressCounts = addressRows.countByTenants(ids);
        var gasCounts = gasAccounts.countByTenants(ids);
        var userCounts = users.countByTenants(ids);
        var items = rows.stream().map(row -> new TenantListItem(row.id(), row.slug(), row.name(), row.status(), row.derivationNamespace(), row.ipAllowlistEnabled(), row.displayCurrency(), row.createdAt(), row.updatedAt(), addressCounts.getOrDefault(row.id(), 0L), gasCounts.getOrDefault(row.id(), 0L), userCounts.getOrDefault(row.id(), 0L))).toList();
        return new TenantPage(items, tenants.count(value, state), pageSize, pageOffset);
    }

    public TenantDetail detail(CustodyPrincipal actor, UUID tenantId) {
        requirePlatformAdmin(actor);
        TenantRecord tenant = requireTenant(tenantId);
        var accounts = gas.listForTenant(tenantId);
        var administrators = users.listByTenant(tenantId);
        var hooks = endpoints.list(tenantId);
        var gasIds = accounts.stream().map(GasAccountRecord::custodyAddressId).toList();
        long customerAddressCount = addressRows.count(new CustodyAddressRepository.Query(tenantId, "", "", "", "", gasIds));
        long activeApiKeys = apiKeys.countActive(tenantId);
        var statistics = new TenantStatistics(customerAddressCount, accounts.stream().filter(row -> "ACTIVE".equals(row.status())).count(), administrators.size(), withdrawals.countByTenant(tenantId, "", "", ""), deposits.countCustomerDeposits(tenantId, gasIds), activeApiKeys, hooks.stream().filter(row -> "ACTIVE".equals(row.status())).count(), hooks.size(), deliveries.countFailed(tenantId), sessions.countActive(tenantId));
        return new TenantDetail(tenant, statistics, onboarding(tenant, accounts, hooks, customerAddressCount, activeApiKeys), administrators, assets.observedBalancesForTenant(tenantId), addresses.listForTenant(tenantId, "", "", "", "", 20, 0), accounts, apiKeys.listByTenant(tenantId), ipRules.list(tenantId), hooks, webhooks.deliveriesForTenant(tenantId, null, null, 20, 0), history.deposits(tenantId, "", "", "", "", 20, 0), history.withdrawals(tenantId, "", "", "", "", 20, 0), audits.list(tenantId, 50, 0));
    }

    public OnboardingStatus onboarding(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope("assets:read")) {
            throw new CustodyForbiddenException("assets:read scope required");
        }
        UUID tenantId = principal.tenantId();
        TenantRecord tenant = requireTenant(tenantId);
        var accounts = gas.listForTenant(tenantId);
        var gasIds = accounts.stream().map(GasAccountRecord::custodyAddressId).toList();
        long customers = addressRows.count(new CustodyAddressRepository.Query(tenantId, "", "", "", "", gasIds));
        return onboarding(tenant, accounts, endpoints.list(tenantId), customers, apiKeys.countActive(tenantId));
    }

    private OnboardingStatus onboarding(TenantRecord tenant, List<GasAccountRecord> accounts, List<WebhookEndpointView> hooks, long customerAddresses, long activeApiKeys) {
        var enabled = profiles.listProfiles(true).stream().map(row -> row.getChain().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        boolean openChain = tenantChains.listActiveChains(tenant.id()).stream().anyMatch(chain -> enabled.contains(chain.toUpperCase(Locale.ROOT)));
        boolean apiKey = activeApiKeys > 0;
        boolean webhook = hooks.stream().anyMatch(row -> "ACTIVE".equals(row.status()) && row.verifiedAt() != null);
        boolean allowlist = tenant.ipAllowlistEnabled() && !ipRules.listActive(tenant.id()).isEmpty();
        boolean customer = customerAddresses > 0;
        boolean gasConfigured = accounts.stream().anyMatch(row -> "ACTIVE".equals(row.status()));
        boolean funded = accounts.stream().anyMatch(row -> "ACTIVE".equals(row.status()) && row.availableBalance().signum() > 0);
        long completed = List.of(openChain, apiKey, webhook, allowlist, customer, gasConfigured, funded).stream().filter(Boolean::booleanValue).count();
        return new OnboardingStatus(tenant.id(), apiKey, openChain, webhook, allowlist, customer, gasConfigured, funded, completed, 7, completed == 7);
    }

    private TenantRecord requireTenant(UUID tenant) {
        return tenants.findById(tenant).orElseThrow(() -> new IllegalArgumentException("tenant not found"));
    }

    private static void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin()) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }

    public record TenantListItem(
            UUID id,
            String slug,
            String name,
            String status,
            @JsonProperty("derivation_namespace") int derivationNamespace,
            @JsonProperty("ip_allowlist_enabled") boolean ipAllowlistEnabled,
            @JsonProperty("display_currency") String displayCurrency,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            long addressCount,
            long gasAccountCount,
            long userCount) {
    }

    public record TenantPage(
            List<TenantListItem> items,
            long total,
            int limit,
            int offset) {
    }

    public record TenantStatistics(
            long addressCount,
            long gasAccountCount,
            long userCount,
            long withdrawalCount,
            long depositCount,
            long activeApiKeyCount,
            long activeWebhookCount,
            long webhookEndpointCount,
            long failedWebhookDeliveryCount,
            long activeSessionCount) {
    }

    public record OnboardingStatus(
            UUID tenantId,
            boolean apiKeyConfigured,
            boolean chainOpened,
            boolean webhookConfigured,
            boolean ipAllowlistConfigured,
            boolean addressCreated,
            boolean gasAccountConfigured,
            boolean gasAccountFunded,
            long completedSteps,
            long totalSteps,
            boolean ready) {
    }

    public record TenantDetail(
            TenantRecord tenant,
            TenantStatistics statistics,
            OnboardingStatus onboarding,
            List<TenantUserView> administrators,
            List<CustodyAssetDashboardService.CustomerAssetBalance> assets,
            List<CustodyAddressRecord> recentAddresses,
            List<GasAccountRecord> gasAccounts,
            List<ApiKeyView> apiKeys,
            List<IpRuleView> ipRules,
            List<WebhookEndpointView> webhooks,
            List<CustodyWebhookService.WebhookDeliveryView> webhookDeliveries,
            List<CustodyTransactionQueryService.DepositView> recentDeposits,
            List<CustodyTransactionQueryService.WithdrawalView> recentWithdrawals,
            List<Map<String, Object>> recentAudit) {
    }
}
