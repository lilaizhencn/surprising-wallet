package com.surprising.wallet.testutil;

import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyEventService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit construction of custody fixtures; application classes keep one injected constructor. */
public final class CustodyTestSupport {
    private CustodyTestSupport() { }

    public static com.surprising.wallet.service.CustodyAddressService addresses(JdbcTemplate jdbc,
            com.surprising.wallet.chain.BlockchainRuntimeService runtime,
            com.surprising.wallet.service.CustodyTenantChainService tenantChains) {
        return new com.surprising.wallet.service.CustodyAddressService(new CustodyTenantTableRepository(jdbc),
                new ChainAddressRepository(jdbc), runtime, tenantChains,
                new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper(),
                new Evm7702CollectionRepository(jdbc), new CustodyAddressRepository(jdbc),
                new CustodyGasAccountRepository(jdbc), new CustodyDerivationSubjectRepository(jdbc),
                new CustodyAuditRepository(jdbc), new LedgerBalanceRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyGasService gas(JdbcTemplate jdbc,
            com.surprising.wallet.service.CustodyAddressService addresses,
            com.surprising.wallet.chain.BlockchainRuntimeService runtime) {
        return new com.surprising.wallet.service.CustodyGasService(addresses, runtime,
                new CustodyGasAccountRepository(jdbc), new CustodyGasUsageRepository(jdbc),
                new CustodyDepositRepository(jdbc), new CustodyDerivationSubjectRepository(jdbc), gasAccounting(jdbc),
                new ChainProfileRepository(jdbc), new ChainAssetRepository(jdbc),
                new CustodyAddressRepository(jdbc), new ChainAddressRepository(jdbc),
                new LedgerBalanceRepository(jdbc), new CustodyAuditRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyGasService gas(JdbcTemplate jdbc) {
        return gas(jdbc, org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyAddressService.class),
                org.mockito.Mockito.mock(com.surprising.wallet.chain.BlockchainRuntimeService.class));
    }

    public static com.surprising.wallet.service.CustodyAssetDashboardService assets(JdbcTemplate jdbc) {
        return new com.surprising.wallet.service.CustodyAssetDashboardService(new CustodyAssetPriceRepository(jdbc),
                new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc), new ChainAddressRepository(jdbc),
                new ChainProfileRepository(jdbc), new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                new CustodyTenantChainTableRepository(jdbc), new LedgerBalanceRepository(jdbc),
                new CustodyReorgDeficitRepository(jdbc), new CustodyAuditRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyTenantService tenants(JdbcTemplate jdbc) {
        return new com.surprising.wallet.service.CustodyTenantService(new CustodyTenantTableRepository(jdbc),
                new com.surprising.wallet.service.CustodyPasswordService(),
                new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper(),
                new CustodyTenantUserRepository(jdbc), new CustodySessionRepository(jdbc), new CustodyAuditRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyTenantQueryService tenantQueries(JdbcTemplate jdbc) {
        var runtime = org.mockito.Mockito.mock(com.surprising.wallet.chain.BlockchainRuntimeService.class);
        var chains = org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyTenantChainService.class);
        var addresses = addresses(jdbc, runtime, chains);
        return new com.surprising.wallet.service.CustodyTenantQueryService(new CustodyTenantTableRepository(jdbc),
                new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc), new CustodyTenantUserRepository(jdbc),
                new CustodySessionRepository(jdbc), new CustodyApiKeyRepository(jdbc), new CustodyIpRuleRepository(jdbc),
                new CustodyAuditRepository(jdbc), new CustodyWebhookEndpointRepository(jdbc), new CustodyWebhookDeliveryRepository(jdbc),
                new CustodyWithdrawalRepository(jdbc), new CustodyDepositRepository(jdbc), new CustodyTenantChainTableRepository(jdbc),
                new ChainProfileRepository(jdbc), addresses, gas(jdbc, addresses, runtime), assets(jdbc), webhooks(jdbc),
                new com.surprising.wallet.service.CustodyTransactionQueryService(new CustodyDepositRepository(jdbc),
                        new CustodyWithdrawalRepository(jdbc), new CustodyAddressRepository(jdbc), new WithdrawalOrderRepository(jdbc)));
    }

    public static com.surprising.wallet.model.CustodyPrincipal platformAdmin() {
        return new com.surprising.wallet.model.CustodyPrincipal(com.surprising.wallet.model.CustodyPrincipal.ActorType.PLATFORM_USER,
                java.util.UUID.randomUUID(), null, null, "PLATFORM_ADMIN", java.util.Set.of("*"));
    }

    public static com.surprising.wallet.model.CustodyPrincipal tenantAdmin(java.util.UUID tenantId) {
        return new com.surprising.wallet.model.CustodyPrincipal(com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER,
                java.util.UUID.randomUUID(), tenantId, "fixture", "TENANT_ADMIN", java.util.Set.of("*"));
    }

    public static com.surprising.wallet.service.CustodyWebhookService webhooks(JdbcTemplate jdbc) {
        var properties = new com.surprising.wallet.model.CustodySecurityProperties();
        properties.setSecretMasterKey("22".repeat(32));
        return webhooks(jdbc, new com.surprising.wallet.service.CustodyCryptoService(properties),
                new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper(),
                org.mockito.Mockito.mock(com.surprising.wallet.gateway.CustodyWebhookHttpClient.class));
    }

    public static com.surprising.wallet.service.CustodyWebhookService webhooks(JdbcTemplate jdbc,
            com.surprising.wallet.service.CustodyCryptoService crypto, tools.jackson.databind.ObjectMapper mapper,
            com.surprising.wallet.gateway.CustodyWebhookHttpClient http) {
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var service = new com.surprising.wallet.service.CustodyWebhookService(
                new CustodyWebhookEndpointRepository(jdbc), new CustodyWebhookDeliveryRepository(jdbc),
                new CustodyWebhookDeliveryAttemptRepository(jdbc), new CustodyEventRepository(jdbc),
                new CustodyAuditRepository(jdbc), crypto, mapper, http, manager);
        var transactions = new org.springframework.transaction.interceptor.TransactionInterceptor();
        transactions.setTransactionManager(manager);
        transactions.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        transactions.afterPropertiesSet();
        var proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(transactions);
        return (com.surprising.wallet.service.CustodyWebhookService) proxy.getProxy();
    }

    public static CustodyEventService events(JdbcTemplate jdbc) {
        return new CustodyEventService(new CustodyEventRepository(jdbc),
                new CustodyWebhookEndpointRepository(jdbc), new CustodyWebhookDeliveryRepository(jdbc));
    }

    public static WithdrawalSettlementService settlements(JdbcTemplate jdbc) {
        return new WithdrawalSettlementService(new WithdrawalOrderRepository(jdbc), new LedgerBalanceRepository(jdbc));
    }
    public static com.surprising.wallet.service.CustodyGasAccountingService gasAccounting(JdbcTemplate jdbc) {
        return new com.surprising.wallet.service.CustodyGasAccountingService(new CustodyGasUsageRepository(jdbc),
                new CustodyGasAccountRepository(jdbc), new CustodyAddressRepository(jdbc), new ChainAddressRepository(jdbc),
                new LedgerBalanceRepository(jdbc), new CustodyLedgerEntryRepository(jdbc), new CustodyWithdrawalRepository(jdbc),
                new EvmCollectionBatchRepository(jdbc), new EvmWithdrawalBatchRepository(jdbc),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public static com.surprising.wallet.service.CustodyGasReconciliationService gasReconciliation(JdbcTemplate jdbc) {
        return new com.surprising.wallet.service.CustodyGasReconciliationService(new CustodyGasUsageRepository(jdbc),
                gasAccounting(jdbc), new ChainAssetRepository(jdbc), new WithdrawalOrderRepository(jdbc),
                new EvmTransactionRepository(jdbc), new SolanaTransactionRepository(jdbc), new AptosTransactionRepository(jdbc),
                new SuiTransactionRepository(jdbc), new TonTransactionRepository(jdbc), new XrpTransactionRepository(jdbc),
                new MoneroTransactionRepository(jdbc), new TronTransactionRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyWithdrawalReconciliationService withdrawalReconciliation(
            JdbcTemplate jdbc, tools.jackson.databind.ObjectMapper mapper) {
        return new com.surprising.wallet.service.CustodyWithdrawalReconciliationService(new CustodyWithdrawalRepository(jdbc),
                new WithdrawalOrderRepository(jdbc), new CustodyAddressRepository(jdbc), new CustodyLedgerEntryRepository(jdbc),
                gasReconciliation(jdbc), events(jdbc), mapper,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

}
