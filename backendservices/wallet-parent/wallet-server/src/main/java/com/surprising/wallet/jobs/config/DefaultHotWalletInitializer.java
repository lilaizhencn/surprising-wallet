package com.surprising.wallet.jobs.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sw.wallet", name = "init-default-hot-wallets", havingValue = "true")
public class DefaultHotWalletInitializer implements ApplicationRunner {
    private final ChainJdbcRepository repository;
    private final HotWalletAddressService hotWalletAddressService;

    @Override
    public void run(ApplicationArguments args) {
        for (AccountChainProfile profile : repository.listEnabledChainProfiles()) {
            ChainAddressRecord address = hotWalletAddressService.deriveDefaultHotAddress(profile);
            repository.upsertChainAddress(address);
            log.info("default hot wallet initialized: chain={} asset={} address={}",
                    address.getChain(), address.getAssetSymbol(), address.getAddress());
        }
    }
}
