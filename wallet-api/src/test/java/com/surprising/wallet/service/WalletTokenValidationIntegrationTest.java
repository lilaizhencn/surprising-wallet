package com.surprising.wallet.service;

import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.custody.CustodyIntegrationDatabase;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.testutil.RuntimeTestSupport;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class WalletTokenValidationIntegrationTest {
    private static final String CONTRACT = "0x" + "ab".repeat(20);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Runnable> duringValidation = new AtomicReference<>();
    private JdbcTemplate jdbc;
    private HttpServer server;
    private AnnotationConfigApplicationContext context;
    private WalletTokenConfigService service;
    private DataSourceTransactionManager manager;
    private boolean bytes32;
    private boolean invalidAbi;
    private int onChainDecimals = 6;

    @BeforeEach void setup() throws Exception {
        var dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("update chain_profile set enabled=true, chain_id=1337 where chain='ETH' and network='sepolia'");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rpc", exchange -> {
            if (!"local-test-key".equals(exchange.getRequestHeaders().getFirst("X-API-Key"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            calls.incrementAndGet();
            Runnable change = duringValidation.getAndSet(null);
            if (change != null) change.run();
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();
            String result;
            if ("eth_chainId".equals(method)) result = "0x539";
            else if ("eth_getCode".equals(method)) result = "0x6000";
            else if (request.path("params").get(0).path("data").asText().equals("0x313ce567")) {
                result = "0x" + Integer.toHexString(onChainDecimals);
            } else if (invalidAbi) result = "0x1234";
            else if (bytes32) result = "0x" + FunctionEncoder.encodeConstructor(List.of(
                    new Bytes32(Arrays.copyOf("TX_TOKEN".getBytes(StandardCharsets.UTF_8), 32))));
            else result = "0x" + FunctionEncoder.encodeConstructor(List.of(new Utf8String("TX_TOKEN")));
            byte[] body = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "result", result))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        new ChainRpcNodeRepository(jdbc).insert("ETH", "sepolia", "test", "token-validation-local", "rpc",
                "HTTP_JSON_RPC", "http://127.0.0.1:" + server.getAddress().getPort() + "/rpc", "API_KEY", null,
                "local-test-key", null, null, 0, 0, true, null, null);
        var nodes = new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc), "test", 1);
        var rpc = spy(new WalletRpcClient(mapper, nodes));
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "RPC must run outside the database transaction");
            return invocation.callRealMethod();
        }).when(rpc).jsonRpc(any(), anyString(), anyList());
        manager = new DataSourceTransactionManager(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> manager);
        context.registerBean(WalletTokenConfigService.class, () -> new WalletTokenConfigService(
                RuntimeTestSupport.database(jdbc), new ChainProfileRepository(jdbc), new ChainRpcNodeRepository(jdbc),
                new ChainAssetRepository(jdbc), new TokenConfigRepository(jdbc),
                new WalletConfigIdentityGuard(new ChainAddressRepository(jdbc), new LedgerBalanceRepository(jdbc),
                        new DepositRecordRepository(jdbc), new WithdrawalOrderRepository(jdbc), new CollectionRecordRepository(jdbc),
                        new com.surprising.wallet.repository.CustodyAddressRepository(jdbc)),
                new CustodyAuditRepository(jdbc), mapper, "test", rpc, manager));
        context.refresh();
        service = context.getBean(WalletTokenConfigService.class);
    }

    @AfterEach void close() {
        if (context != null) context.close();
        if (server != null) server.stop(0);
    }

    @Test void dynamicSymbolIsValidatedOutsideTransactionAndAllWritesCommit() {
        var saved = create();
        assertTrue(saved.enabled()); assertEquals(4, calls.get());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select active from chain_asset where chain='ETH' and symbol='TX_TOKEN'", Boolean.class));
        assertEquals(1, auditCount());
    }

    @Test void bytes32SymbolRemainsSupported() {
        bytes32 = true;
        assertEquals("TX_TOKEN", create().symbol());
        assertEquals(4, calls.get());
    }

    @Test void callerTransactionIsSuspendedBeforeTheFirstRpc() {
        new TransactionTemplate(manager).execute(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            create(); status.setRollbackOnly(); return null;
        });
        assertEquals(1, tokenCount());
    }

    @Test void changedProfileCannotCommitAStaleValidation() {
        duringValidation.set(() -> jdbc.update("update chain_profile set enabled=false where chain='ETH' and network='sepolia'"));
        var error = assertThrows(IllegalStateException.class, this::create);
        assertTrue(error.getMessage().contains("chain configuration changed"));
        assertEquals(0, tokenCount()); assertEquals(0, auditCount()); assertEquals(0, assetCount());
    }

    @Test void changedTokenCannotBeOverwrittenAfterRemoteValidation() {
        var saved = service.createToken(CustodyTestSupport.platformAdmin(), command(false, BigDecimal.ZERO), "127.0.0.1");
        duringValidation.set(() -> jdbc.update("update token_config set gas_strategy='EXTERNAL_CHANGE', updated_at=clock_timestamp() where id=?", saved.id()));
        var error = assertThrows(IllegalStateException.class, () -> service.updateToken(
                CustodyTestSupport.platformAdmin(), saved.id(), command(true, BigDecimal.ONE), "127.0.0.1"));
        assertTrue(error.getMessage().contains("token configuration changed"));
        var current = new TokenConfigRepository(jdbc).findById(saved.id()).orElseThrow();
        assertFalse(current.enabled()); assertEquals("EXTERNAL_CHANGE", current.gasStrategy());
        assertEquals(1, auditCount()); assertEquals(0, assetCount());
    }

    @Test void auditFailureRollsBackTokenAndAssetTogether() {
        assertThrows(RuntimeException.class, () -> service.createToken(
                CustodyTestSupport.platformAdmin(), command(true, BigDecimal.ZERO), "invalid-ip"));
        assertEquals(4, calls.get()); assertEquals(0, tokenCount()); assertEquals(0, assetCount()); assertEquals(0, auditCount());
    }

    @Test void disablingDoesNotPerformRemoteValidationAndDisablesAssetAtomically() {
        var saved = create(); calls.set(0);
        var disabled = service.updateTokenStatus(CustodyTestSupport.platformAdmin(), saved.id(),
                new WalletTokenConfigService.TokenStatusCommand(false), "127.0.0.1");
        assertFalse(disabled.enabled()); assertEquals(0, calls.get());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("select active from chain_asset where chain='ETH' and symbol='TX_TOKEN'", Boolean.class));
        assertEquals(2, auditCount());
    }

    @Test void successfulUpdateUsesValidatedSnapshotAndExactThresholds() {
        var saved = create();
        var updated = service.updateToken(CustodyTestSupport.platformAdmin(), saved.id(),
                command(true, new BigDecimal("0.000001")), "127.0.0.1");
        assertEquals(0, new BigDecimal("0.000001").compareTo(updated.minWithdraw()));
        assertEquals(8, calls.get()); assertEquals(2, auditCount());
    }

    @Test void invalidAbiAndPrecisionCannotProduceConfigurationWrites() {
        invalidAbi = true;
        assertThrows(IllegalStateException.class, this::create);
        invalidAbi = false; onChainDecimals = 7;
        assertThrows(IllegalArgumentException.class, this::create);
        assertEquals(0, tokenCount()); assertEquals(0, assetCount()); assertEquals(0, auditCount());
    }

    private WalletTokenConfigService.TokenView create() {
        return service.createToken(CustodyTestSupport.platformAdmin(), command(true, BigDecimal.ZERO), "127.0.0.1");
    }
    private WalletTokenConfigService.TokenCommand command(boolean enabled, BigDecimal minimum) {
        return new WalletTokenConfigService.TokenCommand("ETH", "sepolia", "TX_TOKEN", "ERC20", CONTRACT,
                null, null, 6, enabled, enabled, BigDecimal.ZERO, minimum, BigDecimal.ONE, "NATIVE", 2);
    }
    private int tokenCount() { return jdbc.queryForObject("select count(*) from token_config where symbol='TX_TOKEN'", Integer.class); }
    private int assetCount() { return jdbc.queryForObject("select count(*) from chain_asset where symbol='TX_TOKEN'", Integer.class); }
    private int auditCount() { return jdbc.queryForObject("select count(*) from custody_audit_log where action like 'WALLET_TOKEN.%'", Integer.class); }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }
}
