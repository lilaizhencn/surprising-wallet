package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.model.mpc.MpcKeyEpoch;
import com.surprising.wallet.model.mpc.MpcSignAttempt;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.WalletMpcKeyEpochRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import com.surprising.wallet.repository.WalletMpcSignAttemptRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.service.MpcSigningService;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PG18 verifies transaction joining, immutable intent, tenant fencing and late-result replay. */
@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class MpcSigningPersistenceIntegrationTest {
    private static final List<String> PARTIES = List.of("11".repeat(32), "22".repeat(32), "33".repeat(32));
    private static final List<String> ONLINE = PARTIES.subList(0, 2);
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private CustodyTenantTableRepository tenants;
    private WalletMpcKeyRepository keys;
    private WalletMpcKeyEpochRepository epochs;
    private WalletMpcSignRequestRepository requests;
    private WalletMpcSignAttemptRepository attempts;
    private LedgerBalanceRepository balances;
    private CustodyCryptoService crypto;
    private MpcSigningService service;
    private UUID tenant;
    private UUID foreign;
    private int namespace;
    private KeyPair fixture;

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        transaction = new TransactionTemplate(manager);
        tenants = new CustodyTenantTableRepository(jdbc);
        keys = new WalletMpcKeyRepository(jdbc);
        epochs = new WalletMpcKeyEpochRepository(jdbc, json);
        requests = new WalletMpcSignRequestRepository(jdbc);
        attempts = new WalletMpcSignAttemptRepository(jdbc, json);
        balances = new LedgerBalanceRepository(jdbc);
        var config = new CustodySecurityProperties();
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        config.setSecretMasterKey(HexFormat.of().formatHex(master));
        Arrays.fill(master, (byte) 0);
        crypto = new CustodyCryptoService(config);
        crypto.validateConfiguration();
        service = new MpcSigningService(tenants, keys, epochs, requests, attempts, crypto, json, manager);
        tenant = UUID.randomUUID();
        foreign = UUID.randomUUID();
        tenants.insert(tenant, "mpc-test-owner", "MPC test owner");
        tenants.insert(foreign, "mpc-test-foreign", "MPC foreign owner");
        namespace = tenants.findById(tenant).orElseThrow().derivationNamespace();
        fixture = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test void tenantCoordinatesAndPublishedPublicKeyCannotBeReplaced() {
        var eth = service.reserveKey(tenant, ChainType.ETH, 7, namespace, 0, PARTIES);
        assertEquals(eth.id(), service.reserveKey(tenant, ChainType.BNB, 7, namespace, 0, PARTIES).id());
        assertThrows(IllegalStateException.class, () -> service.reserveKey(foreign, ChainType.ETH, 7, namespace, 0, PARTIES));
        assertThrows(DataAccessException.class, () -> keys.reserve(UUID.randomUUID(), foreign, "EVM", 7, namespace, 0, "ECDSA_SECP256K1"));
        assertThrows(IllegalStateException.class, () -> service.reserveKey(tenant, ChainType.ETH, 7, namespace, 0,
                List.of(PARTIES.get(1), PARTIES.get(0), PARTIES.get(2))));
        assertTrue(keys.find(foreign, eth.id()).isEmpty());
        assertTrue(epochs.find(foreign, eth.id(), 1).isEmpty());
        assertThrows(IllegalStateException.class, () -> transaction.execute(s -> service.advanceKey(tenant, eth.id(), List.of())));
        assertEquals(1, epochs.observations(tenant, eth.id(), 1, MpcKeyEpoch.State.FAILED, Map.of()));
        assertEquals(1, keys.failCreation(tenant, eth.id()));
        var next = service.reserveKey(tenant, ChainType.ETH, 7, namespace, 0, PARTIES);
        assertNotEquals(eth.id(), next.id());
        assertEquals(MpcKey.State.FAILED, keys.find(tenant, eth.id()).orElseThrow().state());
        var sol = activeKey();
        assertEquals(sol.id(), service.reserveKey(tenant, ChainType.SOLANA, 8, namespace, 0, PARTIES).id());
        assertEquals(0, keys.failCreation(tenant, sol.id()));
        assertEquals(0, keys.activate(tenant, sol.id(), 1, "00".repeat(32)));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_key set public_key_hex=? where id=?", "00".repeat(32), sol.id()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_key set tenant_id=? where id=?", foreign, sol.id()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_key_epoch set request=jsonb_set(request,'{sessionId}',to_jsonb(?::text)) where key_id=?",
                UUID.randomUUID().toString(), sol.id()));
    }

    @Test void businessFreezeAndIntentAndAttemptCommitOrRollbackTogether() {
        var key = activeKey();
        var command = command(key, "rollback");
        balances.increment(tenant, "SOLANA", "SOL", "mpc-ledger-account", new BigDecimal("100"));
        assertThrows(IllegalStateException.class, () -> service.reserveSignature(command, ONLINE));
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(status -> {
            assertTrue(balances.freeze("SOLANA", "SOL", "mpc-ledger-account", BigDecimal.TEN, tenant));
            var request = service.reserveSignature(command, ONLINE);
            assertNotNull(request.currentAttemptId());
            throw new IllegalArgumentException("caller workflow failed");
        }));
        assertEquals(0, count("wallet_mpc_sign_request"));
        assertEquals(0, count("wallet_mpc_sign_attempt"));
        var balance = balances.listOwned(tenant).getFirst();
        assertEquals(0, new BigDecimal("100").compareTo(balance.getAvailableBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance.getLockedBalance()));
        var committed = transaction.execute(status -> {
            assertTrue(balances.freeze("SOLANA", "SOL", "mpc-ledger-account", BigDecimal.TEN, tenant));
            return service.reserveSignature(command, ONLINE);
        });
        assertNotNull(committed.currentAttemptId());
        assertEquals(1, count("wallet_mpc_sign_request"));
        assertEquals(1, count("wallet_mpc_sign_attempt"));
        assertEquals(0, BigDecimal.TEN.compareTo(balances.listOwned(tenant).getFirst().getLockedBalance()));
        assertThrows(IllegalStateException.class, () -> transaction.execute(s ->
                service.advanceSignature(tenant, committed.id(), List.of())));
    }

    @Test void concurrentReservationCreatesExactlyOneImmutableBusinessSlotAndAttempt() throws Exception {
        var command = command(activeKey(), "concurrent");
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = IntStream.range(0, 18).mapToObj(i -> executor.submit(() ->
                    transaction.execute(s -> service.reserveSignature(command, ONLINE)))).toList();
            UUID id = futures.getFirst().get(20, TimeUnit.SECONDS).id();
            for (var future : futures) assertEquals(id, future.get(20, TimeUnit.SECONDS).id());
        }
        assertEquals(1, count("wallet_mpc_sign_request"));
        assertEquals(1, count("wallet_mpc_sign_attempt"));
        var original = requests.pending(10).getFirst();
        var changed = new MpcSignCommand(tenant, command.keyId(), 1, ChainType.SOLANA, command.businessType(),
                command.businessReference(), command.signingSlot(), command.purpose(), command.payloadEncoding(), "abcd", command.preimageHex());
        assertThrows(IllegalStateException.class, () -> transaction.execute(s -> service.reserveSignature(changed, ONLINE)));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_sign_request set payload_hex='00' where id=?", original.id()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_sign_attempt set request=jsonb_set(request,'{online}',?::jsonb) where id=?",
                json.writeValueAsString(List.of(PARTIES.get(0), PARTIES.get(2))), original.currentAttemptId()));
        assertEquals(command, requests.find(tenant, original.id()).orElseThrow().command());
        assertTrue(requests.find(foreign, original.id()).isEmpty());
        assertTrue(attempts.find(foreign, original.currentAttemptId()).isEmpty());
        assertThrows(Exception.class, () -> service.signature(foreign, original.id()));
    }

    @Test void databaseFencesTenantAlgorithmMessageAndNullableAuditFields() {
        var key = activeKey();
        var request = reserve(command(key, "fences"));
        assertEquals(1, attempts.observe(tenant, request.currentAttemptId(), MpcSignAttempt.State.INDETERMINATE, Map.of()));
        for (var nativeRequest : List.of(
                nativeRequest(foreign, key.id(), MpcClient.Algorithm.ED25519, request.command().preimageHex()),
                nativeRequest(tenant, key.id(), MpcClient.Algorithm.ECDSA_SECP256K1, request.command().preimageHex()),
                nativeRequest(tenant, key.id(), MpcClient.Algorithm.ED25519, "01".repeat(32)))) {
            assertThrows(DataAccessException.class, () -> attempts.create(request.id(), nativeRequest, null, null));
        }
        var valid = nativeRequest(tenant, key.id(), MpcClient.Algorithm.ED25519, request.command().preimageHex());
        assertThrows(DataAccessException.class, () -> attempts.create(request.id(), valid, "operator", null));
        assertThrows(DataAccessException.class, () -> attempts.create(request.id(), valid, null, "manual retry"));
        var withNull = new java.util.LinkedHashMap<>(json.readValue(json.writeValueAsString(valid), Map.class));
        withNull.put("online", Arrays.asList(PARTIES.get(0), null));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into wallet_mpc_sign_attempt(request_id,request) values (?,?::jsonb)", request.id(), json.writeValueAsString(withNull)));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_sign_request set current_attempt_id=null,state='SIGNING' where id=?", request.id()));
        assertEquals(1, count("wallet_mpc_sign_attempt"));
    }

    @Test void explicitReplayKeepsOriginalIntentAndHistoryWithoutReleasingFunds() {
        var key = activeKey();
        var request = reserve(command(key, "replay"));
        assertThrows(IllegalStateException.class, () -> service.replay(tenant, request.id(), ONLINE, "operator", "manual retry"));
        assertEquals(1, attempts.observe(tenant, request.currentAttemptId(), MpcSignAttempt.State.INDETERMINATE, Map.of()));
        assertEquals(1, requests.recordState(tenant, request.id(), request.currentAttemptId(), MpcSignRequest.State.INDETERMINATE));
        assertEquals(0, attempts.observe(tenant, request.currentAttemptId(), MpcSignAttempt.State.RUNNING, Map.of()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_sign_attempt set state='RUNNING' where id=?", request.currentAttemptId()));
        assertThrows(IllegalStateException.class, () -> service.replay(tenant, request.id(), ONLINE, "operator", ""));
        var next = service.replay(tenant, request.id(), List.of(PARTIES.get(0), PARTIES.get(2)), "operator-7", "reconcile interrupted node");
        assertNotEquals(request.currentAttemptId(), next.currentAttemptId());
        assertEquals(request.command(), next.command());
        var history = attempts.history(tenant, request.id());
        assertEquals(2, history.size());
        var replay = attempts.find(tenant, next.currentAttemptId()).orElseThrow();
        assertEquals("operator-7", replay.replayActor());
        assertEquals("reconcile interrupted node", replay.replayReason());
        assertEquals(MpcSignAttempt.State.INDETERMINATE, attempts.find(tenant, request.currentAttemptId()).orElseThrow().state());
        assertTrue(service.signature(tenant, request.id()).isEmpty());
        assertThrows(IllegalStateException.class, () -> service.replay(tenant, request.id(), ONLINE, "operator", "manual retry"));
    }

    @Test void encryptedResultBindsOwnershipAndCompletedSelectionAndIsIndependentlyVerified() throws Exception {
        var key = activeKey();
        var first = reserve(command(key, "result-1"));
        var second = reserve(command(key, "result-2"));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(fixture.getPrivate());
        signer.update(HexFormat.of().parseHex(first.command().preimageHex()));
        byte[] signature = signer.sign();
        var bound = Map.of("tenantId", tenant, "requestId", first.id(), "attemptId", first.currentAttemptId(),
                "keyId", key.id(), "epoch", 1, "publicKeyHex", key.publicKeyHex(),
                "preimageHex", first.command().preimageHex(), "signatureHex", HexFormat.of().formatHex(signature),
                "authenticatedParties", List.of(ONLINE.getFirst()));
        String ciphertext = crypto.encrypt(json.writeValueAsString(bound));
        complete(first, ciphertext);
        assertArrayEquals(signature, service.signature(tenant, first.id()).orElseThrow());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_sign_attempt set signature_ciphertext=? where id=?", crypto.encrypt("swapped"), first.currentAttemptId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update wallet_mpc_sign_request set state='SIGNING' where id=?", first.id()));
        complete(second, ciphertext);
        assertThrows(IllegalStateException.class, () -> service.signature(tenant, second.id()));
        var third = reserve(command(key, "invalid-signature"));
        var invalid = new java.util.LinkedHashMap<String, Object>(bound);
        invalid.put("requestId", third.id());
        invalid.put("attemptId", third.currentAttemptId());
        invalid.put("signatureHex", "00".repeat(64));
        complete(third, crypto.encrypt(json.writeValueAsString(invalid)));
        assertThrows(IllegalStateException.class, () -> service.signature(tenant, third.id()));
        var fourth = reserve(command(key, "invalid-attestation"));
        var badAttestation = new java.util.LinkedHashMap<String, Object>(bound);
        badAttestation.put("requestId", fourth.id());
        badAttestation.put("attemptId", fourth.currentAttemptId());
        badAttestation.put("preimageHex", fourth.command().preimageHex());
        badAttestation.put("authenticatedParties", ONLINE);
        complete(fourth, crypto.encrypt(json.writeValueAsString(badAttestation)));
        assertTrue(assertThrows(IllegalStateException.class, () -> service.signature(tenant, fourth.id()))
                .getMessage().contains("attestation"));
        assertArrayEquals(signature, service.signature(tenant, first.id()).orElseThrow());
    }

    @Test void nativeMoneroWireMetadataPersistsAndUnknownSigningCannotCreateReplacementInputs() {
        // Database contract only; actual native key/sign/relay/observation is tested by the independent-node fixture.
        String algorithm = MpcClient.Algorithm.MONERO_NATIVE_2_OF_3.name();
        var root = keys.reserve(UUID.randomUUID(), tenant, "XMR_ROOT", 0, namespace, 0, algorithm);
        String genesis = "aa".repeat(32);
        String rootPolicy = HexFormat.of().formatHex(json.writeValueAsBytes(Map.of("kind", "ROOT", "network", "regtest", "genesisHash", genesis)));
        var rootRequest = new MpcClient.Request(UUID.randomUUID(), tenant, root.id(), 1, MpcClient.Operation.CREATE_KEY,
                MpcClient.Algorithm.MONERO_NATIVE_2_OF_3, PARTIES, rootPolicy);
        assertEquals(rootRequest, epochs.create(rootRequest).request());
        assertThrows(DataAccessException.class, () -> keys.reserve(UUID.randomUUID(), tenant, "XMR_ROOT", 0, namespace, 1, "MONERO_NATIVE"));
        byte[] encoded = fixture.getPublic().getEncoded();
        String publicKey = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length)).repeat(2);
        assertEquals(1, epochs.observations(tenant, root.id(), 1, MpcKeyEpoch.State.ACTIVE, Map.of()));
        assertEquals(1, keys.activate(tenant, root.id(), 1, publicKey));
        var child = keys.reserve(UUID.randomUUID(), tenant, "XMR", 9, namespace, 0, algorithm);
        String childPolicy = HexFormat.of().formatHex(json.writeValueAsBytes(Map.of("kind", "ADDRESS", "network", "regtest",
                "genesisHash", genesis, "rootKeyId", root.id())));
        var childRequest = new MpcClient.Request(UUID.randomUUID(), tenant, child.id(), 1, MpcClient.Operation.CREATE_KEY,
                MpcClient.Algorithm.MONERO_NATIVE_2_OF_3, PARTIES, childPolicy);
        assertEquals(childRequest, epochs.create(childRequest).request());
        assertEquals(1, epochs.observations(tenant, child.id(), 1, MpcKeyEpoch.State.ACTIVE, Map.of()));
        assertEquals(1, keys.activate(tenant, child.id(), 1, publicKey));
        String policy = HexFormat.of().formatHex(json.writeValueAsBytes(Map.of("kind", "PAYMENT", "network", "regtest", "genesisHash", genesis)));
        var command = new MpcSignCommand(tenant, child.id(), 1, ChainType.XMR, MpcSignCommand.BusinessType.WITHDRAWAL,
                "native-original-inputs", "native-payment", MpcSignCommand.Purpose.TRANSACTION, "MONERO_NATIVE_POLICY_JSON", policy, policy);
        var request = reserve(command);
        assertEquals(policy, attempts.find(tenant, request.currentAttemptId()).orElseThrow().request().messageHex());
        transaction.executeWithoutResult(status -> {
            attempts.observe(tenant, request.currentAttemptId(), MpcSignAttempt.State.INDETERMINATE, Map.of());
            requests.recordState(tenant, request.id(), request.currentAttemptId(), MpcSignRequest.State.INDETERMINATE);
        });
        assertThrows(IllegalStateException.class, () -> service.replay(tenant, request.id(), PARTIES.subList(1, 3), "operator", "reconcile original Monero transaction"));
        assertEquals(1, attempts.history(tenant, request.id()).size());
        assertEquals(request.currentAttemptId(), requests.find(tenant, request.id()).orElseThrow().currentAttemptId());
    }

    private MpcKey activeKey() {
        var key = service.reserveKey(tenant, ChainType.SOLANA, 8, namespace, 0, PARTIES);
        byte[] encoded = fixture.getPublic().getEncoded();
        String publicKey = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
        transaction.executeWithoutResult(s -> {
            assertEquals(1, epochs.observations(tenant, key.id(), 1, MpcKeyEpoch.State.ACTIVE, Map.of()));
            assertEquals(1, keys.activate(tenant, key.id(), 1, publicKey));
        });
        return keys.find(tenant, key.id()).orElseThrow();
    }

    @Test void refreshReservationFreezesTheKeyAndPersistsOriginalOperatorAudit() {
        var key = activeKey();
        var reserved = service.reserveRefresh(tenant, key.id(), 1, PARTIES, "operator", "refresh owned test shares");
        assertEquals(MpcKey.State.REFRESHING, reserved.state());
        assertEquals(1, reserved.activeEpoch());
        var original = epochs.find(tenant, key.id(), 2).orElseThrow();
        assertEquals(MpcClient.Operation.REFRESH, original.request().operation());
        assertEquals("operator", original.refreshActor());
        assertEquals("refresh owned test shares", original.refreshReason());
        service.reserveRefresh(tenant, key.id(), 1, PARTIES, "operator", "refresh owned test shares");
        assertEquals(original.request(), epochs.find(tenant, key.id(), 2).orElseThrow().request());
        assertThrows(IllegalStateException.class, () -> reserve(command(key, "blocked-during-refresh")));
        assertThrows(IllegalStateException.class, () -> transaction.execute(s -> service.lockActiveKey(tenant, key.id(), ChainType.SOLANA)));
        assertThrows(IllegalStateException.class, () -> service.reserveRefresh(tenant, key.id(), 1, PARTIES, "another", "changed audit reason"));
        assertTrue(epochs.find(foreign, key.id(), 2).isEmpty());
        assertThrows(DataAccessException.class, () -> jdbc.update("update wallet_mpc_key_epoch set refresh_reason='replace audit' where key_id=? and epoch=2", key.id()));
        assertThrows(IllegalStateException.class, () -> transaction.execute(s -> service.advanceRefresh(tenant, key.id(), 2, List.of())));
        assertEquals(0, count("wallet_mpc_sign_request"));
    }

    @Test void unresolvedSigningOrChangedHolderOrderCannotStartRefresh() {
        var key = activeKey();
        assertThrows(IllegalStateException.class, () -> service.reserveRefresh(tenant, key.id(), 1, PARTIES, "", "refresh test shares"));
        assertThrows(IllegalStateException.class, () -> service.reserveRefresh(tenant, key.id(), 2, PARTIES, "operator", "refresh test shares"));
        assertThrows(IllegalStateException.class, () -> service.reserveRefresh(tenant, key.id(), 1,
                List.of(PARTIES.get(1), PARTIES.get(0), PARTIES.get(2)), "operator", "refresh test shares"));
        var pending = reserve(command(key, "unresolved-before-refresh"));
        assertThrows(IllegalStateException.class, () -> service.reserveRefresh(tenant, key.id(), 1, PARTIES, "operator", "refresh test shares"));
        assertTrue(epochs.find(tenant, key.id(), 2).isEmpty());
        assertEquals(MpcKey.State.ACTIVE, keys.find(tenant, key.id()).orElseThrow().state());
        assertEquals(pending.currentAttemptId(), requests.find(tenant, pending.id()).orElseThrow().currentAttemptId());
        assertThrows(DataAccessException.class, () -> epochs.create(new MpcClient.Request(UUID.randomUUID(), tenant, key.id(), 2,
                MpcClient.Operation.REFRESH, MpcClient.Algorithm.ED25519, PARTIES, "")));
    }

    @Test void refreshedEpochActivatesOnlyAfterAllThreeAttestTheOriginalPublicKey() {
        var key = activeKey();
        service.reserveRefresh(tenant, key.id(), 1, PARTIES, "operator", "refresh owned test shares");
        var request = epochs.find(tenant, key.id(), 2).orElseThrow().request();
        var clients = PARTIES.stream().map(party -> {
            var client = mock(MpcClient.class);
            when(client.node()).thenReturn(new MpcClient.Node(party, java.net.URI.create("https://localhost:1")));
            when(client.result(request)).thenAnswer(call -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                return refreshResult(request, party.equals(PARTIES.get(2)) ? MpcClient.State.BUSY : MpcClient.State.COMPLETED, key.publicKeyHex());
            });
            return client;
        }).toList();
        assertEquals(MpcKey.State.REFRESHING, service.advanceRefresh(tenant, key.id(), 2, clients).state());
        assertEquals(MpcKeyEpoch.State.ACTIVE, epochs.find(tenant, key.id(), 1).orElseThrow().state());
        when(clients.get(2).result(request)).thenReturn(refreshResult(request, MpcClient.State.COMPLETED, "00".repeat(32)));
        assertEquals(MpcKey.State.REFRESHING, service.advanceRefresh(tenant, key.id(), 2, clients).state());
        when(clients.get(2).result(request)).thenReturn(refreshResult(request, MpcClient.State.COMPLETED, key.publicKeyHex()));
        var activated = service.advanceRefresh(tenant, key.id(), 2, clients);
        assertEquals(MpcKey.State.ACTIVE, activated.state());
        assertEquals(2, activated.activeEpoch());
        assertEquals(key.publicKeyHex(), activated.publicKeyHex());
        assertEquals(MpcKeyEpoch.State.RETIRED, epochs.find(tenant, key.id(), 1).orElseThrow().state());
        assertEquals(MpcKeyEpoch.State.ACTIVE, epochs.find(tenant, key.id(), 2).orElseThrow().state());
        assertEquals(activated, service.advanceRefresh(tenant, key.id(), 2, List.of()));
        assertEquals(activated, service.reserveRefresh(tenant, key.id(), 1, PARTIES, "operator", "refresh owned test shares"));
        assertTrue(epochs.find(tenant, key.id(), 3).isEmpty());
        assertThrows(DataAccessException.class, () -> jdbc.update("update wallet_mpc_key_epoch set state='ACTIVE' where key_id=? and epoch=1", key.id()));
        assertThrows(IllegalStateException.class, () -> reserve(command(key, "retired-version")));
    }

    private MpcClient.Result refreshResult(MpcClient.Request request, MpcClient.State state, String publicKey) {
        return new MpcClient.Result(state, request.sessionId(), request.tenantId(), request.keyId(), request.epoch(),
                "11".repeat(32), publicKey, null, null, null);
    }

    private MpcSignCommand command(MpcKey key, String reference) {
        return new MpcSignCommand(tenant, key.id(), 1, ChainType.SOLANA, MpcSignCommand.BusinessType.WITHDRAWAL,
                reference, "sender", MpcSignCommand.Purpose.TRANSACTION, "SOLANA_MESSAGE", "abcdef", "42".repeat(32));
    }

    private MpcSignRequest reserve(MpcSignCommand command) {
        return transaction.execute(s -> service.reserveSignature(command, ONLINE));
    }

    private MpcClient.Request nativeRequest(UUID owner, UUID key, MpcClient.Algorithm algorithm, String message) {
        return new MpcClient.Request(UUID.randomUUID(), owner, key, 1, MpcClient.Operation.SIGN, algorithm, ONLINE, message);
    }

    private void complete(MpcSignRequest request, String ciphertext) {
        transaction.executeWithoutResult(s -> {
            assertEquals(1, attempts.complete(tenant, request.currentAttemptId(), ciphertext, Map.of()));
            assertEquals(1, requests.recordState(tenant, request.id(), request.currentAttemptId(), MpcSignRequest.State.COMPLETED));
        });
    }

    private int count(String table) {
        // Test-only table names; production SQL remains in the corresponding single-table Repository.
        assertTrue(List.of("wallet_mpc_sign_request", "wallet_mpc_sign_attempt").contains(table));
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
