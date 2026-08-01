package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.chain.aptos.AptosTransactionService;
import com.surprising.wallet.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.chain.ton.TonTransactionService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * 验证 {@code AccountChainWorkflowServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class AccountChainWorkflowServiceTest {
    /**
     * 保存 {@code TENANT_ID}，用于标识测试中的交易、区块或业务记录。
     */
    private static final UUID TENANT_ID = UUID.fromString("77020000-0000-0000-0000-000000000010");

    /**
     * 验证 {@code collectionCandidateAlwaysUsesTenantCollectionAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void collectionCandidateAlwaysUsesTenantCollectionAddress() throws Exception {
        TenantCollectionRepository repository = new TenantCollectionRepository();
        AccountChainWorkflowService service = service(repository, new CapturingAptosService());
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("APTOS")
                .network("testnet")
                .family("aptos")
                .nativeSymbol("APT")
                .defaultFee(5_000_000L)
                .dustThreshold(0L)
                .enabled(true)
                .build();

        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "createCollectionCandidates", AccountChainProfile.class);
        method.setAccessible(true);
        method.invoke(service, profile);

        assertEquals("0xtenant-collection", repository.collectionTarget);
        assertEquals(0, new BigDecimal("0.95").compareTo(repository.collectionAmount));
        assertTrue(repository.created);
    }

    /**
     * 验证 {@code evmNativeCollectionReservesGasForEveryEnabledToken} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void evmNativeCollectionReservesGasForEveryEnabledToken() throws Exception {
        EvmCollectionRepository repository = new EvmCollectionRepository();
        CapturingEvmFeeService evm = new CapturingEvmFeeService();
        AccountChainWorkflowService service = service(repository, evm);
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("ETH")
                .network("sepolia")
                .family("evm")
                .nativeSymbol("ETH")
                .defaultFee(1L)
                .dustThreshold(0L)
                .enabled(true)
                .build();

        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "createCollectionCandidates", AccountChainProfile.class);
        method.setAccessible(true);
        method.invoke(service, profile);

        assertEquals(2, evm.enabledTokenCount);
        assertEquals(0, new BigDecimal("4.9995").compareTo(repository.collectionAmount));
        assertTrue(repository.created);
    }

    /**
     * 验证 {@code eip7702ManagedNativeCollectionKeepsTheFullAuthorityBalance} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void eip7702ManagedNativeCollectionKeepsTheFullAuthorityBalance() throws Exception {
        EvmCollectionRepository repository = new EvmCollectionRepository(true);
        CapturingEvmFeeService evm = new CapturingEvmFeeService();
        AccountChainWorkflowService service = service(repository, evm);
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("ETH")
                .network("devtest")
                .family("evm")
                .nativeSymbol("ETH")
                .defaultFee(1L)
                .dustThreshold(0L)
                .enabled(true)
                .build();

        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "createCollectionCandidates", AccountChainProfile.class);
        method.setAccessible(true);
        method.invoke(service, profile);

        assertEquals(0, new BigDecimal("5").compareTo(repository.collectionAmount));
        assertTrue(repository.created);
    }

    /**
     * 验证 {@code broadcastFailureKeepsFundsLockedForManualAudit} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void broadcastFailureKeepsFundsLockedForManualAudit() throws Exception {
        ChainAddressRecord address = ChainAddressRecord.builder()
                .chain("ETH")
                .assetSymbol("ETH")
                .accountId("0xsource")
                .userId(1L)
                .biz(0)
                .addressIndex(1L)
                .address("0xsource")
                .ownerAddress("0xsource")
                .enabled(true)
                .build();
        FakeRepository repository = new FakeRepository(address);
        AccountChainWorkflowService service = service(repository, new FailingEvmService());

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> processWithdrawal(service, profile(), order()));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals(1, repository.signingClaims);
        assertEquals(1, repository.broadcastUnknownMarks);
        assertEquals(0, repository.lockReleases);
        assertEquals("rpc accepted maybe but returned error", repository.broadcastError);
    }

    /**
     * 验证 {@code aptosTokenWithdrawalUsesConfiguredTokenStandard} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void aptosTokenWithdrawalUsesConfiguredTokenStandard() throws Exception {
        ChainAddressRecord address = ChainAddressRecord.builder()
                .chain("APTOS")
                .assetSymbol("USDC")
                .accountId("0xsource")
                .userId(1L)
                .biz(0)
                .addressIndex(1L)
                .address("0xsource")
                .ownerAddress("0xsource")
                .enabled(true)
                .build();
        TokenDefinition token = TokenDefinition.builder()
                .chain("APTOS")
                .symbol("USDC")
                .standard("APTOS_FA")
                .contractAddress("0xmetadata")
                .decimals(6)
                .active(true)
                .build();
        FakeRepository repository = new FakeRepository(address, token);
        CapturingAptosService aptos = new CapturingAptosService();
        AccountChainWorkflowService service = service(repository, aptos);
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("APTOS")
                .network("mainnet")
                .family("aptos")
                .nativeSymbol("APT")
                .enabled(true)
                .build();
        WithdrawalOrderRecord order = WithdrawalOrderRecord.builder()
                .orderNo("aptos-fa-withdrawal")
                .userId(1L)
                .chain("APTOS")
                .assetSymbol("USDC")
                .fromAddress("0xsource")
                .toAddress("0xtarget")
                .amount(new BigDecimal("1.25"))
                .status("FROZEN")
                .build();

        String hash = dispatchWithdrawal(service, profile, order, address);

        assertEquals("0xaptos-fa", hash);
        assertEquals(token, aptos.token);
        assertEquals(1_250_000L, aptos.atomicAmount);
    }

    /**
     * 验证 {@code tonJettonWithdrawalUsesMaterializedWalletAndAtomicAmount} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tonJettonWithdrawalUsesMaterializedWalletAndAtomicAmount() throws Exception {
        ChainAddressRecord owner = ChainAddressRecord.builder()
                .chain("TON").assetSymbol("TON").accountId("owner").userId(1L).biz(0).addressIndex(7L)
                .address("owner").ownerAddress("owner").walletRole("DEPOSIT").enabled(true).build();
        ChainAddressRecord jettonWallet = ChainAddressRecord.builder()
                .chain("TON").assetSymbol("USDT").accountId("owner").userId(1L).biz(0).addressIndex(7L)
                .address("jetton-wallet").ownerAddress("owner").walletRole("DEPOSIT").enabled(true).build();
        TokenDefinition token = TokenDefinition.builder()
                .chain("TON").symbol("USDT").standard("JETTON").contractAddress("jetton-master")
                .decimals(6).active(true).build();
        FakeRepository repository = new FakeRepository(owner, token, jettonWallet);
        CapturingTonService ton = new CapturingTonService();
        AccountChainWorkflowService service = service(repository, ton);
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("TON").network("testnet").family("ton").nativeSymbol("TON").enabled(true).build();
        WithdrawalOrderRecord order = WithdrawalOrderRecord.builder()
                .orderNo("ton-usdt-withdrawal").userId(1L).chain("TON").assetSymbol("USDT")
                .fromAddress("owner").toAddress("destination").amount(new BigDecimal("1.25"))
                .status("FROZEN").build();

        assertEquals("ton-hash", dispatchWithdrawal(service, profile, order, owner));
        assertEquals(jettonWallet, ton.from);
        assertEquals("jetton-wallet", ton.sourceJettonWallet);
        assertEquals(BigInteger.valueOf(1_250_000L), ton.atomicAmount);
    }

    /**
     * 验证 {@code tonWithdrawalSettlesOnlyAfterExternalMessageIsOnChain} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void tonWithdrawalSettlesOnlyAfterExternalMessageIsOnChain() throws Exception {
        ChainAddressRecord owner = ChainAddressRecord.builder()
                .chain("TON").assetSymbol("TON").accountId("owner").userId(1L).biz(0).addressIndex(7L)
                .address("owner").ownerAddress("owner").walletRole("DEPOSIT").enabled(true).build();
        FakeRepository repository = new FakeRepository(owner);
        CapturingTonService ton = new CapturingTonService();
        AccountChainWorkflowService service = service(repository, ton);
        WithdrawalOrderRecord order = WithdrawalOrderRecord.builder()
                .tenantId(TENANT_ID).orderNo("ton-confirm").chain("TON").assetSymbol("TON").fromAddress("owner")
                .debitAccountId("owner").toAddress("destination").amount(BigDecimal.ONE)
                .fee(BigDecimal.ZERO).txHash("ton-message-hash").status("SENT").build();

        ton.messageConfirmed = false;
        confirmTonWithdrawal(service, order, owner);
        assertEquals(0, repository.withdrawalSettlements);

        ton.messageConfirmed = true;
        confirmTonWithdrawal(service, order, owner);
        assertEquals(1, repository.withdrawalSettlements);
    }

    /**
     * 验证 {@code processWithdrawal} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void processWithdrawal(AccountChainWorkflowService service,
                                          AccountChainProfile profile,
                                          WithdrawalOrderRecord order) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "processWithdrawal", AccountChainProfile.class, WithdrawalOrderRecord.class);
        method.setAccessible(true);
        method.invoke(service, profile, order);
    }

    /**
     * 验证 {@code dispatchWithdrawal} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String dispatchWithdrawal(AccountChainWorkflowService service,
                                             AccountChainProfile profile,
                                             WithdrawalOrderRecord order,
                                             ChainAddressRecord address) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "dispatchWithdrawal", AccountChainProfile.class,
                WithdrawalOrderRecord.class, ChainAddressRecord.class);
        method.setAccessible(true);
        return (String) method.invoke(service, profile, order, address);
    }

    /**
     * 验证 {@code confirmTonWithdrawal} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void confirmTonWithdrawal(AccountChainWorkflowService service,
                                             WithdrawalOrderRecord order,
                                             ChainAddressRecord address) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "confirmTonWithdrawal", WithdrawalOrderRecord.class, ChainAddressRecord.class);
        method.setAccessible(true);
        method.invoke(service, order, address);
    }

    /**
     * 验证 {@code profile} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainProfile profile() {
        return AccountChainProfile.builder()
                .chain("ETH")
                .network("sepolia")
                .family("evm")
                .nativeSymbol("ETH")
                .withdrawConfirmations(1)
                .enabled(true)
                .build();
    }

    /**
     * 验证 {@code order} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static WithdrawalOrderRecord order() {
        return WithdrawalOrderRecord.builder()
                .tenantId(TENANT_ID)
                .orderNo("wd-test")
                .userId(1L)
                .chain("ETH")
                .assetSymbol("ETH")
                .fromAddress("0xsource")
                .debitAccountId("0xsource")
                .toAddress("0xtarget")
                .amount(BigDecimal.ONE)
                .fee(BigDecimal.ZERO)
                .status("FROZEN")
                .build();
    }

    /**
     * 验证 {@code service} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainWorkflowService service(ChainJdbcRepository repository,
                                                       EvmAccountTransactionService evmService) {
        return createService(
                repository,
                null,
                null,
                null,
                evmService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 验证 {@code service} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainWorkflowService service(ChainJdbcRepository repository,
                                                       AptosTransactionService aptosService) {
        return createService(
                repository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                aptosService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 验证 {@code service} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainWorkflowService service(ChainJdbcRepository repository,
                                                       TonTransactionService tonService) {
        return createService(
                repository,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, tonService, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 验证 {@code createService} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainWorkflowService createService(
            ChainJdbcRepository repository, Object... dependencies) {
        EvmAccountTransactionService evmService = null;
        AptosTransactionService aptosService = null;
        TonTransactionService tonService = null;
        for (Object dependency : dependencies) {
            if (dependency instanceof EvmAccountTransactionService value) {
                evmService = value;
            } else if (dependency instanceof AptosTransactionService value) {
                aptosService = value;
            } else if (dependency instanceof TonTransactionService value) {
                tonService = value;
            }
        }
        return new AccountChainWorkflowService(
                repository,
                null,
                null,
                new AccountChainAssetService(repository, null),
                null,
                evmService,
                null,
                null,
                aptosService,
                null,
                tonService,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code address}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final ChainAddressRecord address;
        /**
         * 保存 {@code signingClaims}，用于承载当前测试夹具的配置或运行数据。
         */
        private int signingClaims;
        /**
         * 保存 {@code broadcastUnknownMarks}，用于承载当前测试夹具的配置或运行数据。
         */
        private int broadcastUnknownMarks;
        /**
         * 保存 {@code lockReleases}，用于承载当前测试夹具的配置或运行数据。
         */
        private int lockReleases;
        /**
         * 保存 {@code broadcastError}，用于承载当前测试夹具的配置或运行数据。
         */
        private String broadcastError;
        /**
         * 保存 {@code token}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final TokenDefinition token;
        /**
         * 保存 {@code tokenAddress}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final ChainAddressRecord tokenAddress;
        /**
         * 保存 {@code withdrawalSettlements}，记录测试开关、处理状态、确认结果或重试信息。
         */
        private int withdrawalSettlements;

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeRepository(ChainAddressRecord address) {
            this(address, null);
        }

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeRepository(ChainAddressRecord address, TokenDefinition token) {
            this(address, token, null);
        }

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeRepository(ChainAddressRecord address, TokenDefinition token,
                               ChainAddressRecord tokenAddress) {
            super(null);
            this.address = address;
            this.token = token;
            this.tokenAddress = tokenAddress;
        }

        /**
         * 验证 {@code findChainAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAddressRecord> findChainAddress(String chain, String assetSymbol, long userId,
                                                             int biz, long addressIndex, String walletRole) {
            return Optional.ofNullable(tokenAddress);
        }

        /**
         * 验证 {@code findChainAddressByAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String assetSymbol, String address) {
            return Optional.of(this.address);
        }

        /**
         * 验证 {@code findChainAddressByAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String address) {
            return Optional.of(this.address);
        }

        /**
         * 验证 {@code findChainAddressByAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(
                UUID tenantId, String chain, String assetSymbol, String address) {
            return TENANT_ID.equals(tenantId) ? Optional.of(this.address) : Optional.empty();
        }

        /**
         * 验证 {@code findChainAddressByAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(
                UUID tenantId, String chain, String address) {
            return TENANT_ID.equals(tenantId) ? Optional.of(this.address) : Optional.empty();
        }

        /**
         * 验证 {@code findToken} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<TokenDefinition> findToken(String chain, String symbol) {
            return Optional.ofNullable(token);
        }

        /**
         * 验证 {@code findAsset} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return Optional.empty();
        }

        /**
         * 验证 {@code isWithdrawalInPendingEvm7702Batch} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean isWithdrawalInPendingEvm7702Batch(UUID tenantId, long withdrawalOrderId) {
            return false;
        }

        /**
         * 验证 {@code isCollectionInPendingEvm7702Batch} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean isCollectionInPendingEvm7702Batch(UUID tenantId, long collectionRecordId) {
            return false;
        }

        /**
         * 验证 {@code claimWithdrawalSigning} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int claimWithdrawalSigning(String chain, String orderNo, String fromAddress) {
            signingClaims++;
            return 1;
        }

        /**
         * 验证 {@code claimWithdrawalSigning} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int claimWithdrawalSigning(
                UUID tenantId, String chain, String orderNo, String fromAddress) {
            signingClaims++;
            return 1;
        }

        /**
         * 验证 {@code markWithdrawalBroadcastUnknown} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int markWithdrawalBroadcastUnknown(String chain, String orderNo,
                                                  String fromAddress, String errorMessage) {
            broadcastUnknownMarks++;
            broadcastError = errorMessage;
            return 1;
        }

        /**
         * 验证 {@code markWithdrawalBroadcastUnknown} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int markWithdrawalBroadcastUnknown(UUID tenantId, String chain, String orderNo,
                                                  String fromAddress, String errorMessage) {
            broadcastUnknownMarks++;
            broadcastError = errorMessage;
            return 1;
        }

        /**
         * 验证 {@code releaseLockedBalance} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean releaseLockedBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
            lockReleases++;
            return true;
        }

        /**
         * 验证 {@code confirmWithdrawalAndSettle} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean confirmWithdrawalAndSettle(String chain, String orderNo, String txHash,
                                                  String assetSymbol, String accountId, BigDecimal amount) {
            withdrawalSettlements++;
            return true;
        }

        /**
         * 验证 {@code confirmWithdrawalAndSettle} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean confirmWithdrawalAndSettle(UUID tenantId, String chain, String orderNo,
                                                  String txHash, String assetSymbol,
                                                  String accountId, BigDecimal amount) {
            withdrawalSettlements++;
            return true;
        }
    }

    /**
     * 测试辅助类 {@code TenantCollectionRepository}，为相关测试提供隔离环境或共享数据。
     */
    private static final class TenantCollectionRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code custodyAddressId}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final UUID custodyAddressId = UUID.fromString("77020000-0000-0000-0000-000000000011");
        /**
         * 保存 {@code collectionTarget}，记录测试开关、处理状态、确认结果或重试信息。
         */
        private String collectionTarget;
        /**
         * 保存 {@code collectionAmount}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
         */
        private BigDecimal collectionAmount;
        /**
         * 保存 {@code created}，用于记录测试时间边界或审计时间。
         */
        private boolean created;

        /**
         * 验证 {@code TenantCollectionRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private TenantCollectionRepository() {
            super(null);
        }

        /**
         * 验证 {@code listCollectableLedgerBalances} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<CollectionCandidateRecord> listCollectableLedgerBalances(
                String chain, BigDecimal minimumAmount, int limit) {
            return List.of(CollectionCandidateRecord.builder()
                    .tenantId(TENANT_ID)
                    .custodyAddressId(custodyAddressId)
                    .chain("APTOS")
                    .assetSymbol("APT")
                    .accountId("0xdeposit")
                    .address("0xdeposit")
                    .ownerAddress("0xdeposit")
                    .userId(1L)
                    .biz(0)
                    .addressIndex(0L)
                    .walletRole("DEPOSIT")
                    .amount(BigDecimal.ONE)
                    .build());
        }

        /**
         * 验证 {@code findActiveTenantCollectionAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<String> findActiveTenantCollectionAddress(UUID tenantId, String chain) {
            return Optional.of("0xtenant-collection");
        }

        /**
         * 验证 {@code findAsset} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return Optional.of(ChainAsset.builder()
                    .chain("APTOS")
                    .symbol("APT")
                    .decimals(8)
                    .nativeAsset(true)
                    .active(true)
                    .build());
        }

        /**
         * 验证 {@code createCollectionRecord} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int createCollectionRecord(
                UUID tenantId, UUID custodyAddressId, String collectionNo,
                String chain, String assetSymbol, String fromAddress, String toAddress,
                BigDecimal amount, BigDecimal fee, String rawPayload) {
            assertEquals(TENANT_ID, tenantId);
            assertEquals(this.custodyAddressId, custodyAddressId);
            collectionTarget = toAddress;
            collectionAmount = amount;
            created = true;
            return 1;
        }
    }

    /**
     * 测试辅助类 {@code FailingEvmService}，为相关测试提供隔离环境或共享数据。
     */
    private static final class FailingEvmService extends EvmAccountTransactionService {
        /**
         * 验证 {@code FailingEvmService} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FailingEvmService() {
            super(null, null, null, null);
        }

        /**
         * 验证 {@code sendNative} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public String sendNative(String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
            throw new IllegalStateException("rpc accepted maybe but returned error");
        }
    }

    /**
     * 测试替身 {@code CapturingEvmFeeService}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class CapturingEvmFeeService extends EvmAccountTransactionService {
        /**
         * 保存 {@code enabledTokenCount}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private int enabledTokenCount;

        /**
         * 验证 {@code CapturingEvmFeeService} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private CapturingEvmFeeService() {
            super(null, null, null, null);
        }

        /**
         * 验证 {@code estimateCollectionFeeReserve} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public BigDecimal estimateCollectionFeeReserve(String chain, int enabledTokenCount) {
            this.enabledTokenCount = enabledTokenCount;
            return new BigDecimal("0.0005");
        }
    }

    /**
     * 测试辅助类 {@code EvmCollectionRepository}，为相关测试提供隔离环境或共享数据。
     */
    private static final class EvmCollectionRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code custodyAddressId}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private final UUID custodyAddressId = UUID.fromString("77020000-0000-0000-0000-000000000012");
        /**
         * 保存 {@code collectionAmount}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
         */
        private BigDecimal collectionAmount;
        /**
         * 保存 {@code created}，用于记录测试时间边界或审计时间。
         */
        private boolean created;
        /**
         * 保存 {@code eip7702Managed}，用于承载当前测试夹具的配置或运行数据。
         */
        private final boolean eip7702Managed;

        /**
         * 验证 {@code EvmCollectionRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private EvmCollectionRepository() {
            this(false);
        }

        /**
         * 验证 {@code EvmCollectionRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private EvmCollectionRepository(boolean eip7702Managed) {
            super(null);
            this.eip7702Managed = eip7702Managed;
        }

        /**
         * 验证 {@code listCollectableLedgerBalances} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<CollectionCandidateRecord> listCollectableLedgerBalances(
                String chain, BigDecimal minimumAmount, int limit) {
            return List.of(CollectionCandidateRecord.builder()
                    .tenantId(TENANT_ID)
                    .custodyAddressId(custodyAddressId)
                    .chain("ETH")
                    .assetSymbol("ETH")
                    .accountId("0xdeposit")
                    .address("0xdeposit")
                    .ownerAddress("0xdeposit")
                    .userId(1L)
                    .biz(0)
                    .addressIndex(0L)
                    .walletRole("DEPOSIT")
                    .amount(new BigDecimal("5"))
                    .build());
        }

        /**
         * 验证 {@code listTokens} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return List.of(
                    TokenDefinition.builder().chain(chain).symbol("USDC").active(true).build(),
                    TokenDefinition.builder().chain(chain).symbol("USDT").active(true).build());
        }

        /**
         * 验证 {@code findActiveTenantCollectionAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<String> findActiveTenantCollectionAddress(UUID tenantId, String chain) {
            return Optional.of("0xtenant-collection");
        }

        /**
         * 验证 {@code findAsset} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return Optional.of(ChainAsset.builder()
                    .chain(chain).symbol(symbol).decimals(18).nativeAsset(true).active(true).build());
        }

        /**
         * 验证 {@code isEvm7702NativeCollectionActive} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean isEvm7702NativeCollectionActive(String chain, String network) {
            return false;
        }

        /**
         * 验证 {@code isEvm7702Managed} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean isEvm7702Managed(String chain, String network) {
            return eip7702Managed;
        }

        /**
         * 验证 {@code createCollectionRecord} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int createCollectionRecord(
                UUID tenantId, UUID custodyAddressId, String collectionNo,
                String chain, String assetSymbol, String fromAddress, String toAddress,
                BigDecimal amount, BigDecimal fee, String rawPayload) {
            this.collectionAmount = amount;
            this.created = true;
            return 1;
        }
    }

    /**
     * 测试替身 {@code CapturingAptosService}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class CapturingAptosService extends AptosTransactionService {
        /**
         * 保存 {@code token}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private TokenDefinition token;
        /**
         * 保存 {@code atomicAmount}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
         */
        private long atomicAmount;

        /**
         * 验证 {@code CapturingAptosService} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private CapturingAptosService() {
            super(null, null, null);
        }

        /**
         * 验证 {@code sendToken} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public String sendToken(ChainAddressRecord from, TokenDefinition token,
                                String toAddress, long amountAtomic) {
            this.token = token;
            this.atomicAmount = amountAtomic;
            return "0xaptos-fa";
        }
    }

    /**
     * 测试替身 {@code CapturingTonService}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class CapturingTonService extends TonTransactionService {
        /**
         * 保存 {@code from}，用于承载当前测试夹具的配置或运行数据。
         */
        private ChainAddressRecord from;
        /**
         * 保存 {@code sourceJettonWallet}，用于承载当前测试夹具的配置或运行数据。
         */
        private String sourceJettonWallet;
        /**
         * 保存 {@code atomicAmount}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
         */
        private BigInteger atomicAmount;
        /**
         * 保存 {@code messageConfirmed}，记录测试开关、处理状态、确认结果或重试信息。
         */
        private boolean messageConfirmed;

        /**
         * 验证 {@code CapturingTonService} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private CapturingTonService() {
            super(null, null, null);
        }

        /**
         * 验证 {@code prepareJetton} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public PreparedTransfer prepareJetton(ChainAddressRecord from, String sourceJettonWallet,
                                              String destinationOwner, BigInteger tokenAmount,
                                              String responseAddress, String comment) {
            this.from = from;
            this.sourceJettonWallet = sourceJettonWallet;
            this.atomicAmount = tokenAmount;
            return new PreparedTransfer(1L, new byte[]{1}, "AQ==", "message-hash");
        }

        /**
         * 验证 {@code broadcastAndRecord} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public String broadcastAndRecord(PreparedTransfer transfer, String from, String to,
                                         String symbol, String master, BigDecimal amount) {
            return "ton-hash";
        }

        /**
         * 验证 {@code confirmSentMessage} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean confirmSentMessage(String messageHash, String senderAddress) {
            return messageConfirmed;
        }
    }
}
