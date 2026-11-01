package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.model.mpc.MpcKeyEpoch;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.service.MpcSigningService;
import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bitcoinj.crypto.ECKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Test-only local DKG/signature substitute for DB boundary tests. Native probes use actual three-node MPC. */
public final class MpcSigningTestSupport {
    public static final List<String> PARTIES = List.of("11".repeat(32), "22".repeat(32), "33".repeat(32));
    public final MpcSigningService signing;
    public final MpcNodes nodes;
    public final WalletMpcKeyRepository keys;
    public final DataSourceTransactionManager manager;
    private final Map<UUID, ECKey> localKeys = new ConcurrentHashMap<>();
    private final Map<UUID, java.security.KeyPair> localEdKeys = new ConcurrentHashMap<>();
    public final java.util.concurrent.atomic.AtomicBoolean signingPending = new java.util.concurrent.atomic.AtomicBoolean();
    public Runnable beforeSignature = () -> { };

    public MpcSigningTestSupport(JdbcTemplate jdbc) {
        manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        var transaction = new TransactionTemplate(manager);
        var json = new ObjectMapper();
        keys = new WalletMpcKeyRepository(jdbc);
        var epochs = new WalletMpcKeyEpochRepository(jdbc, json);
        var config = new CustodySecurityProperties();
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        config.setSecretMasterKey(HexFormat.of().formatHex(master));
        Arrays.fill(master, (byte) 0);
        var requests = new WalletMpcSignRequestRepository(jdbc);
        var attempts = new WalletMpcSignAttemptRepository(jdbc, json);
        var crypto = new CustodyCryptoService(config);
        signing = spy(new MpcSigningService(new CustodyTenantTableRepository(jdbc), keys, epochs,
                requests, attempts, crypto, json, manager, com.surprising.wallet.testutil.MpcAuthorizationTestSupport.forBoundaryTests(jdbc,json,manager)));
        nodes = mock(MpcNodes.class);
        when(nodes.parties()).thenReturn(PARTIES);
        when(nodes.online()).thenReturn(PARTIES.subList(0, 2));
        when(nodes.clients()).thenReturn(List.of());
        when(nodes.operationTimeout()).thenReturn(Duration.ofSeconds(2));
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "native DKG must execute after commit");
            java.util.UUID tenant = call.getArgument(0), keyId = call.getArgument(1);
            return transaction.execute(status -> {
                var key = keys.lock(tenant, keyId).orElseThrow();
                if (key.state() == MpcKey.State.CREATING) {
                    String publicKey;
                    if (key.algorithm().equals("ECDSA_SECP256K1")) {
                        var local = new ECKey();
                        localKeys.put(keyId, local);
                        publicKey = HexFormat.of().formatHex(local.getPubKey());
                    } else if(key.algorithm().equals("MONERO_NATIVE_2_OF_3")) {
                        try {
                            var generator=KeyPairGenerator.getInstance("Ed25519");
                            byte[] spend=generator.generateKeyPair().getPublic().getEncoded(),view=generator.generateKeyPair().getPublic().getEncoded();
                            publicKey=HexFormat.of().formatHex(Arrays.copyOfRange(spend,spend.length-32,spend.length))
                                    +HexFormat.of().formatHex(Arrays.copyOfRange(view,view.length-32,view.length));
                        } catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException(unavailable);}
                    } else {
                        try {
                            var local = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                            localEdKeys.put(keyId, local);
                            byte[] encoded = local.getPublic().getEncoded();
                            publicKey = HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
                        } catch (java.security.GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
                    }
                    epochs.observations(tenant, keyId, 1, MpcKeyEpoch.State.ACTIVE, Map.of());
                    keys.activate(tenant, keyId, 1, publicKey);
                }
                return keys.find(tenant, keyId).orElseThrow();
            });
        }).when(signing).advanceKey(any(), any(), anyList());
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "signing must execute after commit");
            UUID tenant = call.getArgument(0), id = call.getArgument(1);
            beforeSignature.run();
            if (signingPending.get()) throw new com.surprising.wallet.model.mpc.MpcPendingException(id, "SIGNING");
            return transaction.execute(status -> {
                var request = requests.lock(tenant, id).orElseThrow();
                if (request.state() != MpcSignRequest.State.COMPLETED) {
                    var command = request.command();
                    if(keys.find(tenant,command.keyId()).orElseThrow().algorithm().equals("MONERO_NATIVE_2_OF_3"))
                        throw new UnsupportedOperationException("native Monero payment artifacts require the actual native probe");
                    byte[] signature;
                    if (localEdKeys.containsKey(command.keyId())) {
                        try {
                            var verifier = java.security.Signature.getInstance("Ed25519");
                            verifier.initSign(localEdKeys.get(command.keyId()).getPrivate());
                            verifier.update(HexFormat.of().parseHex(command.preimageHex()));
                            signature = verifier.sign();
                        } catch (java.security.GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
                    } else {
                        var local = Objects.requireNonNull(localKeys.get(command.keyId()), "test-only secp256k1 key missing");
                        signature = local.sign(org.bitcoinj.base.Sha256Hash.wrap(HexFormat.of().parseHex(command.preimageHex()))).encodeToDER();
                    }
                    var bound = Map.of("tenantId", tenant, "requestId", id, "attemptId", request.currentAttemptId(),
                            "keyId", command.keyId(), "epoch", command.keyEpoch(), "publicKeyHex", keys.find(tenant, command.keyId()).orElseThrow().publicKeyHex(),
                            "preimageHex", command.preimageHex(), "signatureHex", HexFormat.of().formatHex(signature),
                            "authenticatedParties", List.of(attempts.find(tenant, request.currentAttemptId()).orElseThrow().request().online().getFirst()));
                    attempts.complete(tenant, request.currentAttemptId(), crypto.encrypt(json.writeValueAsString(bound)), Map.of());
                    // This DB-only fixture substitutes the whole native boundary. Admit its original reservation atomically
                    // with the synthetic selected signature. It is not an independent authorization/native acceptance test.
                    String owner=command.reservationOwner(),chain=command.chain().name();
                    if(command.chain().isEvm()) {
                        var allocations=jdbc.query("select address,pending_nonce from evm_nonce where chain=? and pending_owner=?",
                                (r,n)->Map.entry(r.getString(1),r.getBigDecimal(2).toBigIntegerExact()),chain,owner);
                        var counter=new EvmNonceRepository(jdbc);
                        for(var allocation:allocations)if(!counter.admit(chain,allocation.getKey(),allocation.getValue(),owner))throw new IllegalStateException("fixture nonce admission raced");
                    } else if(java.util.Set.of("APTOS","TON","XRP","NEAR","DOT").contains(chain)) {
                        var allocations=jdbc.query("select address,pending_sequence from account_sequence where chain=? and pending_owner=?",
                                (r,n)->Map.entry(r.getString(1),r.getLong(2)),chain,owner);
                        var counter=new AccountSequenceRepository(jdbc);
                        for(var allocation:allocations)if(!counter.admit(chain,allocation.getKey(),allocation.getValue(),owner))throw new IllegalStateException("fixture sequence admission raced");
                    }
                    requests.recordState(tenant, id, request.currentAttemptId(), MpcSignRequest.State.COMPLETED);
                }
                return signing.signature(tenant, id).orElseThrow();
            });
        }).when(signing).awaitSignature(any(), any(), same(nodes));
    }

    public ChainAddressRecord address(JdbcTemplate jdbc, UUID tenant, ChainType chain, long user, long index, String role) {
        int namespace = new CustodyTenantTableRepository(jdbc).findById(tenant).orElseThrow().derivationNamespace();
        var key = signing.awaitKey(tenant, chain, user, namespace, index, nodes);
        var profile = new ChainProfileRepository(jdbc).findAccountByChain(chain.name()).orElseThrow();
        var address = MpcPublicAddressCodec.encode(profile, HexFormat.of().parseHex(key.publicKeyHex()));
        return new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(key.id())
                .chain(chain.name()).assetSymbol(chain.name()).userId(user).biz(namespace).addressIndex(index)
                .address(address).ownerAddress(address).accountId(address).walletRole(role).derivationPath("mpc:" + key.id()).enabled(true).build());
    }

}
