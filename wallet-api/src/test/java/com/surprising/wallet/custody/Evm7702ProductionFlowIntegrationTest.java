package com.surprising.wallet.custody;
import com.surprising.wallet.custody.job.CustodyWithdrawalReconciliationJob;
import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.service.CustodyCryptoService;
import com.surprising.wallet.custody.service.CustodyWithdrawalService;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.model.CustodySecurityProperties;
import com.surprising.wallet.custody.observer.CustodyDepositCreditObserver;
import com.surprising.wallet.custody.repository.CustodyTenantChainRepository;
import com.surprising.wallet.custody.service.CustodyGasService;
import com.surprising.wallet.custody.service.CustodyTenantChainService;
import com.surprising.wallet.custody.service.CustodyWithdrawalExecutionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.key.WalletKeyConfig;
import com.surprising.wallet.common.key.WalletKeyConfigStore;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.account.coordinator.Evm7702CollectionCoordinator;
import com.surprising.wallet.account.repository.Evm7702CollectionRepository;
import com.surprising.wallet.account.service.Evm7702CollectionWorkflowService;
import com.surprising.wallet.account.coordinator.Evm7702WithdrawalCoordinator;
import com.surprising.wallet.account.repository.Evm7702WithdrawalRepository;
import com.surprising.wallet.account.service.Evm7702WithdrawalWorkflowService;
import com.surprising.wallet.service.chain.evm.EvmDepositScanner;
import com.surprising.wallet.service.chain.evm.EvmLogScanner;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.dao.DepositCreditObserver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
import java.util.Set;
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
    private CustodyRepository custodyRepository;
    private EvmDepositScanner depositScanner;
    private CustodyWithdrawalService withdrawalService;
    private Evm7702WithdrawalWorkflowService withdrawalWorkflow;
    private CustodyWithdrawalReconciliationJob reconciliationJob;
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
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        custodyRepository = new CustodyRepository(jdbc);
        CustodyTenantChainRepository tenantChainRepository =
                new CustodyTenantChainRepository(jdbc);
        CustodyDepositCreditObserver depositObserver = new CustodyDepositCreditObserver(
                jdbc, objectMapper, custodyRepository, tenantChainRepository);
        StaticListableBeanFactory observers = new StaticListableBeanFactory();
        observers.addBean("custodyDepositCreditObserver", depositObserver);
        chainRepository = new ChainJdbcRepository(
                jdbc, observers.getBeanProvider(DepositCreditObserver.class));
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
        depositScanner = new EvmDepositScanner(
                chainRepository, new EvmLogScanner(), RPC, 1);

        ChainRpcNodeService rpcNodes = new ChainRpcNodeService(chainRepository);
        setField(rpcNodes, "environmentName", "eip7702-test");
        setField(rpcNodes, "maxConcurrentRequestsPerProvider", 1);
        CustodySecurityProperties security = new CustodySecurityProperties();
        security.setSecretMasterKey("11".repeat(32));
        CustodyCryptoService crypto = new CustodyCryptoService(security);
        WalletRuntimeConfigService runtimeConfig =
                new WalletRuntimeConfigService(chainRepository);
        CustodyTenantChainService tenantChainService = new CustodyTenantChainService(
                tenantChainRepository, custodyRepository, null);
        CustodyWithdrawalExecutionService withdrawalExecution =
                new CustodyWithdrawalExecutionService(jdbc, chainRepository, runtimeConfig);
        withdrawalService = new CustodyWithdrawalService(
                custodyRepository, withdrawalExecution,
                new CustodyGasService(custodyRepository, null, null),
                tenantChainService, crypto, objectMapper);
        collectionRepository = new Evm7702CollectionRepository(jdbc);
        Evm7702CollectionCoordinator coordinator = new Evm7702CollectionCoordinator(
                collectionRepository, custodyRepository, chainRepository);
        workflow = new Evm7702CollectionWorkflowService(
                collectionRepository, coordinator, chainRepository, rpcNodes, keyService, crypto,
                runtimeConfig);
        Evm7702WithdrawalRepository withdrawalRepository =
                new Evm7702WithdrawalRepository(jdbc, collectionRepository);
        Evm7702WithdrawalCoordinator withdrawalCoordinator = new Evm7702WithdrawalCoordinator(
                withdrawalRepository, custodyRepository, chainRepository);
        withdrawalWorkflow = new Evm7702WithdrawalWorkflowService(
                withdrawalRepository, withdrawalCoordinator, chainRepository,
                rpcNodes, keyService, crypto, runtimeConfig);
        reconciliationJob = new CustodyWithdrawalReconciliationJob(
                custodyRepository, objectMapper);
    }

    @Test
    void shouldCollectRealDepositsInOneTxPerTenantAndSettleGasOnce() throws Exception {
        String collector = requiredProperty("evm.7702.collector");
        String delegate = requiredProperty("evm.7702.delegate");
        Web3j web3j = Web3j.build(new HttpService(RPC));
        try {
            BigInteger adminNonce = web3j.ethGetTransactionCount(
                    HARDHAT_ADMIN.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            Deployment usdt = deployMockToken(web3j, adminNonce, "USDT");
            adminNonce = adminNonce.add(BigInteger.ONE);
            Deployment usdc = deployMockToken(web3j, adminNonce, "USDC");
            adminNonce = adminNonce.add(BigInteger.ONE);

            ChainAddressRecord relayerRecord = keyRecord(null, 9_000_001L, 7702, 0L, "EIP7702_RELAYER");
            Credentials relayer = credentials(relayerRecord);
            long relayerChainAddressId = insertChainAddress(relayerRecord, null);
            Deployment payoutDeployment = deployPayoutDelegate(
                    web3j, adminNonce, relayer.getAddress());
            String payoutDelegate = payoutDeployment.address();
            adminNonce = adminNonce.add(BigInteger.ONE);
            assertEquals("0x1", sendLegacyCall(web3j, adminNonce, collector,
                    FunctionEncoder.encode(new Function("setRelayer",
                            List.of(new Address(relayer.getAddress()), new Bool(true)), List.of()))).getStatus());
            adminNonce = adminNonce.add(BigInteger.ONE);
            assertEquals("0x1", sendLegacyNative(web3j, adminNonce, relayer.getAddress(),
                    new BigInteger("5000000000000000000")).getStatus());
            adminNonce = adminNonce.add(BigInteger.ONE);

            String delegateHash = codeHash(web3j, delegate);
            String collectorHash = codeHash(web3j, collector);
            String payoutDelegateHash = codeHash(web3j, payoutDelegate);
            jdbc.update("""
                    insert into evm_7702_config(
                        id, chain, network, chain_id, version,
                        delegate_address, delegate_code_hash,
                        collector_address, collector_code_hash,
                        payout_delegate_address, payout_delegate_code_hash,
                        relayer_chain_address_id, relayer_address, status,
                        max_batch_items, max_batch_gas, block_gas_ratio,
                        gas_limit_multiplier, signature_ttl_seconds, required_confirmations,
                        native_collection_enabled, batch_withdrawal_enabled,
                        withdrawal_max_wait_ms, withdrawal_max_batch_items)
                    values (?, ?, 'local', ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE',
                            10, 5000000, 0.5000, 1.2000, 900, 1,
                            true, true, 0, 10)
                    """, UUID.randomUUID(), chain, chainId, delegate, delegateHash,
                    collector, collectorHash, payoutDelegate, payoutDelegateHash,
                    relayerChainAddressId, relayer.getAddress());
            assertEquals(List.of(new Evm7702CollectionRepository.RuntimeTarget(chain, "local", true)),
                    collectionRepository.listRuntimeTargets());
            configureToken("USDT", usdt.address());
            configureToken("USDC", usdc.address());

            // The dev node is intentionally persistent. Use a fresh derivation namespace on every
            // run so a prior test's 7702 delegation and nonce cannot leak into this test fixture.
            int runNamespace = 10_000 + adminNonce.mod(BigInteger.valueOf(1_000_000)).intValueExact() * 2;
            TenantFixture tenantA = createTenant("tenant-7702-a", runNamespace, 3, 1000L);
            TenantFixture tenantB = createTenant("tenant-7702-b", runNamespace + 1, 2, 2000L);
            for (AuthorityFixture authority : concat(tenantA.authorities(), tenantB.authorities())) {
                TransactionReceipt mint = sendLegacyCall(web3j, adminNonce, usdt.address(),
                        encodeMint(authority.credentials().getAddress(), authority.amountAtomic()));
                adminNonce = adminNonce.add(BigInteger.ONE);
                assertEquals("0x1", mint.getStatus());
                assertEquals(1, depositScanner.scanAndCreditErc20(
                        ChainType.valueOf(chain), RPC, 1,
                        mint.getBlockNumber().longValueExact()).size());
            }
            assertCreditedDeposits("USDT", 5);
            createCollectionCandidates("USDT", 5);

            jdbc.update("update evm_7702_config set delegate_code_hash = ? where chain = ? and network = 'local'",
                    "0x" + "00".repeat(32), chain);
            assertThrows(IllegalStateException.class, () -> workflow.processOne(profile));
            assertThrows(IllegalStateException.class, () -> workflow.processOne(profile));
            assertThrows(IllegalStateException.class, () -> workflow.processOne(profile));
            assertEquals(3, jdbc.queryForObject("""
                    select count(*) from evm_collection_batch
                     where tenant_id = ? and status = 'FAILED'
                    """, Integer.class, tenantA.tenantId()));
            assertEquals(tenantA.authorities().size() * 2, jdbc.queryForObject("""
                    select count(*) from evm_collection_batch_item
                     where tenant_id = ? and status = 'RETRYABLE'
                    """, Integer.class, tenantA.tenantId()));
            assertEquals(tenantA.authorities().size(), jdbc.queryForObject("""
                    select count(*) from evm_collection_batch_item
                     where tenant_id = ? and status = 'FAILED'
                    """, Integer.class, tenantA.tenantId()));
            assertEquals(tenantA.authorities().size(), jdbc.queryForObject("""
                    select count(*) from collection_record
                     where tenant_id = ? and status = 'FAILED'
                    """, Integer.class, tenantA.tenantId()));
            jdbc.update("update evm_7702_config set delegate_code_hash = ? where chain = ? and network = 'local'",
                    delegateHash, chain);
            jdbc.update("""
                    update collection_record
                       set status = 'RETRYING', error_message = null, updated_at = now()
                     where tenant_id = ? and status = 'FAILED'
                    """, tenantA.tenantId());

            String tenantATx = workflow.processOne(profile).orElseThrow();
            assertEquals(4, batchCount(tenantA.tenantId()));
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
            int tenantASettledGas = settledBatchGasUsages(tenantA.tenantId());
            workflow.confirm(profile);
            assertTenantCompleted(web3j, tenantA, tenantATx);
            assertEquals(tenantASettledGas, settledBatchGasUsages(tenantA.tenantId()));

            String tenantBTx = workflow.processOne(profile).orElseThrow();
            assertNotEquals(tenantATx, tenantBTx);
            workflow.confirm(profile);
            assertTenantCompleted(web3j, tenantB, tenantBTx);

            assertEquals(5, jdbc.queryForObject("select count(*) from evm_collection_batch", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from custody_gas_usage where operation_type = 'COLLECTION_BATCH' and status = 'SETTLED'", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(distinct canonical_tx_hash) from evm_collection_batch", Integer.class));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from evm_collection_batch_item i
                    join evm_collection_batch b on b.id = i.batch_id
                    where i.tenant_id <> b.tenant_id
                    """, Integer.class));

            List<AuthorityFixture> nativeAuthorities = List.of(
                    tenantA.authorities().get(0), tenantA.authorities().get(1),
                    tenantB.authorities().get(0));
            BigInteger[] nativeAmounts = {
                    new BigInteger("1000000000000000000"),
                    new BigInteger("2000000000000000000"),
                    new BigInteger("1500000000000000000")
            };
            BigInteger tenantANativeBalanceBefore = web3j.ethGetBalance(
                    tenantA.hotWallet(), DefaultBlockParameterName.LATEST).send().getBalance();
            BigInteger tenantBNativeBalanceBefore = web3j.ethGetBalance(
                    tenantB.hotWallet(), DefaultBlockParameterName.LATEST).send().getBalance();
            for (int index = 0; index < nativeAuthorities.size(); index++) {
                AuthorityFixture authority = nativeAuthorities.get(index);
                TransactionReceipt deposit = sendLegacyNative(
                        web3j, adminNonce, authority.credentials().getAddress(), nativeAmounts[index]);
                adminNonce = adminNonce.add(BigInteger.ONE);
                assertEquals("0x1", deposit.getStatus());
                assertEquals(1, depositScanner.scanAndCreditNative(
                        ChainType.valueOf(chain), nativeSymbol, RPC, 1,
                        deposit.getBlockNumber().longValueExact()).size());
            }
            assertCreditedDeposits(nativeSymbol, 3);
            createCollectionCandidates(nativeSymbol, 3);
            String tenantANativeCollectionTx = workflow.processOne(profile).orElseThrow();
            workflow.confirm(profile);
            String tenantBNativeCollectionTx = workflow.processOne(profile).orElseThrow();
            workflow.confirm(profile);
            assertNotEquals(tenantANativeCollectionTx, tenantBNativeCollectionTx);
            assertEquals(tenantANativeBalanceBefore.add(new BigInteger("3000000000000000000")),
                    web3j.ethGetBalance(tenantA.hotWallet(), DefaultBlockParameterName.LATEST)
                            .send().getBalance());
            assertEquals(tenantBNativeBalanceBefore.add(new BigInteger("1500000000000000000")),
                    web3j.ethGetBalance(tenantB.hotWallet(), DefaultBlockParameterName.LATEST)
                            .send().getBalance());

            String recipientOne = "0x6000000000000000000000000000000000000001";
            String recipientTwo = "0x6000000000000000000000000000000000000002";
            WithdrawalFixture tokenWithdrawalOne = createWithdrawal(
                    tenantA, tenantA.authorities().get(0), "USDT", recipientOne,
                    new BigDecimal("1"), "token-a-1");
            WithdrawalFixture tokenWithdrawalTwo = createWithdrawal(
                    tenantA, tenantA.authorities().get(1), "USDT", recipientTwo,
                    new BigDecimal("2"), "token-a-2");
            String tokenPayoutTx = withdrawalWorkflow.processOne(profile).orElseThrow();
            simulateLostPayoutBroadcastResponse(tenantA.tenantId());
            withdrawalWorkflow.recoverUnknown(profile);
            withdrawalWorkflow.confirm(profile);
            reconciliationJob.reconcile();
            assertSharedConfirmedPayout(tokenPayoutTx, tokenWithdrawalOne, tokenWithdrawalTwo);
            assertEquals(new BigInteger("1000000"), tokenBalance(web3j, recipientOne));
            assertEquals(new BigInteger("2000000"), tokenBalance(web3j, recipientTwo));

            Deployment rejectingReceiver = deployContract(
                    web3j, adminNonce,
                    "resources/infra/evm-fork/artifacts/contracts/TestPayoutReceiver.sol/TestPayoutReceiver.json");
            adminNonce = adminNonce.add(BigInteger.ONE);
            String nativeRecipient = "0x6000000000000000000000000000000000000003";
            WithdrawalFixture retryingNative = createWithdrawal(
                    tenantA, tenantA.authorities().get(0), nativeSymbol,
                    rejectingReceiver.address(), new BigDecimal("0.1"), "native-retry");
            WithdrawalFixture successfulNative = createWithdrawal(
                    tenantA, tenantA.authorities().get(1), nativeSymbol,
                    nativeRecipient, new BigDecimal("0.2"), "native-success");
            String partialTx = withdrawalWorkflow.processOne(profile).orElseThrow();
            withdrawalWorkflow.confirm(profile);
            reconciliationJob.reconcile();
            assertEquals("RETRYING", withdrawalStatus(retryingNative));
            assertEquals("CONFIRMED", withdrawalStatus(successfulNative));
            assertEquals("PARTIAL_FAILED", jdbc.queryForObject("""
                    select status from evm_withdrawal_batch
                     where tenant_id = ? and canonical_tx_hash = ?
                    """, String.class, tenantA.tenantId(), partialTx));
            assertEquals("0x1", sendLegacyCall(
                    web3j, adminNonce, rejectingReceiver.address(),
                    FunctionEncoder.encode(new Function(
                            "setAccepting", List.of(new Bool(true)), List.of()))).getStatus());
            adminNonce = adminNonce.add(BigInteger.ONE);
            String retryTx = withdrawalWorkflow.processOne(profile).orElseThrow();
            assertNotEquals(partialTx, retryTx);
            withdrawalWorkflow.confirm(profile);
            reconciliationJob.reconcile();
            assertEquals("CONFIRMED", withdrawalStatus(retryingNative));
            assertEquals(new BigInteger("100000000000000000"),
                    web3j.ethGetBalance(rejectingReceiver.address(), DefaultBlockParameterName.LATEST)
                            .send().getBalance());

            WithdrawalFixture tenantBTokenOne = createWithdrawal(
                    tenantB, tenantB.authorities().get(0), "USDT",
                    "0x6000000000000000000000000000000000000004",
                    new BigDecimal("1"), "token-b-1");
            WithdrawalFixture tenantBTokenTwo = createWithdrawal(
                    tenantB, tenantB.authorities().get(0), "USDT",
                    "0x6000000000000000000000000000000000000005",
                    new BigDecimal("1"), "token-b-2");
            String tenantBPayoutTx = withdrawalWorkflow.processOne(profile).orElseThrow();
            withdrawalWorkflow.confirm(profile);
            reconciliationJob.reconcile();
            assertNotEquals(tokenPayoutTx, tenantBPayoutTx);
            assertSharedConfirmedPayout(
                    tenantBPayoutTx, tenantBTokenOne, tenantBTokenTwo);

            List<AuthorityFixture> usdcAuthorities = List.of(
                    tenantA.authorities().get(0), tenantA.authorities().get(1),
                    tenantB.authorities().get(0), tenantB.authorities().get(1));
            for (AuthorityFixture authority : usdcAuthorities) {
                TransactionReceipt mint = sendLegacyCall(web3j, adminNonce, usdc.address(),
                        encodeMint(authority.credentials().getAddress(), authority.amountAtomic()));
                adminNonce = adminNonce.add(BigInteger.ONE);
                assertEquals("0x1", mint.getStatus());
                assertEquals(1, depositScanner.scanAndCreditErc20(
                        ChainType.valueOf(chain), RPC, 1,
                        mint.getBlockNumber().longValueExact()).size());
            }
            assertCreditedDeposits("USDC", 4);
            createCollectionCandidates("USDC", 4);
            String firstUsdcCollectionTx = workflow.processOne(profile).orElseThrow();
            workflow.confirm(profile);
            String secondUsdcCollectionTx = workflow.processOne(profile).orElseThrow();
            workflow.confirm(profile);
            assertNotEquals(firstUsdcCollectionTx, secondUsdcCollectionTx);
            String tenantAUsdcCollectionTx = collectionTx(tenantA.tenantId(), "USDC");
            String tenantBUsdcCollectionTx = collectionTx(tenantB.tenantId(), "USDC");
            assertNotEquals(tenantAUsdcCollectionTx, tenantBUsdcCollectionTx);
            assertAssetCollectionBatch(
                    web3j, tenantA, "USDC", tenantAUsdcCollectionTx,
                    tenantA.authorities().subList(0, 2));
            assertAssetCollectionBatch(
                    web3j, tenantB, "USDC", tenantBUsdcCollectionTx,
                    tenantB.authorities());

            WithdrawalFixture tenantAUsdcOne = createWithdrawal(
                    tenantA, tenantA.authorities().get(0), "USDC",
                    "0x6000000000000000000000000000000000000006",
                    new BigDecimal("1"), "usdc-a-1");
            WithdrawalFixture tenantAUsdcTwo = createWithdrawal(
                    tenantA, tenantA.authorities().get(1), "USDC",
                    "0x6000000000000000000000000000000000000007",
                    new BigDecimal("2"), "usdc-a-2");
            String tenantAUsdcPayoutTx = withdrawalWorkflow.processOne(profile).orElseThrow();
            withdrawalWorkflow.confirm(profile);
            reconciliationJob.reconcile();
            assertSharedConfirmedPayout(
                    tenantAUsdcPayoutTx, tenantAUsdcOne, tenantAUsdcTwo);

            WithdrawalFixture tenantBUsdcOne = createWithdrawal(
                    tenantB, tenantB.authorities().get(0), "USDC",
                    "0x6000000000000000000000000000000000000008",
                    new BigDecimal("1"), "usdc-b-1");
            assertEquals(BigInteger.ZERO, web3j.ethGetBalance(
                    tenantB.authorities().get(1).credentials().getAddress(),
                    DefaultBlockParameterName.LATEST).send().getBalance());
            WithdrawalFixture tenantBUsdcTwo = createWithdrawal(
                    tenantB, tenantB.authorities().get(1), "USDC",
                    "0x6000000000000000000000000000000000000009",
                    new BigDecimal("2"), "usdc-b-2");
            String tenantBUsdcPayoutTx = withdrawalWorkflow.processOne(profile).orElseThrow();
            withdrawalWorkflow.confirm(profile);
            reconciliationJob.reconcile();
            assertNotEquals(tenantAUsdcPayoutTx, tenantBUsdcPayoutTx);
            assertSharedConfirmedPayout(
                    tenantBUsdcPayoutTx, tenantBUsdcOne, tenantBUsdcTwo);
            assertEquals(new BigInteger("1000000"), tokenBalance(
                    web3j, tenantAUsdcOne.recipient(), "USDC"));
            assertEquals(new BigInteger("2000000"), tokenBalance(
                    web3j, tenantAUsdcTwo.recipient(), "USDC"));
            assertEquals(new BigInteger("1000000"), tokenBalance(
                    web3j, tenantBUsdcOne.recipient(), "USDC"));
            assertEquals(new BigInteger("2000000"), tokenBalance(
                    web3j, tenantBUsdcTwo.recipient(), "USDC"));

            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from evm_withdrawal_batch_item item
                    join evm_withdrawal_batch batch on batch.id = item.batch_id
                    join withdrawal_order withdrawal on withdrawal.id = item.withdrawal_order_id
                    where item.tenant_id <> batch.tenant_id
                       or item.tenant_id <> withdrawal.tenant_id
                    """, Integer.class));
            assertEquals(6, jdbc.queryForObject("""
                    select count(*) from custody_gas_usage
                     where operation_type = 'WITHDRAWAL_BATCH' and status = 'SETTLED'
                    """, Integer.class));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from custody_gas_usage
                     where operation_type = 'WITHDRAWAL' and status <> 'RELEASED'
                    """, Integer.class));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*)
                      from custody_ledger_entry entry
                      join custody_gas_account gas
                        on gas.tenant_id = entry.tenant_id and gas.chain = entry.chain
                      join custody_address address
                        on address.tenant_id = gas.tenant_id
                       and address.id = gas.custody_address_id
                      join chain_address base
                        on base.tenant_id = address.tenant_id
                       and base.id = address.chain_address_id
                     where entry.entry_type = 'NETWORK_FEE'
                       and lower(entry.account_id) <> lower(base.account_id)
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

    private void simulateLostPayoutBroadcastResponse(UUID tenantId) {
        jdbc.update("""
                update evm_withdrawal_batch
                   set status = 'BROADCAST_UNKNOWN', canonical_tx_hash = null, submitted_at = null
                 where tenant_id = ? and status = 'SUBMITTED'
                """, tenantId);
        jdbc.update("""
                update evm_withdrawal_batch_attempt attempt
                   set status = 'UNKNOWN', submitted_at = null
                  from evm_withdrawal_batch batch
                 where batch.tenant_id = ? and batch.id = attempt.batch_id
                   and attempt.status = 'SUBMITTED'
                """, tenantId);
        jdbc.update("""
                update evm_withdrawal_batch_item
                   set status = 'SIGNED'
                 where tenant_id = ? and status = 'SUBMITTED'
                """, tenantId);
        jdbc.update("""
                update withdrawal_order withdrawal
                   set status = 'SIGNING', tx_hash = null
                  from evm_withdrawal_batch_item item
                 where item.tenant_id = ? and item.batch_id = (
                       select id from evm_withdrawal_batch
                        where tenant_id = ? and status = 'BROADCAST_UNKNOWN'
                        order by created_at desc limit 1)
                   and withdrawal.tenant_id = item.tenant_id
                   and withdrawal.id = item.withdrawal_order_id
                   and withdrawal.status = 'SENT'
                """, tenantId, tenantId);
    }

    private void configureToken(String symbol, String contractAddress) {
        assertEquals(1, jdbc.update("""
                update token_config set contract_address = ?, contract_address_hex = ?,
                       decimals = 6, standard = 'ERC20', token_standard = 'ERC20',
                       collect_enabled = true
                 where chain = ? and symbol = ? and network = 'local'
                """, contractAddress, contractAddress, chain, symbol));
        assertEquals(1, jdbc.update("""
                update chain_asset set contract_address = ?, decimals = 6
                 where chain = ? and symbol = ?
                """, contractAddress, chain, symbol));
    }

    private void assertCreditedDeposits(String symbol, int expected) {
        assertEquals(expected, jdbc.queryForObject("""
                select count(*) from deposit_record
                 where chain = ? and asset_symbol = ? and credited = true
                """, Integer.class, chain, symbol));
        assertEquals(expected, jdbc.queryForObject("""
                select count(*) from custody_deposit
                 where chain = ? and asset_symbol = ? and status = 'CONFIRMED'
                """, Integer.class, chain, symbol));
        assertEquals(expected, jdbc.queryForObject("""
                select count(*) from custody_ledger_entry
                 where chain = ? and asset_symbol = ? and entry_type = 'DEPOSIT'
                """, Integer.class, chain, symbol));
    }

    private WithdrawalFixture createWithdrawal(
            TenantFixture tenant, AuthorityFixture source, String symbol,
            String recipient, BigDecimal amount, String suffix) {
        String idempotencyKey = "e2e-7702-" + suffix;
        CustodyPrincipal principal = new CustodyPrincipal(
                CustodyPrincipal.ActorType.API_KEY, UUID.nameUUIDFromBytes(
                        (tenant.tenantId() + ":" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                tenant.tenantId(), "e2e", "API_KEY", Set.of("withdrawals:write"));
        CustodyWithdrawalService.CreateWithdrawalCommand command =
                new CustodyWithdrawalService.CreateWithdrawalCommand(
                        source.custodyAddressId(), chain, symbol, recipient,
                        amount.toPlainString(), suffix, true);
        CustodyWithdrawalService.WithdrawalView created = withdrawalService.create(
                principal, command, "API", idempotencyKey, "127.0.0.1");
        CustodyWithdrawalService.WithdrawalView replay = withdrawalService.create(
                principal, command, "API", idempotencyKey, "127.0.0.1");
        assertEquals(created.id(), replay.id());
        assertEquals("FROZEN", created.status());
        Long orderId = jdbc.queryForObject("""
                select id from withdrawal_order
                 where tenant_id = ? and chain = ? and order_no = ?
                """, Long.class, tenant.tenantId(), chain, created.orderNo());
        return new WithdrawalFixture(
                tenant.tenantId(), created.id(), orderId, created.orderNo(),
                symbol, recipient, amount);
    }

    private void assertSharedConfirmedPayout(
            String txHash, WithdrawalFixture... withdrawals) {
        assertTrue(withdrawals.length > 1);
        UUID expectedBatchId = null;
        for (WithdrawalFixture withdrawal : withdrawals) {
            assertEquals("CONFIRMED", withdrawalStatus(withdrawal));
            assertEquals(txHash, jdbc.queryForObject("""
                    select tx_hash from withdrawal_order
                     where tenant_id = ? and id = ?
                    """, String.class, withdrawal.tenantId(), withdrawal.orderId()));
            assertEquals("CONFIRMED", jdbc.queryForObject("""
                    select status from custody_withdrawal
                     where tenant_id = ? and id = ?
                    """, String.class, withdrawal.tenantId(), withdrawal.custodyWithdrawalId()));
            UUID batchId = jdbc.queryForObject("""
                    select batch_id from evm_withdrawal_batch_item
                     where tenant_id = ? and withdrawal_order_id = ? and status = 'CONFIRMED'
                    """, UUID.class, withdrawal.tenantId(), withdrawal.orderId());
            if (expectedBatchId == null) expectedBatchId = batchId;
            else assertEquals(expectedBatchId, batchId);
        }
        assertEquals(withdrawals.length, jdbc.queryForObject(
                "select count(*) from evm_withdrawal_batch_item where batch_id = ? and status = 'CONFIRMED'",
                Integer.class, expectedBatchId));
    }

    private String withdrawalStatus(WithdrawalFixture withdrawal) {
        return jdbc.queryForObject("""
                select status from withdrawal_order
                 where tenant_id = ? and id = ?
                """, String.class, withdrawal.tenantId(), withdrawal.orderId());
    }

    private TenantFixture createTenant(String slug, int namespace, int itemCount, long userBase) throws Exception {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("insert into custody_tenant(id, slug, name, derivation_namespace) values (?, ?, ?, ?)",
                tenantId, slug, slug, namespace);
        UUID tenantAdminId = UUID.randomUUID();
        jdbc.update("""
                insert into custody_tenant_user(
                    id, tenant_id, email, display_name, password_hash, role, status)
                values (?, ?, ?, ?, 'integration-only', 'TENANT_ADMIN', 'ACTIVE')
                """, tenantAdminId, tenantId, slug + "@example.invalid", slug + " admin");
        jdbc.update("""
                insert into custody_tenant_chain(
                    tenant_id, chain, status, opened_by, opened_at)
                values (?, ?, 'ACTIVE', ?, now())
                """, tenantId, chain, tenantAdminId);
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

    private void createCollectionCandidates(String symbol, int expected) {
        List<CollectionCandidateRecord> candidates = chainRepository.listCollectableLedgerBalances(
                chain, BigDecimal.ZERO, 20);
        assertEquals(expected, candidates.size());
        assertTrue(candidates.stream().allMatch(
                candidate -> symbol.equals(candidate.getAssetSymbol())));
        assertEquals(2, candidates.stream().map(CollectionCandidateRecord::getTenantId).distinct().count());
        for (CollectionCandidateRecord candidate : candidates) {
            String hotWallet = chainRepository.findActiveTenantCollectionAddress(
                    candidate.getTenantId(), candidate.getChain()).orElseThrow();
            assertEquals(1, chainRepository.createCollectionRecord(
                    candidate.getTenantId(), candidate.getCustodyAddressId(),
                    "7702:" + symbol + ":" + candidate.getTenantId() + ":" + candidate.getAddress(),
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

    private String collectionTx(UUID tenantId, String symbol) {
        return jdbc.queryForObject("""
                select canonical_tx_hash from evm_collection_batch
                 where tenant_id = ? and asset_symbol = ? and status = 'CONFIRMED'
                """, String.class, tenantId, symbol);
    }

    private void assertAssetCollectionBatch(
            Web3j web3j, TenantFixture tenant, String symbol, String txHash,
            List<AuthorityFixture> authorities) throws Exception {
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from evm_collection_batch
                 where tenant_id = ? and asset_symbol = ? and canonical_tx_hash = ?
                   and status = 'CONFIRMED'
                """, Integer.class, tenant.tenantId(), symbol, txHash));
        assertEquals(authorities.size(), jdbc.queryForObject("""
                select count(*)
                  from evm_collection_batch_item item
                  join evm_collection_batch batch
                    on batch.tenant_id = item.tenant_id and batch.id = item.batch_id
                 where batch.tenant_id = ? and batch.asset_symbol = ?
                   and batch.canonical_tx_hash = ? and item.status = 'CONFIRMED'
                """, Integer.class, tenant.tenantId(), symbol, txHash));
        BigInteger expected = authorities.stream()
                .map(AuthorityFixture::amountAtomic).reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(expected, tokenBalance(web3j, tenant.hotWallet(), symbol));
        for (AuthorityFixture authority : authorities) {
            assertEquals(BigInteger.ZERO,
                    tokenBalance(web3j, authority.credentials().getAddress(), symbol));
        }
    }

    private int batchCount(UUID tenantId) {
        return jdbc.queryForObject(
                "select count(*) from evm_collection_batch where tenant_id = ?", Integer.class, tenantId);
    }

    private int confirmedCollections(UUID tenantId) {
        return jdbc.queryForObject("select count(*) from collection_record where tenant_id = ? and status = 'CONFIRMED'",
                Integer.class, tenantId);
    }

    private int settledBatchGasUsages(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(*) from custody_gas_usage
                 where tenant_id = ? and operation_type = 'COLLECTION_BATCH' and status = 'SETTLED'
                """, Integer.class, tenantId);
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

    private Deployment deployMockToken(Web3j web3j, BigInteger nonce, String symbol) throws Exception {
        JsonNode artifact = new ObjectMapper().readTree(Files.readString(projectRoot().resolve(
                "resources/infra/evm-fork/artifacts/contracts/MockERC20.sol/MockERC20.json")));
        String bytecode = artifact.path("bytecode").asText();
        String constructor = FunctionEncoder.encodeConstructor(List.of(
                new Utf8String("Test " + symbol), new Utf8String(symbol), new Uint8(6)));
        RawTransaction raw = RawTransaction.createContractTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(5_000_000L), BigInteger.ZERO,
                bytecode + Numeric.cleanHexPrefix(constructor));
        TransactionReceipt receipt = sendRaw(web3j, raw);
        return new Deployment(receipt.getContractAddress());
    }

    private Deployment deployContract(Web3j web3j, BigInteger nonce, String artifactPath)
            throws Exception {
        JsonNode artifact = new ObjectMapper().readTree(
                Files.readString(projectRoot().resolve(artifactPath)));
        RawTransaction raw = RawTransaction.createContractTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(5_000_000L), BigInteger.ZERO,
                artifact.path("bytecode").asText());
        return new Deployment(sendRaw(web3j, raw).getContractAddress());
    }

    private Deployment deployPayoutDelegate(
            Web3j web3j, BigInteger nonce, String executor) throws Exception {
        JsonNode artifact = new ObjectMapper().readTree(Files.readString(projectRoot().resolve(
                "resources/infra/evm-fork/artifacts/contracts/Eip7702Payout.sol/Eip7702PayoutDelegate.json")));
        String constructor = FunctionEncoder.encodeConstructor(List.of(new Address(executor)));
        RawTransaction raw = RawTransaction.createContractTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(5_000_000L), BigInteger.ZERO,
                artifact.path("bytecode").asText() + Numeric.cleanHexPrefix(constructor));
        return new Deployment(sendRaw(web3j, raw).getContractAddress());
    }

    private TransactionReceipt sendLegacyCall(
            Web3j web3j, BigInteger nonce, String to, String data) throws Exception {
        return sendRaw(web3j, RawTransaction.createTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(800_000L), to, BigInteger.ZERO, data));
    }

    private TransactionReceipt sendLegacyNative(
            Web3j web3j, BigInteger nonce, String to, BigInteger value) throws Exception {
        return sendRaw(web3j, RawTransaction.createEtherTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(100_000L), to, value));
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
        return tokenBalance(web3j, owner, "USDT");
    }

    private BigInteger tokenBalance(Web3j web3j, String owner, String symbol) throws Exception {
        String token = jdbc.queryForObject(
                "select contract_address from token_config where chain = ? and symbol = ? and network = 'local'",
                String.class, chain, symbol);
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
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("resources/infra/evm-fork"))) {
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

    private record WithdrawalFixture(
            UUID tenantId, UUID custodyWithdrawalId, long orderId, String orderNo,
            String assetSymbol, String recipient, BigDecimal amount) {
    }
}
