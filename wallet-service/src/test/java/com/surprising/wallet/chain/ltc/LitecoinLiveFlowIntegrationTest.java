package com.surprising.wallet.chain.ltc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code LitecoinLiveFlowIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class LitecoinLiveFlowIntegrationTest {
    /**
     * 保存 {@code DEPOSIT_TX}，用于标识测试中的交易、区块或业务记录。
     */
    private static final String DEPOSIT_TX =
            "24aecf832537eb6b9e77722541ab812f3c6f887a75ff40aee83170bd35497f9f";
    /**
     * 保存 {@code DEPOSIT_ADDRESS}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String DEPOSIT_ADDRESS =
            "tltc1qeh6wxfsj4cfwh5dmp0nnpqj52s9u5gkc59gyj94qllg7wnjxx6qsnda7vj";
    /**
     * 保存 {@code WITHDRAW_ADDRESS}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String WITHDRAW_ADDRESS =
            "tltc1qydpzhcujqtca9uuepts0k996jfv483xlnkf8majw0f0umaht9j6q2aktvc";
    /**
     * 保存 {@code HOT_ADDRESS}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private static final String HOT_ADDRESS =
            "tltc1qku2kf64evgw0m79sypm3tp39js97d2e6j6xl6ntf089nvzpkxvnsnc54wn";
    /**
     * 保存 {@code ESPLORA}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String ESPLORA = "https://litecoinspace.org/testnet/api";

    /**
     * 保存 {@code JSON}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * 保存 {@code HTTP}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    /**
     * 保存 {@code withdrawTx}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private static String withdrawTx;
    /**
     * 保存 {@code collectionTx}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private static String collectionTx;

    /**
     * 验证 {@code requireLiveConfiguration} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @BeforeAll
    static void requireLiveConfiguration() {
        Assumptions.assumeTrue(Boolean.getBoolean("ltc.live.enabled"),
                "set -Dltc.live.enabled=true to run the real Litecoin testnet gate");
        withdrawTx = requiredProperty("ltc.live.withdraw.txid");
        collectionTx = requiredProperty("ltc.live.collection.txid");
    }

    /**
     * 验证 {@code realDepositMustBeCreditedExactlyOnce} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void realDepositMustBeCreditedExactlyOnce() throws Exception {
        JsonNode tx = esplora("/tx/" + DEPOSIT_TX);
        assertTrue(tx.path("status").path("confirmed").asBoolean());
        assertEquals(4773130L, tx.path("status").path("block_height").asLong());
        assertTrue(hasOutput(tx, DEPOSIT_ADDRESS, 1_000_000L));

        try (Connection connection = connection()) {
            assertEquals(1L, scalarLong(connection, """
                    select count(*) from deposit_record
                    where chain='LTC' and tx_hash=? and log_index=0
                      and to_address=? and amount=0.01 and status='CREDITED' and credited=true
                    """, DEPOSIT_TX, DEPOSIT_ADDRESS));
            assertEquals(1L, scalarLong(connection, """
                    select count(*) from utxo_record
                    where chain='LTC' and tx_hash=? and vout=0 and credited=true
                    """, DEPOSIT_TX));
            assertEquals(new BigDecimal("0.004996800000000000"), scalarDecimal(connection, """
                    select total_balance from ledger_balance
                    where chain='LTC' and asset_symbol='LTC' and account_id='9001'
                    """));
            assertEquals(0L, scalarLong(connection, """
                    select count(*) from deposit_record
                    where chain='LTC' and tx_hash in (?, ?)
                    """, withdrawTx, collectionTx));
        }
    }

    /**
     * 验证 {@code realWithdrawalMustBeConfirmedAndSettledOnce} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void realWithdrawalMustBeConfirmedAndSettledOnce() throws Exception {
        JsonNode tx = esplora("/tx/" + withdrawTx);
        assertTrue(tx.path("status").path("confirmed").asBoolean());
        assertTrue(confirmations(tx) >= 6);
        assertTrue(hasOutput(tx, WITHDRAW_ADDRESS, 500_000L));
        assertEquals(404L, tx.path("fee").asLong());
        assertEquals(803L, tx.path("weight").asLong());

        try (Connection connection = connection()) {
            assertEquals("CONFIRMED", scalarString(connection, """
                    select status from withdrawal_order
                    where chain='LTC' and order_no='ltc-live-gate-20260621-001'
                    """));
            assertEquals(withdrawTx, scalarString(connection, """
                    select tx_hash from withdrawal_order
                    where chain='LTC' and order_no='ltc-live-gate-20260621-001'
                    """));
            assertEquals(new BigDecimal("0.004996800000000000"), scalarDecimal(connection, """
                    select available_balance from ledger_balance
                    where chain='LTC' and asset_symbol='LTC' and account_id='9001'
                    """));
            assertEquals(BigDecimal.ZERO.setScale(18), scalarDecimal(connection, """
                    select locked_balance from ledger_balance
                    where chain='LTC' and asset_symbol='LTC' and account_id='9001'
                    """));
        }
    }

    /**
     * 验证 {@code realCollectionMustBeConfirmedAndIdempotent} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void realCollectionMustBeConfirmedAndIdempotent() throws Exception {
        JsonNode tx = esplora("/tx/" + collectionTx);
        assertTrue(tx.path("status").path("confirmed").asBoolean());
        assertTrue(confirmations(tx) >= 6);
        assertTrue(hasOutput(tx, HOT_ADDRESS, 499_682L));
        assertEquals(318L, tx.path("fee").asLong());
        assertEquals(632L, tx.path("weight").asLong());

        try (Connection connection = connection()) {
            assertEquals(1L, scalarLong(connection, """
                    select count(*) from collection_record
                    where chain='LTC' and tx_hash=? and status='CONFIRMED'
                    """, collectionTx));
            assertEquals(1L, scalarLong(connection, """
                    select count(*) from chain_signing_transaction
                    where chain='LTC' and tx_id=?
                    """, collectionTx));
            assertEquals(0L, scalarLong(connection, """
                    select count(*) from ledger_balance
                    where chain='LTC' and (available_balance < 0 or locked_balance < 0 or total_balance < 0)
                    """));
            assertEquals(BigDecimal.ZERO.setScale(18), scalarDecimal(connection, """
                    select locked_balance from ledger_balance
                    where chain='LTC' and asset_symbol='LTC' and account_id='9001'
                    """));
        }
    }

    /**
     * 验证 {@code unifiedUtxoLockAndReleaseMustBeGuarded} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void unifiedUtxoLockAndReleaseMustBeGuarded() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            String txHash = "test-" + UUID.randomUUID();
            try {
                update(connection, """
                        insert into utxo_record(chain,asset_symbol,tx_hash,vout,address,amount,block_height,
                                                confirmations,state,credited)
                        values ('LTC','LTC',?,0,?,0.001,1,1,'AVAILABLE',false)
                        """, txHash, DEPOSIT_ADDRESS);
                assertEquals(1, update(connection, """
                        update utxo_record set state='LOCKED',lock_ref='lock-test'
                        where chain='LTC' and tx_hash=? and vout=0 and state='AVAILABLE'
                        """, txHash));
                assertEquals(0, update(connection, """
                        update utxo_record set state='LOCKED',lock_ref='lock-duplicate'
                        where chain='LTC' and tx_hash=? and vout=0 and state='AVAILABLE'
                        """, txHash));
                assertEquals(1, update(connection, """
                        update utxo_record set state='AVAILABLE',lock_ref=null
                        where chain='LTC' and tx_hash=? and vout=0 and state='LOCKED' and lock_ref='lock-test'
                        """, txHash));
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * 验证 {@code connection} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Connection connection() throws Exception {
        String url = env("LTC_LIVE_DB_URL", "jdbc:postgresql://127.0.0.1:5432/wallet");
        String user = env("LTC_LIVE_DB_USER", "wallet");
        String password = env("LTC_LIVE_DB_PASSWORD", "");
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 验证 {@code esplora} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static JsonNode esplora(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ESPLORA + path))
                .version(HttpClient.Version.HTTP_1_1)
                .header("User-Agent", "surprising-wallet-ltc-live-gate/1.0")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    /**
     * 验证 {@code confirmations} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static long confirmations(JsonNode tx) throws Exception {
        long tip = Long.parseLong(HTTP.send(
                HttpRequest.newBuilder(URI.create(ESPLORA + "/blocks/tip/height")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body().trim());
        return tip - tx.path("status").path("block_height").asLong() + 1L;
    }

    /**
     * 验证 {@code hasOutput} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static boolean hasOutput(JsonNode tx, String address, long value) {
        for (JsonNode output : tx.path("vout")) {
            if (address.equals(output.path("scriptpubkey_address").asText())
                    && value == output.path("value").asLong()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 验证 {@code scalarLong} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static long scalarLong(Connection connection, String sql, Object... args) throws Exception {
        try (PreparedStatement statement = prepare(connection, sql, args);
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    /**
     * 验证 {@code scalarDecimal} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static BigDecimal scalarDecimal(Connection connection, String sql, Object... args) throws Exception {
        try (PreparedStatement statement = prepare(connection, sql, args);
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getBigDecimal(1);
        }
    }

    /**
     * 验证 {@code scalarString} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String scalarString(Connection connection, String sql, Object... args) throws Exception {
        try (PreparedStatement statement = prepare(connection, sql, args);
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    /**
     * 验证 {@code update} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static int update(Connection connection, String sql, Object... args) throws Exception {
        try (PreparedStatement statement = prepare(connection, sql, args)) {
            return statement.executeUpdate();
        }
    }

    /**
     * 验证 {@code prepare} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
        return statement;
    }

    /**
     * 验证 {@code requiredProperty} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "missing system property " + name);
        assertFalse(value.isBlank(), "blank system property " + name);
        return value;
    }

    /**
     * 验证 {@code env} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
