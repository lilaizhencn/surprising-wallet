package com.surprising.wallet.approval.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApprovalClockConfiguration {
    @Bean public Clock approvalClock() { return Clock.systemUTC(); }
}
