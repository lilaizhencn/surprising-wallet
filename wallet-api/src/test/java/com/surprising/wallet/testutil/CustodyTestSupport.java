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
        return new CustodyRepository(jdbc, new CustodySecurityRepository(
                new CustodyTenantUserRepository(jdbc), new CustodyTenantTableRepository(jdbc),
                new CustodySessionRepository(jdbc), new CustodyApiKeyRepository(jdbc),
                new CustodyApiNonceRepository(jdbc), new CustodyIpRuleRepository(jdbc)),
                new CustodyTenantTableRepository(jdbc), new CustodyTenantUserRepository(jdbc), events(jdbc));
    }

    public static CustodyEventService events(JdbcTemplate jdbc) {
        return new CustodyEventService(new CustodyEventRepository(jdbc),
                new CustodyWebhookEndpointRepository(jdbc), new CustodyWebhookDeliveryRepository(jdbc));
    }

    public static WithdrawalSettlementService settlements(JdbcTemplate jdbc) {
        return new WithdrawalSettlementService(new WithdrawalOrderRepository(jdbc), new LedgerBalanceRepository(jdbc));
    }
}
