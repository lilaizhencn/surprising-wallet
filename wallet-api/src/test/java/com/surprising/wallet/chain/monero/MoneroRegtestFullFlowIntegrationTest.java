package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.LedgerBalanceRecord;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code MoneroRegtestFullFlowIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class MoneroRegtestFullFlowIntegrationTest {
    /**
     * 保存 {@code TEST_TENANT_ID}，用于标识测试中的交易、区块或业务记录。
     */
    private static final UUID TEST_TENANT_ID = UUID.fromString("77020000-0000-0000-0000-000000000002");
    /**
     * 保存 {@code CHAIN}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String CHAIN = "XMR";
    /**
     * 保存 {@code SYMBOL}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String SYMBOL = "XMR";
    /**
     * 保存 {@code CONFIRMATIONS}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private static final int CONFIRMATIONS = 1;
    /**
     * 保存 {@code DEPOSIT_AMOUNT}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
     */
    private static final BigDecimal DEPOSIT_AMOUNT = new BigDecimal("0.500000000000");
    /**
     * 保存 {@code WITHDRAW_AMOUNT}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
     */
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("0.050000000000");

    /**
     * 验证 {@code moneroRegtestMustScanWithdrawAndAvoidInternalDoubleCredit} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void moneroRegtestMustScanWithdrawAndAvoidInternalDoubleCredit() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("monero.regtest.enabled"),
                "set -Dmonero.regtest.enabled=true to run real XMR regtest flow validation");
        Path root = repoRoot();
        Path script = root.resolve("scripts/regtest/monero-regtest.sh");
        Assumptions.assumeTrue(Files.exists(script), "missing " + script);

        runScript(root, "init");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("MONERO_REGTEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet"),
                env("MONERO_REGTEST_DB_USER", env("BITCOINLIKE_REGTEST_DB_USER", "wallet")),
                env("MONERO_REGTEST_DB_PASSWORD", env("BITCOINLIKE_REGTEST_DB_PASSWORD", "")));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ChainJdbcRepository repository = new ChainJdbcRepository(jdbc);
            try {
                String environment = env("MONERO_REGTEST_ENVIRONMENT", "dev");
                String network = env("MONERO_REGTEST_PROFILE_NETWORK", "regtest");
                jdbc.update("""
                                insert into custody_tenant(id, slug, name)
                                values (?, 'xmr-regtest', 'XMR Regtest')
                                on conflict (id) do update set name = excluded.name, updated_at = now()
                                """, TEST_TENANT_ID);
                ensureMoneroConfig(jdbc, environment, network);

                ChainRpcNodeService rpcNodeService = new ChainRpcNodeService(repository);
                setField(rpcNodeService, "environmentName", environment);
                setField(rpcNodeService, "maxConcurrentRequestsPerProvider", 1);

                MoneroWalletRpcClient walletRpc = new MoneroWalletRpcClient(repository, rpcNodeService);
                MoneroDepositScanner scanner = new MoneroDepositScanner(walletRpc, repository);
                MoneroTransactionService transactionService = new MoneroTransactionService(walletRpc, repository);
                AccountChainProfile profile = profile(network);

                walletRpc.refresh();
                MoneroWalletRpcClient.Subaddress userAddress =
                        walletRpc.createAddress("java-regtest-user-" + UUID.randomUUID());
                String userAccount = "xmr-regtest-user-" + UUID.randomUUID();
                ChainAddressRecord userRecord = addressRecord(1001L, userAccount, userAddress);
                repository.upsertChainAddress(userRecord);

                runScript(root, "fund", userAddress.address(), DEPOSIT_AMOUNT.toPlainString());
                runScript(root, "mine", "12");
                scanner.scanAndCredit(profile);
                assertBalance(repository, userAccount, DEPOSIT_AMOUNT, BigDecimal.ZERO, DEPOSIT_AMOUNT);

                scanner.scanAndCredit(profile);
                assertBalance(repository, userAccount, DEPOSIT_AMOUNT, BigDecimal.ZERO, DEPOSIT_AMOUNT);

                MoneroWalletRpcClient.Subaddress recipientAddress =
                        walletRpc.createAddress("java-regtest-recipient-" + UUID.randomUUID());
                String recipientAccount = "xmr-regtest-recipient-" + UUID.randomUUID();
                ChainAddressRecord recipientRecord = addressRecord(1002L, recipientAccount, recipientAddress);
                repository.upsertChainAddress(recipientRecord);

                String orderNo = "xmr-regtest-withdraw-" + UUID.randomUUID();
                repository.createTenantWithdrawalOrder(TEST_TENANT_ID, orderNo, 1001L, CHAIN, SYMBOL,
                        userAddress.address(), userAccount, recipientAddress.address(),
                        WITHDRAW_AMOUNT, BigDecimal.ZERO);
                assertTrue(repository.freezeLedgerBalance(
                        TEST_TENANT_ID, CHAIN, SYMBOL, userAccount, WITHDRAW_AMOUNT));
                String txHash = transactionService.sendNative(profile, userRecord, recipientAddress.address(), WITHDRAW_AMOUNT);
                repository.updateWithdrawalStatus(
                        TEST_TENANT_ID, CHAIN, orderNo, "SENT", userAddress.address(), txHash, null);
                runScript(root, "mine", "12");
                walletRpc.refresh();

                transactionService.confirmWithdrawal(TEST_TENANT_ID, profile, orderNo, txHash,
                        userAccount, WITHDRAW_AMOUNT, recipientAddress.address(), WITHDRAW_AMOUNT);
                assertEquals("CONFIRMED", repository.findWithdrawalOrder(TEST_TENANT_ID, CHAIN, orderNo)
                        .orElseThrow().getStatus());
                assertBalance(repository, userAccount,
                        DEPOSIT_AMOUNT.subtract(WITHDRAW_AMOUNT), BigDecimal.ZERO,
                        DEPOSIT_AMOUNT.subtract(WITHDRAW_AMOUNT));
                assertBalance(repository, recipientAccount, WITHDRAW_AMOUNT, BigDecimal.ZERO, WITHDRAW_AMOUNT);

                scanner.scanAndCredit(profile);
                assertBalance(repository, recipientAccount, WITHDRAW_AMOUNT, BigDecimal.ZERO, WITHDRAW_AMOUNT);

            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * 验证 {@code profile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainProfile profile(String network) {
        return AccountChainProfile.builder()
                .chain(CHAIN)
                .network(network)
                .family("monero")
                .runtimeCurrencyId(128)
                .bip44CoinType(128)
                .nativeSymbol(SYMBOL)
                .depositConfirmations(CONFIRMATIONS)
                .withdrawConfirmations(CONFIRMATIONS)
                .enabled(true)
                .scanEnabled(true)
                .withdrawEnabled(true)
                .collectionEnabled(true)
                .scanStartHeight(0L)
                .scanBatchSize(100)
                .build();
    }

    /**
     * 验证 {@code addressRecord} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static ChainAddressRecord addressRecord(long userId, String accountId,
                                                    MoneroWalletRpcClient.Subaddress subaddress) {
        return ChainAddressRecord.builder()
                .tenantId(TEST_TENANT_ID)
                .chain(CHAIN)
                .assetSymbol(SYMBOL)
                .accountId(accountId)
                .userId(userId)
                .biz(0)
                .addressIndex((long) subaddress.addressIndex())
                .address(subaddress.address())
                .derivationPath("monero-wallet-rpc:m/0/" + subaddress.addressIndex())
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    /**
     * 验证 {@code ensureMoneroConfig} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void ensureMoneroConfig(JdbcTemplate jdbc, String environment, String network) {
        jdbc.update("""
                        insert into chain_profile(
                            chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                            rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                            default_fee_rate, dust_threshold, enabled, created_at, updated_at,
                            chain_id, gas_policy, scan_batch_size, scan_enabled, withdraw_enabled,
                            collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        )
                        values (?, ?, 'monero', 128, 128, ?, ?, null, ?, ?, null, 100000000, true, now(), now(),
                                null, 'monero-wallet-rpc', 100, true, true, true, false, 0, 0)
                        on conflict (chain, network) do update set
                            family = excluded.family,
                            runtime_currency_id = excluded.runtime_currency_id,
                            bip44_coin_type = excluded.bip44_coin_type,
                            native_symbol = excluded.native_symbol,
                            rpc_url = excluded.rpc_url,
                            deposit_confirmations = excluded.deposit_confirmations,
                            withdraw_confirmations = excluded.withdraw_confirmations,
                            enabled = true,
                            scan_enabled = true,
                            withdraw_enabled = true,
                            collection_enabled = true,
                            gas_policy = excluded.gas_policy,
                            updated_at = now()
                        """,
                CHAIN, network, SYMBOL, walletRpcUrl(), CONFIRMATIONS, CONFIRMATIONS);
        String[] login = rpcLogin();
        upsertRpcNode(jdbc, environment, network, "local-monero-wallet-rpc-regtest", "rpc",
                walletRpcUrl(), login[0], login[1]);
    }

    /**
     * 验证 {@code upsertRpcNode} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void upsertRpcNode(JdbcTemplate jdbc, String environment, String network, String label,
                                      String purpose, String url, String username, String password) {
        String authType = blankToNull(username) == null && blankToNull(password) == null ? "NONE" : "DIGEST";
        jdbc.update("""
                        insert into chain_rpc_node(chain, network, environment, node_label, purpose, connection_type,
                                                   rpc_url, auth_type, auth_header_name, priority,
                                                   min_request_interval_ms, enabled, created_at, updated_at,
                                                   username, password)
                        values (?, ?, ?, ?, ?, 'WALLET_RPC', ?, ?, null, 1, 0, true, now(), now(), ?, ?)
                        on conflict (chain, network, environment, purpose, node_label) do update set
                            connection_type = excluded.connection_type,
                            rpc_url = excluded.rpc_url,
                            auth_type = excluded.auth_type,
                            auth_header_name = excluded.auth_header_name,
                            username = excluded.username,
                            password = excluded.password,
                            priority = excluded.priority,
                            min_request_interval_ms = excluded.min_request_interval_ms,
                            enabled = true,
                            updated_at = now()
                        """,
                CHAIN, network, environment, label, purpose, url, authType, blankToNull(username), blankToNull(password));
    }

    /**
     * 验证 {@code assertBalance} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void assertBalance(ChainJdbcRepository repository, String accountId,
                                      BigDecimal available, BigDecimal locked, BigDecimal total) {
        LedgerBalanceRecord balance = repository.findLedgerBalance(CHAIN, SYMBOL, accountId).orElseThrow();
        assertEquals(0, available.compareTo(balance.getAvailableBalance().stripTrailingZeros()));
        assertEquals(0, locked.compareTo(balance.getLockedBalance().stripTrailingZeros()));
        assertEquals(0, total.compareTo(balance.getTotalBalance().stripTrailingZeros()));
    }

    /**
     * 验证 {@code walletRpcUrl} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String walletRpcUrl() {
        String configured = env("MONERO_REGTEST_WALLET_RPC_URL", "");
        if (!configured.isBlank()) {
            return configured;
        }
        String host = env("MONERO_REGTEST_CLIENT_HOST", env("MONERO_REGTEST_BIND_HOST", "127.0.0.1"));
        String port = env("MONERO_REGTEST_WALLET_RPC_PORT", "18088");
        return "http://" + host + ":" + port;
    }

    /**
     * 验证 {@code rpcLogin} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String[] rpcLogin() {
        String login = env("MONERO_REGTEST_RPC_LOGIN", "");
        int separator = login.indexOf(':');
        if (separator < 0) {
            return new String[]{"", ""};
        }
        return new String[]{login.substring(0, separator), login.substring(separator + 1)};
    }

    /**
     * 验证 {@code runScript} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void runScript(Path root, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(root.resolve("scripts/regtest/monero-regtest.sh").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(4, java.util.concurrent.TimeUnit.MINUTES);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("monero regtest script timed out: " + String.join(" ", args)
                    + "\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("monero regtest script failed: " + String.join(" ", args)
                    + "\n" + output);
        }
    }

    /**
     * 验证 {@code repoRoot} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("scripts/regtest/monero-regtest.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate repo root containing scripts/regtest/monero-regtest.sh");
    }

    /**
     * 验证 {@code setField} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to configure " + name, e);
        }
    }

    /**
     * 验证 {@code env} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 验证 {@code blankToNull} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
