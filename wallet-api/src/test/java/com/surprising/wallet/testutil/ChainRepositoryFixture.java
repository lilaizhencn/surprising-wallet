package com.surprising.wallet.testutil;

import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.*;

/** Typed test wiring only; production code receives its actual table repositories and use cases. */
public class ChainRepositoryFixture {
    public final UtxoRepository utxoRepository;
    public final ChainAddressRepository chainAddressRepository;
    public final ChainProfileRepository chainProfileRepository;
    public final ChainAssetRepository chainAssetRepository;
    public final TokenConfigRepository tokenConfigRepository;
    public final WalletSystemConfigRepository walletSystemConfigRepository;
    public final ChainRpcNodeRepository chainRpcNodeRepository;
    public final AccountSequenceRepository accountSequenceRepository;
    public final EvmTransactionRepository evmTransactionRepository;
    public final TronTransactionRepository tronTransactionRepository;
    public final XrpTransactionRepository xrpTransactionRepository;
    public final SolanaTransactionRepository solanaTransactionRepository;
    public final TonTransactionRepository tonTransactionRepository;
    public final AptosTransactionRepository aptosTransactionRepository;
    public final SuiTransactionRepository suiTransactionRepository;
    public final MoneroTransactionRepository moneroTransactionRepository;
    public final NearTransactionRepository nearTransactionRepository;
    public final LedgerBalanceRepository ledgerBalanceRepository;
    public final DepositRecordRepository depositRecordRepository;
    public final ChainScanBlockRepository chainScanBlockRepository;
    public final ChainScanHeightRepository chainScanHeightRepository;
    public final TronTokenTransferRepository tronTokenTransferRepository;
    public final CollectionRecordRepository collectionRecordRepository;
    public final WithdrawalOrderRepository withdrawalOrderRepository;
    public final CustodyReorgDeficitRepository custodyReorgDeficitRepository;
    public final Evm7702ConfigRepository evm7702ConfigRepository;
    public final CustodyGasAccountRepository custodyGasAccountRepository;
    public final CustodyAddressRepository custodyAddressRepository;
    public final EvmWithdrawalBatchItemRepository evmWithdrawalBatchItemRepository;
    public final EvmWithdrawalBatchRepository evmWithdrawalBatchRepository;
    public final EvmCollectionBatchItemRepository evmCollectionBatchItemRepository;
    public final EvmCollectionBatchRepository evmCollectionBatchRepository;
    public final DepositService depositService;
    public final CollectionPlanningService collectionPlanningService;
    public final UtxoSelectionService utxoSelectionService;
    public final WithdrawalClaimService withdrawalClaimService;
    public final EvmBatchOwnershipService evmBatchOwnershipService;

    protected ChainRepositoryFixture() {
        utxoRepository = mock(UtxoRepository.class);
        chainAddressRepository = mock(ChainAddressRepository.class);
        chainProfileRepository = mock(ChainProfileRepository.class);
        chainAssetRepository = mock(ChainAssetRepository.class);
        tokenConfigRepository = mock(TokenConfigRepository.class);
        walletSystemConfigRepository = mock(WalletSystemConfigRepository.class);
        chainRpcNodeRepository = mock(ChainRpcNodeRepository.class);
        accountSequenceRepository = mock(AccountSequenceRepository.class);
        evmTransactionRepository = mock(EvmTransactionRepository.class);
        tronTransactionRepository = mock(TronTransactionRepository.class);
        xrpTransactionRepository = mock(XrpTransactionRepository.class);
        solanaTransactionRepository = mock(SolanaTransactionRepository.class);
        tonTransactionRepository = mock(TonTransactionRepository.class);
        aptosTransactionRepository = mock(AptosTransactionRepository.class);
        suiTransactionRepository = mock(SuiTransactionRepository.class);
        moneroTransactionRepository = mock(MoneroTransactionRepository.class);
        nearTransactionRepository = mock(NearTransactionRepository.class);
        ledgerBalanceRepository = mock(LedgerBalanceRepository.class);
        depositRecordRepository = mock(DepositRecordRepository.class);
        chainScanBlockRepository = mock(ChainScanBlockRepository.class);
        chainScanHeightRepository = mock(ChainScanHeightRepository.class);
        tronTokenTransferRepository = mock(TronTokenTransferRepository.class);
        collectionRecordRepository = mock(CollectionRecordRepository.class);
        withdrawalOrderRepository = mock(WithdrawalOrderRepository.class);
        custodyReorgDeficitRepository = mock(CustodyReorgDeficitRepository.class);
        evm7702ConfigRepository = mock(Evm7702ConfigRepository.class);
        custodyGasAccountRepository = mock(CustodyGasAccountRepository.class);
        custodyAddressRepository = mock(CustodyAddressRepository.class);
        evmWithdrawalBatchItemRepository = mock(EvmWithdrawalBatchItemRepository.class);
        evmWithdrawalBatchRepository = mock(EvmWithdrawalBatchRepository.class);
        evmCollectionBatchItemRepository = mock(EvmCollectionBatchItemRepository.class);
        evmCollectionBatchRepository = mock(EvmCollectionBatchRepository.class);
        depositService = mock(DepositService.class);
        collectionPlanningService = mock(CollectionPlanningService.class);
        utxoSelectionService = mock(UtxoSelectionService.class);
        withdrawalClaimService = mock(WithdrawalClaimService.class);
        evmBatchOwnershipService = mock(EvmBatchOwnershipService.class);
    }

