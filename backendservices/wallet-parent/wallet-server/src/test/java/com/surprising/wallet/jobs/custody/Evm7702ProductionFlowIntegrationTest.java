package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.key.WalletKeyConfig;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.jobs.account.Evm7702CollectionCoordinator;
import com.surprising.wallet.jobs.account.Evm7702CollectionRepository;
import com.surprising.wallet.jobs.account.Evm7702CollectionWorkflowService;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hardhat Prague + PostgreSQL production-path test with two isolated tenants. */
class Evm7702ProductionFlowIntegrationTest {
    private static final String RPC = "http://127.0.0.1:8545";
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(2_000_000_000L);
    private static final Credentials HARDHAT_ADMIN = Credentials.create(
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");

    private JdbcTemplate jdbc;
    private ChainJdbcRepository chainRepository;
    private AccountSecp256k1KeyService keyService;
    private AccountChainProfile profile;
    private Evm7702CollectionRepository collectionRepository;
    private Evm7702CollectionWorkflowService workflow;
    private String chain;
    private String nativeSymbol;
    private long chainId;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.7702.production.enabled"),
                "start Hardhat Prague, deploy the 7702 contracts, and set the integration properties");
        chain = System.getProperty("evm.7702.test.chain", "ETH").trim().toUpperCase(java.util.Locale.ROOT);
        chainId = Long.parseLong(System.getProperty("evm.7702.test.chain-id", "31337"));
        DriverManagerDataSource dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        chainRepository = new ChainJdbcRepository(jdbc);
        jdbc.update("update chain_profile set network = 'local', chain_id = ?, withdraw_confirmations = 1 where chain = ? and enabled = true",
                chainId, chain);
        jdbc.update("update token_config set network = 'local' where chain = ? and enabled = true", chain);
        jdbc.update("""
                insert into chain_rpc_node(
                    id, chain, network, environment, node_label, purpose,
                    connection_type, rpc_url, priority, min_request_interval_ms, enabled)
                values (9901, ?, 'local', 'eip7702-test', 'hardhat-prague',
                        'rpc', 'HTTP_JSON_RPC', ?, 1, 0, true)
                """, chain, RPC);
        saveTestKeyset(jdbc);
        keyService = new AccountSecp256k1KeyService(new WalletKeyMaterialProvider(
                new WalletKeyConfigStore(jdbc), WalletKeyMaterialProvider.Mode.WALLET_SERVER));
        profile = chainRepository.findProfileByChain(chain).orElseThrow();
        nativeSymbol = profile.getNativeSymbol();

