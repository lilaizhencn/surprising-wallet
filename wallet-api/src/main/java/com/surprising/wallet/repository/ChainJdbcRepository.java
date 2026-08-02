package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.AptosTransactionRecord;
import com.surprising.wallet.chain.model.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.ChainCollectionRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.chain.model.EvmNonceRecord;
import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.chain.model.ChainScanHeightRecord;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.chain.model.LedgerBalanceRecord;
import com.surprising.wallet.chain.model.MoneroTransactionRecord;
import com.surprising.wallet.chain.model.NearTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.chain.model.TronTransactionRecord;
import com.surprising.wallet.chain.model.SolanaTransactionRecord;
import com.surprising.wallet.chain.model.StarknetTransactionRecord;
import com.surprising.wallet.chain.model.TonTransactionRecord;
import com.surprising.wallet.chain.model.SuiTransactionRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.chain.model.XrpTransactionRecord;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.observer.DepositCreditObserver;
import com.surprising.wallet.observer.DepositReorgObserver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 链数据通用 JDBC 仓储，是钱包核心数据访问层，统一管理链资产、地址、余额、
 * 充值记录、提现订单、归集记录、交易记录、UTXO 以及扫描高度等所有链相关数据的持久化。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>链资产（chain_asset）和代币配置（token_config）的 CRUD</li>
 *   <li>链地址（chain_address）管理，支持热钱包/用户地址的分配与查询</li>
 *   <li>充值记录（deposit_record）的原子入账，包含确认数检查与重组检测</li>
 *   <li>分类账余额（ledger_balance）的冻结/解冻/结算</li>
 *   <li>提现订单（withdrawal_order）和归集记录（collection_record）的全生命周期管理</li>
 *   <li>多链交易记录（EVM、TRON、Solana、TON、Aptos、Sui、Monero、NEAR、XRP）</li>
 *   <li>UTXO 管理（扫描、锁定、释放、标记已花费）</li>
 *   <li>扫描高度追踪（chain_scan_height）与区块重组检测（chain_scan_block）</li>
 * </ul>
 *
 * @see DepositCreditObserver
 * @see DepositReorgObserver
 */
@Component
public class ChainJdbcRepository {
    /**
     * 保存 {@code jdbcTemplate}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * 保存 {@code utxoRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final UtxoRepository utxoRepository;
    /** chain_address 单表仓储。 */
    private final ChainAddressRepository chainAddressRepository;
    /** chain_profile 单表仓储。 */
    private final ChainProfileRepository chainProfileRepository;
    /** chain_asset 单表仓储。 */
    private final ChainAssetRepository chainAssetRepository;
    /** token_config 单表仓储。 */
    private final TokenConfigRepository tokenConfigRepository;
    /** wallet_system_config 单表仓储。 */
    private final WalletSystemConfigRepository walletSystemConfigRepository;
    /** chain_rpc_node 单表仓储。 */
    private final ChainRpcNodeRepository chainRpcNodeRepository;
    /** 各链交易表的单表仓储。 */
    private AccountSequenceRepository accountSequenceRepository;
    /** EVM nonce 单表仓储。 */
    private EvmNonceRepository evmNonceRepository;
    /** EVM 交易单表仓储。 */
    private EvmTransactionRepository evmTransactionRepository;
    /** TRON 交易单表仓储。 */
    private TronTransactionRepository tronTransactionRepository;
    /** XRP 交易单表仓储。 */
    private XrpTransactionRepository xrpTransactionRepository;
    /** Solana 交易单表仓储。 */
    private SolanaTransactionRepository solanaTransactionRepository;
    /** TON 交易单表仓储。 */
    private TonTransactionRepository tonTransactionRepository;
    /** Aptos 交易单表仓储。 */
    private AptosTransactionRepository aptosTransactionRepository;
    /** Sui 交易单表仓储。 */
    private SuiTransactionRepository suiTransactionRepository;
    /** Monero 交易单表仓储。 */
    private MoneroTransactionRepository moneroTransactionRepository;
    /** NEAR 交易单表仓储。 */
    private NearTransactionRepository nearTransactionRepository;
    /** Starknet 交易单表仓储。 */
    private StarknetTransactionRepository starknetTransactionRepository;
    /** 账本余额单表仓储。 */
    private LedgerBalanceRepository ledgerBalanceRepository;
    /** 充值记录单表仓储。 */
    private DepositRecordRepository depositRecordRepository;
    /** 扫描区块和高度单表仓储。 */
    private ChainScanBlockRepository chainScanBlockRepository;
    /** 扫描高度单表仓储。 */
    private ChainScanHeightRepository chainScanHeightRepository;
    /** 代币转账单表仓储。 */
    private EvmTokenTransferRepository evmTokenTransferRepository;
    /** TRON 代币转账单表仓储。 */
    private TronTokenTransferRepository tronTokenTransferRepository;
    /** 签名交易单表仓储。 */
    private ChainSigningTransactionRepository chainSigningTransactionRepository;
    /** 归集记录单表仓储。 */
    private CollectionRecordRepository collectionRecordRepository;
    /** 提现订单单表仓储。 */
    private WithdrawalOrderRepository withdrawalOrderRepository;
    /** 重组赤字单表仓储。 */
    private CustodyReorgDeficitRepository custodyReorgDeficitRepository;
    /** EIP-7702 配置单表仓储。 */
    private Evm7702ConfigRepository evm7702ConfigRepository;
    /** 托管 Gas 账户单表仓储。 */
    private CustodyGasAccountRepository custodyGasAccountRepository;
    /** 托管地址单表仓储。 */
    private CustodyAddressRepository custodyAddressRepository;
    /** EVM 批量提现项与批次单表仓储。 */
    private EvmWithdrawalBatchItemRepository evmWithdrawalBatchItemRepository;
    private EvmWithdrawalBatchRepository evmWithdrawalBatchRepository;
    /** EVM 批量归集项与批次单表仓储。 */
    private EvmCollectionBatchItemRepository evmCollectionBatchItemRepository;
    private EvmCollectionBatchRepository evmCollectionBatchRepository;

    /** 充值入账观察者列表（通过 Spring ObjectProvider 注入） */
    private final List<DepositCreditObserver> depositCreditObservers;
    /** 充值重组观察者列表（通过 Spring ObjectProvider 注入） */
    private final List<DepositReorgObserver> depositReorgObservers;

    /**
     * 最小构造器（无观察者）。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public ChainJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.utxoRepository = new UtxoRepository(jdbcTemplate);
        this.chainAddressRepository = new ChainAddressRepository(jdbcTemplate);
        this.chainProfileRepository = new ChainProfileRepository(jdbcTemplate);
        this.chainAssetRepository = new ChainAssetRepository(jdbcTemplate);
        this.tokenConfigRepository = new TokenConfigRepository(jdbcTemplate);
        this.walletSystemConfigRepository = new WalletSystemConfigRepository(jdbcTemplate);
        this.chainRpcNodeRepository = new ChainRpcNodeRepository(jdbcTemplate);
        initializeTableRepositories(jdbcTemplate);
        this.depositCreditObservers = List.of();
        this.depositReorgObservers = List.of();
    }

    /**
     * 仅注入充值入账观察者的构造器。
     *
     * @param jdbcTemplate           JDBC 模板
     * @param depositCreditObservers 充值入账观察者
     */
    public ChainJdbcRepository(JdbcTemplate jdbcTemplate,
                               ObjectProvider<DepositCreditObserver> depositCreditObservers) {
        this.jdbcTemplate = jdbcTemplate;
        this.utxoRepository = new UtxoRepository(jdbcTemplate);
        this.chainAddressRepository = new ChainAddressRepository(jdbcTemplate);
        this.chainProfileRepository = new ChainProfileRepository(jdbcTemplate);
        this.chainAssetRepository = new ChainAssetRepository(jdbcTemplate);
        this.tokenConfigRepository = new TokenConfigRepository(jdbcTemplate);
        this.walletSystemConfigRepository = new WalletSystemConfigRepository(jdbcTemplate);
        this.chainRpcNodeRepository = new ChainRpcNodeRepository(jdbcTemplate);
        initializeTableRepositories(jdbcTemplate);
        this.depositCreditObservers = depositCreditObservers.orderedStream().toList();
        this.depositReorgObservers = List.of();
    }

    /**
     * 完整构造器，注入充值入账和重组两种观察者。
     *
     * @param jdbcTemplate           JDBC 模板
     * @param depositCreditObservers 充值入账观察者
     * @param depositReorgObservers  重组观察者
     */
    public ChainJdbcRepository(JdbcTemplate jdbcTemplate,
                               ObjectProvider<DepositCreditObserver> depositCreditObservers,
                               ObjectProvider<DepositReorgObserver> depositReorgObservers) {
        this(jdbcTemplate, depositCreditObservers, depositReorgObservers,
                new UtxoRepository(jdbcTemplate));
    }

    /**
     * Spring 完整构造器，注入观察者和独立 UTXO 仓储。
     */
    @Autowired
    public ChainJdbcRepository(JdbcTemplate jdbcTemplate,
                               ObjectProvider<DepositCreditObserver> depositCreditObservers,
                               ObjectProvider<DepositReorgObserver> depositReorgObservers,
                               UtxoRepository utxoRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.utxoRepository = utxoRepository;
        this.chainAddressRepository = new ChainAddressRepository(jdbcTemplate);
        this.chainProfileRepository = new ChainProfileRepository(jdbcTemplate);
        this.chainAssetRepository = new ChainAssetRepository(jdbcTemplate);
        this.tokenConfigRepository = new TokenConfigRepository(jdbcTemplate);
        this.walletSystemConfigRepository = new WalletSystemConfigRepository(jdbcTemplate);
        this.chainRpcNodeRepository = new ChainRpcNodeRepository(jdbcTemplate);
        initializeTableRepositories(jdbcTemplate);
        this.depositCreditObservers = depositCreditObservers.orderedStream().toList();
        this.depositReorgObservers = depositReorgObservers.orderedStream().toList();
    }

