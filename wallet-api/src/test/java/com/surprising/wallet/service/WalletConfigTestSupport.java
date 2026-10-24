package com.surprising.wallet.service;

import com.surprising.wallet.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configuration fixtures use the normal constructors, including the package-private RPC client. */
public final class WalletConfigTestSupport {
    private WalletConfigTestSupport() { }

    public static WalletConfigManagementService management(JdbcTemplate jdbc, WalletRuntimeConfigService runtime) {
        return new WalletConfigManagementService(runtime, new ChainProfileRepository(jdbc),
                new ChainRpcNodeRepository(jdbc), new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                new ChainAddressRepository(jdbc), new LedgerBalanceRepository(jdbc), new DepositRecordRepository(jdbc),
                new WithdrawalOrderRepository(jdbc), new CollectionRecordRepository(jdbc), new CustodyAuditRepository(jdbc),
                "test", new WalletRpcClient(new tools.jackson.databind.ObjectMapper()));
    }
}
