package com.surprising.wallet.testutil;

import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyEventService;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit construction of custody fixtures; application classes keep one injected constructor. */
public final class CustodyTestSupport {
    private CustodyTestSupport() { }

    public static CustodyRepository repository(JdbcTemplate jdbc) {
        return new CustodyRepository(jdbc, new CustodySecurityRepository(jdbc),
                new CustodyTenantTableRepository(jdbc), new CustodyTenantUserRepository(jdbc), events(jdbc));
    }

    public static CustodyEventService events(JdbcTemplate jdbc) {
        return new CustodyEventService(new CustodyEventRepository(jdbc),
                new CustodyWebhookEndpointRepository(jdbc), new CustodyWebhookDeliveryRepository(jdbc));
    }
}