    /** 初始化链领域各单表仓储。 */
    private void initializeTableRepositories(JdbcTemplate jdbcTemplate) {
        this.accountSequenceRepository = new AccountSequenceRepository(jdbcTemplate);
        this.evmNonceRepository = new EvmNonceRepository(jdbcTemplate);
        this.evmTransactionRepository = new EvmTransactionRepository(jdbcTemplate);
        this.tronTransactionRepository = new TronTransactionRepository(jdbcTemplate);
        this.xrpTransactionRepository = new XrpTransactionRepository(jdbcTemplate);
        this.solanaTransactionRepository = new SolanaTransactionRepository(jdbcTemplate);
        this.tonTransactionRepository = new TonTransactionRepository(jdbcTemplate);
        this.aptosTransactionRepository = new AptosTransactionRepository(jdbcTemplate);
        this.suiTransactionRepository = new SuiTransactionRepository(jdbcTemplate);
        this.moneroTransactionRepository = new MoneroTransactionRepository(jdbcTemplate);
        this.nearTransactionRepository = new NearTransactionRepository(jdbcTemplate);
        this.starknetTransactionRepository = new StarknetTransactionRepository(jdbcTemplate);
        this.ledgerBalanceRepository = new LedgerBalanceRepository(jdbcTemplate);
        this.depositRecordRepository = new DepositRecordRepository(jdbcTemplate);
        this.chainScanBlockRepository = new ChainScanBlockRepository(jdbcTemplate);
        this.chainScanHeightRepository = new ChainScanHeightRepository(jdbcTemplate);
        this.evmTokenTransferRepository = new EvmTokenTransferRepository(jdbcTemplate);
        this.tronTokenTransferRepository = new TronTokenTransferRepository(jdbcTemplate);
        this.chainSigningTransactionRepository = new ChainSigningTransactionRepository(jdbcTemplate);
        this.collectionRecordRepository = new CollectionRecordRepository(jdbcTemplate);
        this.withdrawalOrderRepository = new WithdrawalOrderRepository(jdbcTemplate);
        this.custodyReorgDeficitRepository = new CustodyReorgDeficitRepository(jdbcTemplate);
        this.evm7702ConfigRepository = new Evm7702ConfigRepository(jdbcTemplate);
        this.custodyGasAccountRepository = new CustodyGasAccountRepository(jdbcTemplate);
        this.custodyAddressRepository = new CustodyAddressRepository(jdbcTemplate);
        this.evmWithdrawalBatchItemRepository = new EvmWithdrawalBatchItemRepository(jdbcTemplate);
        this.evmWithdrawalBatchRepository = new EvmWithdrawalBatchRepository(jdbcTemplate);
        this.evmCollectionBatchItemRepository = new EvmCollectionBatchItemRepository(jdbcTemplate);
        this.evmCollectionBatchRepository = new EvmCollectionBatchRepository(jdbcTemplate);
    }
    /**
     * 写入或更新 {@code upsertChainAsset} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int upsertChainAsset(ChainAsset asset) {
        return chainAssetRepository.upsert(asset);
    }
    /**
     * 获取或查询 {@code findBitcoinLikeProfile} 对应的数据，供调用方读取当前状态。
     */
    public Optional<BitcoinLikeChainProfile> findBitcoinLikeProfile(String chain, String network) {
        return chainProfileRepository.findBitcoinLike(chain, network);
    }
    /**
     * 获取或查询 {@code findAccountChainProfile} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AccountChainProfile> findAccountChainProfile(String chain, String network) {
        return chainProfileRepository.findAccount(chain, network);
    }
    /**
     * 获取或查询 {@code findProfileByRuntimeCurrencyId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AccountChainProfile> findProfileByRuntimeCurrencyId(int runtimeCurrencyId) {
        return chainProfileRepository.findAccountByRuntimeCurrency(runtimeCurrencyId);
    }
    /**
     * 获取或查询 {@code findProfileByChain} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AccountChainProfile> findProfileByChain(String chain) {
        return chainProfileRepository.findAccountByChain(chain);
    }
    /**
     * 获取或查询 {@code findChainByRuntimeCurrencyId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findChainByRuntimeCurrencyId(int runtimeCurrencyId) {
        return chainProfileRepository.findChainByRuntimeCurrency(runtimeCurrencyId);
    }
    /**
     * 获取或查询 {@code findNetworkByRuntimeCurrencyId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findNetworkByRuntimeCurrencyId(int runtimeCurrencyId) {
        return chainProfileRepository.findNetworkByRuntimeCurrency(runtimeCurrencyId);
    }
    /**
     * 判断 {@code isRuntimeCurrencyFamily} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isRuntimeCurrencyFamily(int runtimeCurrencyId, String family) {
        return chainProfileRepository.isRuntimeCurrencyFamily(runtimeCurrencyId, family);
    }
    /**
     * 执行 {@code reserveNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int reserveNonce(EvmNonceRecord nonceRecord) {
        return evmNonceRepository.upsert(nonceRecord);
    }

    /**
     * 执行 {@code reserveEvmNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public long reserveEvmNonce(String chain, String address, long chainNonce) {
        return reserveEvmNonce(chain, address, BigInteger.valueOf(chainNonce)).longValueExact();
    }

    /**
     * 执行 {@code reserveEvmNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public BigInteger reserveEvmNonce(String chain, String address, BigInteger chainNonce) {
        if (chainNonce == null || chainNonce.signum() < 0 || chainNonce.bitLength() > 256) {
            throw new IllegalArgumentException("EVM nonce must be a valid uint256");
        }
        return evmNonceRepository.reserve(chain, address, chainNonce);
    }
    /**
     * 记录或保存 {@code recordEvmTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordEvmTransaction(EvmTransactionRecord tx) {
        return evmTransactionRepository.upsert(tx);
    }
    /**
     * 记录或保存 {@code recordTronTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordTronTransaction(TronTransactionRecord tx) {
        return tronTransactionRepository.upsert(tx);
    }
    /**
     * 记录或保存 {@code recordXrpTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordXrpTransaction(XrpTransactionRecord tx) {
        return xrpTransactionRepository.upsert(tx);
    }
    /**
     * 获取或查询 {@code findXrpTransactionAssetSymbol} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findXrpTransactionAssetSymbol(String chain, String txHash) {
        return xrpTransactionRepository.findAssetSymbol(chain, txHash);
    }
    /**
     * 写入或更新 {@code upsertLedgerBalance} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int upsertLedgerBalance(LedgerBalanceRecord record) {
        return ledgerBalanceRepository.upsert(record);
    }
    /**
     * 获取或查询 {@code listEnabledHotWalletAddresses} 对应的数据，供调用方读取当前状态。
     */
    public Set<String> listEnabledHotWalletAddresses(String chain) {
        return listEnabledChainScanAddresses(chain);
    }
    /**
     * 获取或查询 {@code listEnabledChainScanAddresses} 对应的数据，供调用方读取当前状态。
     */
    public Set<String> listEnabledChainScanAddresses(String chain) {
        return chainAddressRepository.listEnabledAddresses(chain).stream()
                .collect(Collectors.toSet());
    }
    /**
     * 写入或更新 {@code upsertChainAddress} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int upsertChainAddress(ChainAddressRecord address) {
        return chainAddressRepository.upsert(address);
    }

    /**
     * 获取或查询 {@code findChainAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddress(String chain, String assetSymbol, long userId,
                                                         int biz, long addressIndex, String walletRole) {
        return chainAddressRepository.find(chain, assetSymbol, userId, biz, addressIndex, walletRole);
    }
    /**
     * 获取或查询 {@code listDefaultHotAddressCandidates} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listDefaultHotAddressCandidates(String chain, String assetSymbol) {
        return chainAddressRepository.listDefaultHot(chain, assetSymbol,
                HotWalletRules.DEFAULT_HOT_USER_ID, HotWalletRules.DEFAULT_HOT_BIZ,
                HotWalletRules.DEFAULT_HOT_WALLET_ROLE);
    }
    /**
     * 获取或查询 {@code listReservedHotNamespaceAddresses} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listReservedHotNamespaceAddresses(String chain) {
        return chainAddressRepository.listReservedHot(chain,
                HotWalletRules.DEFAULT_HOT_USER_ID, HotWalletRules.DEFAULT_HOT_BIZ);
    }
    /**
     * 获取或查询 {@code listChainAddresses} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
        return chainAddressRepository.listEnabled(chain, assetSymbol);
    }
    /**
     * 获取或查询 {@code listChainAddresses} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listChainAddresses(String chain) {
        return chainAddressRepository.listEnabled(chain);
    }
    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String address) {
        return chainAddressRepository.findEnabledByAddress(chain, address);
    }
    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String assetSymbol, String address) {
        return chainAddressRepository.findEnabledByAddress(chain, assetSymbol, address);
    }

    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(
            UUID tenantId, String chain, String assetSymbol, String address) {
        return chainAddressRepository.findEnabledByTenantAndAddress(tenantId, chain, assetSymbol, address);
    }

    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(
            UUID tenantId, String chain, String address) {
        return chainAddressRepository.findEnabledByTenantAndAddress(tenantId, chain, address);
    }

    /**
     * 获取或查询 {@code findMaxChainAddressIndex} 对应的数据，供调用方读取当前状态。
     */
    public Optional<Long> findMaxChainAddressIndex(String chain, String assetSymbol, long userId,
                                                   int biz, String walletRole) {
        return chainAddressRepository.findMaxIndex(chain, assetSymbol, userId, biz, walletRole);
    }
    /**
     * 记录或保存 {@code recordSolanaTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordSolanaTransaction(SolanaTransactionRecord tx) {
        return solanaTransactionRepository.upsert(tx);
    }
    /**
     * 记录或保存 {@code recordTonTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordTonTransaction(TonTransactionRecord tx) {
        return tonTransactionRepository.upsert(tx);
    }
    /**
     * 设置或更新 {@code updateTonDepositTransactionConfirmations} 对应的状态，并保持相关业务字段一致。
     */
    public int updateTonDepositTransactionConfirmations(
            String chain, String txHash, int confirmations, int requiredConfirmations) {
        return tonTransactionRepository.updateDepositConfirmations(chain, txHash, confirmations, requiredConfirmations);
    }
    /**
     * 写入或更新 {@code markTonTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markTonTransactionConfirmed(String chain, String txHash) {
        return tonTransactionRepository.markConfirmed(chain, txHash);
    }
    /**
     * 获取或查询 {@code findTonTransactionRawPayload} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findTonTransactionRawPayload(String chain, String txHash) {
        return tonTransactionRepository.findRawPayload(chain, txHash);
    }
    /**
     * 记录或保存 {@code recordAptosTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordAptosTransaction(AptosTransactionRecord tx) {
        return aptosTransactionRepository.upsert(tx);
    }

    /**
     * 写入或更新 {@code markAptosTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markAptosTransactionConfirmed(String chain, String txHash, long version,
                                             long gasUsed, long gasUnitPrice, String rawPayload) {
        return aptosTransactionRepository.markConfirmed(chain, txHash, version, gasUsed, gasUnitPrice, rawPayload);
    }
    /**
     * 记录或保存 {@code recordSuiTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordSuiTransaction(SuiTransactionRecord tx) {
        return suiTransactionRepository.upsert(tx);
    }
    /**
     * 记录或保存 {@code recordMoneroTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordMoneroTransaction(MoneroTransactionRecord tx) {
        return moneroTransactionRepository.upsert(tx);
    }

    /**
     * 写入或更新 {@code markSuiTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markSuiTransactionConfirmed(String chain, String txDigest, long checkpoint,
                                           long gasUsed, String rawPayload) {
        return suiTransactionRepository.markConfirmed(chain, txDigest, checkpoint, gasUsed, rawPayload);
    }
    /**
     * 记录或保存 {@code recordNearTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordNearTransaction(NearTransactionRecord tx) {
        return nearTransactionRepository.upsert(tx);
    }

    /** 记录或更新 Starknet 交易。 */
    public int recordStarknetTransaction(StarknetTransactionRecord tx) {
        return starknetTransactionRepository.upsert(tx);
    }

