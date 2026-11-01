package com.surprising.wallet.testutil;

import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyEventService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit construction of custody fixtures; application classes keep one injected constructor. */
public final class CustodyTestSupport {
    public static com.surprising.wallet.service.CustodyPoolFundingService poolFunding(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new com.surprising.wallet.service.CustodyPoolFundingService(
                new com.surprising.wallet.repository.LedgerBalanceRepository(jdbc),
                new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.CustodyLedgerEntryRepository(jdbc));
    }

    /** Minimal batch identity used only by repository/query fixtures, never by production claim recovery. */
    public static String evmClaimSnapshot(java.util.UUID tenant, java.util.UUID batch, String chain, String network, String hash) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "id", batch, "tenantId", tenant, "chain", chain, "network", network, "batchHash", hash));
    }

    private CustodyTestSupport() { }

    public static com.surprising.wallet.service.CustodyTenantChainService tenantChains(JdbcTemplate jdbc,
            com.surprising.wallet.chain.BlockchainRuntimeService runtime) {
        return new com.surprising.wallet.service.CustodyTenantChainService(new CustodyTenantChainTableRepository(jdbc),
                new ChainProfileRepository(jdbc), new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                new Evm7702ConfigRepository(jdbc), new CustodyGasAccountRepository(jdbc),
                new CustodyAddressRepository(jdbc), new CustodyAuditRepository(jdbc), runtime);
    }

    public static com.surprising.wallet.service.CustodyAddressService addresses(JdbcTemplate jdbc,
            com.surprising.wallet.service.CustodyTenantChainService tenantChains) {
        var mpc = new MpcSigningTestSupport(jdbc);
        return new com.surprising.wallet.service.CustodyAddressService(new CustodyTenantTableRepository(jdbc),
                new ChainAddressRepository(jdbc), new ChainProfileRepository(jdbc), tenantChains,
                new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper(),
                new Evm7702AccountRepository(jdbc), new CustodyAddressRepository(jdbc),
                new CustodyGasAccountRepository(jdbc), new CustodyDerivationSubjectRepository(jdbc),
                new CustodyAuditRepository(jdbc), new LedgerBalanceRepository(jdbc), mpc.signing, mpc.nodes, cardanoAnchors(jdbc), moneroAnchors(jdbc), mpc.manager);
    }

    /** Offline address policy fixture. Requested Cardano native acceptance supplies the actual node-backed service. */
    public static com.surprising.wallet.service.CardanoScanAnchorService cardanoAnchors(JdbcTemplate jdbc) {
        var service = org.mockito.Mockito.spy(new com.surprising.wallet.service.CardanoScanAnchorService(
                org.mockito.Mockito.mock(com.surprising.wallet.chain.cardano.CardanoRpcClient.class),
                new ChainScanHeightRepository(jdbc), new ChainScanBlockRepository(jdbc)));
        org.mockito.Mockito.doAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var profile = call.getArgument(0, com.surprising.wallet.common.chain.AccountChainProfile.class);
            return new com.surprising.wallet.service.CardanoScanAnchorService.Anchor(profile.getNetwork(), "11".repeat(32),
                    new com.surprising.wallet.chain.model.ChainScanPoint(100, 200, "22".repeat(32)), "00".repeat(32));
        }).when(service).prepare(org.mockito.ArgumentMatchers.any());
        return service;
    }

    public static com.surprising.wallet.service.MoneroScanAnchorService moneroAnchors(JdbcTemplate jdbc) {
        return new com.surprising.wallet.service.MoneroScanAnchorService(new com.surprising.wallet.chain.monero.MoneroDaemonClient(
                new com.surprising.wallet.service.ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2),
                new tools.jackson.databind.ObjectMapper()),new ChainScanHeightRepository(jdbc),new ChainScanBlockRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyGasService gas(JdbcTemplate jdbc,
            com.surprising.wallet.service.CustodyAddressService addresses) {
        return new com.surprising.wallet.service.CustodyGasService(addresses,
                new CustodyGasAccountRepository(jdbc), new CustodyGasUsageRepository(jdbc),
                new CustodyDepositRepository(jdbc), gasAccounting(jdbc),
                new ChainProfileRepository(jdbc), new ChainAssetRepository(jdbc),
                new CustodyAddressRepository(jdbc), new ChainAddressRepository(jdbc),
                new LedgerBalanceRepository(jdbc), new CustodyAuditRepository(jdbc),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public static com.surprising.wallet.service.CustodyGasService gas(JdbcTemplate jdbc) {
        return gas(jdbc, org.mockito.Mockito.mock(com.surprising.wallet.service.CustodyAddressService.class));
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
        var addresses = addresses(jdbc, chains);
        return new com.surprising.wallet.service.CustodyTenantQueryService(new CustodyTenantTableRepository(jdbc),
                new CustodyAddressRepository(jdbc), new CustodyGasAccountRepository(jdbc), new CustodyTenantUserRepository(jdbc),
                new CustodySessionRepository(jdbc), new CustodyApiKeyRepository(jdbc), new CustodyIpRuleRepository(jdbc),
                new CustodyAuditRepository(jdbc), new CustodyWebhookEndpointRepository(jdbc), new CustodyWebhookDeliveryRepository(jdbc),
                new CustodyWithdrawalRepository(jdbc), new CustodyDepositRepository(jdbc), new CustodyTenantChainTableRepository(jdbc),
                new ChainProfileRepository(jdbc), addresses, gas(jdbc, addresses), assets(jdbc), webhooks(jdbc),
                new com.surprising.wallet.service.CustodyTransactionQueryService(new CustodyDepositRepository(jdbc),
                        new CustodyWithdrawalRepository(jdbc), new CustodyAddressRepository(jdbc), new WithdrawalOrderRepository(jdbc),
                        new com.surprising.wallet.repository.CustodyGasUsageRepository(jdbc)));
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
                new EvmCollectionBatchRepository(jdbc), new EvmWithdrawalBatchRepository(jdbc), new WalletMpcSignRequestRepository(jdbc),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public static com.surprising.wallet.service.CustodyGasReconciliationService gasReconciliation(JdbcTemplate jdbc) {
        return new com.surprising.wallet.service.CustodyGasReconciliationService(new CustodyGasUsageRepository(jdbc),
                gasAccounting(jdbc), new ChainAssetRepository(jdbc), new WithdrawalOrderRepository(jdbc),
                new EvmTransactionRepository(jdbc), new SolanaTransactionRepository(jdbc), new AptosTransactionRepository(jdbc),
                new SuiTransactionRepository(jdbc), new TonTransactionRepository(jdbc), new NearTransactionRepository(jdbc), new PolkadotTransactionRepository(jdbc), new CardanoTransactionRepository(jdbc), new XrpTransactionRepository(jdbc),
                new MoneroTransactionRepository(jdbc), new TronTransactionRepository(jdbc));
    }

    public static com.surprising.wallet.service.CustodyWithdrawalReconciliationService withdrawalReconciliation(
            JdbcTemplate jdbc, tools.jackson.databind.ObjectMapper mapper) {
        return new com.surprising.wallet.service.CustodyWithdrawalReconciliationService(new CustodyWithdrawalRepository(jdbc),
                new WithdrawalOrderRepository(jdbc), new CustodyAddressRepository(jdbc), new CustodyLedgerEntryRepository(jdbc),
                gasReconciliation(jdbc), events(jdbc), mapper,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }


    public static com.surprising.wallet.coordinator.Evm7702CollectionCoordinator evmCollections(JdbcTemplate jdbc) {
        var security=new com.surprising.wallet.model.CustodySecurityProperties();security.setSecretMasterKey("11".repeat(32));
        return evmCollections(jdbc,org.mockito.Mockito.mock(com.surprising.wallet.service.Evm7702SigningService.class),new com.surprising.wallet.service.CustodyCryptoService(security));
    }
    public static com.surprising.wallet.coordinator.Evm7702CollectionCoordinator evmCollections(JdbcTemplate jdbc,
            com.surprising.wallet.service.Evm7702SigningService mpc,com.surprising.wallet.service.CustodyCryptoService crypto) {
        return new com.surprising.wallet.coordinator.Evm7702CollectionCoordinator(
                new Evm7702AccountRepository(jdbc),
                new ChainAddressRepository(jdbc),
                new ChainProfileRepository(jdbc),
                new EvmCollectionBatchRepository(jdbc),
                new EvmCollectionBatchItemRepository(jdbc),
                new EvmCollectionBatchAttemptRepository(jdbc),
                new CollectionRecordRepository(jdbc),
                new CustodyAddressRepository(jdbc),
                new CustodyGasAccountRepository(jdbc),
                new ChainAssetRepository(jdbc),
                new TokenConfigRepository(jdbc),
                gasAccounting(jdbc),
                mpc, crypto, new EvmWithdrawalBatchRepository(jdbc),
                new tools.jackson.databind.ObjectMapper(),
                new EvmNonceRepository(jdbc),
                new com.surprising.wallet.service.Evm7702ConfigurationService(new Evm7702ConfigRepository(jdbc), new ChainAddressRepository(jdbc)),
                new Evm7702ConfigRepository(jdbc));
    }
  public static com.surprising.wallet.service.Evm7702CollectionWorkflowService evmCollectionWorkflow(JdbcTemplate jdbc,
          com.surprising.wallet.coordinator.Evm7702CollectionCoordinator coordinator,
          com.surprising.wallet.chain.evm.Evm7702RpcClient rpc,
          com.surprising.wallet.service.Evm7702SigningService mpc,
          com.surprising.wallet.service.CustodyCryptoService crypto,
          com.surprising.wallet.service.WalletRuntimeConfigService runtime) {
      return new com.surprising.wallet.service.Evm7702CollectionWorkflowService(
              new EvmCollectionBatchRepository(jdbc), new EvmCollectionBatchItemRepository(jdbc),
              new EvmCollectionBatchAttemptRepository(jdbc), new Evm7702ConfigRepository(jdbc),
              coordinator, new ChainProfileRepository(jdbc), new ChainAssetRepository(jdbc),
              rpc, mpc, crypto, runtime);
  }

    public static com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator evmWithdrawals(JdbcTemplate jdbc) {
        var security=new com.surprising.wallet.model.CustodySecurityProperties();security.setSecretMasterKey("11".repeat(32));
        return evmWithdrawals(jdbc,org.mockito.Mockito.mock(com.surprising.wallet.service.Evm7702SigningService.class),new com.surprising.wallet.service.CustodyCryptoService(security));
    }
    public static com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator evmWithdrawals(JdbcTemplate jdbc,
            com.surprising.wallet.service.Evm7702SigningService mpc,com.surprising.wallet.service.CustodyCryptoService crypto) {
        return new com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator(
                new Evm7702ConfigRepository(jdbc),
                new ChainProfileRepository(jdbc),
                new WithdrawalOrderRepository(jdbc),
                new CustodyWithdrawalRepository(jdbc),
                new ChainAssetRepository(jdbc),
                new TokenConfigRepository(jdbc),
                new ChainAddressRepository(jdbc),
                new CustodyGasAccountRepository(jdbc),
                new CustodyAddressRepository(jdbc),
                new EvmWithdrawalBatchRepository(jdbc),
                new EvmWithdrawalBatchItemRepository(jdbc),
                new EvmWithdrawalBatchAttemptRepository(jdbc),
                new Evm7702PayoutAccountRepository(jdbc),
                gasAccounting(jdbc),
                new CustodyGasUsageRepository(jdbc),
                new LedgerBalanceRepository(jdbc),
                mpc, crypto, new EvmCollectionBatchRepository(jdbc),
                new tools.jackson.databind.ObjectMapper(),
                new EvmNonceRepository(jdbc),
                settlements(jdbc),
                new com.surprising.wallet.service.Evm7702ConfigurationService(new Evm7702ConfigRepository(jdbc), new ChainAddressRepository(jdbc)));
    }
  public static com.surprising.wallet.service.Evm7702WithdrawalWorkflowService evmWithdrawalWorkflow(JdbcTemplate jdbc,
          com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator coordinator,
          com.surprising.wallet.chain.evm.Evm7702RpcClient rpc,
          com.surprising.wallet.service.Evm7702SigningService mpc,
          com.surprising.wallet.service.CustodyCryptoService crypto,
          com.surprising.wallet.service.WalletRuntimeConfigService runtime) {
      return new com.surprising.wallet.service.Evm7702WithdrawalWorkflowService(
              new EvmWithdrawalBatchRepository(jdbc), new EvmWithdrawalBatchItemRepository(jdbc),
              new EvmWithdrawalBatchAttemptRepository(jdbc), new Evm7702ConfigRepository(jdbc),
              coordinator, new ChainProfileRepository(jdbc), new ChainAssetRepository(jdbc),
              rpc, mpc, crypto, runtime);
  }
}
