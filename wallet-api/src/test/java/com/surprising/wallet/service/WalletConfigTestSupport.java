package com.surprising.wallet.service;

import com.surprising.wallet.repository.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configuration fixtures use the same required constructors as the application. */
public final class WalletConfigTestSupport {
    private WalletConfigTestSupport() { }

    public static void register(AnnotationConfigApplicationContext context, JdbcTemplate jdbc,
                                WalletRuntimeConfigService runtime) {
        context.registerBean(WalletConfigIdentityGuard.class, () -> identityGuard(jdbc));
        context.registerBean(WalletRpcConfigService.class, () -> new WalletRpcConfigService(
                new ChainProfileRepository(jdbc), new ChainRpcNodeRepository(jdbc),
                new TokenConfigRepository(jdbc), new CustodyAuditRepository(jdbc), "test", client()));
        context.registerBean(WalletTokenConfigService.class, () -> new WalletTokenConfigService(
                runtime, new ChainProfileRepository(jdbc), new ChainRpcNodeRepository(jdbc),
                new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                context.getBean(WalletConfigIdentityGuard.class), new CustodyAuditRepository(jdbc), "test", client()));
        context.registerBean(WalletChainConfigService.class, () -> new WalletChainConfigService(
                new ChainProfileRepository(jdbc), new ChainRpcNodeRepository(jdbc),
                new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                context.getBean(WalletConfigIdentityGuard.class), new CustodyAuditRepository(jdbc), "test",
                context.getBean(WalletRpcConfigService.class), context.getBean(WalletTokenConfigService.class)));
    }

    public static WalletTokenConfigService tokens(JdbcTemplate jdbc, WalletRuntimeConfigService runtime) {
        return new WalletTokenConfigService(runtime, new ChainProfileRepository(jdbc),
                new ChainRpcNodeRepository(jdbc), new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                identityGuard(jdbc), new CustodyAuditRepository(jdbc), "test", client());
    }

    private static WalletConfigIdentityGuard identityGuard(JdbcTemplate jdbc) {
        return new WalletConfigIdentityGuard(new ChainAddressRepository(jdbc), new LedgerBalanceRepository(jdbc),
                new DepositRecordRepository(jdbc), new WithdrawalOrderRepository(jdbc), new CollectionRecordRepository(jdbc));
    }

    private static WalletRpcClient client() {
        return new WalletRpcClient(new tools.jackson.databind.ObjectMapper());
    }
}