    /** 确认 Starknet 交易并保存实际手续费。 */
    public int markStarknetTransactionConfirmed(String chain, String txHash, BigDecimal fee,
                                                Long blockHeight, int confirmations, String rawPayload) {
        return starknetTransactionRepository.markConfirmed(
                chain, txHash, fee, blockHeight, confirmations, rawPayload);
    }

    /**
     * 写入或更新 {@code markNearTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markNearTransactionConfirmed(String chain, String txHash, long blockHeight,
                                            long gasBurnt, String rawPayload) {
        return nearTransactionRepository.markConfirmed(chain, txHash, blockHeight, gasBurnt, rawPayload);
    }
    /**
     * 获取或查询 {@code findNearTransactionSender} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findNearTransactionSender(String chain, String txHash) {
        return nearTransactionRepository.findSender(chain, txHash);
    }

    /**
     * 执行 {@code reserveAccountSequence} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public long reserveAccountSequence(String chain, String address, long chainSequence) {
        return accountSequenceRepository.reserve(chain, address, chainSequence);
    }
    /**
     * 执行 {@code synchronizeAccountSequence} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public void synchronizeAccountSequence(String chain, String address, long chainSequence) {
        accountSequenceRepository.synchronize(chain, address, chainSequence);
    }

        /**
     * 记录或保存 {@code recordAndCreditDeposit} 对应的数据，并遵守幂等和事务约束。
     */
    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCreditDeposit(DepositEvent event, int requiredConfirmations) {
        return recordAndCreditDeposit(event, 0L, requiredConfirmations);
    }

    /**
     * 记录或保存 {@code recordAndCreditDeposit} 对应的数据，并遵守幂等和事务约束。
     */
    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCreditDeposit(DepositEvent event, long logIndex, int requiredConfirmations) {
        return recordAndCreditDeposit(event, logIndex, requiredConfirmations, event.toAddress().toLowerCase());
    }

    /**
     * 记录或保存 {@code recordAndCreditDeposit} 对应的数据，并遵守幂等和事务约束。
     */
    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCreditDeposit(DepositEvent event, long logIndex, int requiredConfirmations,
                                          String accountId) {
        String chain = event.chainType().name();
        UUID tenantId = requireDepositTenant(chain, accountId, event.toAddress());
        if (isInternalCollectionTransfer(tenantId, chain, event.txId(), event.toAddress())) {
            return false;
        }
        String status = event.confirmations() <= 0 ? "DETECTED"
                : event.confirmations() < requiredConfirmations ? "CONFIRMING" : "CONFIRMED";
        int recorded = depositRecordRepository.upsert(tenantId, chain, event.assetSymbol(), event.txId(), logIndex,
                event.fromAddress(), event.toAddress(), event.tokenAddress(), event.amount(), event.blockHeight(),
                event.blockHash(), event.confirmations(), status, accountId, event.rawPayload());
        if (recorded != 1) {
            throw new IllegalStateException("deposit record belongs to another tenant");
        }

        if (event.confirmations() < requiredConfirmations) {
            return false;
        }

        int credited = depositRecordRepository.markCredited(chain, event.txId(), logIndex);
        if (credited == 1) {
            incrementLedgerBalance(tenantId, chain, event.assetSymbol(), accountId, event.amount());
            for (DepositCreditObserver observer : depositCreditObservers) {
                observer.onDepositCredited(event, logIndex, accountId);
            }
            return true;
        }
        return false;
    }

    /**
     * 获取或查询 {@code listPendingDeposits} 对应的数据，供调用方读取当前状态。
     */
    public List<PendingDepositRecord> listPendingDeposits(String chain, int requiredConfirmations, int limit) {
        return depositRecordRepository.listPending(chain, requiredConfirmations, limit).stream()
                .map(row -> new PendingDepositRecord(row.assetSymbol(), row.txHash(), row.logIndex(),
                        row.fromAddress(), row.toAddress(), row.contractAddress(), row.amount(), row.blockHeight(),
                        row.blockHash(), row.confirmations(), row.accountId(), row.rawPayload()))
                .toList();
    }

    public record PendingDepositRecord(
            String assetSymbol,
            String txHash,
            long logIndex,
            String fromAddress,
            String toAddress,
            String contractAddress,
            BigDecimal amount,
            long blockHeight,
            String blockHash,
            int confirmations,
            String accountId,
            String rawPayload) {
    }

        /**
     * 设置或更新 {@code observeCanonicalBlock} 对应的状态，并保持相关业务字段一致。
     */
    @Transactional(rollbackFor = Throwable.class)
    public BlockObservation observeCanonicalBlock(String chain, String scannerName,
                                                   long blockHeight, String blockHash,
                                                   String parentHash) {
        String normalizedChain = requireText(chain, "chain").toUpperCase(java.util.Locale.ROOT);
        String normalizedScanner = requireText(scannerName, "scannerName");
        String normalizedHash = requireText(blockHash, "blockHash");
        List<String> existing = chainScanBlockRepository.findHashForUpdate(
                normalizedChain, normalizedScanner, blockHeight);
        if (existing.isEmpty()) {
            chainScanBlockRepository.insert(normalizedChain, normalizedScanner, blockHeight,
                    normalizedHash, parentHash);
            return new BlockObservation(false, null, normalizedHash, 0);
        }
        String previousHash = existing.getFirst();
        if (previousHash.equalsIgnoreCase(normalizedHash)) {
            chainScanBlockRepository.updateObservation(normalizedChain, normalizedScanner, blockHeight,
                    previousHash, parentHash);
            return new BlockObservation(false, previousHash, normalizedHash, 0);
        }

        List<DepositRecordRepository.ReorgRecord> deposits = depositRecordRepository.listForReorg(
                normalizedChain, blockHeight, normalizedHash);
        utxoRepository.markOrphaned(normalizedChain, blockHeight, normalizedHash);
        int reversed = 0;
        for (DepositRecordRepository.ReorgRecord deposit : deposits) {
            reverseDeposit(deposit, normalizedHash,
                    "canonical block changed from " + previousHash + " to " + normalizedHash);
            reversed++;
        }
        chainScanBlockRepository.updateObservation(normalizedChain, normalizedScanner, blockHeight,
                normalizedHash, parentHash);
        return new BlockObservation(true, previousHash, normalizedHash, reversed);
    }
    /**
     * 执行 {@code reverseDeposit} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void reverseDeposit(DepositRecordRepository.ReorgRecord deposit, String replacementBlockHash, String reason) {
        BigDecimal reversedAmount = BigDecimal.ZERO;
        BigDecimal deficitAmount = BigDecimal.ZERO;
        if (deposit.credited()) {
            BigDecimal available = ledgerBalanceRepository.findAvailableForUpdate(
                    deposit.tenantId(), deposit.chain(), deposit.assetSymbol(), deposit.accountId());
            reversedAmount = available.min(deposit.amount()).max(BigDecimal.ZERO);
            deficitAmount = deposit.amount().subtract(reversedAmount);
            if (reversedAmount.signum() > 0) {
                if (!ledgerBalanceRepository.debit(deposit.chain(), deposit.assetSymbol(), deposit.accountId(),
                        reversedAmount, deposit.tenantId())) {
                    throw new IllegalStateException("unable to reverse orphaned deposit balance");
                }
            }
        }
        int updated = depositRecordRepository.markReorged(deposit.id(), reason);
        if (updated != 1) {
            throw new IllegalStateException("unable to mark deposit reorged: " + deposit.id());
        }
        if (deposit.credited()) {
            DepositReorgObserver.ReorgedDeposit event = new DepositReorgObserver.ReorgedDeposit(
                    deposit.id(), deposit.tenantId(), deposit.chain(), deposit.assetSymbol(),
                    deposit.txHash(), deposit.logIndex(), deposit.accountId(), deposit.toAddress(),
                    deposit.amount(), reversedAmount, deficitAmount, deposit.creditGeneration(),
                    deposit.blockHeight(), deposit.blockHash(), replacementBlockHash, reason);
            for (DepositReorgObserver observer : depositReorgObservers) {
                observer.onDepositReorged(event);
            }
        }
    }
    /**
     * 校验 {@code requireText} 对应的前置条件，不满足时抛出明确异常。
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record BlockObservation(boolean reorg, String previousBlockHash,
                                   String blockHash, int reversedDepositCount) {
    }

    /**
     * 判断 {@code isInternalCollectionTransfer} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isInternalCollectionTransfer(UUID tenantId, String chain,
                                                 String txHash, String toAddress) {
        return collectionRecordRepository.existsInternalTransfer(tenantId, chain, txHash, toAddress);
    }
    /**
     * 校验 {@code requireDepositTenant} 对应的前置条件，不满足时抛出明确异常。
     */
    private UUID requireDepositTenant(String chain, String accountId, String address) {
        List<UUID> tenants = chainAddressRepository.listTenantIds(chain, accountId, address);
        if (tenants.isEmpty()) {
            throw new IllegalStateException("deposit address is not assigned to a tenant");
        }
        if (tenants.size() > 1) {
            throw new IllegalStateException("deposit address maps to more than one tenant");
        }
        return tenants.getFirst();
    }

