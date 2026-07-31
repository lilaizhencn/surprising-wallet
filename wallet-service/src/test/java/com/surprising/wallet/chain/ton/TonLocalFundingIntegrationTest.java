package com.surprising.wallet.chain.ton;

import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TonLocalFundingIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TonLocalFundingIntegrationTest {
    /**
     * 保存 {@code OWNER_INDEX}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final long OWNER_INDEX = 1_100_001L;
    /**
     * 保存 {@code MIN_OWNER_BALANCE}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
     */
    private static final long MIN_OWNER_BALANCE = 1_000_000_000L;
    /**
     * 保存 {@code FUNDING_AMOUNT}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
     */
    private static final long FUNDING_AMOUNT = 15_000_000_000L;

    /**
     * 验证 {@code fundsScopedTenantWalletFromLocalDeterministicWallet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void fundsScopedTenantWalletFromLocalDeterministicWallet() {
        Assumptions.assumeTrue(Boolean.getBoolean("ton.local.funding.enabled"),
                "set -Dton.local.funding.enabled=true for MyLocalTon setup");
        String masterSeed = System.getenv("SW_ED25519_SEED");
        Assumptions.assumeTrue(masterSeed != null && !masterSeed.isBlank(), "SW_ED25519_SEED is required");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("TON_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("TON_DB_USER", "wallet"),
                env("TON_DB_PASSWORD", ""));
        ChainJdbcRepository repository = new ChainJdbcRepository(new JdbcTemplate(dataSource));
        TonCenterClient rpc = new TonCenterClient(new ObjectMapper(),
                env("TON_RPC_URL", "http://127.0.0.1:8081"), "");
        TonKeyService keys = new TonKeyService(masterSeed);
        TonTransactionService transactions = new TonTransactionService(rpc, keys, repository);

        String target = keys.wallet(4001, 0, OWNER_INDEX)
                .getAddress().toString(true, true, false, true);
        if (rpc.balance(target) >= MIN_OWNER_BALANCE) {
            return;
        }

        String source = keys.wallet(OWNER_INDEX).getAddress().toString(true, true, false, true);
        assertTrue(rpc.balance(source) > FUNDING_AMOUNT + 1_000_000_000L,
                "fund deterministic MyLocalTon source with at least 16 TON first: " + source);
        if (!"active".equalsIgnoreCase(rpc.addressInformation(source).path("state").asText())) {
            TonTransactionService.PreparedTransfer deploy = transactions.prepareWalletDeploy(OWNER_INDEX);
            transactions.broadcast(deploy);
            waitFor(() -> "active".equalsIgnoreCase(
                    rpc.addressInformation(source).path("state").asText()), Duration.ofMinutes(3));
        }

        long sourceSeqno = rpc.seqno(source);
        transactions.broadcast(transactions.prepareNative(OWNER_INDEX, target,
                BigInteger.valueOf(FUNDING_AMOUNT), "MyLocalTon tenant funding"));
        waitFor(() -> rpc.seqno(source) > sourceSeqno, Duration.ofMinutes(3));
        waitFor(() -> rpc.balance(target) >= MIN_OWNER_BALANCE, Duration.ofMinutes(5));
    }

    /**
     * 验证 {@code waitFor} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void waitFor(Check check, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (check.done()) {
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TON local funding interrupted", e);
            }
        }
        throw new IllegalStateException("TON local funding timed out after " + timeout);
    }

    /**
     * 验证 {@code env} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 测试辅助类 {@code Check}，为相关测试提供隔离环境或共享数据。
     */
    @FunctionalInterface
    private interface Check {
        /**
         * 验证 {@code done} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        boolean done();
    }
}