        ChainRpcNodeService rpcNodes = new ChainRpcNodeService(chainRepository);
        setField(rpcNodes, "environmentName", "eip7702-test");
        setField(rpcNodes, "maxConcurrentRequestsPerProvider", 1);
        CustodySecurityProperties security = new CustodySecurityProperties();
        security.setSecretMasterKey("11".repeat(32));
        CustodyCryptoService crypto = new CustodyCryptoService(security);
        CustodyRepository custodyRepository = new CustodyRepository(jdbc);
        collectionRepository = new Evm7702CollectionRepository(jdbc);
        Evm7702CollectionCoordinator coordinator = new Evm7702CollectionCoordinator(
                collectionRepository, custodyRepository, chainRepository);
        workflow = new Evm7702CollectionWorkflowService(
                collectionRepository, coordinator, chainRepository, rpcNodes, keyService, crypto);
    }

    @Test
    void shouldCollectRealDepositsInOneTxPerTenantAndSettleGasOnce() throws Exception {
        String collector = requiredProperty("evm.7702.collector");
        String delegate = requiredProperty("evm.7702.delegate");
        Web3j web3j = Web3j.build(new HttpService(RPC));
        try {
            BigInteger adminNonce = web3j.ethGetTransactionCount(
                    HARDHAT_ADMIN.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            Deployment token = deployMockToken(web3j, adminNonce);
            adminNonce = adminNonce.add(BigInteger.ONE);

            ChainAddressRecord relayerRecord = keyRecord(null, 9_000_001L, 7702, 0L, "EIP7702_RELAYER");
            Credentials relayer = credentials(relayerRecord);
            long relayerChainAddressId = insertChainAddress(relayerRecord, null);
            assertEquals("0x1", sendLegacyCall(web3j, adminNonce, collector,
                    FunctionEncoder.encode(new Function("setRelayer",
                            List.of(new Address(relayer.getAddress()), new Bool(true)), List.of()))).getStatus());
            adminNonce = adminNonce.add(BigInteger.ONE);
            assertEquals("0x1", sendLegacyNative(web3j, adminNonce, relayer.getAddress(),
                    new BigInteger("5000000000000000000")).getStatus());
            adminNonce = adminNonce.add(BigInteger.ONE);

            String delegateHash = codeHash(web3j, delegate);
            String collectorHash = codeHash(web3j, collector);
            jdbc.update("""
                    insert into evm_7702_config(
                        id, chain, network, chain_id, version,
                        delegate_address, delegate_code_hash,
                        collector_address, collector_code_hash,
                        relayer_chain_address_id, relayer_address, status,
                        max_batch_items, max_batch_gas, block_gas_ratio,
                        gas_limit_multiplier, signature_ttl_seconds, required_confirmations)
                    values (?, ?, 'local', ?, 1, ?, ?, ?, ?, ?, ?, 'ACTIVE',
                            10, 5000000, 0.5000, 1.2000, 900, 1)
                    """, UUID.randomUUID(), chain, chainId, delegate, delegateHash,
                    collector, collectorHash, relayerChainAddressId, relayer.getAddress());
            assertEquals(List.of(new Evm7702CollectionRepository.RuntimeTarget(chain, "local", true)),
                    collectionRepository.listRuntimeTargets());
            jdbc.update("""
                    update token_config set contract_address = ?, contract_address_hex = ?,
                           decimals = 6, standard = 'ERC20', token_standard = 'ERC20',
                           collect_enabled = true
                     where chain = ? and symbol = 'USDT' and network = 'local'
                    """, token.address(), token.address(), chain);
            jdbc.update("update chain_asset set contract_address = ?, decimals = 6 where chain = ? and symbol = 'USDT'",
                    token.address(), chain);

            TenantFixture tenantA = createTenant("tenant-7702-a", 8101, 3, 1000L);
            TenantFixture tenantB = createTenant("tenant-7702-b", 8102, 2, 2000L);
            for (AuthorityFixture authority : concat(tenantA.authorities(), tenantB.authorities())) {
                TransactionReceipt mint = sendLegacyCall(web3j, adminNonce, token.address(),
                        encodeMint(authority.credentials().getAddress(), authority.amountAtomic()));
                adminNonce = adminNonce.add(BigInteger.ONE);
                assertEquals("0x1", mint.getStatus());
                recordDeposit(authority, token.address(), mint.getTransactionHash());
            }
            createCollectionCandidates();

            jdbc.update("update evm_7702_config set delegate_code_hash = ? where chain = ? and network = 'local'",
                    "0x" + "00".repeat(32), chain);
            assertThrows(IllegalStateException.class, () -> workflow.processOne(profile));
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from evm_collection_batch
                     where tenant_id = ? and status = 'FAILED'
                    """, Integer.class, tenantA.tenantId()));
            assertEquals(tenantA.authorities().size(), jdbc.queryForObject("""
                    select count(*) from evm_collection_batch_item
                     where tenant_id = ? and status = 'RETRYABLE'
                    """, Integer.class, tenantA.tenantId()));
            jdbc.update("update evm_7702_config set delegate_code_hash = ? where chain = ? and network = 'local'",
                    delegateHash, chain);

            String tenantATx = workflow.processOne(profile).orElseThrow();
            assertEquals(2, batchCount(tenantA.tenantId()));
            assertEquals(0, batchCount(tenantB.tenantId()));
            simulateLostBroadcastResponse(tenantA.tenantId());
            workflow.recoverUnknown(profile);
            assertEquals("SUBMITTED", jdbc.queryForObject(
                    "select status from evm_collection_batch where tenant_id = ? and status = 'SUBMITTED'",
                    String.class, tenantA.tenantId()));
            assertEquals(tenantATx, jdbc.queryForObject(
                    "select canonical_tx_hash from evm_collection_batch where tenant_id = ? and canonical_tx_hash is not null",
                    String.class, tenantA.tenantId()));
            workflow.confirm(profile);
            assertTenantCompleted(web3j, tenantA, tenantATx);
            assertEquals(0, confirmedCollections(tenantB.tenantId()));

            String tenantBTx = workflow.processOne(profile).orElseThrow();
            assertNotEquals(tenantATx, tenantBTx);
            workflow.confirm(profile);
            assertTenantCompleted(web3j, tenantB, tenantBTx);

            assertEquals(3, jdbc.queryForObject("select count(*) from evm_collection_batch", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from custody_gas_usage where operation_type = 'COLLECTION_BATCH' and status = 'SETTLED'", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(distinct canonical_tx_hash) from evm_collection_batch", Integer.class));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from evm_collection_batch_item i
                    join evm_collection_batch b on b.id = i.batch_id
                    where i.tenant_id <> b.tenant_id
                    """, Integer.class));
        } finally {
            web3j.shutdown();
        }
    }

    private void simulateLostBroadcastResponse(UUID tenantId) {
        jdbc.update("""
                update evm_collection_batch
                   set status = 'BROADCAST_UNKNOWN', canonical_tx_hash = null, submitted_at = null
                 where tenant_id = ? and status = 'SUBMITTED'
                """, tenantId);
        jdbc.update("""
                update evm_collection_batch_attempt a
                   set status = 'UNKNOWN', submitted_at = null
                  from evm_collection_batch b
                 where b.tenant_id = ? and b.id = a.batch_id and a.status = 'SUBMITTED'
                """, tenantId);
        jdbc.update("""
                update evm_collection_batch_item
                   set status = 'SIGNED'
                 where tenant_id = ? and status = 'SUBMITTED'
                """, tenantId);
    }

    private TenantFixture createTenant(String slug, int namespace, int itemCount, long userBase) throws Exception {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name, derivation_namespace) values (?, ?, ?, ?)",
                tenantId, slug, slug, namespace);
        ChainAddressRecord hotRecord = keyRecord(tenantId, userBase, namespace, 999L, "COLLECTION_GAS");
        long hotChainAddressId = insertChainAddress(hotRecord, tenantId);
        UUID hotCustodyId = insertCustodyAddress(
                tenantId, hotChainAddressId, hotRecord.getAddress(), slug + "-gas", 999L);
        UUID gasAccountId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_gas_account(
                    id, tenant_id, custody_address_id, chain, network, native_symbol, status)
                values (?, ?, ?, ?, 'local', ?, 'ACTIVE')
                """, gasAccountId, tenantId, hotCustodyId, chain, nativeSymbol);
        jdbc.update("""
                insert into ledger_balance(
                    chain, asset_symbol, account_id, available_balance,
                    locked_balance, total_balance, tenant_id)
                values (?, ?, ?, 1, 0, 1, ?)
                """, chain, nativeSymbol, hotRecord.getAddress().toLowerCase(), tenantId);

        ArrayList<AuthorityFixture> authorities = new ArrayList<>();
        for (int index = 0; index < itemCount; index++) {
            ChainAddressRecord record = keyRecord(
                    tenantId, userBase + index + 1, namespace, index, "DEPOSIT");
            Credentials credentials = credentials(record);
            long chainAddressId = insertChainAddress(record, tenantId);
            UUID custodyId = insertCustodyAddress(
                    tenantId, chainAddressId, credentials.getAddress(), slug + "-user-" + index, index);
            collectionRepository.createAccountProjection(
                    tenantId, custodyId, chain, "local", credentials.getAddress());
            BigInteger amount = BigInteger.valueOf((index + 1L) * 10_000_000L);
            authorities.add(new AuthorityFixture(
                    tenantId, custodyId, record, credentials, amount,
                    new BigDecimal(amount).movePointLeft(6), hotRecord.getAddress()));
        }
        return new TenantFixture(tenantId, hotRecord.getAddress(), List.copyOf(authorities));
    }

    private void recordDeposit(AuthorityFixture authority, String token, String txHash) {
        jdbc.update("""
                insert into deposit_record(
                    chain, asset_symbol, tx_hash, log_index, from_address, to_address,
                    contract_address, amount, block_height, confirmations, status,
                    credited, credited_at, tenant_id)
                values (?, 'USDT', ?, 0, ?, ?, ?, ?, 1, 1, 'CREDITED', true, now(), ?)
                """, chain, txHash, "0x0000000000000000000000000000000000000000",
                authority.credentials().getAddress(), token, authority.amount(), authority.tenantId());
        jdbc.update("""
                insert into ledger_balance(
                    chain, asset_symbol, account_id, available_balance,
                    locked_balance, total_balance, tenant_id)
                values (?, 'USDT', ?, ?, 0, ?, ?)
                """, chain, authority.credentials().getAddress().toLowerCase(), authority.amount(),
                authority.amount(), authority.tenantId());
    }

    private void createCollectionCandidates() {
        List<CollectionCandidateRecord> candidates = chainRepository.listCollectableLedgerBalances(
                chain, BigDecimal.ZERO, 20);
        assertEquals(5, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate -> "USDT".equals(candidate.getAssetSymbol())));
        assertEquals(2, candidates.stream().map(CollectionCandidateRecord::getTenantId).distinct().count());
        for (CollectionCandidateRecord candidate : candidates) {
            String hotWallet = chainRepository.findActiveTenantCollectionAddress(
                    candidate.getTenantId(), candidate.getChain()).orElseThrow();
            assertEquals(1, chainRepository.createCollectionRecord(
                    candidate.getTenantId(), candidate.getCustodyAddressId(),
                    "7702:" + candidate.getTenantId() + ":" + candidate.getAddress(),
                    candidate.getChain(), candidate.getAssetSymbol(), candidate.getAddress(), hotWallet,
                    candidate.getAmount(), BigDecimal.ZERO, null));
        }
    }

    private void assertTenantCompleted(Web3j web3j, TenantFixture tenant, String txHash) throws Exception {
        assertEquals(tenant.authorities().size(), confirmedCollections(tenant.tenantId()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from evm_collection_batch
                 where tenant_id = ? and canonical_tx_hash = ?
                   and status = 'CONFIRMED'
                """, Integer.class, tenant.tenantId(), txHash));
        assertEquals(tenant.authorities().size(), jdbc.queryForObject("""
                select count(*) from evm_collection_batch_item
                 where tenant_id = ? and status = 'CONFIRMED'
                """, Integer.class, tenant.tenantId()));
        BigInteger expected = tenant.authorities().stream()
                .map(AuthorityFixture::amountAtomic).reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(expected, tokenBalance(web3j, tenant.hotWallet()));
        for (AuthorityFixture authority : tenant.authorities()) {
            assertEquals(BigInteger.ZERO, web3j.ethGetBalance(
                    authority.credentials().getAddress(), DefaultBlockParameterName.LATEST).send().getBalance());
            assertTrue(web3j.ethGetCode(authority.credentials().getAddress(), DefaultBlockParameterName.LATEST)
                    .send().getCode().startsWith("0xef0100"));
        }
        String ciphertext = jdbc.queryForObject("""
                select a.signed_tx_ciphertext from evm_collection_batch_attempt a
                join evm_collection_batch b on b.id = a.batch_id
                where b.tenant_id = ?
                """, String.class, tenant.tenantId());
        assertTrue(ciphertext.startsWith("v1:"));
        assertFalse(ciphertext.contains("0x04"));
        BigDecimal actualFee = jdbc.queryForObject("""
                select actual_amount from custody_gas_usage
                 where tenant_id = ? and operation_type = 'COLLECTION_BATCH'
                """, BigDecimal.class, tenant.tenantId());
        assertTrue(actualFee.signum() > 0);
        Map<String, Object> fees = jdbc.queryForMap("""
                select l2_fee_atomic, l1_fee_atomic, operator_fee_atomic,
                       total_fee_atomic, actual_fee
                  from evm_collection_batch
                 where tenant_id = ? and canonical_tx_hash = ?
                """, tenant.tenantId(), txHash);
        BigInteger l2Fee = ((BigDecimal) fees.get("l2_fee_atomic")).toBigIntegerExact();
        assertEquals(BigInteger.ZERO,
                ((BigDecimal) fees.get("l1_fee_atomic")).toBigIntegerExact());
        assertEquals(BigInteger.ZERO,
                ((BigDecimal) fees.get("operator_fee_atomic")).toBigIntegerExact());
        assertEquals(l2Fee, ((BigDecimal) fees.get("total_fee_atomic")).toBigIntegerExact());
        assertEquals(0, actualFee.compareTo((BigDecimal) fees.get("actual_fee")));
    }

    private int batchCount(UUID tenantId) {
        return jdbc.queryForObject(
                "select count(*) from evm_collection_batch where tenant_id = ?", Integer.class, tenantId);
    }

    private int confirmedCollections(UUID tenantId) {
        return jdbc.queryForObject("select count(*) from collection_record where tenant_id = ? and status = 'CONFIRMED'",
                Integer.class, tenantId);
    }

    private long insertChainAddress(ChainAddressRecord record, UUID tenantId) {
        return jdbc.queryForObject("""
                insert into chain_address(
                    chain, asset_symbol, account_id, user_id, biz, address_index,
                    address, owner_address, derivation_path, wallet_role, enabled, tenant_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?)
                returning id
                """, Long.class, chain, nativeSymbol, record.getAddress().toLowerCase(),
                record.getUserId(), record.getBiz(),
                record.getAddressIndex(), record.getAddress(), record.getAddress(), record.getDerivationPath(),
                record.getWalletRole(), tenantId);
    }

    private UUID insertCustodyAddress(UUID tenantId, long chainAddressId,
                                      String address, String subject, long child) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into custody_address(
                    id, tenant_id, chain_address_id, chain, network, address,
                    subject, source, derivation_subject, derivation_child)
                values (?, ?, ?, ?, 'local', ?, ?, 'CONSOLE', ?, ?)
                """, id, tenantId, chainAddressId, chain, address, subject,
                Math.toIntExact(100_000L + child + Math.abs(subject.hashCode() % 10_000)), child);
        return id;
    }

    private ChainAddressRecord keyRecord(UUID tenantId, long userId, int biz,
                                         long index, String role) {
        ChainAddressRecord template = ChainAddressRecord.builder()
                .chain(chain).assetSymbol(nativeSymbol).userId(userId).biz(biz)
                .addressIndex(index).walletRole(role).enabled(true)
                .derivationPath("m/44/60/" + biz + "/" + userId + "/" + index).build();
        Credentials credentials = credentials(template);
        template.setAddress(credentials.getAddress());
        template.setAccountId(credentials.getAddress().toLowerCase());
        template.setOwnerAddress(credentials.getAddress());
        return template;
    }

    private Credentials credentials(ChainAddressRecord record) {
        org.bitcoinj.crypto.ECKey key = keyService.key(profile, record);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(key.getPrivKey(), 64));
    }

    private Deployment deployMockToken(Web3j web3j, BigInteger nonce) throws Exception {
        JsonNode artifact = new ObjectMapper().readTree(Files.readString(projectRoot().resolve(
                "evm-fork/artifacts/contracts/MockERC20.sol/MockERC20.json")));
        String bytecode = artifact.path("bytecode").asText();
        String constructor = FunctionEncoder.encodeConstructor(List.of(
                new Utf8String("Test USD"), new Utf8String("USDT"), new Uint8(6)));
        RawTransaction raw = RawTransaction.createContractTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(5_000_000L), BigInteger.ZERO,
                bytecode + Numeric.cleanHexPrefix(constructor));
        TransactionReceipt receipt = sendRaw(web3j, raw);
        return new Deployment(receipt.getContractAddress());
    }

    private TransactionReceipt sendLegacyCall(
            Web3j web3j, BigInteger nonce, String to, String data) throws Exception {
        return sendRaw(web3j, RawTransaction.createTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(800_000L), to, BigInteger.ZERO, data));
    }

    private TransactionReceipt sendLegacyNative(
            Web3j web3j, BigInteger nonce, String to, BigInteger value) throws Exception {
        return sendRaw(web3j, RawTransaction.createEtherTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(21_000L), to, value));
    }

    private TransactionReceipt sendRaw(Web3j web3j, RawTransaction raw) throws Exception {
        byte[] signed = TransactionEncoder.signMessage(raw, chainId, HARDHAT_ADMIN);
        EthSendTransaction response = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
        return waitReceipt(web3j, response.getTransactionHash());
    }

    private static String encodeMint(String recipient, BigInteger amount) {
        return FunctionEncoder.encode(new Function(
                "mint", List.of(new Address(recipient), new Uint256(amount)), List.of()));
    }

    private BigInteger tokenBalance(Web3j web3j, String owner) throws Exception {
        String token = jdbc.queryForObject(
                "select contract_address from token_config where chain = ? and symbol = 'USDT' and network = 'local'",
                String.class, chain);
        Function function = new Function(
                "balanceOf", List.of(new Address(owner)), List.of(new TypeReferenceUint256()));
        EthCall call = web3j.ethCall(Transaction.createEthCallTransaction(
                HARDHAT_ADMIN.getAddress(), token, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        return (BigInteger) org.web3j.abi.FunctionReturnDecoder.decode(
                call.getValue(), function.getOutputParameters()).getFirst().getValue();
    }

    private static String codeHash(Web3j web3j, String address) throws Exception {
        String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
        assertNotEquals("0x", code);
        return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code)));
    }

    private static TransactionReceipt waitReceipt(Web3j web3j, String txHash) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isPresent()) return receipt.get();
            Thread.sleep(100L);
        }
        throw new IllegalStateException("timed out waiting for " + txHash);
    }

    private static void saveTestKeyset(JdbcTemplate jdbc) {
        String[] seeds = new String[4];
        for (int i = 0; i < seeds.length; i++) {
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, (byte) (0x31 + i));
            seeds[i] = Base64.getEncoder().encodeToString(bytes);
        }
        new WalletKeyConfigStore(jdbc).save(
                new WalletKeyConfig(seeds[0], seeds[1], seeds[2], seeds[3], null, null, "test"),
                "eip7702-integration");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (!value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new IllegalArgumentException("missing valid -D" + name);
        }
        return value;
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("evm-fork"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate project root");
    }

    private static List<AuthorityFixture> concat(
            List<AuthorityFixture> first, List<AuthorityFixture> second) {
        ArrayList<AuthorityFixture> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static final class TypeReferenceUint256 extends org.web3j.abi.TypeReference<Uint256> {
    }

    private record Deployment(String address) {
    }

    private record TenantFixture(
            UUID tenantId, String hotWallet, List<AuthorityFixture> authorities) {
    }

    private record AuthorityFixture(
            UUID tenantId, UUID custodyAddressId, ChainAddressRecord record,
            Credentials credentials, BigInteger amountAtomic, BigDecimal amount,
            String hotWallet) {
    }
}
