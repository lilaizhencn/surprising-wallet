package com.surprising.wallet.config.eip7702;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import com.surprising.wallet.wallet.service.HotWalletAddressService;
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
 * 创建开发环境下确定性 relayer 钱包地址，用于本地 EIP-7702 流程的授权签名入口。
 *
 * <p>仅在允许的开发/测试环境与 devtest/local 网络下执行，避免误入生产。 </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "sw.wallet.dev-eip7702-bootstrap",
        name = "enabled",
        havingValue = "true")
public class DevEip7702RelayerBootstrap implements ApplicationRunner {
    /** 开发 relayer 租户 ID（固定值，避免跨租户污染）。 */
    static final long RELAYER_USER_ID = 9_000_001L;
    /** relayer 使用的业务位。 */
    static final int RELAYER_BIZ = 7702;
    /** relayer 地址索引。 */
    static final long RELAYER_ADDRESS_INDEX = 0L;
    /** 角色标识，用于查找/持久化钱包地址。 */
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
        // 环境/网络校验通过后，初始化或校验 dev relayer 地址是否稳定可用。
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

    /**
     * 限制仅允许在开发/联调环境和 devtest/local 网络执行，防止误发到生产。
     */
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
