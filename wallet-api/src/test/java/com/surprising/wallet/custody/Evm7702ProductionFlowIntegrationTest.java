package com.surprising.wallet.custody;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.ChainAddressRuntime;
import com.surprising.wallet.chain.evm.EvmDepositScanner;
import com.surprising.wallet.chain.evm.EvmHttpServiceFactory;
import com.surprising.wallet.chain.evm.EvmLogScanner;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.coordinator.Evm7702CollectionCoordinator;
import com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator;
import com.surprising.wallet.job.custody.CustodyWithdrawalReconciliationJob;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyWithdrawalRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.service.CustodyGasService;
import com.surprising.wallet.service.CustodyTenantChainService;
import com.surprising.wallet.service.CustodyWithdrawalExecutionService;
import com.surprising.wallet.service.CustodyWithdrawalReconciliationService;
import com.surprising.wallet.service.CustodyWithdrawalService;
import com.surprising.wallet.service.Evm7702CollectionWorkflowService;
import com.surprising.wallet.service.Evm7702WithdrawalWorkflowService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code Evm7702ProductionFlowIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class Evm7702ProductionFlowIntegrationTest {
    /**
     * 保存 {@code RPC}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private static final String RPC = "http://127.0.0.1:8545";
    /**
     * 保存 {@code GAS_PRICE}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
     */
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(2_000_000_000L);
    /**
     * 保存 {@code HARDHAT_ADMIN}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final String HARDHAT_ADMIN = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    /**
     * 保存 {@code jdbc}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private JdbcTemplate jdbc;
    /**
     * 保存 {@code chainRepository}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private ChainRepositoryFixture chainRepository;
    /**
     * 保存 {@code profile}，用于承载当前测试夹具的配置或运行数据。
     */
    private AccountChainProfile profile;
    /**
     * 保存 {@code collectionRepository}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private org.springframework.context.annotation.AnnotationConfigApplicationContext transactionContext;
    /**
     * 保存 {@code workflow}，用于承载当前测试夹具的配置或运行数据。
     */
    private Evm7702CollectionWorkflowService workflow;
    /**
     * 保存 {@code custodyRepository}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    /**
     * 保存 {@code depositScanner}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private EvmDepositScanner depositScanner;
    /**
     * 保存 {@code withdrawalService}，用于访问当前测试所依赖的仓储、客户端或服务。
     */
    private CustodyWithdrawalService withdrawalService;
    private CustodyWithdrawalService withdrawalTarget;
    /**
     * 保存 {@code withdrawalWorkflow}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private Evm7702WithdrawalWorkflowService withdrawalWorkflow;
    /** 保存 EIP-7702 提现仓储，用于验证批量提现运行目标发现。 */
    /**
     * 保存 {@code reconciliationJob}，用于承载当前测试夹具的配置或运行数据。
     */
    private CustodyWithdrawalReconciliationJob reconciliationJob;
    /**
     * 保存 {@code chain}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code nativeSymbol}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private String nativeSymbol;
    /**
     * 保存 {@code chainId}，表示测试所覆盖的链、网络、资产或代币配置。
     */
    private com.surprising.wallet.testutil.MpcNativeTestSupport mpcSupport;
    private com.surprising.wallet.service.Evm7702SigningService mpc7702;
    private long chainId;
    private EvmHttpServiceFactory httpServices;
    private com.surprising.wallet.chain.signing.MpcNodes signingNodes;
    private List<Integer> quorum;
    private Path nativeRoot;
    private BroadcastGate broadcastGate;
    private CustodyCryptoService crypto;
    private com.surprising.wallet.chain.evm.Evm7702RpcClient evmRpc;
    private WalletRuntimeConfigService runtimeConfig;

    /**
     * 验证 {@code setUp} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.7702.production.enabled"),
                "start Hardhat Prague, deploy the 7702 contracts, and set the integration properties");
        chain = System.getProperty("evm.7702.test.chain", "ETH").trim().toUpperCase(java.util.Locale.ROOT);
        chainId = Long.parseLong(System.getProperty("evm.7702.test.chain-id", "31337"));
        httpServices = new EvmHttpServiceFactory();
        DriverManagerDataSource dataSource = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        CustodyTenantChainTableRepository tenantChainRepository =
                new CustodyTenantChainTableRepository(jdbc);
        chainRepository = com.surprising.wallet.testutil.DepositTestSupport.repository(
                jdbc,
                com.surprising.wallet.testutil.DepositTestSupport.projection(
                        jdbc,
                        com.surprising.wallet.testutil.CustodyTestSupport.events(jdbc),
                        objectMapper));
        jdbc.update("""
                update chain_profile
                   set network = 'local', chain_id = ?, withdraw_confirmations = 1,
                       scan_enabled = true, withdraw_enabled = true,
                       collection_enabled = true, transfer_enabled = true
                 where chain = ? and enabled = true
                """,
                chainId, chain);
        jdbc.update("update token_config set network = 'local' where chain = ? and enabled = true", chain);
        jdbc.update("""
                insert into chain_rpc_node(
                    id, chain, network, environment, node_label, purpose,
                    connection_type, rpc_url, priority, min_request_interval_ms, enabled)
                values (9901, ?, 'local', 'eip7702-test', 'hardhat-prague',
                        'rpc', 'HTTP_JSON_RPC', ?, 1, 0, true)
                """, chain, RPC);
        profile = chainRepository.chainProfileRepository.findAccountByChain(chain).orElseThrow();
        nativeSymbol = profile.getNativeSymbol();
        depositScanner = com.surprising.wallet.testutil.EvmScanTestSupport.scanner(
                jdbc, ChainType.valueOf(chain), RPC, 1, httpServices);

        ChainRpcNodeService rpcNodes = new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc), "eip7702-test", 1);
        CustodySecurityProperties security = new CustodySecurityProperties();
        security.setSecretMasterKey("11".repeat(32));
        crypto = new CustodyCryptoService(security);
        mpcSupport=new com.surprising.wallet.testutil.MpcNativeTestSupport(jdbc);
        nativeRoot=Path.of(System.getenv("SW_TEST_MPC_DIRECTORY"));
        var nativeConfig=objectMapper.readTree(Files.readString(nativeRoot.resolve("java-probe.json")));
        quorum=java.util.stream.StreamSupport.stream(nativeConfig.path("pair").spliterator(),false)
                .map(JsonNode::asInt).toList();
        assertEquals(2,quorum.size());assertEquals(2,quorum.stream().distinct().count());
        signingNodes=Mockito.spy(mpcSupport.nodes);
        Mockito.doReturn(quorum.stream().map(index->mpcSupport.nodes.parties().get(index)).toList()).when(signingNodes).online();
        mpc7702=new com.surprising.wallet.service.Evm7702SigningService(new com.surprising.wallet.repository.WalletMpcSignRequestRepository(jdbc),
                new com.surprising.wallet.repository.ChainAddressRepository(jdbc),new com.surprising.wallet.repository.EvmNonceRepository(jdbc),
                new com.surprising.wallet.repository.EvmCollectionBatchRepository(jdbc),new com.surprising.wallet.repository.EvmWithdrawalBatchRepository(jdbc),
                mpcSupport.signing,signingNodes,objectMapper,new com.surprising.wallet.repository.CustodyGasUsageRepository(jdbc));
        runtimeConfig =
                new WalletRuntimeConfigService(new ChainProfileRepository(jdbc),
                        new com.surprising.wallet.repository.WalletSystemConfigRepository(jdbc));
        CustodyTenantChainService tenantChainService = com.surprising.wallet.testutil.CustodyTestSupport.tenantChains(jdbc, org.mockito.Mockito.mock(com.surprising.wallet.chain.BlockchainRuntimeService.class));
        CustodyWithdrawalExecutionService withdrawalExecution =
                new CustodyWithdrawalExecutionService(
                        chainRepository.withdrawalOrderRepository,
                        chainRepository.collectionPlanningService,
                        runtimeConfig,
                        new BlockchainRuntimeService(
                                new ChainProfileRepository(jdbc),
                                new ChainAssetRepository(jdbc),
                                new ChainAddressRuntime(),
                                null),
                        new ChainAssetRepository(jdbc),
                        new ChainProfileRepository(jdbc),
                        new TokenConfigRepository(jdbc),
                        new LedgerBalanceRepository(jdbc), com.surprising.wallet.testutil.CustodyTestSupport.poolFunding(jdbc));
        withdrawalService = new CustodyWithdrawalService(


               withdrawalExecution,
                com.surprising.wallet.testutil.CustodyTestSupport.gas(
                        jdbc,

                       Mockito.mock(com.surprising.wallet.service.CustodyAddressService.class)),
                tenantChainService,
                crypto,
                objectMapper,
                com.surprising.wallet.testutil.CustodyTestSupport.events(jdbc),
                new com.surprising.wallet.repository.CustodyAddressRepository(jdbc),
                new com.surprising.wallet.repository.CustodyGasAccountRepository(jdbc), new com.surprising.wallet.repository.CustodyTenantTableRepository(jdbc), new com.surprising.wallet.repository.ChainAddressRepository(jdbc), new com.surprising.wallet.repository.CustodyReorgDeficitRepository(jdbc), new com.surprising.wallet.repository.CustodyIdempotencyRepository(jdbc), new com.surprising.wallet.repository.CustodyWithdrawalRepository(jdbc), new com.surprising.wallet.repository.CustodyAuditRepository(jdbc), com.surprising.wallet.testutil.CustodyTestSupport.poolFunding(jdbc));
        transactionContext = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        transactionContext.register(Transactions.class);
        transactionContext.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
                () -> new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        transactionContext.registerBean(Evm7702CollectionCoordinator.class,
                () -> com.surprising.wallet.testutil.CustodyTestSupport.evmCollections(jdbc,mpc7702,crypto));
        transactionContext.registerBean(Evm7702WithdrawalCoordinator.class,
                () -> com.surprising.wallet.testutil.CustodyTestSupport.evmWithdrawals(jdbc,mpc7702,crypto));
        withdrawalTarget = withdrawalService;
        transactionContext.registerBean(CustodyWithdrawalService.class, () -> withdrawalTarget);
        transactionContext.refresh();
        withdrawalService = transactionContext.getBean(CustodyWithdrawalService.class);
        evmRpc = new com.surprising.wallet.chain.evm.Evm7702RpcClient(rpcNodes, httpServices);
        broadcastGate=new BroadcastGate();
        jdbc.update("update chain_rpc_node set rpc_url=? where id=9901",broadcastGate.endpoint());
        workflow = com.surprising.wallet.testutil.CustodyTestSupport.evmCollectionWorkflow(jdbc,
                transactionContext.getBean(Evm7702CollectionCoordinator.class), evmRpc, mpc7702, crypto, runtimeConfig);
        withdrawalWorkflow = com.surprising.wallet.testutil.CustodyTestSupport.evmWithdrawalWorkflow(jdbc,
                transactionContext.getBean(Evm7702WithdrawalCoordinator.class), evmRpc, mpc7702, crypto, runtimeConfig);
        reconciliationJob = new CustodyWithdrawalReconciliationJob(
                com.surprising.wallet.testutil.CustodyTestSupport.withdrawalReconciliation(jdbc, objectMapper));
    }

    @AfterEach
    void tearDown() {
        if(broadcastGate!=null)broadcastGate.close();
        if(mpcSupport!=null)mpcSupport.close();
        if (transactionContext != null) transactionContext.close();
        if (httpServices != null) {
            httpServices.close();
        }
    }

    /**
     * 验证 {@code shouldCollectRealDepositsInOneTxPerTenantAndSettleGasOnce} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void shouldCollectRealDepositsInOneTxPerTenantAndSettleGasOnce() throws Exception {
        String collector = requiredProperty("evm.7702.collector");
        String delegate = requiredProperty("evm.7702.delegate");
        Web3j web3j = Web3j.build(new HttpService(RPC));
        try {
            BigInteger adminNonce = web3j.ethGetTransactionCount(
                    HARDHAT_ADMIN, DefaultBlockParameterName.PENDING).send().getTransactionCount();
            Deployment usdt = deployMockToken(web3j, adminNonce, "USDT");
            adminNonce = adminNonce.add(BigInteger.ONE);
            Deployment usdc = deployMockToken(web3j, adminNonce, "USDC");
            adminNonce = adminNonce.add(BigInteger.ONE);

            ChainAddressRecord relayerRecord = keyRecord(mpcSupport.tenant, 9_000_001L, 7702, 0L, "EIP7702_RELAYER");
            String relayer = relayerRecord.getAddress();
            long relayerChainAddressId = insertChainAddress(relayerRecord, mpcSupport.tenant);
            Deployment payoutDeployment = deployPayoutDelegate(
                    web3j, adminNonce, relayer);
            String payoutDelegate = payoutDeployment.address();
            adminNonce = adminNonce.add(BigInteger.ONE);
            assertEquals("0x1", sendLegacyCall(web3j, adminNonce, collector,
                    FunctionEncoder.encode(new Function("setRelayer",
                            List.of(new Address(relayer), new Bool(true)), List.of()))).getStatus());
            adminNonce = adminNonce.add(BigInteger.ONE);
            assertEquals("0x1", sendLegacyNative(web3j, adminNonce, relayer,
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
                    relayerChainAddressId, relayer);
            var collectionTargets = workflow.listRuntimeTargets();
            var withdrawalTargets = withdrawalWorkflow.listRuntimeTargets();
            assertEquals(List.of(chain), collectionTargets.stream().map(target -> target.profile().getChain()).toList());
            assertEquals(List.of(chain), withdrawalTargets.stream().map(target -> target.profile().getChain()).toList());
            assertEquals("local", collectionTargets.getFirst().profile().getNetwork());
            assertEquals("local", withdrawalTargets.getFirst().profile().getNetwork());
            assertTrue(collectionTargets.getFirst().active());
            assertTrue(withdrawalTargets.getFirst().active());
            configureToken("USDT", usdt.address());
            configureToken("USDC", usdc.address());

            // The dev node is intentionally persistent. Use a fresh derivation namespace on every
            // run so a prior test's 7702 delegation and nonce cannot leak into this test fixture.
            int runNamespace = 10_000 + adminNonce.mod(BigInteger.valueOf(1_000_000)).intValueExact() * 2;
            TenantFixture tenantA = createTenant("tenant-7702-a", runNamespace, 3, 1000L);
            TenantFixture tenantB = createTenant("tenant-7702-b", runNamespace + 1, 2, 2000L);
            awaitStoppedThird();
            for (AuthorityFixture authority : concat(tenantA.authorities(), tenantB.authorities())) {
                TransactionReceipt mint = sendLegacyCall(web3j, adminNonce, usdt.address(),
                        encodeMint(authority.address(), authority.amountAtomic()));
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

            broadcastGate.loseResponses=true;
            try { assertThrows(IllegalStateException.class,()->workflow.processOne(profile)); }
            finally { broadcastGate.loseResponses=false; }
            String tenantATx=broadcastGate.lastHash;
            broadcastGate.assertHealthy();
            assertEquals(4, batchCount(tenantA.tenantId()));
            assertEquals(0, batchCount(tenantB.tenantId()));
            assertEquals("BROADCAST_UNKNOWN",jdbc.queryForObject("select status from evm_collection_batch where canonical_tx_hash=?",String.class,tenantATx));
            recoverOriginal(false,tenantATx);
            assertEquals("CONFIRMED", jdbc.queryForObject(
                    "select status from evm_collection_batch where tenant_id = ? and status = 'CONFIRMED'",
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
            assertEquals(
                    2,
                    jdbc.queryForObject("select count(*) from custody_gas_usage where operation_type = 'COLLECTION_BATCH' and status = 'SETTLED'", Integer.class));
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
                        web3j, adminNonce, authority.address(), nativeAmounts[index]);
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
            broadcastGate.loseResponses=true;
            try { assertThrows(IllegalStateException.class,()->withdrawalWorkflow.processOne(profile)); }
            finally { broadcastGate.loseResponses=false; }
            String tokenPayoutTx=broadcastGate.lastHash;
            broadcastGate.assertHealthy();
            assertEquals("BROADCAST_UNKNOWN",jdbc.queryForObject("select status from evm_withdrawal_batch where canonical_tx_hash=?",String.class,tokenPayoutTx));
            recoverOriginal(true,tokenPayoutTx);
            jdbc.update("update chain_address set enabled=false where id=?", relayerChainAddressId);
            try {
                withdrawalWorkflow.confirm(profile);
            } finally {
                jdbc.update("update chain_address set enabled=true where id=?", relayerChainAddressId);
            }
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
                        encodeMint(authority.address(), authority.amountAtomic()));
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
                    tenantB.authorities().get(1).address(),
                    DefaultBlockParameterName.LATEST).send().getBalance());
            WithdrawalFixture tenantBUsdcTwo = createWithdrawal(
                    tenantB, tenantB.authorities().get(1), "USDC",
                    tenantA.authorities().get(2).address(),
                    new BigDecimal("2"), "usdc-b-2");
            String tenantBUsdcPayoutTx = withdrawalWorkflow.processOne(profile).orElseThrow();
            withdrawalWorkflow.confirm(profile);
            reconciliationJob.reconcile();
            assertNotEquals(tenantAUsdcPayoutTx, tenantBUsdcPayoutTx);
            assertSharedConfirmedPayout(
                    tenantBUsdcPayoutTx, tenantBUsdcOne, tenantBUsdcTwo);
            long ownedReceiptHeight=waitReceipt(web3j,tenantBUsdcPayoutTx).getBlockNumber().longValueExact();
            for(int scan=0;scan<3;scan++)depositScanner.scanAndCreditErc20(ChainType.valueOf(chain),RPC,1,ownedReceiptHeight);
            assertCreditedDeposits("USDC",5);
            assertEquals(tenantA.tenantId(),jdbc.queryForObject("select tenant_id from deposit_record where tx_hash=? and lower(to_address)=?",
                    UUID.class,tenantBUsdcPayoutTx,tenantBUsdcTwo.recipient().toLowerCase(java.util.Locale.ROOT)));
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
            writePublicProof(web3j,tenantA,tenantB,relayer);
        } finally {
            web3j.shutdown();
        }
    }

    private void awaitStoppedThird() throws Exception {
        assertEquals(8,jdbc.queryForObject("select count(*) from wallet_mpc_key where state='ACTIVE'",Integer.class));
        Files.writeString(nativeRoot.resolve("7702-keys-ready"),"ready");
        awaitMarker("7702-third-stopped");
    }

    private void awaitMarker(String name) throws Exception {
        Instant deadline=Instant.now().plusSeconds(90);
        while(!Files.isRegularFile(nativeRoot.resolve(name)) && Instant.now().isBefore(deadline))Thread.sleep(50);
        assertTrue(Files.isRegularFile(nativeRoot.resolve(name)),"native driver did not acknowledge "+name);
        var proof=new ObjectMapper().readTree(Files.readString(nativeRoot.resolve(name)));
        assertEquals(quorum,new ObjectMapper().convertValue(proof.path("pair"),new tools.jackson.core.type.TypeReference<List<Integer>>(){}));
        assertFalse(quorum.contains(proof.path("absent").asInt()));
    }

    /** Recreate application services using the original durable database and configured encryption keys. */
    private void recreateWorkflows() {
        var json=new ObjectMapper();
        var signing=new com.surprising.wallet.service.MpcSigningService(
                new com.surprising.wallet.repository.CustodyTenantTableRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcKeyRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcKeyEpochRepository(jdbc,json),
                new com.surprising.wallet.repository.WalletMpcSignRequestRepository(jdbc),
                new com.surprising.wallet.repository.WalletMpcSignAttemptRepository(jdbc,json),mpcSupport.crypto,json,mpcSupport.manager);
        mpc7702=new com.surprising.wallet.service.Evm7702SigningService(
                new com.surprising.wallet.repository.WalletMpcSignRequestRepository(jdbc),new ChainAddressRepository(jdbc),
                new com.surprising.wallet.repository.EvmNonceRepository(jdbc),new com.surprising.wallet.repository.EvmCollectionBatchRepository(jdbc),
                new com.surprising.wallet.repository.EvmWithdrawalBatchRepository(jdbc),signing,signingNodes,json,
                new com.surprising.wallet.repository.CustodyGasUsageRepository(jdbc));
        transactionContext.close();
        transactionContext=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        transactionContext.register(Transactions.class);
        transactionContext.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->mpcSupport.manager);
        transactionContext.registerBean(Evm7702CollectionCoordinator.class,()->com.surprising.wallet.testutil.CustodyTestSupport.evmCollections(jdbc,mpc7702,crypto));
        transactionContext.registerBean(Evm7702WithdrawalCoordinator.class,()->com.surprising.wallet.testutil.CustodyTestSupport.evmWithdrawals(jdbc,mpc7702,crypto));
        transactionContext.registerBean(CustodyWithdrawalService.class,()->withdrawalTarget);
        transactionContext.refresh();
        withdrawalService=transactionContext.getBean(CustodyWithdrawalService.class);
        workflow=com.surprising.wallet.testutil.CustodyTestSupport.evmCollectionWorkflow(jdbc,
                transactionContext.getBean(Evm7702CollectionCoordinator.class),evmRpc,mpc7702,crypto,runtimeConfig);
        withdrawalWorkflow=com.surprising.wallet.testutil.CustodyTestSupport.evmWithdrawalWorkflow(jdbc,
                transactionContext.getBean(Evm7702WithdrawalCoordinator.class),evmRpc,mpc7702,crypto,runtimeConfig);
    }

    private void recoverOriginal(boolean payout,String hash) throws Exception {
        var json=new ObjectMapper();
        String before=json.writeValueAsString(jdbc.queryForList("select id,current_attempt_id,payload_hex,preimage_hex from wallet_mpc_sign_request order by id"));
        String phase=payout?"payout":"collection";
        Files.writeString(nativeRoot.resolve("7702-"+phase+"-restart-ready"),"ready");
        awaitMarker("7702-"+phase+"-restarted");
        recreateWorkflows();
        // Accepted work must finish even when all new-business entry points are disabled.
        jdbc.update("update chain_profile set enabled=false,withdraw_enabled=false,collection_enabled=false where id=?",profile.getId());
        jdbc.update("update chain_asset set active=false where chain=?",chain);
        jdbc.update("update token_config set enabled=false where chain=? and network=?",chain,profile.getNetwork());
        jdbc.update("update chain_address set enabled=false where chain=?",chain);
        jdbc.update("update evm_7702_config set status='PAUSED' where chain=? and network=?",chain,profile.getNetwork());
        try {
            if(payout)withdrawalWorkflow.run();else workflow.run();
            broadcastGate.assertHealthy();
            String table=payout?"evm_withdrawal_batch":"evm_collection_batch";
            assertEquals("CONFIRMED",jdbc.queryForObject("select status from "+table+" where canonical_tx_hash=?",String.class,hash));
            assertEquals(before,json.writeValueAsString(jdbc.queryForList("select id,current_attempt_id,payload_hex,preimage_hex from wallet_mpc_sign_request order by id")),"recovery must retain all original intents and attempts");
        } finally {
            jdbc.update("update chain_profile set enabled=true,withdraw_enabled=true,collection_enabled=true where id=?",profile.getId());
            jdbc.update("update chain_asset set active=true where chain=?",chain);
            jdbc.update("update token_config set enabled=true where chain=? and network=?",chain,profile.getNetwork());
            jdbc.update("update chain_address set enabled=true where chain=?",chain);
            jdbc.update("update evm_7702_config set status='ACTIVE' where chain=? and network=?",chain,profile.getNetwork());
        }
    }

    private void writePublicProof(Web3j web3j,TenantFixture a,TenantFixture b,String relayer) throws Exception {
        broadcastGate.assertHealthy();
        assertEquals(12,broadcastGate.transactions.size());
        assertEquals(2,broadcastGate.acceptedLostResponses.size());
        String balancesBefore=ledgerSnapshot();
        int entriesBefore=jdbc.queryForObject("select count(*) from custody_ledger_entry",Integer.class);
        int requestsBefore=jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class);
        BigInteger fees=BigInteger.ZERO;
        var publicTransactions=new ArrayList<Map<String,Object>>();
        for(var item:broadcastGate.transactions.values()) {
            var receipt=waitReceipt(web3j,(String)item.get("transactionHash"));
            assertEquals("0x1",receipt.getStatus());
            for(int scan=0;scan<2;scan++) {
                depositScanner.scanAndCreditNative(ChainType.valueOf(chain),nativeSymbol,RPC,1,receipt.getBlockNumber().longValueExact());
                depositScanner.scanAndCreditErc20(ChainType.valueOf(chain),RPC,1,receipt.getBlockNumber().longValueExact());
            }
            BigInteger fee=receipt.getGasUsed().multiply(Numeric.decodeQuantity(receipt.getEffectiveGasPrice()));
            fees=fees.add(fee);
            var proof=new java.util.LinkedHashMap<>(item);proof.put("feeAtomic",fee.toString());publicTransactions.add(proof);
        }
        workflow.run();withdrawalWorkflow.run();
        assertEquals(balancesBefore,ledgerSnapshot(),"internal/duplicate scans and completed-job replay cannot change balances");
        assertEquals(entriesBefore,jdbc.queryForObject("select count(*) from custody_ledger_entry",Integer.class));
        assertEquals(requestsBefore,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        assertCreditedDeposits("USDT",5);assertCreditedDeposits(nativeSymbol,3);assertCreditedDeposits("USDC",5);
        BigDecimal charged=jdbc.queryForObject("select sum(actual_amount) from custody_gas_usage where status='SETTLED'",BigDecimal.class);
        assertEquals(fees,charged.movePointRight(18).toBigIntegerExact());
        assertEquals(new BigInteger("5000000000000000000").subtract(fees),
                web3j.ethGetBalance(relayer,DefaultBlockParameterName.LATEST).send().getBalance());
        assertEquals(BigInteger.valueOf(12),web3j.ethGetTransactionCount(relayer,DefaultBlockParameterName.LATEST).send().getTransactionCount());
        assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where state<>'COMPLETED'",Integer.class));
        var expectedOnline=quorum.stream().map(index->mpcSupport.nodes.parties().get(index)).toList();
        for(String request:jdbc.queryForList("select request::text from wallet_mpc_sign_attempt",String.class))
            assertEquals(expectedOnline,jsonList(new ObjectMapper().readTree(request).path("online")));
        assertEquals(0,jdbc.queryForObject("select count(*) from ledger_balance where tenant_id in (?,?) and locked_balance<>0",Integer.class,a.tenantId(),b.tenantId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from withdrawal_order where tenant_id in (?,?) and status<>'CONFIRMED'",Integer.class,a.tenantId(),b.tenantId()));
        assertEquals(10,jdbc.queryForObject("select count(*) from withdrawal_order where tenant_id in (?,?)",Integer.class,a.tenantId(),b.tenantId()));
        for(var asset:Map.of("USDT",new BigDecimal("85"),"USDC",new BigDecimal("56")).entrySet()) {
            BigDecimal total=jdbc.queryForObject("select sum(total_balance) from ledger_balance where tenant_id in (?,?) and asset_symbol=?",BigDecimal.class,a.tenantId(),b.tenantId(),asset.getKey());
            assertEquals(0,asset.getValue().compareTo(total));
        }
        BigDecimal nativeLedger=jdbc.queryForObject("select sum(total_balance) from ledger_balance where tenant_id in (?,?) and asset_symbol=?",BigDecimal.class,a.tenantId(),b.tenantId(),nativeSymbol);
        assertEquals(0,new BigDecimal("6.2").subtract(charged).compareTo(nativeLedger));
        assertEquals(new BigInteger("2700000000000000000"),web3j.ethGetBalance(a.hotWallet(),DefaultBlockParameterName.LATEST).send().getBalance());
        assertEquals(new BigInteger("1500000000000000000"),web3j.ethGetBalance(b.hotWallet(),DefaultBlockParameterName.LATEST).send().getBalance());
        assertEquals(12,jdbc.queryForObject("select count(*) from custody_ledger_entry where entry_type='NETWORK_FEE'",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from chain_address where mpc_key_id is null",Integer.class));
        var proof=new java.util.LinkedHashMap<String,Object>();
        proof.put("passed",true);proof.put("pair",quorum);proof.put("transactions",publicTransactions);
        proof.put("feeAtomic",fees.toString());proof.put("relayerFundedAtomic","5000000000000000000");
        proof.put("relayerRemainingAtomic",new BigInteger("5000000000000000000").subtract(fees).toString());
        proof.put("lostResponses",broadcastGate.acceptedLostResponses.size());proof.put("holderRestarts",2);proof.put("applicationServiceRecreations",2);
        proof.put("mpcRequests",jdbc.queryForObject("select count(*) from wallet_mpc_sign_request",Integer.class));
        proof.put("disabledEntryRecovery",true);proof.put("principalSettledOrders",10);
        proof.put("internalRescanNoCredit",true);proof.put("ownedIncomingCreditOnce",true);proof.put("completedReplayUnchanged",true);
        Files.writeString(nativeRoot.resolve("7702-business-public.json"),new ObjectMapper().writeValueAsString(proof));
    }

    private String ledgerSnapshot() {
        return new ObjectMapper().writeValueAsString(jdbc.queryForList("select tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance from ledger_balance order by tenant_id,chain,asset_symbol,account_id"));
    }

    private static List<String> jsonList(JsonNode value) {
        return java.util.stream.StreamSupport.stream(value.spliterator(),false).map(JsonNode::asText).toList();
    }

    /** All workflow broadcasts pass a real loopback gate; faucet/deployment calls use the node directly. */
    private final class BroadcastGate implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final java.net.http.HttpClient forward=java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        private final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        private final Map<String,Map<String,Object>> transactions=new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<String> acceptedLostResponses=java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile boolean loseResponses;
        private volatile String lastHash;
        BroadcastGate() throws Exception {
            server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                try {
                    byte[] body=exchange.getRequestBody().readAllBytes();var request=new ObjectMapper().readTree(body);
                    boolean broadcast="eth_sendRawTransaction".equals(request.path("method").asText());
                    if(broadcast)verifyCommitted(request.path("params").get(0).asText());
                    var response=forward.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(RPC)).timeout(Duration.ofSeconds(10))
                            .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    if(broadcast && loseResponses) {
                        assertEquals(200,response.statusCode(),"the node must answer before its reply is lost");
                        String hash=Hash.sha3(request.path("params").get(0).asText());
                        var accepted=new ObjectMapper().readTree(response.body());
                        if(hash.equalsIgnoreCase(accepted.path("result").asText()))acceptedLostResponses.add(hash);
                        else assertTrue(acceptedLostResponses.contains(hash),"a lost initial response must prove actual acceptance");
                        exchange.close();return;
                    }
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
                } catch(Throwable error) {
                    failure.compareAndSet(null,error);
                    byte[] message="fixture RPC gate validation failed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try {exchange.sendResponseHeaders(500,message.length);exchange.getResponseBody().write(message);}catch(java.io.IOException ignored){}
                } finally {exchange.close();}
            });server.start();
        }
        String endpoint() {return "http://127.0.0.1:"+server.getAddress().getPort();}
        void assertHealthy() {if(failure.get()!=null)throw new AssertionError("workflow RPC gate failed",failure.get());}
        private void verifyCommitted(String raw) {
            String hash=Hash.sha3(raw);
            var selected=jdbc.queryForList("select tenant_id,id,'COLLECTION' kind,status from evm_collection_batch where canonical_tx_hash=? union all select tenant_id,id,'WITHDRAWAL' kind,status from evm_withdrawal_batch where canonical_tx_hash=?",hash,hash);
            assertEquals(1,selected.size(),"broadcast must follow original business raw/hash commit");
            var row=selected.getFirst();UUID tenant=(UUID)row.get("tenant_id"),batch=(UUID)row.get("id");boolean payout="WITHDRAWAL".equals(row.get("kind"));
            assertTrue(Set.of("BROADCAST_UNKNOWN","SUBMITTED").contains(row.get("status")));
            String table=payout?"evm_withdrawal_batch_attempt":"evm_collection_batch_attempt";
            String encrypted=jdbc.queryForObject("select signed_tx_ciphertext from "+table+" where tenant_id=? and batch_id=? and tx_hash=?",String.class,tenant,batch,hash);
            assertTrue(raw.equals(crypto.decrypt(encrypted)),"wire must equal the committed encrypted selection");
            var operation=new com.surprising.wallet.service.Evm7702SigningService.Operation(tenant,batch,chain,
                    payout?com.surprising.wallet.model.mpc.MpcSignCommand.BusinessType.WITHDRAWAL:com.surprising.wallet.model.mpc.MpcSignCommand.BusinessType.COLLECTION);
            mpc7702.verifySelectedRaw(operation,raw,hash);
            assertEquals("RESERVED",jdbc.queryForObject("select status from custody_gas_usage where tenant_id=? and operation_type=? and operation_id=?",String.class,tenant,payout?"WITHDRAWAL_BATCH":"COLLECTION_BATCH",batch));
            assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where business_reference=? and state<>'COMPLETED'",Integer.class,operation.reference()));
            transactions.putIfAbsent(hash,Map.of("transactionHash",hash,"tenantId",tenant.toString(),"batchId",batch.toString(),
                    "kind",row.get("kind"),"type",raw.startsWith("0x04")?4:2));
            lastHash=hash;
        }
        @Override public void close() {server.stop(0);forward.close();}
    }

    /**
     * 验证 {@code configureToken} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private void configureToken(String symbol, String contractAddress) {
        assertEquals(1, jdbc.update("""
                update token_config set contract_address = ?, contract_address_hex = ?,
                       decimals = 6, standard = 'ERC20', collect_enabled = true
                 where chain = ? and symbol = ? and network = 'local'
                """, contractAddress, contractAddress, chain, symbol));
        assertEquals(1, jdbc.update("""
                update chain_asset set contract_address = ?, decimals = 6
                 where chain = ? and symbol = ?
                """, contractAddress, chain, symbol));
    }

    /**
     * 验证 {@code assertCreditedDeposits} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code createWithdrawal} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
                        amount.toPlainString(), suffix, true, "user:42", CustodyWithdrawalService.FundingSource.ADDRESS);
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

    /**
     * 验证 {@code assertSharedConfirmedPayout} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code withdrawalStatus} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private String withdrawalStatus(WithdrawalFixture withdrawal) {
        return jdbc.queryForObject("""
                select status from withdrawal_order
                 where tenant_id = ? and id = ?
                """, String.class, withdrawal.tenantId(), withdrawal.orderId());
    }

    /**
     * 验证 {@code createTenant} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private TenantFixture createTenant(String slug, int namespace, int itemCount, long userBase) throws Exception {
        UUID tenantId = UUID.nameUUIDFromBytes(slug.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            String address = record.getAddress();
            long chainAddressId = insertChainAddress(record, tenantId);
            UUID custodyId = insertCustodyAddress(
                    tenantId, chainAddressId, address, slug + "-user-" + index, index);
            new com.surprising.wallet.repository.Evm7702AccountRepository(jdbc).createProjection(
                    tenantId, custodyId, chain, "local", address);
            BigInteger amount = BigInteger.valueOf((index + 1L) * 10_000_000L);
            authorities.add(new AuthorityFixture(
                    tenantId, custodyId, record, address, amount,
                    new BigDecimal(amount).movePointLeft(6), hotRecord.getAddress()));
        }
        return new TenantFixture(tenantId, hotRecord.getAddress(), List.copyOf(authorities));
    }

    /**
     * 验证 {@code createCollectionCandidates} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private void createCollectionCandidates(String symbol, int expected) {
        List<CollectionCandidateRecord> candidates = chainRepository.collectionPlanningService.candidates(chain, BigDecimal.ZERO, 20);
        assertEquals(expected, candidates.size());
        assertTrue(candidates.stream().allMatch(
                candidate -> symbol.equals(candidate.getAssetSymbol())));
        assertEquals(2, candidates.stream().map(CollectionCandidateRecord::getTenantId).distinct().count());
        for (CollectionCandidateRecord candidate : candidates) {
            String hotWallet = chainRepository.collectionPlanningService.destination(candidate.getTenantId(), candidate.getChain()).orElseThrow();
            assertEquals(
                    1,
                    chainRepository.collectionRecordRepository.create(
                            candidate.getTenantId(),
                            candidate.getCustodyAddressId(),
                            "7702:" + symbol + ":" + candidate.getTenantId() + ":" + candidate.getAddress(),
                            candidate.getChain(),
                            candidate.getAssetSymbol(),
                            candidate.getAddress(),
                            hotWallet,
                            candidate.getAmount(),
                            BigDecimal.ZERO,
                            null));
        }
    }

    /**
     * 验证 {@code assertTenantCompleted} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
                    authority.address(), DefaultBlockParameterName.LATEST).send().getBalance());
            assertTrue(web3j.ethGetCode(authority.address(), DefaultBlockParameterName.LATEST)
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

    /**
     * 验证 {@code collectionTx} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private String collectionTx(UUID tenantId, String symbol) {
        return jdbc.queryForObject("""
                select canonical_tx_hash from evm_collection_batch
                 where tenant_id = ? and asset_symbol = ? and status = 'CONFIRMED'
                """, String.class, tenantId, symbol);
    }

    /**
     * 验证 {@code assertAssetCollectionBatch} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
                    tokenBalance(web3j, authority.address(), symbol));
        }
    }

    /**
     * 验证 {@code batchCount} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private int batchCount(UUID tenantId) {
        return jdbc.queryForObject(
                "select count(*) from evm_collection_batch where tenant_id = ?", Integer.class, tenantId);
    }

    /**
     * 验证 {@code confirmedCollections} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private int confirmedCollections(UUID tenantId) {
        return jdbc.queryForObject("select count(*) from collection_record where tenant_id = ? and status = 'CONFIRMED'",
                Integer.class, tenantId);
    }

    /**
     * 验证 {@code settledBatchGasUsages} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private int settledBatchGasUsages(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(*) from custody_gas_usage
                 where tenant_id = ? and operation_type = 'COLLECTION_BATCH' and status = 'SETTLED'
                """, Integer.class, tenantId);
    }

    /**
     * 验证 {@code insertChainAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private long insertChainAddress(ChainAddressRecord record, UUID tenantId) {
        assertEquals(tenantId,record.getTenantId());
        return new ChainAddressRepository(jdbc).publishMpc(record).getId();
    }

    /**
     * 验证 {@code insertCustodyAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code keyRecord} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private ChainAddressRecord keyRecord(UUID tenantId, long userId, int biz,
                                         long index, String role) {
        int namespace=new com.surprising.wallet.repository.CustodyTenantTableRepository(jdbc)
                .findById(tenantId).orElseThrow().derivationNamespace();
        var key=mpcSupport.signing.awaitKey(tenantId,ChainType.valueOf(chain),userId,namespace,index,mpcSupport.nodes);
        String address=com.surprising.wallet.chain.MpcPublicAddressCodec.encode(profile,
                java.util.HexFormat.of().parseHex(key.publicKeyHex()));
        return ChainAddressRecord.builder().tenantId(tenantId).mpcKeyId(key.id())
                .chain(chain).assetSymbol(nativeSymbol).userId(userId).biz(namespace)
                .addressIndex(index).walletRole(role).enabled(true).derivationPath("mpc:"+key.id())
                .address(address).accountId(address.toLowerCase(java.util.Locale.ROOT)).ownerAddress(address).build();
    }

    /**
     * 验证 {@code deployMockToken} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code deployContract} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private Deployment deployContract(Web3j web3j, BigInteger nonce, String artifactPath)
            throws Exception {
        JsonNode artifact = new ObjectMapper().readTree(
                Files.readString(projectRoot().resolve(artifactPath)));
        RawTransaction raw = RawTransaction.createContractTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(5_000_000L), BigInteger.ZERO,
                artifact.path("bytecode").asText());
        return new Deployment(sendRaw(web3j, raw).getContractAddress());
    }

    /**
     * 验证 {@code deployPayoutDelegate} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code sendLegacyCall} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private TransactionReceipt sendLegacyCall(
            Web3j web3j, BigInteger nonce, String to, String data) throws Exception {
        return sendRaw(web3j, RawTransaction.createTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(800_000L), to, BigInteger.ZERO, data));
    }

    /**
     * 验证 {@code sendLegacyNative} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private TransactionReceipt sendLegacyNative(
            Web3j web3j, BigInteger nonce, String to, BigInteger value) throws Exception {
        return sendRaw(web3j, RawTransaction.createEtherTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(100_000L), to, value));
    }

    /**
     * 验证 {@code sendRaw} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private TransactionReceipt sendRaw(Web3j web3j, RawTransaction raw) throws Exception {
        // Funding and deployments use only the isolated node's unlocked faucet account.
        String to=raw.getTo()==null || raw.getTo().isBlank()?null:raw.getTo();
        EthSendTransaction response = web3j.ethSendTransaction(new Transaction(HARDHAT_ADMIN,raw.getNonce(),
                raw.getGasPrice(),raw.getGasLimit(),to,raw.getValue(),raw.getData())).send();
        assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
        return waitReceipt(web3j, response.getTransactionHash());
    }

    /**
     * 验证 {@code encodeMint} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String encodeMint(String recipient, BigInteger amount) {
        return FunctionEncoder.encode(new Function(
                "mint", List.of(new Address(recipient), new Uint256(amount)), List.of()));
    }

    /**
     * 验证 {@code tokenBalance} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private BigInteger tokenBalance(Web3j web3j, String owner) throws Exception {
        return tokenBalance(web3j, owner, "USDT");
    }

    /**
     * 验证 {@code tokenBalance} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private BigInteger tokenBalance(Web3j web3j, String owner, String symbol) throws Exception {
        String token = jdbc.queryForObject(
                "select contract_address from token_config where chain = ? and symbol = ? and network = 'local'",
                String.class, chain, symbol);
        Function function = new Function(
                "balanceOf", List.of(new Address(owner)), List.of(new TypeReferenceUint256()));
        EthCall call = web3j.ethCall(Transaction.createEthCallTransaction(
                HARDHAT_ADMIN, token, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        return (BigInteger) org.web3j.abi.FunctionReturnDecoder.decode(
                call.getValue(), function.getOutputParameters()).getFirst().getValue();
    }

    /**
     * 验证 {@code codeHash} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String codeHash(Web3j web3j, String address) throws Exception {
        String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
        assertNotEquals("0x", code);
        return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code)));
    }

    /**
     * 验证 {@code waitReceipt} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static TransactionReceipt waitReceipt(Web3j web3j, String txHash) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isPresent()) return receipt.get();
            Thread.sleep(100L);
        }
        throw new IllegalStateException("timed out waiting for " + txHash);
    }

    /**
     * 验证 {@code requiredProperty} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (!value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new IllegalArgumentException("missing valid -D" + name);
        }
        return value;
    }

    /**
     * 验证 {@code projectRoot} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code concat} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static List<AuthorityFixture> concat(
            List<AuthorityFixture> first, List<AuthorityFixture> second) {
        ArrayList<AuthorityFixture> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    /**
     * 测试辅助类 {@code TypeReferenceUint256}，为相关测试提供隔离环境或共享数据。
     */
    private static final class TypeReferenceUint256 extends org.web3j.abi.TypeReference<Uint256> {
    }

    private record Deployment(String address) {
    }

    private record TenantFixture(
            UUID tenantId, String hotWallet, List<AuthorityFixture> authorities) {
    }

    private record AuthorityFixture(
            UUID tenantId, UUID custodyAddressId, ChainAddressRecord record,
            String address, BigInteger amountAtomic, BigDecimal amount,
            String hotWallet) {
    }

    private record WithdrawalFixture(
            UUID tenantId, UUID custodyWithdrawalId, long orderId, String orderNo,
            String assetSymbol, String recipient, BigDecimal amount) {
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions { }
}
