package com.surprising.wallet.testutil;

import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyEventService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit construction of custody fixtures; application classes keep one injected constructor. */
public final class CustodyTestSupport {
    private CustodyTestSupport() { }

    public static com.surprising.wallet.service.CustodyAddressService addresses(JdbcTemplate jdbc,
            CustodyRepository custody, com.surprising.wallet.chain.BlockchainRuntimeService runtime,
            com.surprising.wallet.service.CustodyTenantChainService tenantChains) {
        return new com.surprising.wallet.service.CustodyAddressService(custody,
                new ChainAddressRepository(jdbc), runtime, tenantChains,
                new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper(),
                new Evm7702CollectionRepository(jdbc), new CustodyAddressRepository(jdbc),
                new CustodyGasAccountRepository(jdbc), new CustodyDerivationSubjectRepository(jdbc),
                new CustodyAuditRepository(jdbc), new LedgerBalanceRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyGasService gas(JdbcTemplate jdbc,
            CustodyRepository custody, com.surprising.wallet.service.CustodyAddressService addresses,
            com.surprising.wallet.chain.BlockchainRuntimeService runtime) {
        return new com.surprising.wallet.service.CustodyGasService(custody, addresses, runtime,
                new CustodyGasAccountRepository(jdbc), new CustodyGasUsageRepository(jdbc),
                new CustodyDepositRepository(jdbc), new CustodyDerivationSubjectRepository(jdbc));
    }

    public static CustodyRepository repository(JdbcTemplate jdbc) {
        return new CustodyRepository(jdbc, new CustodyTenantTableRepository(jdbc),
                new CustodyTenantUserRepository(jdbc), events(jdbc), new CustodyApiKeyRepository(jdbc),
                new CustodySessionRepository(jdbc), new CustodyIpRuleRepository(jdbc));
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
}
