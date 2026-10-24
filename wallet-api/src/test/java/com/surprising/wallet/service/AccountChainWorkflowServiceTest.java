package com.surprising.wallet.service;

import com.surprising.wallet.chain.aptos.AptosTransactionService;
import com.surprising.wallet.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.ton.TonTransactionService;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.testutil.ChainRepositoryFixture;
import com.surprising.wallet.testutil.RuntimeTestSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
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

    @Test
    void emptyCollectionCandidatesSkipEvmFeeRpc() throws Exception {
        EmptyCollectionRepository repository = new EmptyCollectionRepository();
        CapturingEvmFeeService evm = new CapturingEvmFeeService();
        AccountChainWorkflowService service = service(repository, evm);

        createCollectionCandidates(service, profile());

        assertEquals(0, evm.estimateCalls);
    }

    @Test
    void collectionAddressLookupIsCachedPerTenantAndChainWithinOneCycle() throws Exception {
        TenantCollectionRepository repository = new TenantCollectionRepository(2);
        AccountChainWorkflowService service = service(repository, new CapturingAptosService());
        AccountChainProfile profile = AccountChainProfile.builder()
                .chain("APTOS").network("testnet").family("aptos").nativeSymbol("APT")
                .defaultFee(5_000_000L).dustThreshold(0L).enabled(true).build();

        createCollectionCandidates(service, profile);

        assertEquals(1, repository.collectionAddressLookups);
        assertEquals(2, repository.createdCount);
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
        confirmTonWithdrawal(service, order);
        assertEquals(0, repository.withdrawalSettlements);

        ton.messageConfirmed = true;
        confirmTonWithdrawal(service, order);
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

    private static void createCollectionCandidates(AccountChainWorkflowService service,
                                                   AccountChainProfile profile) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "createCollectionCandidates", AccountChainProfile.class);
        method.setAccessible(true);
        method.invoke(service, profile);
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
                                             WithdrawalOrderRecord order) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "confirmTonWithdrawal", WithdrawalOrderRecord.class);
        method.setAccessible(true);
        method.invoke(service, order);
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
    private static AccountChainWorkflowService service(ChainRepositoryFixture repository,
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
                null,
                null,
                null,
                null);
    }

    /**
     * 验证 {@code service} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static AccountChainWorkflowService service(ChainRepositoryFixture repository,
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
    private static AccountChainWorkflowService service(ChainRepositoryFixture repository,
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
            ChainRepositoryFixture repository, Object... dependencies) {
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
        var settlements = Mockito.mock(WithdrawalSettlementService.class);
        Mockito.when(settlements.confirm(any(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (!(repository instanceof FakeRepository fake)) {
                throw new AssertionError("unexpected withdrawal settlement in this fixture");
            }
            fake.withdrawalSettlements++;
            return true;
        });
        return new AccountChainWorkflowService(
                repository.chainProfileRepository,
                repository.evm7702ConfigRepository,
                repository.withdrawalOrderRepository,
                repository.collectionRecordRepository,
                repository.withdrawalClaimService,
                repository.evmBatchOwnershipService,
                repository.collectionPlanningService,
                repository.tokenConfigRepository,
                repository.chainAddressRepository,
                null,
                null,
                new AccountChainAssetService(
                        repository.evm7702ConfigRepository,
                        repository.chainAddressRepository,
                        repository.chainProfileRepository,
                        repository.tokenConfigRepository,
                        repository.chainAssetRepository,
                        null),
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
                null,
                settlements);
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainRepositoryFixture {
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
        private FakeRepository(ChainAddressRecord address, TokenDefinition token, ChainAddressRecord tokenAddress) {
            super();
            this.address = address;
            this.token = token;
            this.tokenAddress = tokenAddress;
            Mockito.doAnswer(invocation -> java.util.Optional.of(com.surprising.wallet.common.chain.AccountChainProfile.builder().chain(invocation.getArgument(0)).network("testnet").build())).when(chainProfileRepository).findAccountByChain(ArgumentMatchers.anyString());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_assetSymbol = invocation.getArgument(1);
                long arg_userId = invocation.getArgument(2);
                int arg_biz = invocation.getArgument(3);
                long arg_addressIndex = invocation.getArgument(4);
                String arg_walletRole = invocation.getArgument(5);
                return Optional.ofNullable(tokenAddress);
            }).when(chainAddressRepository).find(
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.anyLong(),
                    ArgumentMatchers.anyInt(),
                    ArgumentMatchers.anyLong(),
                    ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_assetSymbol = invocation.getArgument(1);
                String arg_address = invocation.getArgument(2);
                return Optional.of(this.address);
            }).when(chainAddressRepository).findEnabledByAddress(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_address = invocation.getArgument(1);
                return Optional.of(this.address);
            }).when(chainAddressRepository).findEnabledByAddress(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                String arg_chain = invocation.getArgument(1);
                String arg_assetSymbol = invocation.getArgument(2);
                String arg_address = invocation.getArgument(3);
                return TENANT_ID.equals(arg_tenantId) ? Optional.of(this.address) : Optional.empty();
            }).when(chainAddressRepository).findEnabledByTenantAndAddress(ArgumentMatchers.<UUID>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                String arg_chain = invocation.getArgument(1);
                String arg_address = invocation.getArgument(2);
                return TENANT_ID.equals(arg_tenantId) ? Optional.of(this.address) : Optional.empty();
            }).when(chainAddressRepository).findEnabledByTenantAndAddress(ArgumentMatchers.<UUID>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_symbol = invocation.getArgument(2);
                return Optional.ofNullable(token);
            }).when(tokenConfigRepository).findDefinition(ArgumentMatchers.<String>any(), ArgumentMatchers.any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_symbol = invocation.getArgument(1);
                return Optional.empty();
            }).when(chainAssetRepository).findActiveDefinition(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                long arg_withdrawalOrderId = invocation.getArgument(1);
                return false;
            }).when(evmBatchOwnershipService).withdrawalPending(ArgumentMatchers.<UUID>any(), ArgumentMatchers.anyLong());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                long arg_collectionRecordId = invocation.getArgument(1);
                return false;
            }).when(evmBatchOwnershipService).collectionPending(ArgumentMatchers.<UUID>any(), ArgumentMatchers.anyLong());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                String arg_chain = invocation.getArgument(1);
                String arg_orderNo = invocation.getArgument(2);
                String arg_fromAddress = invocation.getArgument(3);
                signingClaims++;
                return 1;
            }).when(withdrawalClaimService).claim(ArgumentMatchers.<UUID>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_orderNo = invocation.getArgument(1);
                String arg_fromAddress = invocation.getArgument(2);
                String arg_errorMessage = invocation.getArgument(3);
                broadcastUnknownMarks++;
                broadcastError = arg_errorMessage;
                return 1;
            }).when(withdrawalOrderRepository).markBroadcastUnknown(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                String arg_chain = invocation.getArgument(1);
                String arg_orderNo = invocation.getArgument(2);
                String arg_fromAddress = invocation.getArgument(3);
                String arg_errorMessage = invocation.getArgument(4);
                broadcastUnknownMarks++;
                broadcastError = arg_errorMessage;
                return 1;
            }).when(withdrawalOrderRepository).markBroadcastUnknown(
                    ArgumentMatchers.<UUID>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_assetSymbol = invocation.getArgument(1);
                String arg_accountId = invocation.getArgument(2);
                BigDecimal arg_amount = invocation.getArgument(3);
                lockReleases++;
                return true;
            }).when(ledgerBalanceRepository).release(
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<BigDecimal>any(),
                    ArgumentMatchers.isNull());
        }


    }

    /**
     * 测试辅助类 {@code TenantCollectionRepository}，为相关测试提供隔离环境或共享数据。
     */
    private static final class TenantCollectionRepository extends ChainRepositoryFixture {
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
        private final int candidateCount;
        private int collectionAddressLookups;
        private int createdCount;

        /**
         * 验证 {@code TenantCollectionRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private TenantCollectionRepository() {
            this(1);
        }

        private TenantCollectionRepository(int candidateCount) {
            super();
            this.candidateCount = candidateCount;
            Mockito.doAnswer(invocation -> java.util.Optional.of(com.surprising.wallet.common.chain.AccountChainProfile.builder().chain(invocation.getArgument(0)).network("testnet").build())).when(chainProfileRepository).findAccountByChain(ArgumentMatchers.anyString());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                BigDecimal arg_minimumAmount = invocation.getArgument(1);
                int arg_limit = invocation.getArgument(2);
                return java.util.stream.IntStream.range(0, candidateCount).mapToObj(index -> CollectionCandidateRecord.builder().tenantId(TENANT_ID).custodyAddressId(custodyAddressId).chain("APTOS").assetSymbol("APT").accountId("0xdeposit" + index).address("0xdeposit" + index).ownerAddress("0xdeposit" + index).userId(1L).biz(0).addressIndex((long) index).walletRole("DEPOSIT").amount(BigDecimal.ONE).build()).toList();
            }).when(collectionPlanningService).candidates(ArgumentMatchers.<String>any(), ArgumentMatchers.<BigDecimal>any(), ArgumentMatchers.anyInt());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                String arg_chain = invocation.getArgument(1);
                collectionAddressLookups++;
                return Optional.of("0xtenant-collection");
            }).when(collectionPlanningService).destination(ArgumentMatchers.<UUID>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_symbol = invocation.getArgument(1);
                return Optional.of(ChainAsset.builder().chain("APTOS").symbol("APT").decimals(8).nativeAsset(true).active(true).build());
            }).when(chainAssetRepository).findActiveDefinition(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                UUID arg_custodyAddressId = invocation.getArgument(1);
                String arg_collectionNo = invocation.getArgument(2);
                String arg_chain = invocation.getArgument(3);
                String arg_assetSymbol = invocation.getArgument(4);
                String arg_fromAddress = invocation.getArgument(5);
                String arg_toAddress = invocation.getArgument(6);
                BigDecimal arg_amount = invocation.getArgument(7);
                BigDecimal arg_fee = invocation.getArgument(8);
                String arg_rawPayload = invocation.getArgument(9);
                assertEquals(TENANT_ID, arg_tenantId);
                assertEquals(this.custodyAddressId, arg_custodyAddressId);
                collectionTarget = arg_toAddress;
                collectionAmount = arg_amount;
                created = true;
                createdCount++;
                return 1;
            }).when(collectionRecordRepository).create(
                    ArgumentMatchers.<UUID>any(),
                    ArgumentMatchers.<UUID>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<BigDecimal>any(),
                    ArgumentMatchers.<BigDecimal>any(),
                    ArgumentMatchers.<String>any());
        }


    }

    private static final class EmptyCollectionRepository extends ChainRepositoryFixture {
        private EmptyCollectionRepository() {
            super();
            Mockito.doAnswer(invocation -> java.util.Optional.of(com.surprising.wallet.common.chain.AccountChainProfile.builder().chain(invocation.getArgument(0)).network("testnet").build())).when(chainProfileRepository).findAccountByChain(ArgumentMatchers.anyString());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                BigDecimal arg_minimumAmount = invocation.getArgument(1);
                int arg_limit = invocation.getArgument(2);
                return List.of();
            }).when(collectionPlanningService).candidates(ArgumentMatchers.<String>any(), ArgumentMatchers.<BigDecimal>any(), ArgumentMatchers.anyInt());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                return List.of();
            }).when(tokenConfigRepository).listDefinitions(ArgumentMatchers.<String>any(), ArgumentMatchers.any());
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
            super(
                    mock(WithdrawalOrderRepository.class),
                    mock(CollectionRecordRepository.class),
                    mock(EvmTransactionRepository.class),
                    mock(ChainProfileRepository.class),
                    mock(ChainAssetRepository.class),
                    null,
                    null,
                    null,
                    null,
                    mock(WithdrawalSettlementService.class),
                    mock(EvmNonceRepository.class));
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
        private int estimateCalls;

        /**
         * 验证 {@code CapturingEvmFeeService} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private CapturingEvmFeeService() {
            super(
                    mock(WithdrawalOrderRepository.class),
                    mock(CollectionRecordRepository.class),
                    mock(EvmTransactionRepository.class),
                    mock(ChainProfileRepository.class),
                    mock(ChainAssetRepository.class),
                    null,
                    null,
                    null,
                    null,
                    mock(WithdrawalSettlementService.class),
                    mock(EvmNonceRepository.class));
        }

        /**
         * 验证 {@code estimateCollectionFeeReserve} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public BigDecimal estimateCollectionFeeReserve(String chain, int enabledTokenCount) {
            estimateCalls++;
            this.enabledTokenCount = enabledTokenCount;
            return new BigDecimal("0.0005");
        }
    }

    /**
     * 测试辅助类 {@code EvmCollectionRepository}，为相关测试提供隔离环境或共享数据。
     */
    private static final class EvmCollectionRepository extends ChainRepositoryFixture {
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
            super();
            this.eip7702Managed = eip7702Managed;
            Mockito.doAnswer(invocation -> java.util.Optional.of(com.surprising.wallet.common.chain.AccountChainProfile.builder().chain(invocation.getArgument(0)).network("testnet").build())).when(chainProfileRepository).findAccountByChain(ArgumentMatchers.anyString());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                BigDecimal arg_minimumAmount = invocation.getArgument(1);
                int arg_limit = invocation.getArgument(2);
                return List.of(CollectionCandidateRecord.builder().tenantId(TENANT_ID).custodyAddressId(custodyAddressId).chain("ETH").assetSymbol("ETH").accountId("0xdeposit").address("0xdeposit").ownerAddress("0xdeposit").userId(1L).biz(0).addressIndex(0L).walletRole("DEPOSIT").amount(new BigDecimal("5")).build());
            }).when(collectionPlanningService).candidates(ArgumentMatchers.<String>any(), ArgumentMatchers.<BigDecimal>any(), ArgumentMatchers.anyInt());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                return List.of(
                        TokenDefinition.builder().chain(arg_chain).symbol("USDC").active(true).build(),
                        TokenDefinition.builder().chain(arg_chain).symbol("USDT").active(true).build());
            }).when(tokenConfigRepository).listDefinitions(ArgumentMatchers.<String>any(), ArgumentMatchers.any());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                String arg_chain = invocation.getArgument(1);
                return Optional.of("0xtenant-collection");
            }).when(collectionPlanningService).destination(ArgumentMatchers.<UUID>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_symbol = invocation.getArgument(1);
                return Optional.of(ChainAsset.builder().chain(arg_chain).symbol(arg_symbol).decimals(18).nativeAsset(true).active(true).build());
            }).when(chainAssetRepository).findActiveDefinition(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_network = invocation.getArgument(1);
                return false;
            }).when(evm7702ConfigRepository).existsActiveNativeCollection(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                String arg_chain = invocation.getArgument(0);
                String arg_network = invocation.getArgument(1);
                return eip7702Managed;
            }).when(evm7702ConfigRepository).existsManaged(ArgumentMatchers.<String>any(), ArgumentMatchers.<String>any());
            Mockito.doAnswer(invocation -> {
                UUID arg_tenantId = invocation.getArgument(0);
                UUID arg_custodyAddressId = invocation.getArgument(1);
                String arg_collectionNo = invocation.getArgument(2);
                String arg_chain = invocation.getArgument(3);
                String arg_assetSymbol = invocation.getArgument(4);
                String arg_fromAddress = invocation.getArgument(5);
                String arg_toAddress = invocation.getArgument(6);
                BigDecimal arg_amount = invocation.getArgument(7);
                BigDecimal arg_fee = invocation.getArgument(8);
                String arg_rawPayload = invocation.getArgument(9);
                this.collectionAmount = arg_amount;
                this.created = true;
                return 1;
            }).when(collectionRecordRepository).create(
                    ArgumentMatchers.<UUID>any(),
                    ArgumentMatchers.<UUID>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<String>any(),
                    ArgumentMatchers.<BigDecimal>any(),
                    ArgumentMatchers.<BigDecimal>any(),
                    ArgumentMatchers.<String>any());
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
            super(
                    null,
                    null,
                    mock(AccountSequenceRepository.class),
                    mock(ChainProfileRepository.class),
                    mock(TokenConfigRepository.class),
                    mock(WithdrawalOrderRepository.class),
                    mock(LedgerBalanceRepository.class),
                    mock(WithdrawalClaimService.class),
                    mock(CollectionRecordRepository.class),
                    mock(AptosTransactionRepository.class),
                    mock(WithdrawalSettlementService.class),
                    RuntimeTestSupport.enabled());
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
            super(
                    null,
                    null,
                    mock(AccountSequenceRepository.class),
                    mock(TonTransactionRepository.class),
                    mock(WithdrawalOrderRepository.class),
                    mock(LedgerBalanceRepository.class),
                    mock(WithdrawalClaimService.class),
                    mock(ChainProfileRepository.class),
                    mock(TokenConfigRepository.class),
                    mock(CollectionRecordRepository.class),
                    mock(WithdrawalSettlementService.class),
                    RuntimeTestSupport.enabled());
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
