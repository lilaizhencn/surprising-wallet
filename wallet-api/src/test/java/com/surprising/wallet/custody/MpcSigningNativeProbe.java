package com.surprising.wallet.custody;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.model.mpc.MpcNodeProperties;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.ChainScanHeightRepository;
import com.surprising.wallet.repository.ChainScanBlockRepository;
import com.surprising.wallet.repository.WalletMpcKeyEpochRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import com.surprising.wallet.repository.WalletMpcSignAttemptRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.service.MpcSigningService;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Actual custody/EVM business integration and crypto components with real native nodes and guarded PG18. */
public final class MpcSigningNativeProbe {
    private static final long EVM_CHAIN_ID = 31337;
    private static final BigInteger EVM_PRICE = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger EVM_GAS = BigInteger.valueOf(21000);
    private static final String EVM_TO = "0x" + "ab".repeat(20);
    private MpcSigningNativeProbe() { }

    public static void main(String[] arguments) throws Exception {
        var json = new ObjectMapper();
        var root = Path.of(arguments[0]);
        var config = json.readTree(Files.readAllBytes(root.resolve("java-probe.json")));
        String evmRpc = config.path("evmRpcUrl").asText();
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        var jdbc = new JdbcTemplate(ds);
        for (var stage : com.surprising.wallet.common.queue.WalletQueue.values()) {
            jdbc.execute("truncate pgmq.q_" + stage.queueName() + ", pgmq.a_" + stage.queueName()
                    + ", pgmq.q_" + stage.queueName() + "_dead, pgmq.a_" + stage.queueName() + "_dead");
        }
        var manager = new DataSourceTransactionManager(ds);
        var transaction = new TransactionTemplate(manager);
        var tenants = new CustodyTenantTableRepository(jdbc);
        var keys = new WalletMpcKeyRepository(jdbc);
        var epochs = new WalletMpcKeyEpochRepository(jdbc, json);
        var requests = new WalletMpcSignRequestRepository(jdbc);
        var attempts = new WalletMpcSignAttemptRepository(jdbc, json);
        var security = new CustodySecurityProperties();
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        security.setSecretMasterKey(HexFormat.of().formatHex(master));
        java.util.Arrays.fill(master, (byte) 0);
        var crypto = new CustodyCryptoService(security);
        crypto.validateConfiguration();
        var service = new MpcSigningService(tenants, keys, epochs, requests, attempts, crypto, json, manager);
        UUID tenant = UUID.fromString(config.path("tenantId").asText());
        tenants.insert(tenant, "native-mpc-probe", "Isolated native MPC probe");
        int namespace = tenants.findById(tenant).orElseThrow().derivationNamespace();
        try (var nodes = new MpcNodes(nodeProperties(root, config))) {
            var clients = nodes.clients();
            verifyAddressPublication(jdbc, service, nodes, tenant, root, config, manager);
            if(config.hasNonNull("tonRpcUrl")) {
                MpcTonNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                MpcTonJettonNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                MpcTonBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                Files.writeString(root.resolve("ton-business-passed"),"AB,AC,BC TON/USDT/USDC production deposits, withdrawals, lost replies, collection, skipped send and Jetton source abort refunds, exact net expense");
                Files.writeString(root.resolve("ton-codec-passed"),"AB,AC,BC: V4R2 mode=3, initial StateInit, native TON, masterchain recipient, skipped-send fee and replay");
                Files.writeString(root.resolve("ton-jetton-codec-passed"),"AB,AC,BC: USDT,USDC, actual TEP-74 debit/credit, raw trace, replay");
            }
            MpcEvmBusinessNativeProbe.verify(jdbc, manager, service, nodes, tenant, evmRpc);
            if (java.util.stream.Stream.of("btc", "ltc", "doge", "bch").anyMatch(chain -> config.hasNonNull(chain + "RpcUrl"))) MpcUtxoNativeProbe.verify(jdbc, transaction, service, nodes, tenant, config);
            if(config.hasNonNull("tronHttpUrl")) com.surprising.wallet.service.MpcTronBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
            if(config.hasNonNull("xrpRpcUrl")) {
                MpcXrpBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                Files.writeString(root.resolve("xrp-business-passed"),"AB,AC,BC: XRP,USDT,USDC; tenant activation,TrustSet");
            }
            if(config.hasNonNull("hypercoreTestnetUrl")) {
                MpcHyperCoreAuthenticationNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                Files.writeString(root.resolve("hypercore-auth-passed"),"official testnet owner recovery AB,AC,BC; unfunded actions remain unknown");
            }
            if(config.hasNonNull("aptosRpcUrl")) {
                MpcAptosNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                if(config.hasNonNull("aptosFaFixture")) {
                    MpcAptosBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                    Files.writeString(root.resolve("aptos-business-passed"),"AB,AC,BC APT/USDT/USDC, real deposits, collection, lost replies, committed FA failure refund, actual net fees");
                }
                Files.writeString(root.resolve("aptos-codec-passed"),"AB,AC,BC native APT; BCS hash, exact recipient and fee, identical replay. Business/FA not covered.");
            }
            if(config.hasNonNull("nearRpcUrl")) {
                MpcNearNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                MpcNearBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                Files.writeString(root.resolve("near-business-passed"),"AB,AC,BC NEAR/USDT/USDC; one-yocto deposit/payment; exact tenant principal; durable nonce/wire; lost replies; disabled-gate recovery; collection; actual transfer_call refund; final failures and precise fees");
                Files.writeString(root.resolve("near-codec-passed"),"AB,AC,BC NEAR/USDT/USDC Borsh, smallest native unit, finality, three committed failures, physical fee and one-yocto retention/refund, identical raw replay");
            }
            if(config.hasNonNull("dotFixture")) {
                MpcPolkadotNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                MpcPolkadotBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                Files.writeString(root.resolve("dot-business-passed"),"AB,AC,BC DOT/USDT/USDC; real deposits, smallest-unit native payment, durable per-role nonce/wire, lost replies, disabled-gate recovery, keep-alive collection, owned MPC Gas funding, committed native/asset dispatch failures and actual fee reconciliation");
                Files.writeString(root.resolve("dot-codec-passed"),"AB,AC,BC relay native plus AssetHub native/USDT/USDC; external Ed25519 SCALE V4; actual final event principal/fee and raw replay. Formal business not covered.");
            }
            if (config.hasNonNull("cardanoFixture")) {
                MpcCardanoNativeProbe.verify(jdbc, manager, service, nodes, tenant, config);
                MpcCardanoBusinessNativeProbe.verify(jdbc, manager, service, nodes, tenant, config);
                Files.writeString(root.resolve("cardano-business-passed"), "AB,AC,BC ADA/USDT/USDC production custody deposits, immutable native submissions, reply-loss recovery with new work disabled, owned recipient credit, actual MPC Gas supplementation, complete TTL/unspent zero-fee refund, three collections and exact physical fee conservation");
                Files.writeString(root.resolve("cardano-codec-passed"), "AB,AC,BC ADA/USDT/USDC native-node consensus; fixed CBOR, CLI hash, external witness, exact principal, multiasset change, minimum ADA, actual fee and same-wire replay; real expired submission rejected, complete canonical search and original inputs still unspent, proven zero fee. Formal custody acceptance has its own mandatory marker.");
            }
            if(config.hasNonNull("suiRpcUrl")) {
                MpcSuiNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                if(config.hasNonNull("suiCoinFixture")) {
                    MpcSuiBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                    Files.writeString(root.resolve("sui-business-passed"),"AB,AC,BC SUI/USDT/USDC deposits/replay, durable objects/wire, lost replies, disabled-gate recovery, principal, collection, committed out-of-gas refund, signed storage expense");
                }
                Files.writeString(root.resolve("sui-codec-passed"),"AB,AC,BC native SUI fixed objects/PTB, exact principal and signed net Gas, same-wire replay; business and token not covered");
            }
            if(config.hasNonNull("solanaRpcUrl")) {
                MpcSolanaNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                MpcSolanaBusinessNativeProbe.verify(jdbc,manager,service,nodes,tenant,config);
                Files.writeString(root.resolve("solana-business-passed"),"AB,AC,BC SOL,USDT,USDC; joint Gas payer, deposit replay, raw recovery, collection, actual failure refund");
                Files.writeString(root.resolve("solana-codec-passed"),"AB,AC,BC: native SOL consensus, exact raw, duplicate replay, distinct memo");
            }
            var parties = clients.stream().map(client -> client.node().partyId()).toList();
            var results = new ArrayList<Map<String, String>>();
            for (var chain : List.of(ChainType.ETH, ChainType.SOLANA)) {
                var reserved = service.reserveKey(tenant, chain, 17, namespace, 0, parties);
                MpcKey key = reserved;
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (key.state() == MpcKey.State.CREATING && System.nanoTime() < deadline) {
                    key = service.advanceKey(tenant, key.id(), clients);
                    Thread.sleep(10);
                }
                require(key.state() == MpcKey.State.ACTIVE, "Wallet DKG did not durably activate");
                for (var target : ChainType.values()) {
                    if (target == ChainType.XMR || MpcSigningService.algorithm(target) != MpcSigningService.algorithm(chain)) continue;
                    var profile = AccountChainProfile.builder().chain(target.name()).network("testnet").build();
                    String address = MpcPublicAddressCodec.encode(profile, HexFormat.of().parseHex(key.publicKeyHex()));
                    require(!address.isBlank(), "Joint public key could not encode a chain address");
                    require(address.equals(MpcPublicAddressCodec.encode(profile, HexFormat.of().parseHex(key.publicKeyHex()))),
                            "Joint public key address is unstable");
                }
                require(key.id().equals(service.reserveKey(tenant, chain, 17, namespace, 0, parties).id()), "DKG retry changed key identity");
                if (chain == ChainType.ETH) require(key.id().equals(
                        service.reserveKey(tenant, ChainType.BNB, 17, namespace, 0, parties).id()), "EVM key group differs");
                int evmNonce = 0;
                for (var pair : List.of(List.of(0, 1), List.of(0, 2), List.of(1, 2))) {
                    var online = pair.stream().map(clients::get).map(client -> client.node().partyId()).toList();
                    org.web3j.crypto.RawTransaction evmTransaction = null;
                    if (chain == ChainType.ETH) {
                        BigInteger nonce = BigInteger.valueOf(evmNonce++);
                        evmTransaction = switch (nonce.intValueExact()) {
                            case 0 -> org.web3j.crypto.RawTransaction.createEtherTransaction(nonce, EVM_PRICE, EVM_GAS, EVM_TO, BigInteger.ONE);
                            case 1 -> org.web3j.crypto.RawTransaction.createTransaction(EVM_CHAIN_ID, nonce, EVM_PRICE, EVM_GAS,
                                    EVM_TO, BigInteger.ONE, "", List.of());
                            default -> org.web3j.crypto.RawTransaction.createEtherTransaction(EVM_CHAIN_ID, nonce, EVM_GAS,
                                    EVM_TO, BigInteger.ONE, EVM_PRICE, EVM_PRICE);
                        };
                    }
                    byte[] message = new byte[chain == ChainType.ETH ? 32 : 73];
                    new SecureRandom().nextBytes(message);
                    if (evmTransaction != null) message = MpcEvmTransactionCodec.digest(evmTransaction, EVM_CHAIN_ID);
                    String messageHex = HexFormat.of().formatHex(message);
                    String payloadHex = evmTransaction == null ? messageHex
                            : HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(evmTransaction, EVM_CHAIN_ID));
                    var command = new MpcSignCommand(tenant, key.id(), 1, chain, MpcSignCommand.BusinessType.WITHDRAWAL,
                            "component-" + chain.name() + pair, "sender", MpcSignCommand.Purpose.TRANSACTION,
                            evmTransaction == null ? "COMPONENT_FIXTURE" : "EVM_RLP_UNSIGNED", payloadHex, messageHex);
                    var request = transaction.execute(s -> service.reserveSignature(command, online));
                    require(request != null && request.currentAttemptId() != null, "Wallet intent did not commit");
                    require(service.signature(tenant, request.id()).isEmpty(), "Unfinished result became usable");
                    // Native result can already exist before the wallet sees its response.
                    var attempt = attempts.find(tenant, request.currentAttemptId()).orElseThrow();
                    if (pair.equals(List.of(0, 2))) for (var index : pair) clients.get(index).submit(attempt.request());
                    deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    while (request.state() == MpcSignRequest.State.SIGNING && System.nanoTime() < deadline) {
                        request = service.advanceSignature(tenant, request.id(), clients);
                        Thread.sleep(10);
                    }
                    require(request.state() == MpcSignRequest.State.COMPLETED, "Wallet native signature did not complete");
                    var durable = transaction.execute(s -> service.reserveSignature(command, online));
                    require(request.id().equals(durable.id()) && request.currentAttemptId().equals(durable.currentAttemptId()), "Duplicate intent added an attempt");
                    require(attempts.history(tenant, request.id()).size() == 1, "Duplicate native call changed attempt history");
                    require(request.equals(service.advanceSignature(tenant, request.id(), List.of())), "Completed intent called native nodes again");
                    // A recreated application object loads only committed metadata and ciphertext.
                    var restarted = new MpcSigningService(tenants, new WalletMpcKeyRepository(jdbc),
                            new WalletMpcKeyEpochRepository(jdbc, json), new WalletMpcSignRequestRepository(jdbc),
                            new WalletMpcSignAttemptRepository(jdbc, json), new CustodyCryptoService(security), json, manager);
                    byte[] signature = restarted.signature(tenant, request.id()).orElseThrow();
                    var signedFact = attempts.find(tenant, request.currentAttemptId()).orElseThrow();
                    require(signedFact.signatureCiphertext().startsWith("v1:"), "Wallet result is not encrypted");
                    String serializedStates = json.writeValueAsString(signedFact.nodeResults());
                    require(!serializedStates.contains(HexFormat.of().formatHex(signature)), "Wallet observations leaked usable signature");
                    if (evmTransaction != null) {
                        require(payloadHex.equals(request.command().payloadHex()), "Persisted unsigned EVM intent differs");
                        byte[] signedBytes = MpcEvmTransactionCodec.assemble(HexFormat.of().parseHex(request.command().payloadHex()), EVM_CHAIN_ID,
                                HexFormat.of().parseHex(key.publicKeyHex()), signature);
                        String sender = MpcPublicAddressCodec.encode(AccountChainProfile.builder().chain("ETH").network("devtest").build(),
                                HexFormat.of().parseHex(key.publicKeyHex()));
                        verifyEvmTransfer(evmRpc, sender, signedBytes, evmTransaction.getValue());
                    }
                    results.add(Map.of("algorithm", MpcSigningService.algorithm(chain).name(), "publicKeyHex", key.publicKeyHex(),
                            "messageHex", messageHex, "signatureHex", HexFormat.of().formatHex(signature)));
                }
                if (chain == ChainType.SOLANA) {
                    String messageHex = "45".repeat(73);
                    var command = new MpcSignCommand(tenant, key.id(), 1, chain, MpcSignCommand.BusinessType.WITHDRAWAL,
                            "component-network-recovery", "sender", MpcSignCommand.Purpose.TRANSACTION,
                            "COMPONENT_FIXTURE", messageHex, messageHex);
                    var request = transaction.execute(s -> service.reserveSignature(command, parties.subList(0, 2)));
                    UUID originalAttempt = request.currentAttemptId();
                    try (var offlineFirst = unreachable(root, clients.get(0));
                         var offlineSecond = unreachable(root, clients.get(1))) {
                        var broken = List.of(offlineFirst, clients.get(1), clients.get(2));
                        deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
                        while (request.state() == MpcSignRequest.State.SIGNING && System.nanoTime() < deadline) {
                            request = service.advanceSignature(tenant, request.id(), broken);
                            require(service.signature(tenant, request.id()).isEmpty(), "Interrupted quorum exposed a signature");
                            Thread.sleep(20);
                        }
                        require(request.state() == MpcSignRequest.State.INDETERMINATE, "Interrupted quorum was not fenced");
                        request = service.advanceSignature(tenant, request.id(), List.of(offlineFirst, offlineSecond, clients.get(2)));
                        require(request.state() == MpcSignRequest.State.INDETERMINATE, "Loss of all observations rewound unknown state");
                        require(attempts.find(tenant, originalAttempt).orElseThrow().state()
                                == com.surprising.wallet.model.mpc.MpcSignAttempt.State.INDETERMINATE, "Unknown attempt rewound");
                    }
                    var next = service.replay(tenant, request.id(), List.of(parties.get(0), parties.get(2)),
                            "isolated-test-operator", "recover interrupted native quorum");
                    require(!originalAttempt.equals(next.currentAttemptId()), "Explicit replay reused an interrupted session");
                    require(command.equals(next.command()), "Replay changed the accepted transaction intent");
                    deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    while (next.state() == MpcSignRequest.State.SIGNING && System.nanoTime() < deadline) {
                        next = service.advanceSignature(tenant, next.id(), clients);
                        Thread.sleep(10);
                    }
                    require(next.state() == MpcSignRequest.State.COMPLETED, "Explicit replay did not recover with another quorum");
                    require(attempts.history(tenant, request.id()).size() == 2, "Replay discarded the original attempt");
                    var replay = attempts.find(tenant, next.currentAttemptId()).orElseThrow();
                    require("isolated-test-operator".equals(replay.replayActor())
                            && "recover interrupted native quorum".equals(replay.replayReason()), "Replay audit was lost");
                    results.add(Map.of("algorithm", "ED25519", "publicKeyHex", key.publicKeyHex(), "messageHex", messageHex,
                            "signatureHex", HexFormat.of().formatHex(service.signature(tenant, request.id()).orElseThrow())));
                }
            }
            System.out.println(json.writeValueAsString(results));
        }
    }

    private static MpcNodeProperties nodeProperties(Path root, tools.jackson.databind.JsonNode config) {
        var properties = new MpcNodeProperties();
        properties.setCaFile(root.resolve("ca.pem"));
        properties.setCertificateFile(root.resolve("client.pem"));
        properties.setPrivateKeyFile(root.resolve("client.key"));
        var nodes = new ArrayList<MpcNodeProperties.Node>();
        for (var peer : config.path("peers")) nodes.add(new MpcNodeProperties.Node(peer.path("identity").asText(),
                URI.create("https://localhost:" + peer.path("port").asInt())));
        properties.setNodes(nodes);
        return properties;
    }

    static com.surprising.wallet.service.CustodyAddressService addresses(JdbcTemplate jdbc, MpcSigningService signing,
                MpcNodes nodes, DataSourceTransactionManager manager) {
        return addresses(jdbc, signing, nodes, manager, com.surprising.wallet.testutil.CustodyTestSupport.cardanoAnchors(jdbc));
    }

    static com.surprising.wallet.service.CustodyAddressService addresses(JdbcTemplate jdbc, MpcSigningService signing,
                MpcNodes nodes, DataSourceTransactionManager manager, com.surprising.wallet.service.CardanoScanAnchorService cardanoAnchors) {
        var profiles = new com.surprising.wallet.repository.ChainProfileRepository(jdbc);
        var runtime = new com.surprising.wallet.chain.BlockchainRuntimeService(profiles,
                new com.surprising.wallet.repository.ChainAssetRepository(jdbc), null, null);
        return new com.surprising.wallet.service.CustodyAddressService(new CustodyTenantTableRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc), profiles,
                com.surprising.wallet.testutil.CustodyTestSupport.tenantChains(jdbc, runtime), new ObjectMapper(),
                new com.surprising.wallet.repository.Evm7702AccountRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc),
                new com.surprising.wallet.repository.CustodyDerivationSubjectRepository(jdbc),
                new com.surprising.wallet.repository.CustodyAuditRepository(jdbc),
                new com.surprising.wallet.repository.LedgerBalanceRepository(jdbc), signing, nodes, cardanoAnchors,
                com.surprising.wallet.testutil.CustodyTestSupport.moneroAnchors(jdbc), manager);
    }

    private static void verifyAddressPublication(JdbcTemplate jdbc, MpcSigningService signing, MpcNodes nodes, UUID tenant,
                Path root, tools.jackson.databind.JsonNode config, DataSourceTransactionManager manager) throws Exception {
        var cardanoAnchors = com.surprising.wallet.testutil.CustodyTestSupport.cardanoAnchors(jdbc);
        if (config.hasNonNull("cardanoFixture")) {
            var descriptor = new ObjectMapper().readTree(Files.readAllBytes(Path.of(config.path("cardanoFixture").asText())));
            var endpoint = java.net.URI.create(descriptor.path("rpcUrl").asText());
            require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()), "Cardano address anchor requires isolated native RPC");
            jdbc.update("update chain_rpc_node set enabled=false where chain='ADA'");
            for (String network : jdbc.queryForList("select network from chain_profile where chain='ADA'", String.class))
                jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('ADA',?,'mpc-test','mpc-cardano-address-anchor','rpc',?,0)", network, endpoint.toString());
            cardanoAnchors = new com.surprising.wallet.service.CardanoScanAnchorService(new com.surprising.wallet.chain.cardano.CardanoRpcClient(
                    new ChainProfileRepository(jdbc), new com.surprising.wallet.service.ChainRpcNodeService(new ChainRpcNodeRepository(jdbc), "mpc-test", 2)),
                    new ChainScanHeightRepository(jdbc), new ChainScanBlockRepository(jdbc));
        }
        var addresses = addresses(jdbc, signing, nodes, manager, cardanoAnchors);
        var chainAddresses = new com.surprising.wallet.repository.ChainAddressRepository(jdbc);
        var custody = new com.surprising.wallet.repository.CustodyAddressRepository(jdbc);
        var profiles = new com.surprising.wallet.repository.ChainProfileRepository(jdbc);
        var tenantChains = new com.surprising.wallet.repository.CustodyTenantChainTableRepository(jdbc);
        var keys = new WalletMpcKeyRepository(jdbc);
        UUID actor = UUID.randomUUID();
        jdbc.update("""
                insert into custody_tenant_user(id,tenant_id,email,display_name,password_hash,role,status)
                values (?,?,'native-mpc-admin@example.test','Native probe administrator','test-only','TENANT_ADMIN','ACTIVE')
                """, actor, tenant);
        var principal = new com.surprising.wallet.model.CustodyPrincipal(
                com.surprising.wallet.model.CustodyPrincipal.ActorType.TENANT_USER, actor, tenant,
                "native-mpc-probe", "TENANT_ADMIN", java.util.Set.of("*"));
        var evmAddresses = new java.util.HashSet<String>();
        var evmKeys = new java.util.HashSet<UUID>();
        var allKeys = new java.util.HashSet<UUID>();
        int created = 0;
        for (var chain : ChainType.values()) {
            if (chain == ChainType.XMR) continue;
            jdbc.update("update chain_profile set enabled=false where chain=?", chain.name());
            Long profileId = jdbc.queryForObject("""
                    select id from chain_profile where chain=? order by case network
                        when 'regtest' then 0 when 'devtest' then 1 when 'testnet' then 2 when 'devnet' then 3 else 4 end,id limit 1
                    """, Long.class, chain.name());
            jdbc.update("update chain_profile set enabled=true where id=?", profileId);
            tenantChains.setStatus(tenant, chain.name(), "ACTIVE", actor);
            var command = new com.surprising.wallet.service.CustodyAddressService.CreateAddressCommand(
                    chain.name(), "native-customer", 0L, null, Map.of());
            var view = addresses.create(principal, command, "API", "127.0.0.1");
            var owner = chainAddresses.findOwned(tenant, custody.require(tenant, view.id()).chainAddressId()).orElseThrow();
            var key = keys.find(tenant, owner.getMpcKeyId()).orElseThrow();
            require(key.state() == MpcKey.State.ACTIVE && key.publicKeyHex() != null, "Address was published before DKG activated");
            require(view.address().equals(MpcPublicAddressCodec.encode(profiles.findAccountByChain(chain.name()).orElseThrow(),
                    HexFormat.of().parseHex(key.publicKeyHex()))), "Custody address does not match the joint public key");
            require(view.id().equals(addresses.create(principal, command, "API", "127.0.0.1").id()), "Address retry changed identity");
            require(owner.getDerivationPath().equals("mpc:" + key.id()), "Address lost its MPC key reference");
            allKeys.add(key.id());
            if (chain.isEvm()) { evmAddresses.add(view.address()); evmKeys.add(key.id()); }
            created++;
        }
        require(created == 27 && allKeys.size() == 15 && evmAddresses.size() == 1 && evmKeys.size() == 1,
                "Custody address DKG did not cover all current non-XMR chains or reuse the EVM key group");
        var transaction = new TransactionTemplate(manager);
        try {
            transaction.executeWithoutResult(status -> addresses.create(principal,
                    new com.surprising.wallet.service.CustodyAddressService.CreateAddressCommand("ETH", "outer-transaction", 0L, null, Map.of()),
                    "API", "127.0.0.1"));
            throw new AssertionError("Address DKG accepted an outer transaction");
        } catch (IllegalStateException expected) { }
        require(count(jdbc, "custody_address", tenant) == 27, "Outer transaction exposed an address");
        var gas = com.surprising.wallet.testutil.CustodyTestSupport.gas(jdbc, addresses);
        jdbc.execute("""
                create function public.mpc_test_reject_gas_audit() returns trigger language plpgsql as $$
                begin
                    if new.action='GAS_ACCOUNT.CREATE' then raise exception 'native probe Gas audit failure'; end if;
                    return new;
                end $$;
                create trigger mpc_test_reject_gas_audit before insert on custody_audit_log
                for each row execute function public.mpc_test_reject_gas_audit()
                """);
        try {
            try {
                gas.create(principal, new com.surprising.wallet.service.CustodyGasService.CreateGasAccountCommand("ETH"), "127.0.0.1");
                throw new AssertionError("Gas audit failure was ignored");
            } catch (org.springframework.dao.DataAccessException expected) { }
            require(count(jdbc, "custody_address", tenant) == 27 && count(jdbc, "chain_address", tenant) == 27
                            && count(jdbc, "custody_gas_account", tenant) == 0 && count(jdbc, "custody_audit_log", tenant) == 27,
                    "Gas publication did not roll back address, account, projection and audit together");
            require(count(jdbc, "wallet_mpc_key", tenant) == 16, "Gas failure discarded its committed DKG key");
        } finally {
            jdbc.execute("drop trigger mpc_test_reject_gas_audit on custody_audit_log; drop function public.mpc_test_reject_gas_audit()");
        }
        var ethGas = gas.create(principal, new com.surprising.wallet.service.CustodyGasService.CreateGasAccountCommand("ETH"), "127.0.0.1");
        var bnbGas = gas.create(principal, new com.surprising.wallet.service.CustodyGasService.CreateGasAccountCommand("BNB"), "127.0.0.1");
        require(ethGas.address().equals(bnbGas.address()), "EVM Gas addresses did not reuse the system key");
        require(ethGas.id().equals(gas.create(principal,
                new com.surprising.wallet.service.CustodyGasService.CreateGasAccountCommand("ETH"), "127.0.0.1").id()), "Gas retry created another account");
        require(count(jdbc, "wallet_mpc_key", tenant) == 16 && count(jdbc, "custody_gas_account", tenant) == 2,
                "Gas retry regenerated a published key");
        var properties = nodeProperties(root, config);
        properties.setCallTimeout(Duration.ofMillis(150));
        properties.setOperationTimeout(Duration.ofMillis(200));
        var listeners = new ArrayList<java.net.ServerSocket>();
        try {
            var offline = new ArrayList<MpcNodeProperties.Node>();
            for (String party : nodes.parties()) {
                var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                listeners.add(listener);
                offline.add(new MpcNodeProperties.Node(party, URI.create("https://localhost:" + listener.getLocalPort())));
            }
            properties.setNodes(offline);
            var command = new com.surprising.wallet.service.CustodyAddressService.CreateAddressCommand("ETH", "native-pending", 0L, null, Map.of());
            UUID pendingKey;
            try (var unavailable = new MpcNodes(properties)) {
                try {
                    addresses(jdbc, signing, unavailable, manager).create(principal, command, "API", "127.0.0.1");
                    throw new AssertionError("Unavailable DKG exposed an address");
                } catch (com.surprising.wallet.model.mpc.MpcPendingException pending) {
                    pendingKey = pending.reference();
                    var response = new com.surprising.wallet.exception.CustodyExceptionHandler().mpcPending(pending);
                    require(response.getStatusCode().value() == 503 && "2".equals(response.getHeaders().getFirst("Retry-After")),
                            "Pending address did not return the retryable HTTP contract");
                }
            }
            require(count(jdbc, "custody_address", tenant) == 29 && keys.find(tenant, pendingKey).orElseThrow().state() == MpcKey.State.CREATING,
                    "Unavailable DKG published an address or lost the durable session");
            var resumed = addresses.create(principal, command, "API", "127.0.0.1");
            require(pendingKey.equals(chainAddresses.findOwned(tenant, custody.require(tenant, resumed.id()).chainAddressId()).orElseThrow().getMpcKeyId()),
                    "Address retry abandoned the original DKG key/session");
        } finally {
            for (var listener : listeners) listener.close();
        }
        require(count(jdbc, "custody_address", tenant) == 30 && count(jdbc, "wallet_mpc_key", tenant) == 17,
                "Actual custody address/gas publication did not finish with its expected key identities");
        try (var concurrent = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 3; index++) {
                String subject = "native-concurrent-" + index;
                futures.add(concurrent.submit(() -> {
                    start.await();
                    return addresses.create(principal,
                            new com.surprising.wallet.service.CustodyAddressService.CreateAddressCommand("ETH", subject, 0L, null, Map.of()),
                            "API", "127.0.0.1").address();
                }));
            }
            start.countDown();
            var distinct = new java.util.HashSet<String>();
            for (var future : futures) distinct.add(future.get(40, java.util.concurrent.TimeUnit.SECONDS));
            require(distinct.size() == 3 && count(jdbc, "custody_address", tenant) == 33 && count(jdbc, "wallet_mpc_key", tenant) == 20,
                    "Concurrent DKG did not independently activate and publish every fixed address");
        }
    }

    private static long count(JdbcTemplate jdbc, String table, UUID tenant) {
        require(java.util.Set.of("custody_address", "chain_address", "custody_gas_account", "custody_audit_log", "wallet_mpc_key").contains(table),
                "unexpected native probe table");
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Long.class, tenant);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static MpcClient unreachable(Path root, MpcClient actual) throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        return MpcClient.connect(new MpcClient.Node(actual.node().partyId(), URI.create("https://localhost:" + port)),
                root.resolve("ca.pem"), root.resolve("client.pem"), root.resolve("client.key"), Duration.ofMillis(250));
    }

    private static void verifyEvmTransfer(String endpoint, String sender, byte[] signed, BigInteger value) throws Exception {
        rpc(endpoint, "anvil_setBalance", List.of(sender, "0x56bc75e2d63100000"));
        BigInteger before = org.web3j.utils.Numeric.decodeQuantity(rpc(endpoint, "eth_getBalance", List.of(EVM_TO, "latest")).asText());
        String raw = org.web3j.utils.Numeric.toHexString(signed);
        String hash = rpc(endpoint, "eth_sendRawTransaction", List.of(raw)).asText();
        require(org.web3j.crypto.Hash.sha3(raw).equalsIgnoreCase(hash), "EVM node returned another transaction hash");
        tools.jackson.databind.JsonNode receipt = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            receipt = rpc(endpoint, "eth_getTransactionReceipt", List.of(hash));
            if (!receipt.isNull()) break;
            Thread.sleep(10);
        }
        require(receipt != null && "0x1".equals(receipt.path("status").asText()), "MPC EVM transfer was not confirmed");
        require(sender.equalsIgnoreCase(receipt.path("from").asText()) && EVM_TO.equalsIgnoreCase(receipt.path("to").asText()),
                "MPC EVM transaction sender or recipient differs");
        BigInteger after = org.web3j.utils.Numeric.decodeQuantity(rpc(endpoint, "eth_getBalance", List.of(EVM_TO, "latest")).asText());
        require(before.add(value).equals(after), "MPC EVM transfer did not credit the exact amount");
        try {
            require(hash.equalsIgnoreCase(rpc(endpoint, "eth_sendRawTransaction", List.of(raw)).asText()), "Duplicate EVM broadcast hash differs");
        } catch (IllegalStateException alreadyKnown) {
            require(!rpc(endpoint, "eth_getTransactionReceipt", List.of(hash)).isNull(), "Duplicate EVM rejection lost the receipt");
        }
        require(after.equals(org.web3j.utils.Numeric.decodeQuantity(rpc(endpoint, "eth_getBalance", List.of(EVM_TO, "latest")).asText())),
                "Duplicate broadcast transferred funds twice");
    }

    private static tools.jackson.databind.JsonNode rpc(String endpoint, String method, List<?> params) throws Exception {
        URI uri = URI.create(endpoint);
        require("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()), "EVM probe requires its isolated loopback node");
        var json = new ObjectMapper();
        var body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var request = java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            require(response.statusCode() == 200, "Isolated EVM RPC HTTP failure");
            var result = json.readTree(response.body());
            if (result.has("error")) throw new IllegalStateException("Isolated EVM RPC rejected " + method + ": " + result.path("error").path("message").asText());
            require(result.has("result"), "Isolated EVM RPC returned no result");
            return result.get("result");
        }
    }
}
