package com.surprising.wallet.account;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.chain.aptos.AptosTransactionService;
import com.surprising.wallet.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.chain.ton.TonTransactionService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
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
import com.surprising.wallet.account.service.AccountChainWorkflowService;

class AccountChainWorkflowServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("77020000-0000-0000-0000-000000000010");

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

    private static void processWithdrawal(AccountChainWorkflowService service,
                                          AccountChainProfile profile,
                                          WithdrawalOrderRecord order) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "processWithdrawal", AccountChainProfile.class, WithdrawalOrderRecord.class);
        method.setAccessible(true);
        method.invoke(service, profile, order);
    }

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

    private static void confirmTonWithdrawal(AccountChainWorkflowService service,
                                             WithdrawalOrderRecord order,
                                             ChainAddressRecord address) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "confirmTonWithdrawal", WithdrawalOrderRecord.class, ChainAddressRecord.class);
        method.setAccessible(true);
        method.invoke(service, order, address);
    }

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

    private static AccountChainWorkflowService service(ChainJdbcRepository repository,
                                                       EvmAccountTransactionService evmService) {
        return new AccountChainWorkflowService(
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

    private static AccountChainWorkflowService service(ChainJdbcRepository repository,
                                                       AptosTransactionService aptosService) {
        return new AccountChainWorkflowService(
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

    private static AccountChainWorkflowService service(ChainJdbcRepository repository,
                                                       TonTransactionService tonService) {
        return new AccountChainWorkflowService(
                repository,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, tonService, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final ChainAddressRecord address;
        private int signingClaims;
        private int broadcastUnknownMarks;
        private int lockReleases;
        private String broadcastError;
        private final TokenDefinition token;
        private final ChainAddressRecord tokenAddress;
        private int withdrawalSettlements;

        private FakeRepository(ChainAddressRecord address) {
            this(address, null);
        }

        private FakeRepository(ChainAddressRecord address, TokenDefinition token) {
            this(address, token, null);
        }

        private FakeRepository(ChainAddressRecord address, TokenDefinition token,
                               ChainAddressRecord tokenAddress) {
            super(null);
            this.address = address;
            this.token = token;
            this.tokenAddress = tokenAddress;
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddress(String chain, String assetSymbol, long userId,
                                                             int biz, long addressIndex, String walletRole) {
            return Optional.ofNullable(tokenAddress);
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String assetSymbol, String address) {
            return Optional.of(this.address);
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String address) {
            return Optional.of(this.address);
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(
                UUID tenantId, String chain, String assetSymbol, String address) {
            return TENANT_ID.equals(tenantId) ? Optional.of(this.address) : Optional.empty();
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(
                UUID tenantId, String chain, String address) {
            return TENANT_ID.equals(tenantId) ? Optional.of(this.address) : Optional.empty();
        }

        @Override
        public Optional<TokenDefinition> findToken(String chain, String symbol) {
            return Optional.ofNullable(token);
        }

        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return Optional.empty();
        }

        @Override
        public boolean isWithdrawalInPendingEvm7702Batch(UUID tenantId, long withdrawalOrderId) {
            return false;
        }

        @Override
        public boolean isCollectionInPendingEvm7702Batch(UUID tenantId, long collectionRecordId) {
            return false;
        }

        @Override
        public int claimWithdrawalSigning(String chain, String orderNo, String fromAddress) {
            signingClaims++;
            return 1;
        }

        @Override
        public int claimWithdrawalSigning(
                UUID tenantId, String chain, String orderNo, String fromAddress) {
            signingClaims++;
            return 1;
        }

        @Override
        public int markWithdrawalBroadcastUnknown(String chain, String orderNo,
                                                  String fromAddress, String errorMessage) {
            broadcastUnknownMarks++;
            broadcastError = errorMessage;
            return 1;
        }

        @Override
        public int markWithdrawalBroadcastUnknown(UUID tenantId, String chain, String orderNo,
                                                  String fromAddress, String errorMessage) {
            broadcastUnknownMarks++;
            broadcastError = errorMessage;
            return 1;
        }

        @Override
        public boolean releaseLockedBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
            lockReleases++;
            return true;
        }

        @Override
        public boolean confirmWithdrawalAndSettle(String chain, String orderNo, String txHash,
                                                  String assetSymbol, String accountId, BigDecimal amount) {
            withdrawalSettlements++;
            return true;
        }

        @Override
        public boolean confirmWithdrawalAndSettle(UUID tenantId, String chain, String orderNo,
                                                  String txHash, String assetSymbol,
                                                  String accountId, BigDecimal amount) {
            withdrawalSettlements++;
            return true;
        }
    }

    private static final class TenantCollectionRepository extends ChainJdbcRepository {
        private final UUID custodyAddressId = UUID.fromString("77020000-0000-0000-0000-000000000011");
        private String collectionTarget;
        private BigDecimal collectionAmount;
        private boolean created;

        private TenantCollectionRepository() {
            super(null);
        }

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

        @Override
        public Optional<String> findActiveTenantCollectionAddress(UUID tenantId, String chain) {
            return Optional.of("0xtenant-collection");
        }

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

    private static final class FailingEvmService extends EvmAccountTransactionService {
        private FailingEvmService() {
            super(null, null, null, null);
        }

        @Override
        public String sendNative(String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
            throw new IllegalStateException("rpc accepted maybe but returned error");
        }
    }

    private static final class CapturingEvmFeeService extends EvmAccountTransactionService {
        private int enabledTokenCount;

        private CapturingEvmFeeService() {
            super(null, null, null, null);
        }

        @Override
        public BigDecimal estimateCollectionFeeReserve(String chain, int enabledTokenCount) {
            this.enabledTokenCount = enabledTokenCount;
            return new BigDecimal("0.0005");
        }
    }

    private static final class EvmCollectionRepository extends ChainJdbcRepository {
        private final UUID custodyAddressId = UUID.fromString("77020000-0000-0000-0000-000000000012");
        private BigDecimal collectionAmount;
        private boolean created;
        private final boolean eip7702Managed;

        private EvmCollectionRepository() {
            this(false);
        }

        private EvmCollectionRepository(boolean eip7702Managed) {
            super(null);
            this.eip7702Managed = eip7702Managed;
        }

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

        @Override
        public List<TokenDefinition> listTokens(String chain) {
            return List.of(
                    TokenDefinition.builder().chain(chain).symbol("USDC").active(true).build(),
                    TokenDefinition.builder().chain(chain).symbol("USDT").active(true).build());
        }

        @Override
        public Optional<String> findActiveTenantCollectionAddress(UUID tenantId, String chain) {
            return Optional.of("0xtenant-collection");
        }

        @Override
        public Optional<ChainAsset> findAsset(String chain, String symbol) {
            return Optional.of(ChainAsset.builder()
                    .chain(chain).symbol(symbol).decimals(18).nativeAsset(true).active(true).build());
        }

        @Override
        public boolean isEvm7702NativeCollectionActive(String chain, String network) {
            return false;
        }

        @Override
        public boolean isEvm7702Managed(String chain, String network) {
            return eip7702Managed;
        }

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

    private static final class CapturingAptosService extends AptosTransactionService {
        private TokenDefinition token;
        private long atomicAmount;

        private CapturingAptosService() {
            super(null, null, null);
        }

        @Override
        public String sendToken(ChainAddressRecord from, TokenDefinition token,
                                String toAddress, long amountAtomic) {
            this.token = token;
            this.atomicAmount = amountAtomic;
            return "0xaptos-fa";
        }
    }

    private static final class CapturingTonService extends TonTransactionService {
        private ChainAddressRecord from;
        private String sourceJettonWallet;
        private BigInteger atomicAmount;
        private boolean messageConfirmed;

        private CapturingTonService() {
            super(null, null, null);
        }

        @Override
        public PreparedTransfer prepareJetton(ChainAddressRecord from, String sourceJettonWallet,
                                              String destinationOwner, BigInteger tokenAmount,
                                              String responseAddress, String comment) {
            this.from = from;
            this.sourceJettonWallet = sourceJettonWallet;
            this.atomicAmount = tokenAmount;
            return new PreparedTransfer(1L, new byte[]{1}, "AQ==", "message-hash");
        }

        @Override
        public String broadcastAndRecord(PreparedTransfer transfer, String from, String to,
                                         String symbol, String master, BigDecimal amount) {
            return "ton-hash";
        }

        @Override
        public boolean confirmSentMessage(String messageHash, String senderAddress) {
            return messageConfirmed;
        }
    }
}