    /**
     * 写入或更新 {@code upsertUtxo} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void upsertUtxo(String chain, String assetSymbol, String txHash, int vout, String address,
                           BigDecimal amount, long blockHeight, String blockHash,
                           int confirmations, boolean credited) {
        utxoRepository.upsert(
                chain, assetSymbol, txHash, vout, address, amount, blockHeight, blockHash,
                confirmations, credited);
    }
    /**
     * 写入或更新 {@code markUtxoCredited} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markUtxoCredited(String chain, String txHash, int vout) {
        return utxoRepository.markCredited(chain, txHash, vout);
    }
    /**
     * 执行 {@code lockUtxo} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int lockUtxo(String chain, String txHash, int vout, String lockRef) {
        return utxoRepository.lock(chain, txHash, vout, lockRef);
    }
    /**
     * 执行 {@code lockUtxo} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int lockUtxo(UUID tenantId, String chain, String txHash, int vout, String lockRef) {
        String address = utxoRepository.findAddress(chain, txHash, vout).orElse(null);
        if (address == null || !chainAddressRepository.existsEnabledAddress(tenantId, chain, address)) {
            return 0;
        }
        return utxoRepository.lock(chain, txHash, vout, lockRef);
    }
    /**
     * 删除或释放 {@code releaseUtxos} 对应的资源，并收敛相关业务状态。
     */
    public int releaseUtxos(String chain, String lockRef) {
        return utxoRepository.release(chain, lockRef);
    }
    /**
     * 写入或更新 {@code markUtxosSpent} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markUtxosSpent(String chain, String lockRef, String spentTxHash) {
        return utxoRepository.markSpent(chain, lockRef, spentTxHash);
    }
    /**
     * 设置或更新 {@code updateUtxoConfirmations} 对应的状态，并保持相关业务字段一致。
     */
    public int updateUtxoConfirmations(String chain, String txHash, int vout, int confirmations) {
        return utxoRepository.updateConfirmations(chain, txHash, vout, confirmations);
    }

    /**
     * 获取或查询 {@code listSpendableUtxos} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listSpendableUtxos(String chain, String assetSymbol,
                                                    long requiredConfirmations,
                                                    int limit, int offset) {
        return utxoRepository.listSpendable(
                chain, assetSymbol, requiredConfirmations, limit, offset,
                requireRuntimeCurrencyId(chain));
    }

    /**
     * 获取或查询 {@code listSpendableUtxos} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listSpendableUtxos(UUID tenantId, String chain, String assetSymbol,
                                                    long requiredConfirmations, int limit, int offset) {
        return utxoRepository.listSpendable(
                chain, assetSymbol, requiredConfirmations, limit, offset,
                requireRuntimeCurrencyId(chain), chainAddressRepository.listEnabledAddresses(tenantId, chain));
    }

    /**
     * 获取或查询 {@code listAvailableUtxosBelowConfirmations} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listAvailableUtxosBelowConfirmations(String chain, String assetSymbol,
                                                                      long maxConfirmations,
                                                                      long afterId, int limit) {
        return utxoRepository.listAvailableBelowConfirmations(
                chain, assetSymbol, maxConfirmations, afterId, limit,
                requireRuntimeCurrencyId(chain));
    }
    /**
     * 执行 {@code sumAvailableUtxoAmount} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public BigDecimal sumAvailableUtxoAmount(String chain, String assetSymbol) {
        return utxoRepository.sumAvailableAmount(chain, assetSymbol);
    }
    /**
     * 获取或查询 {@code listUtxosByAddress} 对应的数据，供调用方读取当前状态。
     */
    public List<UtxoTransaction> listUtxosByAddress(String chain, String address, int limit) {
        return utxoRepository.listByAddress(chain, address, limit, requireRuntimeCurrencyId(chain));
    }

