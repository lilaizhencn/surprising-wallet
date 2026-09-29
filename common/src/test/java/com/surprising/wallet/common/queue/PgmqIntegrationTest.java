package com.surprising.wallet.common.queue;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL 18 only; creates and drops one isolated temporary test database. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_PGMQ", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgmqIntegrationTest {
    private String database;
    private JdbcTemplate jdbc;
    private PgmqClient client;
    private QueueWorker worker;
    private TransactionTemplate transaction;
    private final String username = System.getenv().getOrDefault("SW_TEST_DB_USERNAME", System.getProperty("user.name"));
    private final String password = System.getenv().getOrDefault("SW_TEST_DB_PASSWORD", "");
    private static final String HEADERS = "{\"tenant_id\":\"00000000-0000-0000-0000-000000000001\"}";

    @BeforeAll void createDatabase() throws Exception {
        database = "surprising_wallet_test_pgmq_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/postgres", username, password);
             var stmt = c.createStatement()) {
            try (var rs = stmt.executeQuery("show server_version_num")) {
                assertTrue(rs.next()); assertEquals(18, rs.getInt(1) / 10000);
            }
            stmt.execute("create database " + database);
        }
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:5432/" + database, username, password);
        jdbc = new JdbcTemplate(ds);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("resources/docs/db/surprising-wallet-init-pgsql.sql"))) root = root.getParent();
        try (var c = ds.getConnection(); var stmt = c.createStatement()) {
            stmt.execute(Files.readString(root.resolve("resources/docs/db/surprising-wallet-init-pgsql.sql")));
        }
        client = new PgmqClient(jdbc);
        var manager = new DataSourceTransactionManager(ds);
        worker = new QueueWorker(client, manager);
        transaction = new TransactionTemplate(manager);
    }
    @AfterAll void dropDatabase() throws Exception {
        if (database == null) return;
        try (var c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/postgres", username, password);
             var stmt = c.createStatement()) { stmt.execute("drop database if exists " + database + " with (force)"); }
    }
    @BeforeEach void clearQueues() {
        for (var q : WalletQueue.values()) {
            for (var suffix : List.of("", "_dead")) {
                jdbc.execute("truncate pgmq.q_" + q.queueName() + suffix);
                jdbc.execute("truncate pgmq.a_" + q.queueName() + suffix);
            }
        }
        jdbc.execute("delete from chain_fee_rate");
    }
    @Test void competingConsumersClaimDistinctMessages() throws Exception {
        for (int i = 0; i < 40; i++) client.send(WalletQueue.SIGN_FIRST, "{\"n\":" + i + "}", HEADERS);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                for (var m : client.read(WalletQueue.SIGN_FIRST, 300, 10)) assertTrue(ids.add(m.id()));
            }));
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
        }
        assertEquals(40, ids.size());
    }
    @Test void expiredClaimRecoversAndRejectsStaleOwner() {
        client.send(WalletQueue.SIGN_FIRST, "{}", HEADERS);
        var old = client.read(WalletQueue.SIGN_FIRST, 300, 1).getFirst();
        jdbc.execute("update pgmq.q_wallet_sign_first set vt = now() - interval '1 second'");
        var current = client.read(WalletQueue.SIGN_FIRST, 300, 1).getFirst();
        assertEquals(old.id(), current.id()); assertEquals(old.reads() + 1, current.reads());
        transaction.executeWithoutResult(s -> {
            assertFalse(client.lock(WalletQueue.SIGN_FIRST, old));
            assertTrue(client.lock(WalletQueue.SIGN_FIRST, current));
            client.archive(WalletQueue.SIGN_FIRST, current);
        });
        assertEquals(0, count("pgmq.q_wallet_sign_first"));
        assertEquals(1, count("pgmq.a_wallet_sign_first"));
    }
    @Test void lockedProcessingCannotBeReclaimedEvenAfterVisibilityExpires() throws Exception {
        client.send(WalletQueue.SIGN_FIRST, "{}", HEADERS);
        var message = client.read(WalletQueue.SIGN_FIRST, 300, 1).getFirst();
        transaction.executeWithoutResult(s -> {
            assertTrue(client.lock(WalletQueue.SIGN_FIRST, message));
            jdbc.execute("update pgmq.q_wallet_sign_first set vt = now() - interval '1 second'");
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertTrue(executor.submit(() -> client.read(WalletQueue.SIGN_FIRST, 300, 1)).get(5, TimeUnit.SECONDS).isEmpty());
            } catch (Exception e) { throw new RuntimeException(e); }
            client.archive(WalletQueue.SIGN_FIRST, message);
        });
    }
    @Test void handlerFailureRollsBackBusinessWriteAndRetries() {
        client.send(WalletQueue.SIGN_FIRST, "{}", HEADERS);
        worker.drain(WalletQueue.SIGN_FIRST, 1, m -> {
            jdbc.update("insert into chain_fee_rate(chain,fee_rate) values ('BTC',10)");
            client.send(WalletQueue.SIGN_SECOND, "{}", m.headers());
            throw new IllegalStateException("simulated crash");
        });
        assertEquals(0, count("chain_fee_rate"));
        assertEquals(0, count("pgmq.q_wallet_sign_second"));
        assertEquals(1, count("pgmq.q_wallet_sign_first"));
        assertEquals(0, count("pgmq.a_wallet_sign_first"));
        assertTrue(client.read(WalletQueue.SIGN_FIRST, 300, 1).isEmpty());
    }
    @Test void stageAdvanceAndArchiveCommitWithBusinessWriteAndTenant() {
        client.send(WalletQueue.SIGN_FIRST, "{}", HEADERS);
        worker.drain(WalletQueue.SIGN_FIRST, 1, m -> {
            jdbc.update("insert into chain_fee_rate(chain,fee_rate) values ('BTC',10)");
            return new QueueWorker.Next(WalletQueue.SIGN_SECOND, "{\"signed\":true}");
        });
        assertEquals(1, count("chain_fee_rate"));
        assertEquals(0, count("pgmq.q_wallet_sign_first"));
        assertEquals(1, count("pgmq.a_wallet_sign_first"));
        var next = client.read(WalletQueue.SIGN_SECOND, 300, 1).getFirst();
        assertTrue(next.headers().contains("00000000-0000-0000-0000-000000000001"));
    }
    @Test void poisonMessageIsDeadLetteredAndReplayIsAuditedAndIdempotent() {
        client.send(WalletQueue.RBF, "{\"transactionId\":123}", HEADERS);
        jdbc.execute("update pgmq.q_wallet_rbf set read_ct = 19");
        worker.drain(WalletQueue.RBF, 1, m -> { throw new IllegalArgumentException("poison"); });
        assertEquals(0, count("pgmq.q_wallet_rbf"));
        long id = jdbc.queryForObject("select msg_id from pgmq.q_wallet_rbf_dead", Long.class);
        assertNotNull(jdbc.queryForObject("select wallet_replay_dead('wallet_rbf', ?, 'operator repair verified')", Long.class, id));
        assertNull(jdbc.queryForObject("select wallet_replay_dead('wallet_rbf', ?, 'operator repair verified')", Long.class, id));
        assertEquals(1, count("pgmq.q_wallet_rbf"));
        assertEquals(1, count("pgmq.a_wallet_rbf_dead"));
        assertEquals(username, jdbc.queryForObject("select headers->>'replay_actor' from pgmq.q_wallet_rbf", String.class));
    }
    @Test void enqueueRollsBackWithBusinessTransaction() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> {
            client.send(WalletQueue.SIGN_FIRST, "{}", HEADERS);
            throw new IllegalStateException();
        }));
        assertEquals(0, count("pgmq.q_wallet_sign_first"));
    }
    @Test void signerRoleCanProcessButCannotReadBusinessTables() throws Exception {
        String role = "sw_test_signer_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create role " + role);
        try {
            jdbc.execute("grant usage on schema pgmq to " + role);
            jdbc.execute("grant execute on all functions in schema pgmq to " + role);
            jdbc.execute("grant select, update, delete on pgmq.q_wallet_sign_first to " + role);
            jdbc.execute("grant insert, select(msg_id) on pgmq.a_wallet_sign_first, pgmq.q_wallet_sign_second, pgmq.q_wallet_sign_first_dead to " + role);
            jdbc.execute("grant usage, select on sequence pgmq.q_wallet_sign_second_msg_id_seq, pgmq.q_wallet_sign_first_dead_msg_id_seq to " + role);
            client.send(WalletQueue.SIGN_FIRST, "{}", HEADERS);
            try (var connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/" + database, username, password)) {
                connection.createStatement().execute("set role " + role);
                var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
                var signerJdbc = new JdbcTemplate(ds);
                var signer = new PgmqClient(signerJdbc);
                var signerWorker = new QueueWorker(signer, new DataSourceTransactionManager(ds));
                signerWorker.drain(WalletQueue.SIGN_FIRST, 1, m -> new QueueWorker.Next(WalletQueue.SIGN_SECOND, "{}"));
                assertThrows(org.springframework.dao.DataAccessException.class,
                        () -> signerJdbc.queryForList("select * from ledger_balance"));
            }
            assertEquals(0, count("pgmq.q_wallet_sign_first"));
            assertEquals(1, count("pgmq.q_wallet_sign_second"));
        } finally {
            jdbc.execute("drop owned by " + role);
            jdbc.execute("drop role " + role);
        }
    }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
}
