package com.surprising.wallet.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creates the deterministic platform relayer address needed by the local EIP-7702 runtime.
 * This runner is opt-in and refuses production-like environments or non-devtest networks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "sw.wallet.dev-eip7702-bootstrap",
        name = "enabled",
        havingValue = "true")
public class DevEip7702RelayerBootstrap implements ApplicationRunner {
    static final long RELAYER_USER_ID = 9_000_001L;
    static final int RELAYER_BIZ = 7702;
    static final long RELAYER_ADDRESS_INDEX = 0L;
    static final String RELAYER_ROLE = "EIP7702_RELAYER";

    private final ChainJdbcRepository repository;
    private final HotWalletAddressService addressService;

    @Value("${sw.app.env.name:dev}")
    private String environment;

    @Value("${sw.wallet.dev-eip7702-bootstrap.chain:ETH}")
    private String chain;

    @Value("${sw.wallet.dev-eip7702-bootstrap.network:devtest}")
    private String network;

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void run(ApplicationArguments args) {
        requireSafeDevEnvironment();
        AccountChainProfile profile = repository.findProfileByChain(chain.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain profile for dev EIP-7702 bootstrap: " + chain));
        if (!"evm".equalsIgnoreCase(profile.getFamily())
                || !network.equalsIgnoreCase(profile.getNetwork())) {
            throw new IllegalStateException(
                    "dev EIP-7702 bootstrap profile mismatch: expected " + chain + "/" + network
                            + " but found " + profile.getChain() + "/" + profile.getNetwork());
        }

        ChainAddressRecord expected = addressService.deriveAddress(
                profile, RELAYER_USER_ID, RELAYER_BIZ, RELAYER_ADDRESS_INDEX, RELAYER_ROLE);
        expected.setTenantId(null);
        var existing = repository.findChainAddress(
                        profile.getChain(), profile.getNativeSymbol(), RELAYER_USER_ID,
                        RELAYER_BIZ, RELAYER_ADDRESS_INDEX, RELAYER_ROLE);
        existing.ifPresent(record -> {
                    if (record.getTenantId() != null
                            || !record.getAddress().equalsIgnoreCase(expected.getAddress())) {
                        throw new IllegalStateException(
                                "existing dev EIP-7702 relayer ownership or derived address mismatch");
                    }
                });
        if (existing.isEmpty()) {
            repository.upsertChainAddress(expected);
        }

        ChainAddressRecord saved = repository.findChainAddress(
                        profile.getChain(), profile.getNativeSymbol(), RELAYER_USER_ID,
                        RELAYER_BIZ, RELAYER_ADDRESS_INDEX, RELAYER_ROLE)
                .orElseThrow(() -> new IllegalStateException("dev EIP-7702 relayer was not persisted"));
        if (saved.getTenantId() != null || !saved.getAddress().equalsIgnoreCase(expected.getAddress())) {
            throw new IllegalStateException("dev EIP-7702 relayer ownership or derived address mismatch");
        }
        log.info("dev EIP-7702 platform relayer is ready: chain={} network={} addressId={} address={}",
                profile.getChain(), profile.getNetwork(), saved.getId(), saved.getAddress());
    }

    private void requireSafeDevEnvironment() {
        String normalizedEnvironment = environment == null
                ? "" : environment.trim().toLowerCase(Locale.ROOT);
        String normalizedNetwork = network == null
                ? "" : network.trim().toLowerCase(Locale.ROOT);
        if (!(normalizedEnvironment.equals("dev")
                || normalizedEnvironment.equals("development")
                || normalizedEnvironment.equals("devtest")
                || normalizedEnvironment.equals("test"))) {
            throw new IllegalStateException(
                    "dev EIP-7702 bootstrap is forbidden in environment " + environment);
        }
        if (!(normalizedNetwork.equals("devtest") || normalizedNetwork.equals("local"))) {
            throw new IllegalStateException(
                    "dev EIP-7702 bootstrap requires devtest/local network");
        }
    }
}
