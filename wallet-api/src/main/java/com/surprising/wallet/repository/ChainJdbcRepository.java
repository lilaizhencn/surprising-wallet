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
import org.springframework.stereotype.Repository;
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
@Repository
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
        this.depositCreditObservers = depositCreditObservers.orderedStream().toList();
        this.depositReorgObservers = depositReorgObservers.orderedStream().toList();
    }
    /**
     * 写入或更新 {@code upsertChainAsset} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int upsertChainAsset(ChainAsset asset) {
        return jdbcTemplate.update("""
                insert into chain_asset(chain, symbol, asset_kind, contract_address, decimals, native_asset, active,
                                        min_transfer, min_withdraw, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, symbol) do update set
                    asset_kind = excluded.asset_kind,
                    contract_address = excluded.contract_address,
                    decimals = excluded.decimals,
                    native_asset = excluded.native_asset,
                    active = excluded.active,
                    min_transfer = excluded.min_transfer,
                    min_withdraw = excluded.min_withdraw,
                    updated_at = excluded.updated_at
                """,
                asset.getChain(), asset.getSymbol(), asset.getAssetKind(), asset.getContractAddress(),
                asset.getDecimals(), asset.getNativeAsset(), asset.getActive(), asset.getMinTransfer(),
                asset.getMinWithdraw(), toTs(nowOr(asset.getCreatedAt())), toTs(nowOr(asset.getUpdatedAt())));
    }
    /**
     * 获取或查询 {@code findBitcoinLikeProfile} 对应的数据，供调用方读取当前状态。
     */
    public Optional<BitcoinLikeChainProfile> findBitcoinLikeProfile(String chain, String network) {
        List<BitcoinLikeChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, fee_model, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        where chain = ? and network = ? and enabled = true
                        """,
                (rs, rowNum) -> mapBitcoinLikeProfile(rs),
                chain, network);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findAccountChainProfile} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AccountChainProfile> findAccountChainProfile(String chain, String network) {
        List<AccountChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, fee_model, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        where chain = ? and network = ? and enabled = true
                        """,
                (rs, rowNum) -> mapAccountProfile(rs),
                chain, network);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findProfileByRuntimeCurrencyId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AccountChainProfile> findProfileByRuntimeCurrencyId(int runtimeCurrencyId) {
        List<AccountChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, fee_model, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        where runtime_currency_id = ? and enabled = true
                        order by case network
                            when 'regtest' then 0
                            when 'testnet' then 1
                            when 'testnet3' then 1
                            when 'devnet' then 1
                            else 2
                        end
                        limit 1
                        """,
                (rs, rowNum) -> mapAccountProfile(rs),
                runtimeCurrencyId);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findProfileByChain} 对应的数据，供调用方读取当前状态。
     */
    public Optional<AccountChainProfile> findProfileByChain(String chain) {
        List<AccountChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, fee_model, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        where upper(chain) = upper(?) and enabled = true
                        order by case network
                            when 'regtest' then 0
                            when 'testnet' then 1
                            when 'testnet3' then 1
                            when 'devnet' then 1
                            else 2
                        end
                        limit 1
                        """,
                (rs, rowNum) -> mapAccountProfile(rs),
                chain);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findChainByRuntimeCurrencyId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findChainByRuntimeCurrencyId(int runtimeCurrencyId) {
        List<String> results = jdbcTemplate.queryForList("""
                        select distinct chain
                        from chain_profile
                        where runtime_currency_id = ? and enabled = true
                        order by chain
                        limit 1
                        """, String.class, runtimeCurrencyId);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findNetworkByRuntimeCurrencyId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findNetworkByRuntimeCurrencyId(int runtimeCurrencyId) {
        List<String> results = jdbcTemplate.queryForList("""
                        select distinct network
                        from chain_profile
                        where runtime_currency_id = ? and enabled = true
                        order by network
                        limit 1
                        """, String.class, runtimeCurrencyId);
        return results.stream().findFirst();
    }
    /**
     * 判断 {@code isRuntimeCurrencyFamily} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isRuntimeCurrencyFamily(int runtimeCurrencyId, String family) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from chain_profile
                            where runtime_currency_id = ?
                              and lower(family) = lower(?)
                              and enabled = true
                        )
                        """, Boolean.class, runtimeCurrencyId, family);
        return Boolean.TRUE.equals(exists);
    }
    /**
     * 执行 {@code reserveNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int reserveNonce(EvmNonceRecord nonceRecord) {
        return jdbcTemplate.update("""
                insert into evm_nonce(chain, address, chain_nonce, reserved_nonce, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, address) do update set
                    chain_nonce = greatest(evm_nonce.chain_nonce, excluded.chain_nonce),
                    reserved_nonce = excluded.reserved_nonce,
                    status = excluded.status,
                    updated_at = excluded.updated_at
                """,
                nonceRecord.getChain(), nonceRecord.getAddress(), nonceRecord.getChainNonce(),
                nonceRecord.getReservedNonce(), nonceRecord.getStatus(), toTs(now()), toTs(now()));
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
        jdbcTemplate.update("""
                        insert into evm_nonce(chain, address, chain_nonce, reserved_nonce, status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        on conflict (chain, address) do nothing
                        """,
                chain, address, chainNonce, chainNonce, toTs(now()), toTs(now()));
        BigDecimal nextValue = jdbcTemplate.queryForObject("""
                        select reserved_nonce from evm_nonce
                        where chain = ? and address = ?
                        for update
                        """, BigDecimal.class, chain, address);
        BigInteger next = nextValue == null ? chainNonce : nextValue.toBigIntegerExact();
        BigInteger reserved = chainNonce.max(next);
        jdbcTemplate.update("""
                        update evm_nonce
                        set chain_nonce = greatest(chain_nonce, ?),
                            reserved_nonce = ?,
                            status = 'ACTIVE',
                            updated_at = ?
                        where chain = ? and address = ?
                        """,
                chainNonce, reserved.add(BigInteger.ONE), toTs(now()), chain, address);
        return reserved;
    }
    /**
     * 记录或保存 {@code recordEvmTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordEvmTransaction(EvmTransactionRecord tx) {
        return jdbcTemplate.update("""
                insert into evm_tx(chain, tx_hash, from_address, to_address, asset_symbol, contract_address,
                                   amount, fee, nonce, block_height, confirmations, status, raw_payload,
                                   created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    fee = excluded.fee,
                    block_height = coalesce(excluded.block_height, evm_tx.block_height),
                    confirmations = excluded.confirmations,
                    status = excluded.status,
                    raw_payload = coalesce(excluded.raw_payload, evm_tx.raw_payload),
                    updated_at = excluded.updated_at
                """,
                tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getContractAddress(), tx.getAmount(), tx.getFee(), tx.getNonce(), tx.getBlockHeight(),
                tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }
    /**
     * 记录或保存 {@code recordTronTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordTronTransaction(TronTransactionRecord tx) {
        return jdbcTemplate.update("""
                insert into tron_tx(chain, tx_hash, from_address, to_address, asset_symbol, contract_address,
                                    amount, fee, block_height, confirmations, status, raw_payload,
                                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    fee = excluded.fee,
                    block_height = coalesce(excluded.block_height, tron_tx.block_height),
                    confirmations = excluded.confirmations,
                    status = excluded.status,
                    raw_payload = coalesce(excluded.raw_payload, tron_tx.raw_payload),
                    updated_at = excluded.updated_at
                """,
                tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getContractAddress(), tx.getAmount(), tx.getFee(), tx.getBlockHeight(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }
    /**
     * 记录或保存 {@code recordXrpTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordXrpTransaction(XrpTransactionRecord tx) {
        return jdbcTemplate.update("""
                        insert into xrp_transaction(
                            chain, tx_hash, from_address, to_address, asset_symbol, issuer_address, currency_code,
                            amount, fee_drops, ledger_index, sequence_number, confirmations, status, raw_payload,
                            created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_hash) do update set
                            fee_drops = coalesce(excluded.fee_drops, xrp_transaction.fee_drops),
                            ledger_index = coalesce(excluded.ledger_index, xrp_transaction.ledger_index),
                            sequence_number = coalesce(excluded.sequence_number, xrp_transaction.sequence_number),
                            confirmations = greatest(xrp_transaction.confirmations, excluded.confirmations),
                            status = excluded.status,
                            raw_payload = coalesce(excluded.raw_payload, xrp_transaction.raw_payload),
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getIssuerAddress(), tx.getCurrencyCode(), tx.getAmount(), tx.getFeeDrops(), tx.getLedgerIndex(),
                tx.getSequenceNumber(), tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(),
                toTs(now()), toTs(now()));
    }
    /**
     * 获取或查询 {@code findXrpTransactionAssetSymbol} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findXrpTransactionAssetSymbol(String chain, String txHash) {
        List<String> results = jdbcTemplate.queryForList("""
                        select asset_symbol
                        from xrp_transaction
                        where chain = ? and tx_hash = ?
                        limit 1
                        """, String.class, chain, txHash);
        return results.stream().findFirst();
    }
    /**
     * 写入或更新 {@code upsertLedgerBalance} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int upsertLedgerBalance(LedgerBalanceRecord record) {
        return jdbcTemplate.update("""
                insert into ledger_balance(chain, asset_symbol, account_id, available_balance, locked_balance,
                                           total_balance, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, asset_symbol, account_id) do update set
                    available_balance = excluded.available_balance,
                    locked_balance = excluded.locked_balance,
                    total_balance = excluded.total_balance,
                    updated_at = excluded.updated_at
                """,
                record.getChain(), record.getAssetSymbol(), record.getAccountId(), record.getAvailableBalance(),
                record.getLockedBalance(), record.getTotalBalance(), toTs(now()), toTs(now()));
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
        return jdbcTemplate.queryForList("""
                        select lower(address) from chain_address
                        where chain = ? and enabled = true
                        """, String.class, chain)
                .stream()
                .collect(Collectors.toSet());
    }
    /**
     * 写入或更新 {@code upsertChainAddress} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int upsertChainAddress(ChainAddressRecord address) {
        return jdbcTemplate.update("""
                insert into chain_address(
                            tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                            owner_address, derivation_path, wallet_role, enabled, created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, asset_symbol, user_id, biz, address_index, wallet_role)
                        do update set
                            tenant_id = excluded.tenant_id,
                            account_id = excluded.account_id,
                            address = excluded.address,
                            owner_address = excluded.owner_address,
                            derivation_path = excluded.derivation_path,
                            enabled = excluded.enabled,
                            updated_at = excluded.updated_at
                        """,
                address.getTenantId(), address.getChain(), address.getAssetSymbol(), address.getAccountId(), address.getUserId(),
                address.getBiz(), address.getAddressIndex(), address.getAddress(), address.getOwnerAddress(),
                address.getDerivationPath(), address.getWalletRole(), address.getEnabled(),
                toTs(now()), toTs(now()));
    }

    /**
     * 获取或查询 {@code findChainAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddress(String chain, String assetSymbol, long userId,
                                                         int biz, long addressIndex, String walletRole) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and asset_symbol = ? and user_id = ? and biz = ?
                          and address_index = ? and wallet_role = ?
                        """,
                (rs, rowNum) -> mapChainAddress(rs),
                chain, assetSymbol, userId, biz, addressIndex, walletRole);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code listDefaultHotAddressCandidates} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listDefaultHotAddressCandidates(String chain, String assetSymbol) {
        return jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ?
                          and asset_symbol = ?
                          and user_id = ?
                          and biz = ?
                          and wallet_role = ?
                        order by address_index, id
                        """,
                (rs, rowNum) -> mapChainAddress(rs),
                chain,
                assetSymbol,
                HotWalletRules.DEFAULT_HOT_USER_ID,
                HotWalletRules.DEFAULT_HOT_BIZ,
                HotWalletRules.DEFAULT_HOT_WALLET_ROLE);
    }
    /**
     * 获取或查询 {@code listReservedHotNamespaceAddresses} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listReservedHotNamespaceAddresses(String chain) {
        return jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ?
                          and user_id = ?
                          and biz = ?
                        order by asset_symbol, wallet_role, address_index, id
                        """,
                (rs, rowNum) -> mapChainAddress(rs),
                chain,
                HotWalletRules.DEFAULT_HOT_USER_ID,
                HotWalletRules.DEFAULT_HOT_BIZ);
    }
    /**
     * 获取或查询 {@code listChainAddresses} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
        return jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and asset_symbol = ? and enabled = true
                        order by id
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain, assetSymbol);
    }
    /**
     * 获取或查询 {@code listChainAddresses} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainAddressRecord> listChainAddresses(String chain) {
        return jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and enabled = true
                        order by id
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain);
    }
    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String address) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and address = ? and enabled = true
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain, address);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String assetSymbol, String address) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and asset_symbol = ? and address = ? and enabled = true
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain, assetSymbol, address);
        return results.stream().findFirst();
    }

    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(
            UUID tenantId, String chain, String assetSymbol, String address) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz,
                               address_index, address, owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where tenant_id = ? and chain = ? and asset_symbol = ?
                          and address = ? and enabled = true
                        """,
                (rs, rowNum) -> mapChainAddress(rs), tenantId, chain, assetSymbol, address);
        return results.stream().findFirst();
    }

    /**
     * 获取或查询 {@code findChainAddressByAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<ChainAddressRecord> findChainAddressByAddress(
            UUID tenantId, String chain, String address) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, account_id, user_id, biz,
                               address_index, address, owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where tenant_id = ? and chain = ? and address = ? and enabled = true
                        """,
                (rs, rowNum) -> mapChainAddress(rs), tenantId, chain, address);
        return results.stream().findFirst();
    }

    /**
     * 获取或查询 {@code findMaxChainAddressIndex} 对应的数据，供调用方读取当前状态。
     */
    public Optional<Long> findMaxChainAddressIndex(String chain, String assetSymbol, long userId,
                                                   int biz, String walletRole) {
        Long maxIndex = jdbcTemplate.queryForObject("""
                        select max(address_index)
                        from chain_address
                        where chain = ?
                          and asset_symbol = ?
                          and user_id = ?
                          and biz = ?
                          and wallet_role = ?
                          and enabled = true
                        """, Long.class, chain, assetSymbol, userId, biz, walletRole);
        return Optional.ofNullable(maxIndex);
    }
    /**
     * 记录或保存 {@code recordSolanaTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordSolanaTransaction(SolanaTransactionRecord tx) {
        return jdbcTemplate.update("""
                        insert into sol_transaction(
                            chain, signature, from_address, to_address, asset_symbol, mint_address,
                            amount, fee_lamports, slot, confirmations, status, raw_payload,
                            created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, signature) do update set
                            fee_lamports = excluded.fee_lamports,
                            slot = excluded.slot,
                            confirmations = greatest(sol_transaction.confirmations, excluded.confirmations),
                            status = excluded.status,
                            raw_payload = excluded.raw_payload,
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getSignature(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getMintAddress(), tx.getAmount(), tx.getFeeLamports(), tx.getSlot(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }
    /**
     * 记录或保存 {@code recordTonTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordTonTransaction(TonTransactionRecord tx) {
        return jdbcTemplate.update("""
                        insert into ton_transaction(
                            chain, tx_hash, from_address, to_address, asset_symbol, jetton_master,
                            amount, fee_nano, logical_time, confirmations, status, raw_payload,
                            created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_hash) do update set
                            fee_nano = excluded.fee_nano,
                            logical_time = excluded.logical_time,
                            confirmations = case
                                when ton_transaction.status = 'CONFIRMED' then ton_transaction.confirmations
                                else greatest(ton_transaction.confirmations, excluded.confirmations)
                            end,
                            status = case
                                when ton_transaction.status = 'CONFIRMED' then ton_transaction.status
                                else excluded.status
                            end,
                            raw_payload = excluded.raw_payload,
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getJettonMaster(), tx.getAmount(), tx.getFeeNano(), tx.getLogicalTime(),
                tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }
    /**
     * 设置或更新 {@code updateTonDepositTransactionConfirmations} 对应的状态，并保持相关业务字段一致。
     */
    public int updateTonDepositTransactionConfirmations(
            String chain, String txHash, int confirmations, int requiredConfirmations) {
        return jdbcTemplate.update("""
                        update ton_transaction
                           set confirmations = ?,
                               status = case when ? >= ? then 'CONFIRMED' else 'CONFIRMING' end,
                               updated_at = ?
                         where chain = ? and tx_hash = ? and status <> 'CONFIRMED'
                        """,
                confirmations, confirmations, requiredConfirmations, toTs(now()), chain, txHash);
    }
    /**
     * 写入或更新 {@code markTonTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markTonTransactionConfirmed(String chain, String txHash) {
        return jdbcTemplate.update("""
                        update ton_transaction
                        set confirmations = greatest(confirmations, 1),
                            status = 'CONFIRMED',
                            updated_at = ?
                        where chain = ? and tx_hash = ? and status <> 'CONFIRMED'
                        """,
                toTs(now()), chain, txHash);
    }
    /**
     * 获取或查询 {@code findTonTransactionRawPayload} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findTonTransactionRawPayload(String chain, String txHash) {
        List<String> results = jdbcTemplate.queryForList("""
                        select raw_payload from ton_transaction
                        where chain = ? and tx_hash = ? and raw_payload is not null
                        """, String.class, chain, txHash);
        return results.stream().findFirst();
    }
    /**
     * 记录或保存 {@code recordAptosTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordAptosTransaction(AptosTransactionRecord tx) {
        return jdbcTemplate.update("""
                        insert into aptos_transaction(
                            chain, tx_hash, sender, receiver, asset_symbol, coin_type,
                            amount, gas_used, gas_unit_price, version, sequence_number,
                            confirmations, status, raw_payload, created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_hash) do update set
                            gas_used = greatest(aptos_transaction.gas_used, excluded.gas_used),
                            gas_unit_price = greatest(aptos_transaction.gas_unit_price, excluded.gas_unit_price),
                            version = coalesce(excluded.version, aptos_transaction.version),
                            sequence_number = coalesce(excluded.sequence_number, aptos_transaction.sequence_number),
                            confirmations = greatest(aptos_transaction.confirmations, excluded.confirmations),
                            status = excluded.status,
                            raw_payload = coalesce(excluded.raw_payload, aptos_transaction.raw_payload),
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getTxHash(), tx.getSender(), tx.getReceiver(), tx.getAssetSymbol(),
                tx.getCoinType(), tx.getAmount(), tx.getGasUsed(), tx.getGasUnitPrice(), tx.getVersion(),
                tx.getSequenceNumber(), tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(),
                toTs(now()), toTs(now()));
    }

    /**
     * 写入或更新 {@code markAptosTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markAptosTransactionConfirmed(String chain, String txHash, long version,
                                             long gasUsed, long gasUnitPrice, String rawPayload) {
        return jdbcTemplate.update("""
                        update aptos_transaction
                        set confirmations = greatest(confirmations, 1),
                            status = 'CONFIRMED',
                            version = ?,
                            gas_used = ?,
                            gas_unit_price = ?,
                            raw_payload = coalesce(?, raw_payload),
                            updated_at = ?
                        where chain = ? and tx_hash = ? and status <> 'CONFIRMED'
                        """,
                version, gasUsed, gasUnitPrice, rawPayload, toTs(now()), chain, txHash);
    }
    /**
     * 记录或保存 {@code recordSuiTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordSuiTransaction(SuiTransactionRecord tx) {
        return jdbcTemplate.update("""
                        insert into sui_transaction(
                            chain, tx_digest, sender, receiver, asset_symbol, coin_type,
                            amount, gas_used, checkpoint, status, raw_payload, created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_digest) do update set
                            gas_used = greatest(sui_transaction.gas_used, excluded.gas_used),
                            checkpoint = coalesce(excluded.checkpoint, sui_transaction.checkpoint),
                            status = excluded.status,
                            raw_payload = coalesce(excluded.raw_payload, sui_transaction.raw_payload),
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getTxDigest(), tx.getSender(), tx.getReceiver(), tx.getAssetSymbol(),
                tx.getCoinType(), tx.getAmount(), tx.getGasUsed(), tx.getCheckpoint(), tx.getStatus(),
                tx.getRawPayload(), toTs(now()), toTs(now()));
    }
    /**
     * 记录或保存 {@code recordMoneroTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordMoneroTransaction(MoneroTransactionRecord tx) {
        return jdbcTemplate.update("""
                        insert into monero_transaction(
                            chain, tx_hash, direction, account_index, subaddress_index, address, asset_symbol,
                            amount, fee_atomic, block_height, confirmations, status, raw_payload,
                            created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_hash, direction, subaddress_index) do update set
                            amount = coalesce(excluded.amount, monero_transaction.amount),
                            fee_atomic = coalesce(excluded.fee_atomic, monero_transaction.fee_atomic),
                            block_height = coalesce(excluded.block_height, monero_transaction.block_height),
                            confirmations = greatest(monero_transaction.confirmations, excluded.confirmations),
                            status = excluded.status,
                            raw_payload = coalesce(excluded.raw_payload, monero_transaction.raw_payload),
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getTxHash(), tx.getDirection(), tx.getAccountIndex(), tx.getSubaddressIndex(),
                tx.getAddress(), tx.getAssetSymbol(), tx.getAmount(), tx.getFeeAtomic(), tx.getBlockHeight(),
                tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }

    /**
     * 写入或更新 {@code markSuiTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markSuiTransactionConfirmed(String chain, String txDigest, long checkpoint,
                                           long gasUsed, String rawPayload) {
        return jdbcTemplate.update("""
                        update sui_transaction
                        set status = 'CONFIRMED',
                            checkpoint = ?,
                            gas_used = ?,
                            raw_payload = coalesce(?, raw_payload),
                            updated_at = ?
                        where chain = ? and tx_digest = ? and status <> 'CONFIRMED'
                        """,
                checkpoint, gasUsed, rawPayload, toTs(now()), chain, txDigest);
    }
    /**
     * 记录或保存 {@code recordNearTransaction} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordNearTransaction(NearTransactionRecord tx) {
        return jdbcTemplate.update("""
                        insert into near_transaction(
                            chain, tx_hash, action_index, sender, receiver, asset_symbol,
                            amount, gas_burnt, block_height, status, raw_payload,
                            created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_hash, action_index) do update set
                            sender = excluded.sender,
                            receiver = excluded.receiver,
                            asset_symbol = excluded.asset_symbol,
                            amount = excluded.amount,
                            gas_burnt = greatest(near_transaction.gas_burnt, excluded.gas_burnt),
                            block_height = greatest(coalesce(near_transaction.block_height, 0),
                                                     coalesce(excluded.block_height, 0)),
                            status = excluded.status,
                            raw_payload = coalesce(excluded.raw_payload, near_transaction.raw_payload),
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getTxHash(), tx.getActionIndex() == null ? 0L : tx.getActionIndex(),
                tx.getSender(), tx.getReceiver(), tx.getAssetSymbol(),
                tx.getAmount(), tx.getGasBurnt(), tx.getBlockHeight(), tx.getStatus(), tx.getRawPayload(),
                toTs(now()), toTs(now()));
    }

    /**
     * 写入或更新 {@code markNearTransactionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markNearTransactionConfirmed(String chain, String txHash, long blockHeight,
                                            long gasBurnt, String rawPayload) {
        return jdbcTemplate.update("""
                        update near_transaction
                        set status = 'CONFIRMED',
                            block_height = greatest(coalesce(block_height, 0), ?),
                            gas_burnt = greatest(gas_burnt, ?),
                            raw_payload = coalesce(?, raw_payload),
                            updated_at = ?
                        where chain = ? and tx_hash = ?
                        """,
                blockHeight, gasBurnt, rawPayload, toTs(now()), chain, txHash);
    }
    /**
     * 获取或查询 {@code findNearTransactionSender} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findNearTransactionSender(String chain, String txHash) {
        List<String> results = jdbcTemplate.queryForList("""
                        select sender from near_transaction
                        where chain = ? and tx_hash = ?
                        """, String.class, chain, txHash);
        return results.stream().findFirst();
    }

    /**
     * 执行 {@code reserveAccountSequence} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    @Transactional(rollbackFor = Throwable.class)
    public long reserveAccountSequence(String chain, String address, long chainSequence) {
        jdbcTemplate.update("""
                        insert into account_sequence(
                            chain, address, chain_sequence, next_sequence, status, created_at, updated_at
                        )
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        on conflict (chain, address) do nothing
                        """,
                chain, address, chainSequence, chainSequence, toTs(now()), toTs(now()));
        Long next = jdbcTemplate.queryForObject("""
                        select next_sequence from account_sequence
                        where chain = ? and address = ?
                        for update
                        """, Long.class, chain, address);
        long reserved = Math.max(chainSequence, next == null ? chainSequence : next);
        jdbcTemplate.update("""
                        update account_sequence
                        set chain_sequence = greatest(chain_sequence, ?),
                            next_sequence = ?,
                            status = 'ACTIVE',
                            updated_at = ?
                        where chain = ? and address = ?
                        """,
                chainSequence, reserved + 1, toTs(now()), chain, address);
        return reserved;
    }
    /**
     * 执行 {@code synchronizeAccountSequence} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public void synchronizeAccountSequence(String chain, String address, long chainSequence) {
        jdbcTemplate.update("""
                        insert into account_sequence(
                            chain, address, chain_sequence, next_sequence, status, created_at, updated_at
                        )
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        on conflict (chain, address) do update set
                            chain_sequence = excluded.chain_sequence,
                            next_sequence = greatest(account_sequence.next_sequence, excluded.next_sequence),
                            status = 'ACTIVE',
                            updated_at = excluded.updated_at
                        """,
                chain, address, chainSequence, chainSequence, toTs(now()), toTs(now()));
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
        int recorded = jdbcTemplate.update("""
                        insert into deposit_record(tenant_id, chain, asset_symbol, tx_hash, log_index, from_address, to_address,
                                                   contract_address, amount, block_height, block_hash, confirmations, status,
                                                   credited, credit_generation, canonical_status, account_id,
                                                   raw_payload, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, 0, 'CANONICAL', ?, ?, ?, ?)
                        on conflict (chain, tx_hash, log_index) do update set
                            block_height = case
                                when deposit_record.canonical_status = 'REORGED' then excluded.block_height
                                else deposit_record.block_height
                            end,
                            block_hash = case
                                when deposit_record.canonical_status = 'REORGED' then excluded.block_hash
                                else deposit_record.block_hash
                            end,
                            confirmations = case
                                when deposit_record.credited then deposit_record.confirmations
                                when deposit_record.canonical_status = 'REORGED' then excluded.confirmations
                                else greatest(deposit_record.confirmations, excluded.confirmations)
                            end,
                            status = case when deposit_record.credited then 'CREDITED' else excluded.status end,
                            canonical_status = 'CANONICAL',
                            reorged_at = null,
                            reorg_reason = null,
                            tenant_id = coalesce(deposit_record.tenant_id, excluded.tenant_id),
                            account_id = excluded.account_id,
                            raw_payload = excluded.raw_payload,
                            updated_at = excluded.updated_at
                        where deposit_record.tenant_id is null
                           or deposit_record.tenant_id = excluded.tenant_id
                        """,
                tenantId, chain, event.assetSymbol(), event.txId(), logIndex, event.fromAddress(), event.toAddress(),
                event.tokenAddress(), event.amount(), event.blockHeight(), event.blockHash(), event.confirmations(), status,
                accountId,
                event.rawPayload(), toTs(now()), toTs(now()));
        if (recorded != 1) {
            throw new IllegalStateException("deposit record belongs to another tenant");
        }

        if (event.confirmations() < requiredConfirmations) {
            return false;
        }

        int credited = jdbcTemplate.update("""
                        update deposit_record
                        set credited = true, credited_at = ?, status = 'CREDITED',
                            credit_generation = credit_generation + 1, updated_at = ?
                        where chain = ? and tx_hash = ? and log_index = ?
                          and credited = false and canonical_status = 'CANONICAL'
                        """,
                toTs(now()), toTs(now()), chain, event.txId(), logIndex);
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
        return jdbcTemplate.query("""
                        select asset_symbol, tx_hash, log_index, from_address, to_address,
                               contract_address, amount, block_height, block_hash, confirmations,
                               account_id, raw_payload
                          from deposit_record
                         where chain = ?
                           and credited = false
                           and canonical_status = 'CANONICAL'
                           and status in ('DETECTED', 'CONFIRMING')
                           and confirmations < ?
                         order by id
                         limit ?
                        """,
                (rs, rowNum) -> new PendingDepositRecord(
                        rs.getString("asset_symbol"),
                        rs.getString("tx_hash"),
                        rs.getLong("log_index"),
                        rs.getString("from_address"),
                        rs.getString("to_address"),
                        rs.getString("contract_address"),
                        rs.getBigDecimal("amount"),
                        rs.getLong("block_height"),
                        rs.getString("block_hash"),
                        rs.getInt("confirmations"),
                        rs.getString("account_id"),
                        rs.getString("raw_payload")),
                chain, requiredConfirmations, limit);
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
        List<String> existing = jdbcTemplate.queryForList("""
                        select block_hash from chain_scan_block
                         where chain = ? and scanner_name = ? and block_height = ?
                         for update
                        """, String.class, normalizedChain, normalizedScanner, blockHeight);
        if (existing.isEmpty()) {
            jdbcTemplate.update("""
                            insert into chain_scan_block(
                                chain, scanner_name, block_height, block_hash, parent_hash, observed_at)
                            values (?, ?, ?, ?, ?, ?)
                            """, normalizedChain, normalizedScanner, blockHeight,
                    normalizedHash, parentHash, toTs(now()));
            return new BlockObservation(false, null, normalizedHash, 0);
        }
        String previousHash = existing.getFirst();
        if (previousHash.equalsIgnoreCase(normalizedHash)) {
            jdbcTemplate.update("""
                            update chain_scan_block
                               set parent_hash = ?, observed_at = ?
                             where chain = ? and scanner_name = ? and block_height = ?
                            """, parentHash, toTs(now()), normalizedChain, normalizedScanner, blockHeight);
            return new BlockObservation(false, previousHash, normalizedHash, 0);
        }

        List<DepositForReorg> deposits = jdbcTemplate.query("""
                        select id, tenant_id, chain, asset_symbol, tx_hash, log_index,
                               account_id, to_address, amount, credited, credit_generation,
                               block_height, block_hash
                          from deposit_record
                         where chain = ? and block_height = ? and canonical_status = 'CANONICAL'
                           and block_hash is not null and lower(block_hash) <> lower(?)
                         order by id
                         for update
                        """, (rs, rowNum) -> new DepositForReorg(
                        rs.getLong("id"), rs.getObject("tenant_id", UUID.class),
                        rs.getString("chain"), rs.getString("asset_symbol"),
                        rs.getString("tx_hash"), rs.getLong("log_index"),
                        rs.getString("account_id"), rs.getString("to_address"),
                        rs.getBigDecimal("amount"), rs.getBoolean("credited"),
                        rs.getInt("credit_generation"), rs.getLong("block_height"),
                        rs.getString("block_hash")),
                normalizedChain, blockHeight, normalizedHash);
        jdbcTemplate.update("""
                        update utxo_record
                           set state = 'ORPHANED', confirmations = 0, credited = false, updated_at = ?
                         where chain = ? and block_height = ?
                           and lower(block_hash) <> lower(?) and state <> 'SPENT'
                        """, toTs(now()), normalizedChain, blockHeight, normalizedHash);
        int reversed = 0;
        for (DepositForReorg deposit : deposits) {
            reverseDeposit(deposit, normalizedHash,
                    "canonical block changed from " + previousHash + " to " + normalizedHash);
            reversed++;
        }
        jdbcTemplate.update("""
                        update chain_scan_block
                           set block_hash = ?, parent_hash = ?, observed_at = ?
                         where chain = ? and scanner_name = ? and block_height = ?
                        """, normalizedHash, parentHash, toTs(now()),
                normalizedChain, normalizedScanner, blockHeight);
        return new BlockObservation(true, previousHash, normalizedHash, reversed);
    }
    /**
     * 执行 {@code reverseDeposit} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void reverseDeposit(DepositForReorg deposit, String replacementBlockHash, String reason) {
        BigDecimal reversedAmount = BigDecimal.ZERO;
        BigDecimal deficitAmount = BigDecimal.ZERO;
        if (deposit.credited()) {
            BigDecimal available = jdbcTemplate.query("""
                            select available_balance from ledger_balance
                             where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                             for update
                            """, (rs, rowNum) -> rs.getBigDecimal(1),
                    deposit.tenantId(), deposit.chain(), deposit.assetSymbol(), deposit.accountId())
                    .stream().findFirst().orElse(BigDecimal.ZERO);
            reversedAmount = available.min(deposit.amount()).max(BigDecimal.ZERO);
            deficitAmount = deposit.amount().subtract(reversedAmount);
            if (reversedAmount.signum() > 0) {
                int updated = jdbcTemplate.update("""
                                update ledger_balance
                                   set available_balance = available_balance - ?,
                                       total_balance = total_balance - ?, updated_at = ?
                                 where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                                   and available_balance >= ? and total_balance >= ?
                                """, reversedAmount, reversedAmount, toTs(now()),
                        deposit.tenantId(), deposit.chain(), deposit.assetSymbol(), deposit.accountId(),
                        reversedAmount, reversedAmount);
                if (updated != 1) {
                    throw new IllegalStateException("unable to reverse orphaned deposit balance");
                }
            }
        }
        int updated = jdbcTemplate.update("""
                        update deposit_record
                           set credited = false, status = 'REORGED', canonical_status = 'REORGED',
                               confirmations = 0, reorged_at = ?, reorg_reason = ?, updated_at = ?
                         where id = ? and canonical_status = 'CANONICAL'
                        """, toTs(now()), reason, toTs(now()), deposit.id());
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

    private record DepositForReorg(
            long id, UUID tenantId, String chain, String assetSymbol, String txHash,
            long logIndex, String accountId, String toAddress, BigDecimal amount,
            boolean credited, int creditGeneration, long blockHeight, String blockHash) {
    }

    /**
     * 判断 {@code isInternalCollectionTransfer} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isInternalCollectionTransfer(UUID tenantId, String chain,
                                                 String txHash, String toAddress) {
        Boolean internal = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                      from collection_record
                     where tenant_id = ? and chain = ?
                       and lower(tx_hash) = lower(?) and lower(to_address) = lower(?)
                )
                """, Boolean.class, tenantId, chain, txHash, toAddress);
        return Boolean.TRUE.equals(internal);
    }
    /**
     * 校验 {@code requireDepositTenant} 对应的前置条件，不满足时抛出明确异常。
     */
    private UUID requireDepositTenant(String chain, String accountId, String address) {
        List<UUID> tenants = jdbcTemplate.queryForList("""
                        select distinct tenant_id
                          from chain_address
                         where tenant_id is not null and enabled = true and chain = ?
                           and (lower(account_id) = lower(?) or lower(address) = lower(?))
                         limit 2
                        """, UUID.class, chain, accountId, address);
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
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from deposit_record
                            where chain = ? and tx_hash = ? and log_index = ?
                        )
                        """,
                Boolean.class, chain, txHash, logIndex);
        return Boolean.TRUE.equals(exists);
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
        return jdbcTemplate.update("""
                        insert into withdrawal_order(order_no, user_id, chain, asset_symbol, from_address,
                                                     debit_account_id, to_address, amount, fee, status,
                                                     created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                        on conflict (chain, order_no) do nothing
                        """,
                orderNo, userId, chain, assetSymbol, fromAddress, debitAccountId, toAddress, amount, fee,
                toTs(now()), toTs(now()));
    }

    /**
     * 构建或生成 {@code createTenantWithdrawalOrder} 对应的结果，并执行输入和状态校验。
     */
    public int createTenantWithdrawalOrder(UUID tenantId, String orderNo, long userId,
                                           String chain, String assetSymbol,
                                           String fromAddress, String debitAccountId,
                                           String toAddress, BigDecimal amount, BigDecimal fee) {
        return jdbcTemplate.update("""
                        insert into withdrawal_order(
                            tenant_id, order_no, user_id, chain, asset_symbol, from_address,
                            debit_account_id, to_address, amount, fee, status,
                            created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                        on conflict (chain, order_no) do nothing
                        """,
                tenantId, orderNo, userId, chain, assetSymbol, fromAddress,
                debitAccountId, toAddress, amount, fee, toTs(now()), toTs(now()));
    }
    /**
     * 获取或查询 {@code listWithdrawalsForSigning} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawalOrderRecord> listWithdrawalsForSigning(String chain, String assetSymbol, int limit) {
        return jdbcTemplate.query("""
                        select w.id, w.tenant_id, w.order_no, w.user_id, w.chain, w.asset_symbol,
                               w.from_address, w.debit_account_id, w.to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order w
                        where w.tenant_id = (
                            select candidate.tenant_id
                              from withdrawal_order candidate
                             where candidate.tenant_id is not null and candidate.chain = ?
                               and candidate.asset_symbol = ?
                               and candidate.status in ('FROZEN', 'RETRYING')
                             order by candidate.id
                             limit 1
                        )
                          and w.chain = ? and w.asset_symbol = ?
                          and w.status in ('FROZEN', 'RETRYING')
                        order by w.id
                        limit ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, assetSymbol, chain, assetSymbol, limit);
    }
    /**
     * 获取或查询 {@code listWithdrawalsForSigning} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawalOrderRecord> listWithdrawalsForSigning(String chain, int limit) {
        return jdbcTemplate.query("""
                        select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where tenant_id is not null and chain = ? and status in ('FROZEN', 'RETRYING')
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, limit);
    }
    /**
     * 获取或查询 {@code listWithdrawalsByStatus} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawalOrderRecord> listWithdrawalsByStatus(String chain, String status, int limit) {
        return jdbcTemplate.query("""
                        select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where tenant_id is not null and chain = ? and status = ?
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, status, limit);
    }
    /**
     * 判断 {@code isWithdrawalInPendingEvm7702Batch} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isWithdrawalInPendingEvm7702Batch(UUID tenantId, long withdrawalOrderId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                      from evm_withdrawal_batch_item item
                      join evm_withdrawal_batch batch
                        on batch.tenant_id = item.tenant_id and batch.id = item.batch_id
                     where item.tenant_id = ? and item.withdrawal_order_id = ?
                       and item.status = 'SUBMITTED'
                       and batch.status in ('SUBMITTED', 'CONFIRMING', 'BROADCAST_UNKNOWN')
                )
                """, Boolean.class, tenantId, withdrawalOrderId));
    }
    /**
     * 判断 {@code isCollectionInPendingEvm7702Batch} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isCollectionInPendingEvm7702Batch(UUID tenantId, long collectionRecordId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                      from evm_collection_batch_item item
                      join evm_collection_batch batch
                        on batch.tenant_id = item.tenant_id and batch.id = item.batch_id
                     where item.tenant_id = ? and item.collection_record_id = ?
                       and item.status = 'SUBMITTED'
                       and batch.status in ('SUBMITTED', 'CONFIRMING', 'BROADCAST_UNKNOWN')
                )
                """, Boolean.class, tenantId, collectionRecordId));
    }
    /**
     * 执行 {@code claimWithdrawalSigning} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int claimWithdrawalSigning(String chain, String orderNo, String fromAddress) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'SIGNING',
                            from_address = coalesce(?, from_address),
                            error_message = null,
                            updated_at = ?
                        where chain = ? and order_no = ? and status in ('FROZEN', 'RETRYING')
                          and not exists (
                              select 1 from custody_reorg_deficit deficit
                               where deficit.tenant_id = withdrawal_order.tenant_id
                                 and deficit.chain = withdrawal_order.chain
                                 and deficit.asset_symbol = withdrawal_order.asset_symbol
                                 and deficit.account_id = withdrawal_order.debit_account_id
                                 and deficit.status = 'OPEN')
                        """,
                fromAddress, toTs(now()), chain, orderNo);
    }
    /**
     * 执行 {@code claimWithdrawalSigning} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int claimWithdrawalSigning(UUID tenantId, String chain, String orderNo, String fromAddress) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'SIGNING',
                            from_address = coalesce(?, from_address),
                            error_message = null,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and order_no = ?
                          and status in ('FROZEN', 'RETRYING')
                          and not exists (
                              select 1 from custody_reorg_deficit deficit
                               where deficit.tenant_id = withdrawal_order.tenant_id
                                 and deficit.chain = withdrawal_order.chain
                                 and deficit.asset_symbol = withdrawal_order.asset_symbol
                                 and deficit.account_id = withdrawal_order.debit_account_id
                                 and deficit.status = 'OPEN')
                        """,
                fromAddress, toTs(now()), tenantId, chain, orderNo);
    }
    /**
     * 写入或更新 {@code markStaleSigningWithdrawalsUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markStaleSigningWithdrawalsUnknown(String chain, Instant before) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'BROADCAST_UNKNOWN',
                            error_message = 'signing state expired before a tx hash was recorded; manual chain audit required',
                            updated_at = ?
                        where tenant_id is not null and chain = ? and status = 'SIGNING'
                          and tx_hash is null and updated_at < ?
                        """,
                toTs(now()), chain, toTs(before));
    }
    /**
     * 写入或更新 {@code markWithdrawalSent} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalSent(String chain, String orderNo, String fromAddress, String txHash) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("withdrawal tx hash must not be blank");
        }
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'SENT',
                            from_address = coalesce(?, from_address),
                            tx_hash = ?,
                            error_message = null,
                            updated_at = ?
                        where chain = ? and order_no = ? and status = 'SIGNING' and tx_hash is null
                        """,
                fromAddress, txHash, toTs(now()), chain, orderNo);
    }

    /**
     * 写入或更新 {@code markWithdrawalSent} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalSent(UUID tenantId, String chain, String orderNo,
                                  String fromAddress, String txHash) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("withdrawal tx hash must not be blank");
        }
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'SENT',
                            from_address = coalesce(?, from_address),
                            tx_hash = ?,
                            error_message = null,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and order_no = ?
                          and status = 'SIGNING' and tx_hash is null
                        """,
                fromAddress, txHash, toTs(now()), tenantId, chain, orderNo);
    }
    /**
     * 写入或更新 {@code markWithdrawalBroadcastUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalBroadcastUnknown(String chain, String orderNo, String fromAddress, String errorMessage) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'BROADCAST_UNKNOWN',
                            from_address = coalesce(?, from_address),
                            error_message = ?,
                            updated_at = ?
                        where chain = ? and order_no = ? and status = 'SIGNING' and tx_hash is null
                        """,
                fromAddress, errorMessage, toTs(now()), chain, orderNo);
    }

    /**
     * 写入或更新 {@code markWithdrawalBroadcastUnknown} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalBroadcastUnknown(UUID tenantId, String chain, String orderNo,
                                              String fromAddress, String errorMessage) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'BROADCAST_UNKNOWN',
                            from_address = coalesce(?, from_address),
                            error_message = ?,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and order_no = ?
                          and status = 'SIGNING' and tx_hash is null
                        """,
                fromAddress, errorMessage, toTs(now()), tenantId, chain, orderNo);
    }

    /**
     * 设置或更新 {@code updateWithdrawalStatus} 对应的状态，并保持相关业务字段一致。
     */
    public int updateWithdrawalStatus(String chain, String orderNo, String status, String fromAddress,
                                      String txHash, String errorMessage) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = ?,
                            from_address = coalesce(?, from_address),
                            tx_hash = coalesce(?, tx_hash),
                            error_message = ?,
                            updated_at = ?
                        where chain = ? and order_no = ?
                        """,
                status, fromAddress, txHash, errorMessage, toTs(now()), chain, orderNo);
    }

    /**
     * 设置或更新 {@code updateWithdrawalStatus} 对应的状态，并保持相关业务字段一致。
     */
    public int updateWithdrawalStatus(UUID tenantId, String chain, String orderNo, String status,
                                      String fromAddress, String txHash, String errorMessage) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = ?,
                            from_address = coalesce(?, from_address),
                            tx_hash = coalesce(?, tx_hash),
                            error_message = ?,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and order_no = ?
                        """,
                status, fromAddress, txHash, errorMessage, toTs(now()), tenantId, chain, orderNo);
    }
    /**
     * 写入或更新 {@code markWithdrawalConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalConfirmed(String chain, String orderNo, String txHash) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'CONFIRMED', tx_hash = ?, error_message = null, updated_at = ?
                        where chain = ? and order_no = ? and status in ('SENT', 'CONFIRMING')
                          and tx_hash = ?
                        """,
                txHash, toTs(now()), chain, orderNo, txHash);
    }
    /**
     * 写入或更新 {@code markWithdrawalConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markWithdrawalConfirmed(UUID tenantId, String chain, String orderNo, String txHash) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'CONFIRMED', tx_hash = ?, error_message = null, updated_at = ?
                        where tenant_id = ? and chain = ? and order_no = ?
                          and status in ('SENT', 'CONFIRMING') and tx_hash = ?
                        """,
                txHash, toTs(now()), tenantId, chain, orderNo, txHash);
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
        List<String> results = jdbcTemplate.queryForList("""
                        select status from withdrawal_order where chain = ? and order_no = ?
                        """, String.class, chain, orderNo);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findWithdrawalTxHash} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findWithdrawalTxHash(String chain, String orderNo) {
        List<String> results = jdbcTemplate.queryForList("""
                        select tx_hash from withdrawal_order
                        where chain = ? and order_no = ? and tx_hash is not null
                        """, String.class, chain, orderNo);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findWithdrawalTxHash} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findWithdrawalTxHash(UUID tenantId, String chain, String orderNo) {
        List<String> results = jdbcTemplate.queryForList("""
                        select tx_hash from withdrawal_order
                        where tenant_id = ? and chain = ? and order_no = ? and tx_hash is not null
                        """, String.class, tenantId, chain, orderNo);
        return results.stream().findFirst();
    }
    /**
     * 校验 {@code requireWithdrawalTenant} 对应的前置条件，不满足时抛出明确异常。
     */
    public UUID requireWithdrawalTenant(String chain, String orderNo) {
        List<UUID> tenants = jdbcTemplate.queryForList("""
                        select tenant_id from withdrawal_order
                        where chain = ? and order_no = ? and tenant_id is not null
                        """, UUID.class, chain, orderNo);
        if (tenants.size() != 1) {
            throw new IllegalStateException(
                    "withdrawal order must belong to exactly one tenant: " + chain + ":" + orderNo);
        }
        return tenants.getFirst();
    }
    /**
     * 获取或查询 {@code findWithdrawalOrder} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawalOrderRecord> findWithdrawalOrder(String chain, String orderNo) {
        List<WithdrawalOrderRecord> results = jdbcTemplate.query("""
                        select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where chain = ? and order_no = ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, orderNo);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findWithdrawalOrder} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawalOrderRecord> findWithdrawalOrder(UUID tenantId, String chain, String orderNo) {
        List<WithdrawalOrderRecord> results = jdbcTemplate.query("""
                        select id, tenant_id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where tenant_id = ? and chain = ? and order_no = ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs), tenantId, chain, orderNo);
        return results.stream().findFirst();
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
        return jdbcTemplate.update("""
                        insert into collection_record(collection_no, chain, asset_symbol, from_address, to_address,
                                                      amount, fee, status, raw_payload, tenant_id,
                                                      custody_address_id, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                        on conflict (chain, collection_no) do nothing
                        """,
                collectionNo, chain, assetSymbol, fromAddress, toAddress, amount, fee, rawPayload,
                tenantId, custodyAddressId,
                toTs(now()), toTs(now()));
    }
    /**
     * 获取或查询 {@code listCollectionsForSigning} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainCollectionRecord> listCollectionsForSigning(String chain, int limit) {
        return jdbcTemplate.query("""
                        select id, tenant_id, custody_address_id, collection_no, chain, asset_symbol, from_address, to_address,
                               amount, fee, tx_hash, status, error_message, raw_payload, created_at, updated_at
                        from collection_record
                        where tenant_id is not null and custody_address_id is not null
                          and chain = ? and status in ('CREATED', 'RETRYING')
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapCollectionRecord(rs),
                chain, limit);
    }
    /**
     * 获取或查询 {@code listCollectionsByStatus} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainCollectionRecord> listCollectionsByStatus(String chain, String status, int limit) {
        return jdbcTemplate.query("""
                        select id, tenant_id, custody_address_id, collection_no, chain, asset_symbol, from_address, to_address,
                               amount, fee, tx_hash, status, error_message, raw_payload, created_at, updated_at
                        from collection_record
                        where tenant_id is not null and custody_address_id is not null
                          and chain = ? and status = ?
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapCollectionRecord(rs),
                chain, status, limit);
    }

    /**
     * 设置或更新 {@code updateCollectionStatus} 对应的状态，并保持相关业务字段一致。
     */
    public int updateCollectionStatus(UUID tenantId, String chain, String collectionNo,
                                      String status, String txHash, String errorMessage,
                                      String rawPayload) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return jdbcTemplate.update("""
                        update collection_record
                        set status = ?,
                            tx_hash = coalesce(?, tx_hash),
                            error_message = ?,
                            raw_payload = coalesce(?, raw_payload),
                            updated_at = ?
                        where tenant_id = ?
                          and chain = ? and collection_no = ?
                        """,
                status, txHash, errorMessage, rawPayload, toTs(now()),
                tenantId, chain, collectionNo);
    }

        /**
     * 执行 {@code claimCollectionSigning} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public int claimCollectionSigning(UUID tenantId, String chain,
                                      String collectionNo, String rawPayload) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return jdbcTemplate.update("""
                        update collection_record
                        set status = 'SIGNING',
                            error_message = null,
                            raw_payload = coalesce(?, raw_payload),
                            updated_at = ?
                        where tenant_id = ?
                          and chain = ? and collection_no = ?
                          and status in ('CREATED', 'RETRYING')
                        """,
                rawPayload, toTs(now()), tenantId, chain, collectionNo);
    }

    /**
     * 写入或更新 {@code markCollectionConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markCollectionConfirmed(UUID tenantId, String chain,
                                       String collectionNo, String txHash) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return jdbcTemplate.update("""
                        update collection_record
                        set status = 'CONFIRMED', tx_hash = ?, error_message = null, updated_at = ?
                        where tenant_id = ?
                          and chain = ? and collection_no = ? and status <> 'CONFIRMED'
                        """,
                txHash, toTs(now()), tenantId, chain, collectionNo);
    }
    /**
     * 获取或查询 {@code findCollectionStatus} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findCollectionStatus(UUID tenantId, String chain, String collectionNo) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        List<String> results = jdbcTemplate.queryForList("""
                        select status from collection_record
                         where tenant_id = ?
                           and chain = ? and collection_no = ?
                        """, String.class, tenantId, chain, collectionNo);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code findCollectionTxHash} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findCollectionTxHash(UUID tenantId, String chain, String collectionNo) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        List<String> results = jdbcTemplate.queryForList("""
                        select tx_hash from collection_record
                         where tenant_id = ?
                           and chain = ? and collection_no = ? and tx_hash is not null
                        """, String.class, tenantId, chain, collectionNo);
        return results.stream().findFirst();
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
        List<WithdrawTransaction> inserted = jdbcTemplate.query("""
                        insert into chain_signing_transaction(
                            chain, asset_symbol, business_type, business_no,
                            tx_id, balance, signature, currency, status, create_date, update_date
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, business_type, business_no) do update set
                            tx_id = excluded.tx_id,
                            balance = excluded.balance,
                            signature = excluded.signature,
                            currency = excluded.currency,
                            status = excluded.status,
                            error_message = null,
                            update_date = excluded.update_date
                        where chain_signing_transaction.status in (?, ?)
                        returning id, tx_id, balance, signature, currency, status, create_date, update_date
                        """,
                (rs, rowNum) -> mapSigningTransaction(rs),
                chain,
                assetSymbol,
                businessType,
                businessNo,
                transaction.getTxId(),
                transaction.getBalance(),
                transaction.getSignature(),
                transaction.getCurrency(),
                transaction.getStatus(),
                toTs(now()),
                toTs(now()),
                Constants.WAITING,
                Constants.SIGNING);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return findBitcoinLikeSigningTransaction(chain, businessType, businessNo)
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
        List<WithdrawTransaction> results = jdbcTemplate.query("""
                        select id, tx_id, balance, signature, currency, status, create_date, update_date
                        from chain_signing_transaction
                        where chain = ? and business_type = ? and business_no = ?
                        """,
                (rs, rowNum) -> mapSigningTransaction(rs),
                chain, businessType, businessNo);
        return results.stream().findFirst();
    }

    /**
     * 获取或查询 {@code findBitcoinLikeSigningTransactionById} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransactionById(
            AssetRuntimeMetadata currency, int transactionId) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        List<WithdrawTransaction> results = jdbcTemplate.query("""
                        select id, tx_id, balance, signature, currency, status, create_date, update_date
                        from chain_signing_transaction
                        where chain = ? and id = ?
                        """,
                (rs, rowNum) -> mapSigningTransaction(rs),
                chain, transactionId);
        return results.stream().findFirst();
    }

    /**
     * 获取或查询 {@code findBitcoinLikeSigningTransactionByTxId} 对应的数据，供调用方读取当前状态。
     */
    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransactionByTxId(
            AssetRuntimeMetadata currency, String txId) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        List<WithdrawTransaction> results = jdbcTemplate.query("""
                        select id, tx_id, balance, signature, currency, status, create_date, update_date
                        from chain_signing_transaction
                        where chain = ? and tx_id = ?
                        order by id desc
                        limit 1
                        """,
                (rs, rowNum) -> mapSigningTransaction(rs),
                chain, txId);
        return results.stream().findFirst();
    }
    /**
     * 执行 {@code bitcoinLikeSigningTransactionExists} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean bitcoinLikeSigningTransactionExists(AssetRuntimeMetadata currency, String txId) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from chain_signing_transaction
                            where chain = ? and tx_id = ?
                        )
                        """, Boolean.class, chain, txId);
        return Boolean.TRUE.equals(exists);
    }
    /**
     * 设置或更新 {@code updateBitcoinLikeSigningTransaction} 对应的状态，并保持相关业务字段一致。
     */
    public int updateBitcoinLikeSigningTransaction(AssetRuntimeMetadata currency, WithdrawTransaction transaction) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.update("""
                        update chain_signing_transaction
                        set tx_id = ?,
                            balance = ?,
                            signature = ?,
                            currency = ?,
                            status = ?,
                            error_message = null,
                            update_date = ?
                        where chain = ? and id = ?
                        """,
                transaction.getTxId(),
                transaction.getBalance(),
                transaction.getSignature(),
                transaction.getCurrency(),
                transaction.getStatus(),
                toTs(now()),
                chain,
                transaction.getId());
    }
    /**
     * 写入或更新 {@code markBitcoinLikeSigningError} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public int markBitcoinLikeSigningError(AssetRuntimeMetadata currency, int transactionId, String errorMessage) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.update("""
                        update chain_signing_transaction
                        set error_message = ?,
                            update_date = ?
                        where chain = ? and id = ?
                        """,
                errorMessage, toTs(now()), chain, transactionId);
    }
    /**
     * 获取或查询 {@code findSentBitcoinLikeSigningTransactions} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawTransaction> findSentBitcoinLikeSigningTransactions(AssetRuntimeMetadata currency) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.query("""
                        select id, tx_id, balance, signature, currency, status, create_date, update_date
                        from chain_signing_transaction
                        where chain = ? and status = ?
                        order by id
                        """,
                (rs, rowNum) -> mapSigningTransaction(rs),
                chain, Constants.SENT);
    }
    /**
     * 获取或查询 {@code findLedgerBalance} 对应的数据，供调用方读取当前状态。
     */
    public Optional<LedgerBalanceRecord> findLedgerBalance(String chain, String assetSymbol, String accountId) {
        List<LedgerBalanceRecord> results = jdbcTemplate.query("""
                        select chain, asset_symbol, account_id, available_balance, locked_balance, total_balance,
                               created_at, updated_at
                        from ledger_balance
                        where chain = ? and asset_symbol = ? and account_id = ?
                        """,
                (rs, rowNum) -> LedgerBalanceRecord.builder()
                        .chain(rs.getString("chain"))
                        .assetSymbol(rs.getString("asset_symbol"))
                        .accountId(rs.getString("account_id"))
                        .availableBalance(rs.getBigDecimal("available_balance"))
                        .lockedBalance(rs.getBigDecimal("locked_balance"))
                        .totalBalance(rs.getBigDecimal("total_balance"))
                        .createdAt(toInstant(rs.getTimestamp("created_at")))
                        .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                        .build(),
                chain, assetSymbol, accountId);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code listLedgerBalances} 对应的数据，供调用方读取当前状态。
     */
    public List<LedgerBalanceRecord> listLedgerBalances() {
        return jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, available_balance, locked_balance, total_balance,
                               created_at, updated_at
                        from ledger_balance
                        order by chain, asset_symbol, account_id
                        """,
                (rs, rowNum) -> LedgerBalanceRecord.builder()
                        .id(rs.getLong("id"))
                        .chain(rs.getString("chain"))
                        .assetSymbol(rs.getString("asset_symbol"))
                        .accountId(rs.getString("account_id"))
                        .availableBalance(rs.getBigDecimal("available_balance"))
                        .lockedBalance(rs.getBigDecimal("locked_balance"))
                        .totalBalance(rs.getBigDecimal("total_balance"))
                        .createdAt(toInstant(rs.getTimestamp("created_at")))
                        .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                        .build());
    }
    /**
     * 执行 {@code sumLedgerTotalBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public BigDecimal sumLedgerTotalBalance(String chain, String assetSymbol) {
        BigDecimal balance = jdbcTemplate.queryForObject("""
                        select coalesce(sum(total_balance), 0)
                        from ledger_balance
                        where chain = ? and asset_symbol = ?
                        """,
                BigDecimal.class, chain, assetSymbol);
        return balance == null ? BigDecimal.ZERO : balance;
    }
    /**
     * 执行 {@code sumLedgerAvailableBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public BigDecimal sumLedgerAvailableBalance(String chain, String assetSymbol) {
        BigDecimal balance = jdbcTemplate.queryForObject("""
                        select coalesce(sum(available_balance), 0)
                        from ledger_balance
                        where chain = ? and asset_symbol = ?
                        """,
                BigDecimal.class, chain, assetSymbol);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    /**
     * 获取或查询 {@code findStaleBitcoinLikeSigningTransactions} 对应的数据，供调用方读取当前状态。
     */
    public List<WithdrawTransaction> findStaleBitcoinLikeSigningTransactions(
            AssetRuntimeMetadata currency, long staleSeconds) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.query("""
                        select id, tx_id, balance, signature, currency, status, create_date, update_date
                        from chain_signing_transaction
                        where chain = ?
                          and status = ?
                          and update_date < now() - (? * interval '1 second')
                        order by id
                        limit 100
                        """,
                (rs, rowNum) -> mapSigningTransaction(rs),
                chain, Constants.SIGNING, staleSeconds);
    }

    /**
     * 执行 {@code claimBitcoinLikeSigningRecovery} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean claimBitcoinLikeSigningRecovery(
            AssetRuntimeMetadata currency, int transactionId, long staleSeconds) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.update("""
                        update chain_signing_transaction
                        set update_date = now()
                        where chain = ? and id = ? and status = ?
                          and update_date < now() - (? * interval '1 second')
                        """,
                chain, transactionId, Constants.SIGNING, staleSeconds) == 1;
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
        return jdbcTemplate.update("""
                        insert into evm_token_transfer(chain, tx_hash, log_index, token_symbol, contract_address,
                                                       from_address, to_address, amount, block_height,
                                                       confirmations, status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_hash, log_index) do update set
                            confirmations = greatest(evm_token_transfer.confirmations, excluded.confirmations),
                            status = excluded.status,
                            updated_at = excluded.updated_at
                        """,
                event.chainType().name(), event.txId(), logIndex, event.assetSymbol(), event.tokenAddress(),
                event.fromAddress(), event.toAddress(), event.amount(), event.blockHeight(),
                event.confirmations(), status, toTs(now()), toTs(now()));
    }
    /**
     * 记录或保存 {@code recordTronTokenTransfer} 对应的数据，并遵守幂等和事务约束。
     */
    public int recordTronTokenTransfer(DepositEvent event, long logIndex, String status) {
        return jdbcTemplate.update("""
                        insert into tron_token_transfer(chain, tx_hash, log_index, token_symbol, contract_address,
                                                        from_address, to_address, amount, block_height,
                                                        confirmations, status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, tx_hash, log_index) do update set
                            confirmations = greatest(tron_token_transfer.confirmations, excluded.confirmations),
                            status = excluded.status,
                            updated_at = excluded.updated_at
                        """,
                event.chainType().name(), event.txId(), logIndex, event.assetSymbol(), event.tokenAddress(),
                event.fromAddress(), event.toAddress(), event.amount(), event.blockHeight(),
                event.confirmations(), status, toTs(now()), toTs(now()));
    }

    /**
     * 执行 {@code incrementLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public void incrementLedgerBalance(UUID tenantId, String chain, String assetSymbol,
                                       String accountId, BigDecimal amount) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        int updated = jdbcTemplate.update("""
                        insert into ledger_balance(tenant_id, chain, asset_symbol, account_id, available_balance, locked_balance,
                                                   total_balance, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 0, ?, ?, ?)
                        on conflict (chain, asset_symbol, account_id) do update set
                            available_balance = ledger_balance.available_balance + excluded.available_balance,
                            total_balance = ledger_balance.total_balance + excluded.total_balance,
                            tenant_id = coalesce(ledger_balance.tenant_id, excluded.tenant_id),
                            updated_at = excluded.updated_at
                        where ledger_balance.tenant_id is null
                           or ledger_balance.tenant_id = excluded.tenant_id
                        """,
                tenantId, chain, assetSymbol, accountId, amount, amount, toTs(now()), toTs(now()));
        if (updated != 1) {
            throw new IllegalStateException("ledger balance belongs to another tenant");
        }
    }
    /**
     * 执行 {@code debitLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean debitLedgerBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set available_balance = available_balance - ?,
                            total_balance = total_balance - ?,
                            updated_at = ?
                        where chain = ? and asset_symbol = ? and account_id = ?
                          and available_balance >= ?
                        """,
                amount, amount, toTs(now()), chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

    /**
     * 执行 {@code debitLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean debitLedgerBalance(UUID tenantId, String chain, String assetSymbol,
                                      String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set available_balance = available_balance - ?,
                            total_balance = total_balance - ?,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                          and available_balance >= ?
                        """,
                amount, amount, toTs(now()), tenantId, chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

        /**
     * 执行 {@code freezeLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean freezeLedgerBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set available_balance = available_balance - ?,
                            locked_balance = locked_balance + ?,
                            updated_at = ?
                        where chain = ? and asset_symbol = ? and account_id = ?
                          and available_balance >= ?
                        """,
                amount, amount, toTs(now()), chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

    /**
     * 执行 {@code freezeLedgerBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean freezeLedgerBalance(UUID tenantId, String chain, String assetSymbol,
                                       String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set available_balance = available_balance - ?,
                            locked_balance = locked_balance + ?,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                          and available_balance >= ?
                        """,
                amount, amount, toTs(now()), tenantId, chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

        /**
     * 删除或释放 {@code releaseLockedBalance} 对应的资源，并收敛相关业务状态。
     */
    public boolean releaseLockedBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set available_balance = available_balance + ?,
                            locked_balance = locked_balance - ?,
                            updated_at = ?
                        where chain = ? and asset_symbol = ? and account_id = ?
                          and locked_balance >= ?
                        """,
                amount, amount, toTs(now()), chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

    /**
     * 删除或释放 {@code releaseLockedBalance} 对应的资源，并收敛相关业务状态。
     */
    public boolean releaseLockedBalance(UUID tenantId, String chain, String assetSymbol,
                                        String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set available_balance = available_balance + ?,
                            locked_balance = locked_balance - ?,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                          and locked_balance >= ?
                        """,
                amount, amount, toTs(now()), tenantId, chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

        /**
     * 设置或更新 {@code settleLockedDebit} 对应的状态，并保持相关业务字段一致。
     */
    public boolean settleLockedDebit(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set locked_balance = locked_balance - ?,
                            total_balance = total_balance - ?,
                            updated_at = ?
                        where chain = ? and asset_symbol = ? and account_id = ?
                          and locked_balance >= ?
                        """,
                amount, amount, toTs(now()), chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

    /**
     * 设置或更新 {@code settleLockedDebit} 对应的状态，并保持相关业务字段一致。
     */
    public boolean settleLockedDebit(UUID tenantId, String chain, String assetSymbol,
                                     String accountId, BigDecimal amount) {
        int updated = jdbcTemplate.update("""
                        update ledger_balance
                        set locked_balance = locked_balance - ?,
                            total_balance = total_balance - ?,
                            updated_at = ?
                        where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                          and locked_balance >= ?
                        """,
                amount, amount, toTs(now()), tenantId, chain, assetSymbol, accountId, amount);
        return updated == 1;
    }

    /**
     * 获取或查询 {@code listCollectableLedgerBalances} 对应的数据，供调用方读取当前状态。
     */
    public List<CollectionCandidateRecord> listCollectableLedgerBalances(String chain,
                                                                         BigDecimal minimumAmount,
                                                                         int limit) {
        return jdbcTemplate.query("""
                        with collected as (
                            select tenant_id, chain, asset_symbol, lower(from_address) as from_address,
                                   coalesce(sum(amount), 0) amount
                            from collection_record
                            where chain = ?
                              and tenant_id is not null
                              and status <> 'FAILED'
                            group by tenant_id, chain, asset_symbol, lower(from_address)
                        ),
                        deposited as (
                            select tenant_id, chain, asset_symbol, lower(to_address) as to_address,
                                   coalesce(sum(amount), 0) amount
                            from deposit_record
                            where chain = ?
                              and tenant_id is not null
                              and credited = true
                            group by tenant_id, chain, asset_symbol, lower(to_address)
                        ),
                        pending as (
                            select distinct tenant_id, chain, asset_symbol, lower(from_address) as from_address
                            from collection_record
                            where chain = ?
                              and tenant_id is not null
                              and status in ('CREATED', 'RETRYING', 'SIGNING', 'SENT')
                        ),
                        candidates as (
                            select deposited.tenant_id, custody.id as custody_address_id,
                                   ca.chain, deposited.asset_symbol, ca.account_id, ca.address, ca.owner_address,
                                   ca.user_id, ca.biz, ca.address_index, ca.wallet_role,
                                   greatest(deposited.amount - coalesce(collected.amount, 0), 0) as amount,
                                   greatest(coalesce(a.min_transfer, 0), ?) as minimum_amount
                            from deposited
                            join chain_address ca
                              on ca.tenant_id = deposited.tenant_id
                             and ca.chain = deposited.chain
                             and lower(ca.address) = deposited.to_address
                             and ca.enabled = true
                             and ca.wallet_role = 'DEPOSIT'
                             and ca.user_id <> ?
                            join chain_asset native_asset
                              on native_asset.chain = ca.chain
                             and native_asset.symbol = ca.asset_symbol
                             and native_asset.native_asset = true
                             and native_asset.active = true
                            join custody_address custody
                              on custody.tenant_id = deposited.tenant_id
                             and custody.chain_address_id = ca.id
                             and custody.status = 'ACTIVE'
                            join chain_asset a
                              on a.chain = deposited.chain
                             and a.symbol = deposited.asset_symbol
                             and a.active = true
                            left join collected
                              on collected.tenant_id = deposited.tenant_id
                             and collected.chain = ca.chain
                             and collected.asset_symbol = deposited.asset_symbol
                             and collected.from_address = lower(ca.address)
                            left join pending
                              on pending.tenant_id = deposited.tenant_id
                             and pending.chain = ca.chain
                             and pending.asset_symbol = deposited.asset_symbol
                             and pending.from_address = lower(ca.address)
                            where deposited.chain = ?
                              and pending.from_address is null
                        ),
                        positive_candidates as (
                            select tenant_id, custody_address_id, chain, asset_symbol, account_id, address, owner_address,
                                   user_id, biz, address_index, wallet_role, amount, minimum_amount
                            from candidates
                            where amount > 0
                              and amount >= minimum_amount
                        )
                        select tenant_id, custody_address_id, chain, asset_symbol, account_id, address, owner_address,
                               user_id, biz, address_index, wallet_role, amount
                        from positive_candidates
                        order by amount desc, address_index
                        limit ?
                        """,
                (rs, rowNum) -> CollectionCandidateRecord.builder()
                        .tenantId(rs.getObject("tenant_id", UUID.class))
                        .custodyAddressId(rs.getObject("custody_address_id", UUID.class))
                        .chain(rs.getString("chain"))
                        .assetSymbol(rs.getString("asset_symbol"))
                        .accountId(rs.getString("account_id"))
                        .address(rs.getString("address"))
                        .ownerAddress(rs.getString("owner_address"))
                        .userId(rs.getLong("user_id"))
                        .biz(rs.getInt("biz"))
                        .addressIndex(rs.getLong("address_index"))
                        .walletRole(rs.getString("wallet_role"))
                        .amount(rs.getBigDecimal("amount"))
                        .build(),
                chain, chain, chain, minimumAmount, HotWalletRules.DEFAULT_HOT_USER_ID, chain, limit);
    }
    /**
     * 获取或查询 {@code findActiveTenantCollectionAddress} 对应的数据，供调用方读取当前状态。
     */
    public Optional<String> findActiveTenantCollectionAddress(UUID tenantId, String chain) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.queryForList("""
                        select a.address
                          from custody_gas_account g
                          join custody_address a
                            on a.tenant_id = g.tenant_id
                           and a.id = g.custody_address_id
                         where g.tenant_id = ? and g.chain = ?
                           and g.status = 'ACTIVE' and a.status = 'ACTIVE'
                        """, String.class, tenantId, chain)
                .stream().findFirst();
    }
    /**
     * 判断 {@code isEvm7702CollectionActive} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702CollectionActive(String chain, String network) {
        Boolean active = jdbcTemplate.queryForObject("""
                select exists(select 1 from evm_7702_config
                               where chain = ? and network = ? and status = 'ACTIVE')
                """, Boolean.class, chain, network);
        return Boolean.TRUE.equals(active);
    }
    /**
     * 判断 {@code isEvm7702Managed} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702Managed(String chain, String network) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists(select 1 from evm_7702_config
                               where chain = ? and network = ?
                                 and status in ('ACTIVE', 'PAUSED'))
                """, Boolean.class, chain, network));
    }
    /**
     * 判断 {@code isEvm7702NativeCollectionActive} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702NativeCollectionActive(String chain, String network) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists(select 1 from evm_7702_config
                               where chain = ? and network = ? and status = 'ACTIVE'
                                 and native_collection_enabled = true)
                """, Boolean.class, chain, network));
    }
    /**
     * 判断 {@code isEvm7702BatchWithdrawalActive} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEvm7702BatchWithdrawalActive(String chain, String network) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists(select 1 from evm_7702_config
                               where chain = ? and network = ? and status = 'ACTIVE'
                                 and batch_withdrawal_enabled = true)
                """, Boolean.class, chain, network));
    }
    /**
     * 设置或更新 {@code updateScanHeight} 对应的状态，并保持相关业务字段一致。
     */
    public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        jdbcTemplate.update("""
                        insert into chain_scan_height(chain, scanner_name, best_height, safe_height, status,
                                                      created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        on conflict (chain, scanner_name) do update set
                            best_height = greatest(chain_scan_height.best_height, excluded.best_height),
                            safe_height = case
                                when excluded.best_height >= chain_scan_height.best_height
                                    then excluded.safe_height
                                else chain_scan_height.safe_height
                            end,
                            status = 'ACTIVE',
                            updated_at = excluded.updated_at
                        """,
                chain, scannerName, bestHeight, safeHeight, toTs(now()), toTs(now()));
    }
    /**
     * 获取或查询 {@code findScanSafeHeight} 对应的数据，供调用方读取当前状态。
     */
    public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
        List<Long> results = jdbcTemplate.queryForList("""
                        select safe_height from chain_scan_height
                        where chain = ? and scanner_name = ?
                        """, Long.class, chain, scannerName);
        return results.stream().findFirst();
    }
    /**
     * 获取或查询 {@code listCanonicalDepositBlockHeights} 对应的数据，供调用方读取当前状态。
     */
    public List<Long> listCanonicalDepositBlockHeights(String chain, long minimumHeight) {
        return jdbcTemplate.queryForList("""
                        select distinct block_height
                          from deposit_record
                         where chain = ? and block_height >= ? and credited = true
                           and canonical_status = 'CANONICAL' and block_hash is not null
                         order by block_height
                        """, Long.class, chain, minimumHeight);
    }
    /**
     * 获取或查询 {@code listActiveScanHeights} 对应的数据，供调用方读取当前状态。
     */
    public List<ChainScanHeightRecord> listActiveScanHeights() {
        return jdbcTemplate.query("""
                        select chain, scanner_name, best_height, safe_height, status, updated_at
                        from chain_scan_height
                        where status = 'ACTIVE'
                        order by chain, scanner_name
                        """,
                (rs, rowNum) -> ChainScanHeightRecord.builder()
                        .chain(rs.getString("chain"))
                        .scannerName(rs.getString("scanner_name"))
                        .bestHeight(rs.getLong("best_height"))
                        .safeHeight(rs.getLong("safe_height"))
                        .status(rs.getString("status"))
                        .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                        .build());
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
