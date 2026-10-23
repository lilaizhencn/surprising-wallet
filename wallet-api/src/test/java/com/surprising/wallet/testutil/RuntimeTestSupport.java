package com.surprising.wallet.testutil;

import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;

/** Unit tests isolate switch policy; database flow tests use the actual configured switches. */
public final class RuntimeTestSupport {
    private RuntimeTestSupport() { }

    public static WalletRuntimeConfigService enabled() {
        return mock(WalletRuntimeConfigService.class);
    }

    public static WalletRuntimeConfigService database(JdbcTemplate jdbc) {
        return new WalletRuntimeConfigService(new ChainProfileRepository(jdbc), new WalletSystemConfigRepository(jdbc));
    }
}
