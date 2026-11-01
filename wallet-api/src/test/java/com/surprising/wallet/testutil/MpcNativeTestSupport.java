package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.MpcNodeProperties;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.service.MpcSigningService;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real pinned-mTLS nodes supplied by with-test-nodes.py; never substitutes signing or DKG. */
public final class MpcNativeTestSupport implements AutoCloseable {
    public final ObjectMapper json = new ObjectMapper();
    public final DataSourceTransactionManager manager;
    public final MpcNodes nodes;
    public final MpcSigningService signing;
    public final CustodyCryptoService crypto;
    public final WalletMpcSignRequestRepository requests;
    public final UUID tenant;
    private final JdbcTemplate jdbc;

    public MpcNativeTestSupport(JdbcTemplate jdbc) throws Exception {
        this.jdbc = jdbc;
        var dataSource = Objects.requireNonNull(jdbc.getDataSource());
        try (var connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:postgresql://127.0.0.1:5432/surprising_wallet_test_")) {
                throw new IllegalArgumentException("MPC acceptance requires an isolated local PostgreSQL 18 test database");
            }
        }
        String directory = System.getenv("SW_TEST_MPC_DIRECTORY");
        if (directory == null || directory.isBlank()) throw new IllegalStateException("Run chain acceptance through resources/infra/mpc-signer/with-test-nodes.py");
        var root = Path.of(directory);
        var config = json.readTree(Files.readAllBytes(root.resolve("java-probe.json")));
        tenant = UUID.fromString(config.path("tenantId").asText());
        var properties = new MpcNodeProperties();
        properties.setCaFile(root.resolve("ca.pem"));
        properties.setCertificateFile(root.resolve("client.pem"));
        properties.setPrivateKeyFile(root.resolve("client.key"));
        var peers = new ArrayList<MpcNodeProperties.Node>();
        for (var peer : config.path("peers")) peers.add(new MpcNodeProperties.Node(peer.path("identity").asText(),
                URI.create("https://localhost:" + peer.path("port").asInt())));
        properties.setNodes(peers);
        nodes = new MpcNodes(properties);
        manager = new DataSourceTransactionManager(dataSource);
        var security = new CustodySecurityProperties();
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        security.setSecretMasterKey(HexFormat.of().formatHex(master));
        Arrays.fill(master, (byte) 0);
        requests = new WalletMpcSignRequestRepository(jdbc);
        crypto = new CustodyCryptoService(security);
        signing = new MpcSigningService(new CustodyTenantTableRepository(jdbc), new WalletMpcKeyRepository(jdbc),
                new WalletMpcKeyEpochRepository(jdbc, json), requests, new WalletMpcSignAttemptRepository(jdbc, json),
                crypto, json, manager, com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,manager));
    }

    public ChainAddressRecord address(ChainType chain, long userId, long index, String role) {
        int namespace = new CustodyTenantTableRepository(jdbc).findById(tenant).orElseThrow().derivationNamespace();
        var key = signing.awaitKey(tenant, chain, userId, namespace, index, nodes);
        var profile = new ChainProfileRepository(jdbc).findAccountByChain(chain.name()).orElseThrow();
        String address = MpcPublicAddressCodec.encode(profile, HexFormat.of().parseHex(key.publicKeyHex()));
        return new ChainAddressRepository(jdbc).publishMpc(ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(key.id())
                .chain(chain.name()).assetSymbol(profile.getNativeSymbol()).userId(userId).biz(namespace).addressIndex(index)
                .address(address).ownerAddress(address).accountId(chain.isEvm() ? address.toLowerCase(Locale.ROOT) : address)
                .walletRole(role).derivationPath("mpc:" + key.id()).enabled(true).build());
    }

    @Override public void close() { nodes.close(); }
}