    /** 获取统一 UTXO 运行时使用的币种编号。 */
    private int requireRuntimeCurrencyId(String chain) {
        return findProfileByChain(chain)
                .map(AccountChainProfile::getRuntimeCurrencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain profile for unified UTXO chain " + chain));
    }
    /**
     * 处理 {@code depositRecordExists} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean depositRecordExists(String chain, String txHash, int logIndex) {
        return depositRecordRepository.existsCanonical(chain, txHash, logIndex);
    }
    /**
     * 构建或生成 {@code createWithdrawalOrder} 对应的结果，并执行输入和状态校验。
     */
    public int createWithdrawalOrder(String orderNo, long userId, String chain, String assetSymbol,
                                     String toAddress, BigDecimal amount, BigDecimal fee) {
        return createWithdrawalOrder(orderNo, userId, chain, assetSymbol, null, null, toAddress, amount, fee);
    }

    /**
     * 构建或生成 {@code createWithdrawalOrder} 对应的结果，并执行输入和状态校验。
     */
    public int createWithdrawalOrder(String orderNo, long userId, String chain, String assetSymbol,
                                     String fromAddress, String debitAccountId, String toAddress,
                                     BigDecimal amount, BigDecimal fee) {
        return withdrawalOrderRepository.create(orderNo, userId, chain, assetSymbol, fromAddress,
                debitAccountId, toAddress, amount, fee);
    }

    /**
     * 构建或生成 {@code createTenantWithdrawalOrder} 对应的结果，并执行输入和状态校验。
     */
    public int createTenantWithdrawalOrder(UUID tenantId, String orderNo, long userId,
                                           String chain, String assetSymbol,
                                           String fromAddress, String debitAccountId,
                                           String toAddress, BigDecimal amount, BigDecimal fee) {
        return withdrawalOrderRepository.createForTenant(tenantId, orderNo, userId, chain, assetSymbol,
                fromAddress, debitAccountId, toAddress, amount, fee);
    }
    /**
     * 获取或查询 {@code listWithdrawalsForSigning} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawalOrderRecord> listWithdrawalsForSigning(String chain, String assetSymbol, int limit) {
        return withdrawalOrderRepository.listForSigning(chain, assetSymbol, limit);
    }
    /**
     * 获取或查询 {@code listWithdrawalsForSigning} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawalOrderRecord> listWithdrawalsForSigning(String chain, int limit) {
        return withdrawalOrderRepository.listForSigning(chain, limit);
    }
    /**
     * 获取或查询 {@code listWithdrawalsByStatus} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawalOrderRecord> listWithdrawalsByStatus(String chain, String status, int limit) {
        return withdrawalOrderRepository.listByStatus(chain, status, limit);
    }
    /**
     * 判断 {@code isWithdrawalInPendingEvm7702Batch} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isWithdrawalInPendingEvm7702Batch(UUID tenantId, long withdrawalOrderId) {
        return evmWithdrawalBatchItemRepository.listSubmittedByWithdrawal(tenantId, withdrawalOrderId).stream()
                .map(row -> (UUID) row.get("batch_id"))
                .anyMatch(batchId -> evmWithdrawalBatchRepository.findStatuses(tenantId, batchId).stream()
                        .anyMatch(status -> Set.of("SUBMITTED", "CONFIRMING", "BROADCAST_UNKNOWN").contains(status)));
    }
    /**
     * 判断 {@code isCollectionInPendingEvm7702Batch} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isCollectionInPendingEvm7702Batch(UUID tenantId, long collectionRecordId) {
        return evmCollectionBatchItemRepository.listSubmittedByCollection(tenantId, collectionRecordId).stream()
                .map(row -> ((Number) row.get("batch_id")).longValue())
                .anyMatch(batchId -> evmCollectionBatchRepository.findStatuses(tenantId, batchId).stream()
                        .anyMatch(status -> Set.of("SUBMITTED", "CONFIRMING", "BROADCAST_UNKNOWN").contains(status)));
    }
    /**
     * 执行 {@code claimWithdrawalSigning} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int claimWithdrawalSigning(String chain, String orderNo, String fromAddress) {
        Optional<WithdrawalOrderRecord> order = withdrawalOrderRepository.find(chain, orderNo, null);
        if (order.isEmpty() || hasOpenReorgDeficit(order.get())) {
            return 0;
        }
        return withdrawalOrderRepository.claimSigning(chain, orderNo, fromAddress);
    }
    /**
     * 执行 {@code claimWithdrawalSigning} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int claimWithdrawalSigning(UUID tenantId, String chain, String orderNo, String fromAddress) {
        Optional<WithdrawalOrderRecord> order = withdrawalOrderRepository.find(chain, orderNo, tenantId);
        if (order.isEmpty() || hasOpenReorgDeficit(order.get())) {
            return 0;
        }
        return withdrawalOrderRepository.claimSigning(tenantId, chain, orderNo, fromAddress);
    }

    /** 判断提现账户是否存在未弥补的重组赤字。 */
    private boolean hasOpenReorgDeficit(WithdrawalOrderRecord order) {
        return order.getTenantId() != null && order.getDebitAccountId() != null
                && custodyReorgDeficitRepository.existsOpen(order.getTenantId(), order.getChain(),
                order.getAssetSymbol(), order.getDebitAccountId());
    }
    /**
     * 写入或更新 {@code markStaleSigningWithdrawalsUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markStaleSigningWithdrawalsUnknown(String chain, Instant before) {
        return withdrawalOrderRepository.markStaleSigningUnknown(chain, before);
    }
    /**
     * 写入或更新 {@code markWithdrawalSent} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalSent(String chain, String orderNo, String fromAddress, String txHash) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("withdrawal tx hash must not be blank");
        }
        return withdrawalOrderRepository.markSent(chain, orderNo, fromAddress, txHash);
    }

    /**
     * 写入或更新 {@code markWithdrawalSent} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalSent(UUID tenantId, String chain, String orderNo,
                                  String fromAddress, String txHash) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("withdrawal tx hash must not be blank");
        }
        return withdrawalOrderRepository.markSent(tenantId, chain, orderNo, fromAddress, txHash);
    }
    /**
     * 写入或更新 {@code markWithdrawalBroadcastUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalBroadcastUnknown(String chain, String orderNo, String fromAddress, String errorMessage) {
        return withdrawalOrderRepository.markBroadcastUnknown(chain, orderNo, fromAddress, errorMessage);
    }

    /**
     * 写入或更新 {@code markWithdrawalBroadcastUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalBroadcastUnknown(UUID tenantId, String chain, String orderNo,
                                              String fromAddress, String errorMessage) {
        return withdrawalOrderRepository.markBroadcastUnknown(tenantId, chain, orderNo, fromAddress, errorMessage);
    }

    /**
     * 设置或更新 {@code updateWithdrawalStatus} 对应的状态，并保持相关业务字段一致。
     */
    public int updateWithdrawalStatus(String chain, String orderNo, String status, String fromAddress,
                                      String txHash, String errorMessage) {
        return withdrawalOrderRepository.updateStatus(chain, orderNo, status, fromAddress, txHash, errorMessage);
    }

    /**
     * 设置或更新 {@code updateWithdrawalStatus} 对应的状态，并保持相关业务字段一致。
     */
    public int updateWithdrawalStatus(UUID tenantId, String chain, String orderNo, String status,
                                      String fromAddress, String txHash, String errorMessage) {
        return withdrawalOrderRepository.updateStatus(tenantId, chain, orderNo, status,
                fromAddress, txHash, errorMessage);
    }
    /**
     * 写入或更新 {@code markWithdrawalConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalConfirmed(String chain, String orderNo, String txHash) {
        return withdrawalOrderRepository.markConfirmed(chain, orderNo, txHash);
    }
    /**
     * 写入或更新 {@code markWithdrawalConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalConfirmed(UUID tenantId, String chain, String orderNo, String txHash) {
        return withdrawalOrderRepository.markConfirmed(tenantId, chain, orderNo, txHash);
    }

    /**
     * 处理 {@code confirmWithdrawalAndSettle} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public boolean confirmWithdrawalAndSettle(UUID tenantId, String chain, String orderNo, String txHash,
                                              String assetSymbol, String accountId, BigDecimal amount) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("withdrawal tx hash must not be blank");
        }
        WithdrawalOrderRecord record = findWithdrawalOrder(tenantId, chain, orderNo)
                .orElseThrow(() -> new IllegalStateException(
                        "missing tenant withdrawal order " + chain + ":" + orderNo));
        if ("CONFIRMED".equals(record.getStatus())) {
            return false;
        }
        if (!Set.of("SENT", "CONFIRMING").contains(record.getStatus())) {
            throw new IllegalStateException("withdrawal " + orderNo + " is not confirmable from status "
                    + record.getStatus());
        }
        if (record.getTxHash() == null || !record.getTxHash().equals(txHash)) {
            throw new IllegalStateException("withdrawal " + orderNo + " tx hash mismatch");
        }
        if (!settleLockedDebit(tenantId, chain, assetSymbol, accountId, amount)) {
            throw new IllegalStateException("unable to settle locked " + assetSymbol + " balance");
        }
        if (markWithdrawalConfirmed(tenantId, chain, orderNo, txHash) != 1) {
            throw new IllegalStateException("unable to mark withdrawal " + orderNo + " confirmed");
        }
        return true;
    }

    /**
     * 处理 {@code confirmWithdrawalAndSettle} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public boolean confirmWithdrawalAndSettle(String chain, String orderNo, String txHash,
                                              String assetSymbol, String accountId, BigDecimal amount) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("withdrawal tx hash must not be blank");
        }
        Optional<WithdrawalOrderRecord> order = findWithdrawalOrder(chain, orderNo);
        if (order.isEmpty()) {
            throw new IllegalStateException("missing withdrawal order " + chain + ":" + orderNo);
        }
        WithdrawalOrderRecord record = order.get();
        if ("CONFIRMED".equals(record.getStatus())) {
            return false;
        }
        if (!Set.of("SENT", "CONFIRMING").contains(record.getStatus())) {
            throw new IllegalStateException("withdrawal " + orderNo + " is not confirmable from status "
                    + record.getStatus());
        }
        if (record.getTxHash() == null || !record.getTxHash().equals(txHash)) {
            throw new IllegalStateException("withdrawal " + orderNo + " tx hash mismatch");
        }
        if (!settleLockedDebit(chain, assetSymbol, accountId, amount)) {
            throw new IllegalStateException("unable to settle locked " + assetSymbol + " balance");
        }
        int confirmed = markWithdrawalConfirmed(chain, orderNo, txHash);
        if (confirmed != 1) {
            throw new IllegalStateException("unable to mark withdrawal " + orderNo + " confirmed");
        }
        return true;
    }
    /**
     * 获取或查询 {@code findWithdrawalStatus} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findWithdrawalStatus(String chain, String orderNo) {
        return withdrawalOrderRepository.findStatus(chain, orderNo);
    }
    /**
     * 获取或查询 {@code findWithdrawalTxHash} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findWithdrawalTxHash(String chain, String orderNo) {
        return withdrawalOrderRepository.findTxHash(chain, orderNo, null);
    }
    /**
     * 获取或查询 {@code findWithdrawalTxHash} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findWithdrawalTxHash(UUID tenantId, String chain, String orderNo) {
        return withdrawalOrderRepository.findTxHash(chain, orderNo, tenantId);
    }
    /**
     * 校验 {@code requireWithdrawalTenant} 对应的前置条件，不满足时抛出明确异常。
     */
    public UUID requireWithdrawalTenant(String chain, String orderNo) {
        Optional<UUID> tenant = withdrawalOrderRepository.findTenant(chain, orderNo);
        if (tenant.isEmpty()) {
            throw new IllegalStateException(
                    "withdrawal order must belong to exactly one tenant: " + chain + ":" + orderNo);
        }
        return tenant.get();
    }
    /**
     * 获取或查询 {@code findWithdrawalOrder} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawalOrderRecord> findWithdrawalOrder(String chain, String orderNo) {
        return withdrawalOrderRepository.find(chain, orderNo, null);
    }
    /**
     * 获取或查询 {@code findWithdrawalOrder} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawalOrderRecord> findWithdrawalOrder(UUID tenantId, String chain, String orderNo) {
        return withdrawalOrderRepository.find(chain, orderNo, tenantId);
    }
    /**
     * 执行 {@code mapWithdrawalOrder} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private WithdrawalOrderRecord mapWithdrawalOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return WithdrawalOrderRecord.builder()
                .id(rs.getLong("id"))
                .tenantId(rs.getObject("tenant_id", UUID.class))
                .orderNo(rs.getString("order_no"))
                .userId(rs.getLong("user_id"))
                .chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol"))
                .fromAddress(rs.getString("from_address"))
                .debitAccountId(rs.getString("debit_account_id"))
                .toAddress(rs.getString("to_address"))
                .amount(rs.getBigDecimal("amount"))
                .fee(rs.getBigDecimal("fee"))
                .txHash(rs.getString("tx_hash"))
                .status(rs.getString("status"))
                .errorMessage(rs.getString("error_message"))
                .createdAt(toInstant(rs.getTimestamp("created_at")))
                .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                .build();
    }

    /**
     * 构建或生成 {@code createCollectionRecord} 对应的结果，并执行输入和状态校验。
     */
    public int createCollectionRecord(UUID tenantId, UUID custodyAddressId,
                                      String collectionNo, String chain, String assetSymbol,
                                      String fromAddress, String toAddress, BigDecimal amount, BigDecimal fee,
                                      String rawPayload) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(custodyAddressId, "custodyAddressId is required");
        return collectionRecordRepository.create(tenantId, custodyAddressId, collectionNo, chain, assetSymbol,
                fromAddress, toAddress, amount, fee, rawPayload);
    }
    /**
     * 获取或查询 {@code listCollectionsForSigning} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainCollectionRecord> listCollectionsForSigning(String chain, int limit) {
        return collectionRecordRepository.listForSigning(chain, limit);
    }
    /**
     * 获取或查询 {@code listCollectionsByStatus} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainCollectionRecord> listCollectionsByStatus(String chain, String status, int limit) {
        return collectionRecordRepository.listByStatus(chain, status, limit);
    }

    /**
     * 设置或更新 {@code updateCollectionStatus} 对应的状态，并保持相关业务字段一致。
     */
    public int updateCollectionStatus(UUID tenantId, String chain, String collectionNo,
                                      String status, String txHash, String errorMessage,
                                      String rawPayload) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return collectionRecordRepository.updateStatus(tenantId, chain, collectionNo, status,
                txHash, errorMessage, rawPayload);
    }

        /**
     * 执行 {@code claimCollectionSigning} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int claimCollectionSigning(UUID tenantId, String chain,
                                      String collectionNo, String rawPayload) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return collectionRecordRepository.claimSigning(tenantId, chain, collectionNo, rawPayload);
    }

    /**
     * 写入或更新 {@code markCollectionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markCollectionConfirmed(UUID tenantId, String chain,
                                       String collectionNo, String txHash) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return collectionRecordRepository.markConfirmed(tenantId, chain, collectionNo, txHash);
    }
    /**
     * 获取或查询 {@code findCollectionStatus} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findCollectionStatus(UUID tenantId, String chain, String collectionNo) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return collectionRecordRepository.findStatus(tenantId, chain, collectionNo);
    }
    /**
     * 获取或查询 {@code findCollectionTxHash} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findCollectionTxHash(UUID tenantId, String chain, String collectionNo) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return collectionRecordRepository.findTxHash(tenantId, chain, collectionNo);
    }
    /**
     * 执行 {@code mapCollectionRecord} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ChainCollectionRecord mapCollectionRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ChainCollectionRecord.builder()
                .id(rs.getLong("id"))
                .tenantId(rs.getObject("tenant_id", UUID.class))
                .custodyAddressId(rs.getObject("custody_address_id", UUID.class))
                .collectionNo(rs.getString("collection_no"))
                .chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol"))
                .fromAddress(rs.getString("from_address"))
                .toAddress(rs.getString("to_address"))
                .amount(rs.getBigDecimal("amount"))
                .fee(rs.getBigDecimal("fee"))
                .txHash(rs.getString("tx_hash"))
                .status(rs.getString("status"))
                .errorMessage(rs.getString("error_message"))
                .rawPayload(rs.getString("raw_payload"))
                .createdAt(toInstant(rs.getTimestamp("created_at")))
                .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                .build();
    }

    /**
     * 构建或生成 {@code createBitcoinLikeSigningTransaction} 对应的结果，并执行输入和状态校验。
     */
    public WithdrawTransaction createBitcoinLikeSigningTransaction(
            AssetRuntimeMetadata currency,
            String businessType,
            String businessNo,
            WithdrawTransaction transaction) {
        WithdrawTransaction persisted = createBitcoinLikeSigningTransaction(
                currency.getName().toUpperCase(java.util.Locale.ROOT),
                currency.getName().toUpperCase(java.util.Locale.ROOT),
                businessType,
                businessNo,
                transaction);
        currency.applyTo(persisted);
        return persisted;
    }

    /**
     * 构建或生成 {@code createBitcoinLikeSigningTransaction} 对应的结果，并执行输入和状态校验。
     */
    public WithdrawTransaction createBitcoinLikeSigningTransaction(
            String chain,
            String assetSymbol,
            String businessType,
            String businessNo,
            WithdrawTransaction transaction) {
        return chainSigningTransactionRepository.create(chain, assetSymbol, businessType, businessNo, transaction,
                        Constants.WAITING, Constants.SIGNING)
                .orElseThrow(() -> new IllegalStateException(
                        "failed to create " + chain + " signing transaction " + businessType + "/" + businessNo));
    }

    /**
     * 获取或查询 {@code findBitcoinLikeSigningTransaction} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransaction(
            AssetRuntimeMetadata currency, String businessType, String businessNo) {
        return findBitcoinLikeSigningTransaction(
                currency.getName().toUpperCase(java.util.Locale.ROOT), businessType, businessNo);
    }

    /**
     * 获取或查询 {@code findBitcoinLikeSigningTransaction} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransaction(
            String chain, String businessType, String businessNo) {
        return chainSigningTransactionRepository.findByBusiness(chain, businessType, businessNo);
    }

    /**
     * 获取或查询 {@code findBitcoinLikeSigningTransactionById} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransactionById(
            AssetRuntimeMetadata currency, int transactionId) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.findById(chain, transactionId);
    }

    /**
     * 获取或查询 {@code findBitcoinLikeSigningTransactionByTxId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransactionByTxId(
            AssetRuntimeMetadata currency, String txId) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.findByTxId(chain, txId);
    }
    /**
     * 执行 {@code bitcoinLikeSigningTransactionExists} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean bitcoinLikeSigningTransactionExists(AssetRuntimeMetadata currency, String txId) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.exists(chain, txId);
    }
    /**
     * 设置或更新 {@code updateBitcoinLikeSigningTransaction} 对应的状态，并保持相关业务字段一致。
     */
    public int updateBitcoinLikeSigningTransaction(AssetRuntimeMetadata currency, WithdrawTransaction transaction) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.updateStatus(chain, transaction.getId(), transaction);
    }
    /**
     * 写入或更新 {@code markBitcoinLikeSigningError} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markBitcoinLikeSigningError(AssetRuntimeMetadata currency, int transactionId, String errorMessage) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.markError(chain, transactionId, errorMessage);
    }
    /**
     * 获取或查询 {@code findSentBitcoinLikeSigningTransactions} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawTransaction> findSentBitcoinLikeSigningTransactions(AssetRuntimeMetadata currency) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.listSent(chain, Constants.SENT);
    }
    /**
     * 获取或查询 {@code findLedgerBalance} 对应的数据，供调用方读取当前状态。
     */
    public Optional<LedgerBalanceRecord> findLedgerBalance(String chain, String assetSymbol, String accountId) {
        return ledgerBalanceRepository.find(chain, assetSymbol, accountId);
    }
    /**
     * 获取或查询 {@code listLedgerBalances} 对应的数据，供调用方读取当前状态。
     */
    public List<LedgerBalanceRecord> listLedgerBalances() {
        return ledgerBalanceRepository.listAll();
    }
    /**
     * 执行 {@code sumLedgerTotalBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public BigDecimal sumLedgerTotalBalance(String chain, String assetSymbol) {
        return ledgerBalanceRepository.sumTotal(chain, assetSymbol);
    }
    /**
     * 执行 {@code sumLedgerAvailableBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public BigDecimal sumLedgerAvailableBalance(String chain, String assetSymbol) {
        return ledgerBalanceRepository.sumAvailable(chain, assetSymbol);
    }

    /**
     * 获取或查询 {@code findStaleBitcoinLikeSigningTransactions} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawTransaction> findStaleBitcoinLikeSigningTransactions(
            AssetRuntimeMetadata currency, long staleSeconds) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.listStale(chain, Constants.SIGNING, staleSeconds);
    }

    /**
     * 执行 {@code claimBitcoinLikeSigningRecovery} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean claimBitcoinLikeSigningRecovery(
            AssetRuntimeMetadata currency, int transactionId, long staleSeconds) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return chainSigningTransactionRepository.claimRecovery(chain, transactionId, Constants.SIGNING, staleSeconds);
    }
    /**
     * 执行 {@code mapSigningTransaction} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private WithdrawTransaction mapSigningTransaction(java.sql.ResultSet rs) throws java.sql.SQLException {
        return WithdrawTransaction.builder()
                .id(rs.getInt("id"))
                .txId(rs.getString("tx_id"))
                .balance(rs.getBigDecimal("balance"))
                .signature(rs.getString("signature"))
                .currency(rs.getInt("currency"))
                .status(rs.getShort("status"))
                .createDate(rs.getTimestamp("create_date"))
                .updateDate(rs.getTimestamp("update_date"))
                .build();
    }
    /**
     * 记录或保存 {@code recordEvmTokenTransfer} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordEvmTokenTransfer(DepositEvent event, long logIndex, String status) {
        return evmTokenTransferRepository.upsert(event, logIndex, status);
    }
    /**
     * 记录或保存 {@code recordTronTokenTransfer} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordTronTokenTransfer(DepositEvent event, long logIndex, String status) {
        return tronTokenTransferRepository.upsert(event, logIndex, status);
    }

    /**
     * 执行 {@code incrementLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public void incrementLedgerBalance(UUID tenantId, String chain, String assetSymbol,
                                       String accountId, BigDecimal amount) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        int updated = ledgerBalanceRepository.increment(tenantId, chain, assetSymbol, accountId, amount);
        if (updated != 1) {
            throw new IllegalStateException("ledger balance belongs to another tenant");
        }
    }
    /**
     * 执行 {@code debitLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean debitLedgerBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.debit(chain, assetSymbol, accountId, amount, null);
    }

    /**
     * 执行 {@code debitLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean debitLedgerBalance(UUID tenantId, String chain, String assetSymbol,
                                      String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.debit(chain, assetSymbol, accountId, amount, tenantId);
    }

        /**
     * 执行 {@code freezeLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean freezeLedgerBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.freeze(chain, assetSymbol, accountId, amount, null);
    }

    /**
     * 执行 {@code freezeLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean freezeLedgerBalance(UUID tenantId, String chain, String assetSymbol,
                                       String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.freeze(chain, assetSymbol, accountId, amount, tenantId);
    }

        /**
     * 删除或释放 {@code releaseLockedBalance} 对应的资源，并收敛相关业务状态。
     */
    public boolean releaseLockedBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.release(chain, assetSymbol, accountId, amount, null);
    }

    /**
     * 删除或释放 {@code releaseLockedBalance} 对应的资源，并收敛相关业务状态。
     */
    public boolean releaseLockedBalance(UUID tenantId, String chain, String assetSymbol,
                                        String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.release(chain, assetSymbol, accountId, amount, tenantId);
    }

        /**
     * 设置或更新 {@code settleLockedDebit} 对应的状态，并保持相关业务字段一致。
     */
    public boolean settleLockedDebit(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.settle(chain, assetSymbol, accountId, amount, null);
    }

    /**
     * 设置或更新 {@code settleLockedDebit} 对应的状态，并保持相关业务字段一致。
     */
    public boolean settleLockedDebit(UUID tenantId, String chain, String assetSymbol,
                                     String accountId, BigDecimal amount) {
        return ledgerBalanceRepository.settle(chain, assetSymbol, accountId, amount, tenantId);
    }

    /**
     * 获取或查询 {@code listCollectableLedgerBalances} 对应的数据，供调用方读取当前状态。
     */
    public List<CollectionCandidateRecord> listCollectableLedgerBalances(String chain,
                                                                         BigDecimal minimumAmount,
                                                                         int limit) {
        record BalanceKey(UUID tenantId, String assetSymbol, String address) { }
        Map<BalanceKey, BigDecimal> deposited = new java.util.HashMap<>();
        for (Map<String, Object> row : depositRecordRepository.listCreditedForCollectionBalance(chain)) {
            BalanceKey key = new BalanceKey((UUID) row.get("tenant_id"),
                    String.valueOf(row.get("asset_symbol")), String.valueOf(row.get("to_address")));
            deposited.merge(key, (BigDecimal) row.get("amount"), BigDecimal::add);
        }
        Map<BalanceKey, BigDecimal> collected = new java.util.HashMap<>();
        Set<BalanceKey> pending = new java.util.HashSet<>();
        for (Map<String, Object> row : collectionRecordRepository.listForCollectionBalance(chain)) {
            BalanceKey key = new BalanceKey((UUID) row.get("tenant_id"),
                    String.valueOf(row.get("asset_symbol")), String.valueOf(row.get("from_address")));
            String status = String.valueOf(row.get("status"));
            if (!"FAILED".equals(status)) {
                collected.merge(key, (BigDecimal) row.get("amount"), BigDecimal::add);
            }
            if (Set.of("CREATED", "RETRYING", "SIGNING", "SENT").contains(status)) {
                pending.add(key);
            }
        }
        Map<String, Map<String, Object>> activeAssets = new java.util.HashMap<>();
        for (Map<String, Object> row : chainAssetRepository.listActiveByChain(chain)) {
            activeAssets.put(String.valueOf(row.get("symbol")).toLowerCase(java.util.Locale.ROOT), row);
        }
        Map<Long, UUID> custodyByChainAddress = new java.util.HashMap<>();
        for (Map<String, Object> row : custodyAddressRepository.listActiveByChain(chain)) {
            custodyByChainAddress.put(((Number) row.get("chain_address_id")).longValue(),
                    (UUID) row.get("id"));
        }
        List<CollectionCandidateRecord> result = new java.util.ArrayList<>();
        for (Map<String, Object> address : chainAddressRepository.listEnabledByChain(chain)) {
            UUID tenantId = (UUID) address.get("tenant_id");
            String walletRole = String.valueOf(address.get("wallet_role"));
            long userId = ((Number) address.get("user_id")).longValue();
            if (tenantId == null || !"DEPOSIT".equals(walletRole)
                    || userId == HotWalletRules.DEFAULT_HOT_USER_ID) {
                continue;
            }
            Map<String, Object> nativeAsset = activeAssets.get(
                    String.valueOf(address.get("asset_symbol")).toLowerCase(java.util.Locale.ROOT));
            if (nativeAsset == null || !Boolean.TRUE.equals(nativeAsset.get("native_asset"))) {
                continue;
            }
            Long custodyAddressId = ((Number) address.get("id")).longValue();
            UUID custodyId = custodyByChainAddress.get(custodyAddressId);
            if (custodyId == null) {
                continue;
            }
            String sourceAddress = String.valueOf(address.get("address"))
                    .toLowerCase(java.util.Locale.ROOT);
            for (Map.Entry<BalanceKey, BigDecimal> entry : deposited.entrySet()) {
                BalanceKey key = entry.getKey();
                if (!tenantId.equals(key.tenantId()) || !sourceAddress.equals(key.address())
                        || pending.contains(new BalanceKey(tenantId, key.assetSymbol(), sourceAddress))) {
                    continue;
                }
                Map<String, Object> asset = activeAssets.get(key.assetSymbol().toLowerCase(java.util.Locale.ROOT));
                if (asset == null) {
                    continue;
                }
                BigDecimal amount = entry.getValue().subtract(collected.getOrDefault(key, BigDecimal.ZERO))
                        .max(BigDecimal.ZERO);
                BigDecimal minTransfer = (BigDecimal) asset.get("min_transfer");
                BigDecimal required = (minTransfer == null ? BigDecimal.ZERO : minTransfer)
                        .max(minimumAmount == null ? BigDecimal.ZERO : minimumAmount);
                if (amount.signum() <= 0 || amount.compareTo(required) < 0) {
                    continue;
                }
                result.add(CollectionCandidateRecord.builder().tenantId(tenantId).custodyAddressId(custodyId)
                        .chain(chain).assetSymbol(key.assetSymbol()).accountId((String) address.get("account_id"))
                        .address((String) address.get("address")).ownerAddress((String) address.get("owner_address"))
                        .userId(userId).biz(((Number) address.get("biz")).intValue())
                        .addressIndex(((Number) address.get("address_index")).longValue())
                        .walletRole(walletRole).amount(amount).build());
            }
        }
        return result.stream().sorted(java.util.Comparator.comparing(CollectionCandidateRecord::getAmount).reversed()
                        .thenComparing(CollectionCandidateRecord::getAddressIndex)).limit(limit).toList();
    }
    /**
     * 获取或查询 {@code findActiveTenantCollectionAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findActiveTenantCollectionAddress(UUID tenantId, String chain) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return custodyGasAccountRepository.listActiveByTenantAndChain(tenantId, chain).stream()
                .map(row -> (UUID) row.get("custody_address_id"))
                .map(id -> custodyAddressRepository.findByTenantAndId(tenantId, id))
                .flatMap(Optional::stream)
                .filter(row -> "ACTIVE".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .map(row -> String.valueOf(row.get("address")))
                .findFirst();
    }
    /**
     * 判断 {@code isEvm7702CollectionActive} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702CollectionActive(String chain, String network) {
        return evm7702ConfigRepository.existsActive(chain, network);
    }
    /**
     * 判断 {@code isEvm7702Managed} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702Managed(String chain, String network) {
        return evm7702ConfigRepository.existsManaged(chain, network);
    }

    /** 判断指定链的批量提现是否由 EIP-7702 接管。 */
    public boolean isEvm7702BatchWithdrawalManaged(String chain, String network) {
        return evm7702ConfigRepository.existsManagedBatchWithdrawal(chain, network);
    }
    /**
     * 判断 {@code isEvm7702NativeCollectionActive} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702NativeCollectionActive(String chain, String network) {
        return evm7702ConfigRepository.existsActiveNativeCollection(chain, network);
    }
    /**
     * 判断 {@code isEvm7702BatchWithdrawalActive} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702BatchWithdrawalActive(String chain, String network) {
        return evm7702ConfigRepository.existsActiveBatchWithdrawal(chain, network);
    }
    /**
     * 设置或更新 {@code updateScanHeight} 对应的状态，并保持相关业务字段一致。
     */
    public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        chainScanHeightRepository.upsert(chain, scannerName, bestHeight, safeHeight);
    }
    /**
     * 获取或查询 {@code findScanSafeHeight} 对应的数据，供调用方读取当前状态。
     */
    public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
        return chainScanHeightRepository.findSafeHeight(chain, scannerName);
    }
    /**
     * 获取或查询 {@code listCanonicalDepositBlockHeights} 对应的数据，供调用方读取当前状态。
     */
    public List<Long> listCanonicalDepositBlockHeights(String chain, long minimumHeight) {
        return depositRecordRepository.listCanonicalBlockHeights(chain, minimumHeight);
    }
    /**
     * 获取或查询 {@code listActiveScanHeights} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainScanHeightRecord> listActiveScanHeights() {
        return chainScanHeightRepository.listActive();
    }
    /**
     * 获取或查询 {@code findToken} 对应的数据，供调用方读取当前状态。
     */
    public Optional<TokenDefinition> findToken(String chain, String symbol) {
        return tokenConfigRepository.listAll().stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> symbol.equalsIgnoreCase(String.valueOf(row.get("symbol"))))
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .map(ChainJdbcRepository::mapTokenDefinition)
                .findFirst();
    }
    /**
     * 获取或查询 {@code findTokenByContract} 对应的数据，供调用方读取当前状态。
     */
    public Optional<TokenDefinition> findTokenByContract(String chain, String contractAddress) {
        return tokenConfigRepository.listAll().stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .filter(row -> contractAddress.equalsIgnoreCase(String.valueOf(row.get("contract_address")))
                        || contractAddress.equalsIgnoreCase(String.valueOf(row.get("contract_address_base58")))
                        || contractAddress.equalsIgnoreCase(String.valueOf(row.get("contract_address_hex"))))
                .map(ChainJdbcRepository::mapTokenDefinition)
                .findFirst();
    }
    /**
     * 获取或查询 {@code listTokens} 对应的数据，供调用方读取当前状态。
     */
    public List<TokenDefinition> listTokens(String chain) {
        return tokenConfigRepository.listAll().stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .map(ChainJdbcRepository::mapTokenDefinition)
                .sorted(java.util.Comparator.comparing(TokenDefinition::getSymbol))
                .toList();
    }
    /**
     * 获取或查询 {@code findAsset} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAsset> findAsset(String chain, String symbol) {
        return chainAssetRepository.listActive().stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> symbol.equalsIgnoreCase(String.valueOf(row.get("symbol"))))
                .map(ChainJdbcRepository::mapChainAsset)
                .findFirst();
    }

    /**
     * 执行 {@code countActiveNativeAssets} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int countActiveNativeAssets(String chain) {
        return (int) chainAssetRepository.listActive().stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> Boolean.TRUE.equals(row.get("native_asset")))
                .count();
    }
    /**
     * 获取或查询 {@code listEnabledChainProfiles} 对应的数据，供调用方读取当前状态。
     */
    public List<AccountChainProfile> listEnabledChainProfiles() {
        return chainProfileRepository.listAll().stream()
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .map(ChainJdbcRepository::mapAccountProfile)
                .toList();
    }
    /**
     * 获取或查询 {@code listAllChainProfiles} 对应的数据，供调用方读取当前状态。
     */
    public List<AccountChainProfile> listAllChainProfiles() {
        return chainProfileRepository.listAll().stream()
                .map(ChainJdbcRepository::mapAccountProfile)
                .toList();
    }
    /**
     * 执行 {@code systemBoolean} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean systemBoolean(String configKey, boolean defaultValue) {
        List<Map<String, Object>> rows = walletSystemConfigRepository.listAll().stream()
                .filter(row -> configKey.equals(row.get("config_key")))
                .toList();
        if (rows.isEmpty()) {
            return defaultValue;
        }
        Map<String, Object> row = rows.get(0);
        Object enabled = row.get("enabled");
        if (enabled instanceof Boolean bool && !bool) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(row.get("config_value")));
    }
    /**
     * 执行 {@code systemValue} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Optional<String> systemValue(String configKey) {
        return walletSystemConfigRepository.listAll().stream()
                .filter(row -> configKey.equals(row.get("config_key")))
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .map(row -> String.valueOf(row.get("config_value")))
                .findFirst();
    }
    /**
     * 获取或查询 {@code listEnabledRpcNodes} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network, String environment, String purpose) {
        String env = environment == null ? "" : environment;
        String nodePurpose = purpose == null ? "rpc" : purpose;
        return chainRpcNodeRepository.listByChain(chain, network).stream()
                .filter(row -> bool(row.get("enabled")))
                .filter(row -> env.equalsIgnoreCase(String.valueOf(row.get("environment"))))
                .filter(row -> nodePurpose.equalsIgnoreCase(String.valueOf(row.get("purpose")))
                        || "all".equalsIgnoreCase(String.valueOf(row.get("purpose"))))
                .sorted(java.util.Comparator.comparing(row -> ((Number) row.get("priority")).intValue()))
                .map(ChainJdbcRepository::mapRpcNode)
                .toList();
    }
    /**
     * 获取或查询 {@code listEnabledRpcNodes} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network, String environment) {
        return listEnabledRpcNodes(chain, network, environment, "rpc");
    }
    /**
     * 获取或查询 {@code listAllEnabledRpcNodes} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainRpcNode> listAllEnabledRpcNodes(String chain, String network, String environment) {
        String env = environment == null ? "" : environment;
        return chainRpcNodeRepository.listByChain(chain, network).stream()
                .filter(row -> bool(row.get("enabled")))
                .filter(row -> env.equalsIgnoreCase(String.valueOf(row.get("environment"))))
                .sorted(java.util.Comparator.<Map<String, Object>, String>comparing(
                                row -> String.valueOf(row.get("purpose")))
                        .thenComparing(row -> ((Number) row.get("priority")).intValue()))
                .map(ChainJdbcRepository::mapRpcNode)
                .toList();
    }
    /**
     * 编码 {@code toTs} 对应的数据，生成链上或接口所需的表示。
     */
    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
    /**
     * 执行 {@code nowOr} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static Instant nowOr(Instant instant) {
        return instant == null ? now() : instant;
    }
    /**
     * 执行 {@code now} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static Instant now() {
        return Instant.now();
    }
    /**
     * 编码 {@code toInstant} 对应的数据，生成链上或接口所需的表示。
     */
    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
    /** 将 token_config 单表字段转换为代币模型。 */
    private static TokenDefinition mapTokenDefinition(Map<String, Object> row) {
        Object contract = row.get("contract_address");
        if (contract == null) contract = row.get("contract_address_base58");
        if (contract == null) contract = row.get("contract_address_hex");
        return TokenDefinition.builder()
                .id(((Number) row.get("id")).longValue())
                .chain(String.valueOf(row.get("chain")))
                .symbol(String.valueOf(row.get("symbol")))
                .contractAddress(contract == null ? null : contract.toString())
                .decimals(((Number) row.get("decimals")).intValue())
                .standard(String.valueOf(row.get("standard")))
                .nativeAsset(false)
                .active(Boolean.TRUE.equals(row.get("enabled")))
                .build();
    }

    /** 将 chain_asset 单表字段转换为资产模型。 */
    private static ChainAsset mapChainAsset(Map<String, Object> row) {
        return ChainAsset.builder()
                .id(((Number) row.get("id")).longValue())
                .chain(String.valueOf(row.get("chain")))
                .symbol(String.valueOf(row.get("symbol")))
                .assetKind(String.valueOf(row.get("asset_kind")))
                .contractAddress((String) row.get("contract_address"))
                .decimals(((Number) row.get("decimals")).intValue())
                .nativeAsset(Boolean.TRUE.equals(row.get("native_asset")))
                .active(Boolean.TRUE.equals(row.get("active")))
                .minTransfer((java.math.BigDecimal) row.get("min_transfer"))
                .minWithdraw((java.math.BigDecimal) row.get("min_withdraw"))
                .createdAt(mapInstant(row.get("created_at")))
                .updatedAt(mapInstant(row.get("updated_at")))
                .build();
    }

    /** 将 chain_profile 单表字段转换为账户链配置模型。 */
    private static AccountChainProfile mapAccountProfile(Map<String, Object> row) {
        return AccountChainProfile.builder()
                .chain(String.valueOf(row.get("chain")))
                .network(String.valueOf(row.get("network")))
                .family(String.valueOf(row.get("family")))
                .runtimeCurrencyId(((Number) row.get("runtime_currency_id")).intValue())
                .bip44CoinType(((Number) row.get("bip44_coin_type")).intValue())
                .nativeSymbol(String.valueOf(row.get("native_symbol")))
                .rpcUrl((String) row.get("rpc_url"))
                .explorerUrl((String) row.get("explorer_url"))
                .depositConfirmations(((Number) row.get("deposit_confirmations")).intValue())
                .withdrawConfirmations(((Number) row.get("withdraw_confirmations")).intValue())
                .defaultFee((Long) row.get("default_fee_rate"))
                .dustThreshold((Long) row.get("dust_threshold"))
                .enabled(Boolean.TRUE.equals(row.get("enabled")))
                .chainId((Long) row.get("chain_id"))
                .gasPolicy((String) row.get("gas_policy"))
                .accountClassHash((String) row.get("account_class_hash"))
                .feeModel((String) row.get("fee_model"))
                .scanBatchSize((Integer) row.get("scan_batch_size"))
                .scanEnabled(Boolean.TRUE.equals(row.get("scan_enabled")))
                .withdrawEnabled(Boolean.TRUE.equals(row.get("withdraw_enabled")))
                .collectionEnabled(Boolean.TRUE.equals(row.get("collection_enabled")))
                .transferEnabled(Boolean.TRUE.equals(row.get("transfer_enabled")))
                .scanStartHeight((Long) row.get("scan_start_height"))
                .scanMaxBlocksPerRun((Long) row.get("scan_max_blocks_per_run"))
                .build();
    }

    /** 将 chain_rpc_node 单表字段转换为 RPC 节点模型。 */
    private static ChainRpcNode mapRpcNode(Map<String, Object> row) {
        return ChainRpcNode.builder()
                .id(((Number) row.get("id")).longValue())
                .chain(String.valueOf(row.get("chain")))
                .network(String.valueOf(row.get("network")))
                .environment((String) row.get("environment"))
                .nodeLabel((String) row.get("node_label"))
                .purpose((String) row.get("purpose"))
                .connectionType((String) row.get("connection_type"))
                .rpcUrl((String) row.get("rpc_url"))
                .authType((String) row.get("auth_type"))
                .authHeaderName((String) row.get("auth_header_name"))
                .apiKey((String) row.get("api_key"))
                .apiKeyRef((String) row.get("api_key_ref"))
                .username((String) row.get("username"))
                .usernameRef((String) row.get("username_ref"))
                .password((String) row.get("password"))
                .passwordRef((String) row.get("password_ref"))
                .priority(((Number) row.get("priority")).intValue())
                .minRequestIntervalMs((Integer) row.get("min_request_interval_ms"))
                .enabled(Boolean.TRUE.equals(row.get("enabled")))
                .renewalDueAt(mapInstant(row.get("renewal_due_at")))
                .remark((String) row.get("remark"))
                .build();
    }

    /** 读取 Map 中的时间字段。 */
    private static Instant mapInstant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant()
                : value instanceof Instant instant ? instant : null;
    }

    /** 读取 Map 中的布尔字段。 */
    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
    /**
     * 执行 {@code mapAccountProfile} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static AccountChainProfile mapAccountProfile(ResultSet rs) throws SQLException {
        return AccountChainProfile.builder()
                .chain(rs.getString("chain"))
                .network(rs.getString("network"))
                .family(rs.getString("family"))
                .runtimeCurrencyId(rs.getInt("runtime_currency_id"))
                .bip44CoinType(rs.getInt("bip44_coin_type"))
                .nativeSymbol(rs.getString("native_symbol"))
                .rpcUrl(rs.getString("rpc_url"))
                .explorerUrl(rs.getString("explorer_url"))
                .depositConfirmations(rs.getInt("deposit_confirmations"))
                .withdrawConfirmations(rs.getInt("withdraw_confirmations"))
                .defaultFee(rs.getObject("default_fee_rate", Long.class))
                .dustThreshold(rs.getObject("dust_threshold", Long.class))
                .enabled(rs.getBoolean("enabled"))
                .chainId(rs.getObject("chain_id", Long.class))
                .gasPolicy(rs.getString("gas_policy"))
                .accountClassHash(rs.getString("account_class_hash"))
                .feeModel(rs.getString("fee_model"))
                .scanBatchSize(rs.getObject("scan_batch_size", Integer.class))
                .scanEnabled(rs.getBoolean("scan_enabled"))
                .withdrawEnabled(rs.getBoolean("withdraw_enabled"))
                .collectionEnabled(rs.getBoolean("collection_enabled"))
                .transferEnabled(rs.getBoolean("transfer_enabled"))
                .scanStartHeight(rs.getObject("scan_start_height", Long.class))
                .scanMaxBlocksPerRun(rs.getObject("scan_max_blocks_per_run", Long.class))
                .build();
    }
    /**
     * 执行 {@code mapBitcoinLikeProfile} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static BitcoinLikeChainProfile mapBitcoinLikeProfile(ResultSet rs) throws SQLException {
        return BitcoinLikeChainProfile.builder()
                .chain(rs.getString("chain"))
                .network(rs.getString("network"))
                .family(rs.getString("family"))
                .runtimeCurrencyId(rs.getInt("runtime_currency_id"))
                .bip44CoinType(rs.getInt("bip44_coin_type"))
                .nativeSymbol(rs.getString("native_symbol"))
                .rpcUrl(rs.getString("rpc_url"))
                .explorerUrl(rs.getString("explorer_url"))
                .depositConfirmations(rs.getInt("deposit_confirmations"))
                .withdrawConfirmations(rs.getInt("withdraw_confirmations"))
                .defaultFeeRate(rs.getObject("default_fee_rate", Long.class))
                .dustThreshold(rs.getObject("dust_threshold", Long.class))
                .enabled(rs.getBoolean("enabled"))
                .chainId(rs.getObject("chain_id", Long.class))
                .gasPolicy(rs.getString("gas_policy"))
                .scanBatchSize(rs.getObject("scan_batch_size", Integer.class))
                .scanEnabled(rs.getBoolean("scan_enabled"))
                .withdrawEnabled(rs.getBoolean("withdraw_enabled"))
                .collectionEnabled(rs.getBoolean("collection_enabled"))
                .transferEnabled(rs.getBoolean("transfer_enabled"))
                .scanStartHeight(rs.getObject("scan_start_height", Long.class))
                .scanMaxBlocksPerRun(rs.getObject("scan_max_blocks_per_run", Long.class))
                .build();
    }
    /**
     * 执行 {@code mapRpcNode} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static ChainRpcNode mapRpcNode(ResultSet rs) throws SQLException {
        return ChainRpcNode.builder()
                .id(rs.getLong("id"))
                .chain(rs.getString("chain"))
                .network(rs.getString("network"))
                .environment(rs.getString("environment"))
                .nodeLabel(rs.getString("node_label"))
                .purpose(rs.getString("purpose"))
                .connectionType(rs.getString("connection_type"))
                .rpcUrl(rs.getString("rpc_url"))
                .authType(rs.getString("auth_type"))
                .authHeaderName(rs.getString("auth_header_name"))
                .apiKey(rs.getString("api_key"))
                .apiKeyRef(rs.getString("api_key_ref"))
                .username(rs.getString("username"))
                .usernameRef(rs.getString("username_ref"))
                .password(rs.getString("password"))
                .passwordRef(rs.getString("password_ref"))
                .priority(rs.getInt("priority"))
                .minRequestIntervalMs(rs.getObject("min_request_interval_ms", Integer.class))
                .enabled(rs.getBoolean("enabled"))
                .renewalDueAt(toInstant(rs.getTimestamp("renewal_due_at")))
                .remark(rs.getString("remark"))
                .build();
    }
    /**
     * 执行 {@code mapChainAddress} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static ChainAddressRecord mapChainAddress(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ChainAddressRecord.builder()
                .id(rs.getLong("id"))
                .tenantId(rs.getObject("tenant_id", UUID.class))
                .chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol"))
                .accountId(rs.getString("account_id"))
                .userId(rs.getLong("user_id"))
                .biz(rs.getInt("biz"))
                .addressIndex(rs.getLong("address_index"))
                .address(rs.getString("address"))
                .ownerAddress(rs.getString("owner_address"))
                .derivationPath(rs.getString("derivation_path"))
                .walletRole(rs.getString("wallet_role"))
                .enabled(rs.getBoolean("enabled"))
                .build();
    }
}