    public static ChainRepositoryFixture mocked() { return new ChainRepositoryFixture(); }

    public ChainRepositoryFixture(JdbcTemplate jdbc, UtxoRepository utxos, DepositService deposits) {
        utxoRepository = utxos;
        chainAddressRepository = new ChainAddressRepository(jdbc);
        chainProfileRepository = new ChainProfileRepository(jdbc);
        chainAssetRepository = new ChainAssetRepository(jdbc);
        tokenConfigRepository = new TokenConfigRepository(jdbc);
        walletSystemConfigRepository = new WalletSystemConfigRepository(jdbc);
        chainRpcNodeRepository = new ChainRpcNodeRepository(jdbc);
        accountSequenceRepository = new AccountSequenceRepository(jdbc);
        evmTransactionRepository = new EvmTransactionRepository(jdbc);
        tronTransactionRepository = new TronTransactionRepository(jdbc);
        xrpTransactionRepository = new XrpTransactionRepository(jdbc);
        solanaTransactionRepository = new SolanaTransactionRepository(jdbc);
        tonTransactionRepository = new TonTransactionRepository(jdbc);
        aptosTransactionRepository = new AptosTransactionRepository(jdbc);
        suiTransactionRepository = new SuiTransactionRepository(jdbc);
        moneroTransactionRepository = new MoneroTransactionRepository(jdbc);
        nearTransactionRepository = new NearTransactionRepository(jdbc);
        ledgerBalanceRepository = new LedgerBalanceRepository(jdbc);
        depositRecordRepository = new DepositRecordRepository(jdbc);
        chainScanBlockRepository = new ChainScanBlockRepository(jdbc);
        chainScanHeightRepository = new ChainScanHeightRepository(jdbc);
        tronTokenTransferRepository = new TronTokenTransferRepository(jdbc);
        collectionRecordRepository = new CollectionRecordRepository(jdbc);
        withdrawalOrderRepository = new WithdrawalOrderRepository(jdbc);
        custodyReorgDeficitRepository = new CustodyReorgDeficitRepository(jdbc);
        evm7702ConfigRepository = new Evm7702ConfigRepository(jdbc);
        custodyGasAccountRepository = new CustodyGasAccountRepository(jdbc);
        custodyAddressRepository = new CustodyAddressRepository(jdbc);
        evmWithdrawalBatchItemRepository = new EvmWithdrawalBatchItemRepository(jdbc);
        evmWithdrawalBatchRepository = new EvmWithdrawalBatchRepository(jdbc);
        evmCollectionBatchItemRepository = new EvmCollectionBatchItemRepository(jdbc);
        evmCollectionBatchRepository = new EvmCollectionBatchRepository(jdbc);
        depositService = deposits;
        collectionPlanningService = new CollectionPlanningService(
                depositRecordRepository,
                collectionRecordRepository,
                chainAssetRepository,
                custodyAddressRepository,
                chainAddressRepository,
                custodyGasAccountRepository);
        utxoSelectionService = new UtxoSelectionService(utxoRepository, chainAddressRepository, chainProfileRepository);
        withdrawalClaimService = new WithdrawalClaimService(withdrawalOrderRepository, custodyReorgDeficitRepository);
        evmBatchOwnershipService = new EvmBatchOwnershipService(
                evmWithdrawalBatchItemRepository,
                evmWithdrawalBatchRepository,
                evmCollectionBatchItemRepository,
                evmCollectionBatchRepository);
    }
    public Object[] dependencies() {
        return new Object[]{
                utxoRepository,
                chainAddressRepository,
                chainProfileRepository,
                chainAssetRepository,
                tokenConfigRepository,
                walletSystemConfigRepository,
                chainRpcNodeRepository,
                accountSequenceRepository,
                evmTransactionRepository,
                tronTransactionRepository,
                xrpTransactionRepository,
                solanaTransactionRepository,
                tonTransactionRepository,
                aptosTransactionRepository,
                suiTransactionRepository,
                moneroTransactionRepository,
                nearTransactionRepository,
                ledgerBalanceRepository,
                depositRecordRepository,
                chainScanBlockRepository,
                chainScanHeightRepository,
                tronTokenTransferRepository,
                collectionRecordRepository,
                withdrawalOrderRepository,
                custodyReorgDeficitRepository,
                evm7702ConfigRepository,
                custodyGasAccountRepository,
                custodyAddressRepository,
                evmWithdrawalBatchItemRepository,
                evmWithdrawalBatchRepository,
                evmCollectionBatchItemRepository,
                evmCollectionBatchRepository,
                depositService,
                collectionPlanningService,
                utxoSelectionService,
                withdrawalClaimService,
                evmBatchOwnershipService
        };
    }
    public void register(AnnotationConfigApplicationContext context) {
        register(context, UtxoRepository.class, utxoRepository);
        register(context, ChainAddressRepository.class, chainAddressRepository);
        register(context, ChainProfileRepository.class, chainProfileRepository);
        register(context, ChainAssetRepository.class, chainAssetRepository);
        register(context, TokenConfigRepository.class, tokenConfigRepository);
        register(context, WalletSystemConfigRepository.class, walletSystemConfigRepository);
        register(context, ChainRpcNodeRepository.class, chainRpcNodeRepository);
        register(context, AccountSequenceRepository.class, accountSequenceRepository);
        register(context, EvmTransactionRepository.class, evmTransactionRepository);
        register(context, TronTransactionRepository.class, tronTransactionRepository);
        register(context, XrpTransactionRepository.class, xrpTransactionRepository);
        register(context, SolanaTransactionRepository.class, solanaTransactionRepository);
        register(context, TonTransactionRepository.class, tonTransactionRepository);
        register(context, AptosTransactionRepository.class, aptosTransactionRepository);
        register(context, SuiTransactionRepository.class, suiTransactionRepository);
        register(context, MoneroTransactionRepository.class, moneroTransactionRepository);
        register(context, NearTransactionRepository.class, nearTransactionRepository);
        register(context, LedgerBalanceRepository.class, ledgerBalanceRepository);
        register(context, DepositRecordRepository.class, depositRecordRepository);
        register(context, ChainScanBlockRepository.class, chainScanBlockRepository);
        register(context, ChainScanHeightRepository.class, chainScanHeightRepository);
        register(context, TronTokenTransferRepository.class, tronTokenTransferRepository);
        register(context, CollectionRecordRepository.class, collectionRecordRepository);
        register(context, WithdrawalOrderRepository.class, withdrawalOrderRepository);
        register(context, CustodyReorgDeficitRepository.class, custodyReorgDeficitRepository);
        register(context, Evm7702ConfigRepository.class, evm7702ConfigRepository);
        register(context, CustodyGasAccountRepository.class, custodyGasAccountRepository);
        register(context, CustodyAddressRepository.class, custodyAddressRepository);
        register(context, EvmWithdrawalBatchItemRepository.class, evmWithdrawalBatchItemRepository);
        register(context, EvmWithdrawalBatchRepository.class, evmWithdrawalBatchRepository);
        register(context, EvmCollectionBatchItemRepository.class, evmCollectionBatchItemRepository);
        register(context, EvmCollectionBatchRepository.class, evmCollectionBatchRepository);
        register(context, DepositService.class, depositService);
        register(context, CollectionPlanningService.class, collectionPlanningService);
        register(context, UtxoSelectionService.class, utxoSelectionService);
        register(context, WithdrawalClaimService.class, withdrawalClaimService);
        register(context, EvmBatchOwnershipService.class, evmBatchOwnershipService);
    }
    private static <T> void register(AnnotationConfigApplicationContext context, Class<T> type, T value) {
        if (context.getBeanFactory().getBeanNamesForType(type, true, false).length == 0) context.registerBean(type.getName(), type, () -> value);
    }
}
