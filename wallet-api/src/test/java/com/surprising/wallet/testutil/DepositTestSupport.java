package com.surprising.wallet.testutil;

import com.surprising.wallet.config.custody.CustodyJacksonConfiguration;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyDepositProjectionService;
import com.surprising.wallet.service.CustodyEventService;
import com.surprising.wallet.service.DepositService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicit wiring for fixtures that construct application services outside a Spring context. */
public final class DepositTestSupport {
    private DepositTestSupport() { }

    public static org.springframework.context.annotation.AnnotationConfigApplicationContext context(
            JdbcTemplate jdbc, CustodyEventService events, ObjectMapper mapper) {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
                () -> new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        context.registerBean(CustodyEventService.class, () -> events);
        context.registerBean(CustodyDepositProjectionService.class,
                () -> projection(jdbc, context.getBean(CustodyEventService.class), mapper));
        context.registerBean(DepositService.class, () -> service(jdbc, context.getBean(CustodyDepositProjectionService.class)));
        context.refresh();
        return context;
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions { }

    public static ChainJdbcRepository repository(JdbcTemplate jdbc) {
        return repository(jdbc, projection(jdbc, CustodyTestSupport.events(jdbc),
                new CustodyJacksonConfiguration().custodyObjectMapper()));
    }

    public static ChainJdbcRepository repository(JdbcTemplate jdbc, CustodyDepositProjectionService projection) {
        return new ChainJdbcRepository(jdbc, new UtxoRepository(jdbc), service(jdbc, projection));
    }

    public static DepositService service(JdbcTemplate jdbc) {
        return service(jdbc, projection(jdbc, CustodyTestSupport.events(jdbc),
                new CustodyJacksonConfiguration().custodyObjectMapper()));
    }

    public static DepositService service(JdbcTemplate jdbc, CustodyDepositProjectionService projection) {
        return new DepositService(new ChainAddressRepository(jdbc), new DepositRecordRepository(jdbc),
                new LedgerBalanceRepository(jdbc), new ChainScanBlockRepository(jdbc), new UtxoRepository(jdbc),
                new CollectionRecordRepository(jdbc), new ChainSigningTransactionRepository(jdbc, new tools.jackson.databind.ObjectMapper()), projection);
    }

    public static CustodyDepositProjectionService projection(
            JdbcTemplate jdbc, CustodyEventService events, ObjectMapper mapper) {
        return new CustodyDepositProjectionService(new ChainAddressRepository(jdbc), new CustodyAddressRepository(jdbc),
                new CustodyGasAccountRepository(jdbc), new CustodyTenantChainRepository(jdbc),
                new CustodyDepositRepository(jdbc), new CustodyLedgerEntryRepository(jdbc),
                new CustodyReorgDeficitRepository(jdbc), new LedgerBalanceRepository(jdbc),
                events, new CustodyAuditRepository(jdbc), mapper);
    }
}
