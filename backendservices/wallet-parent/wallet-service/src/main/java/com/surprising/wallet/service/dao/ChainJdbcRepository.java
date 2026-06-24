package com.surprising.wallet.service.dao;

import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AptosTransactionRecord;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.EvmNonceRecord;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TronTransactionRecord;
import com.surprising.wallet.common.chain.SolanaTransactionRecord;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.common.chain.SuiTransactionRecord;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.utils.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ChainJdbcRepository {
    private final JdbcTemplate jdbcTemplate;

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

    public Optional<BitcoinLikeChainProfile> findBitcoinLikeProfile(String chain, String network) {
        List<BitcoinLikeChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled
                        from chain_profile
                        where chain = ? and network = ? and enabled = true
                        """,
                (rs, rowNum) -> BitcoinLikeChainProfile.builder()
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
                        .build(),
                chain, network);
        return results.stream().findFirst();
    }

    public Optional<AccountChainProfile> findAccountChainProfile(String chain, String network) {
        List<AccountChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled
                        from chain_profile
                        where chain = ? and network = ? and enabled = true
                        """,
                (rs, rowNum) -> AccountChainProfile.builder()
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
                        .build(),
                chain, network);
        return results.stream().findFirst();
    }

    public int upsertToken(TokenDefinition token) {
        return jdbcTemplate.update("""
                insert into token_registry(chain, symbol, contract_address, decimals, standard, native_asset, active,
                                           created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, symbol) do update set
                    contract_address = excluded.contract_address,
                    decimals = excluded.decimals,
                    standard = excluded.standard,
                    native_asset = excluded.native_asset,
                    active = excluded.active,
                    updated_at = excluded.updated_at
                """,
                token.getChain(), token.getSymbol(), token.getContractAddress(), token.getDecimals(),
                token.getStandard(), token.getNativeAsset(), token.getActive(), toTs(now()), toTs(now()));
    }

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

    public int recordEvmTransaction(EvmTransactionRecord tx) {
        return jdbcTemplate.update("""
                insert into evm_tx(chain, tx_hash, from_address, to_address, asset_symbol, contract_address,
                                   amount, fee, nonce, block_height, confirmations, status, raw_payload,
                                   created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    confirmations = excluded.confirmations,
                    status = excluded.status,
                    updated_at = excluded.updated_at
                """,
                tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getContractAddress(), tx.getAmount(), tx.getFee(), tx.getNonce(), tx.getBlockHeight(),
                tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }

    public int recordTronTransaction(TronTransactionRecord tx) {
        return jdbcTemplate.update("""
                insert into tron_tx(chain, tx_hash, from_address, to_address, asset_symbol, contract_address,
                                    amount, fee, block_height, confirmations, status, raw_payload,
                                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash) do update set
                    confirmations = excluded.confirmations,
                    status = excluded.status,
                    updated_at = excluded.updated_at
                """,
                tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getContractAddress(), tx.getAmount(), tx.getFee(), tx.getBlockHeight(), tx.getConfirmations(),
                tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }

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

    public Set<String> listEnabledHotWalletAddresses(String chain) {
        return jdbcTemplate.queryForList("""
                        select lower(address) from hot_wallet_address
                        where chain = ? and enabled = true
                        """, String.class, chain)
                .stream()
                .collect(Collectors.toSet());
    }

    public int upsertChainAddress(ChainAddressRecord address) {
        return jdbcTemplate.update("""
                        insert into chain_address(
                            chain, asset_symbol, account_id, user_id, biz, address_index, address,
                            owner_address, derivation_path, wallet_role, enabled, created_at, updated_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, asset_symbol, user_id, biz, address_index, wallet_role)
                        do update set
                            account_id = excluded.account_id,
                            address = excluded.address,
                            owner_address = excluded.owner_address,
                            derivation_path = excluded.derivation_path,
                            enabled = excluded.enabled,
                            updated_at = excluded.updated_at
                        """,
                address.getChain(), address.getAssetSymbol(), address.getAccountId(), address.getUserId(),
                address.getBiz(), address.getAddressIndex(), address.getAddress(), address.getOwnerAddress(),
                address.getDerivationPath(), address.getWalletRole(), address.getEnabled(),
                toTs(now()), toTs(now()));
    }

    public Optional<ChainAddressRecord> findChainAddress(String chain, String assetSymbol, long userId,
                                                         int biz, long addressIndex, String walletRole) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and asset_symbol = ? and user_id = ? and biz = ?
                          and address_index = ? and wallet_role = ?
                        """,
                (rs, rowNum) -> mapChainAddress(rs),
                chain, assetSymbol, userId, biz, addressIndex, walletRole);
        return results.stream().findFirst();
    }

    public List<ChainAddressRecord> listChainAddresses(String chain, String assetSymbol) {
        return jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and asset_symbol = ? and enabled = true
                        order by id
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain, assetSymbol);
    }

    public List<ChainAddressRecord> listChainAddresses(String chain) {
        return jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and enabled = true
                        order by id
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain);
    }

    public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String address) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and address = ? and enabled = true
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain, address);
        return results.stream().findFirst();
    }

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
                            confirmations = greatest(ton_transaction.confirmations, excluded.confirmations),
                            status = excluded.status,
                            raw_payload = excluded.raw_payload,
                            updated_at = excluded.updated_at
                        """,
                tx.getChain(), tx.getTxHash(), tx.getFromAddress(), tx.getToAddress(), tx.getAssetSymbol(),
                tx.getJettonMaster(), tx.getAmount(), tx.getFeeNano(), tx.getLogicalTime(),
                tx.getConfirmations(), tx.getStatus(), tx.getRawPayload(), toTs(now()), toTs(now()));
    }

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
     * Records a deposit and credits ledger_balance once. The credited=false predicate is the
     * idempotency guard: repeated scans update confirmations but cannot credit the same tx twice.
     */
    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCreditDeposit(DepositEvent event, int requiredConfirmations) {
        return recordAndCreditDeposit(event, 0L, requiredConfirmations);
    }

    public boolean recordAndCreditDeposit(DepositEvent event, long logIndex, int requiredConfirmations) {
        return recordAndCreditDeposit(event, logIndex, requiredConfirmations, event.toAddress().toLowerCase());
    }

    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCreditDeposit(DepositEvent event, long logIndex, int requiredConfirmations,
                                          String accountId) {
        String chain = event.chainType().name();
        String status = event.confirmations() <= 0 ? "DETECTED"
                : event.confirmations() < requiredConfirmations ? "CONFIRMING" : "CONFIRMED";
        jdbcTemplate.update("""
                        insert into deposit_record(chain, asset_symbol, tx_hash, log_index, from_address, to_address,
                                                   contract_address, amount, block_height, confirmations, status,
                                                   credited, raw_payload, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?)
                        on conflict (chain, tx_hash, log_index) do update set
                            confirmations = greatest(deposit_record.confirmations, excluded.confirmations),
                            status = case when deposit_record.credited then 'CREDITED' else excluded.status end,
                            raw_payload = excluded.raw_payload,
                            updated_at = excluded.updated_at
                        """,
                chain, event.assetSymbol(), event.txId(), logIndex, event.fromAddress(), event.toAddress(),
                event.tokenAddress(), event.amount(), event.blockHeight(), event.confirmations(), status,
                event.rawPayload(), toTs(now()), toTs(now()));

        if (event.confirmations() < requiredConfirmations) {
            return false;
        }

        int credited = jdbcTemplate.update("""
                        update deposit_record
                        set credited = true, credited_at = ?, status = 'CREDITED', updated_at = ?
                        where chain = ? and tx_hash = ? and log_index = ? and credited = false
                        """,
                toTs(now()), toTs(now()), chain, event.txId(), logIndex);
        if (credited == 1) {
            incrementLedgerBalance(chain, event.assetSymbol(), accountId, event.amount());
            return true;
        }
        return false;
    }

    public void upsertUtxo(String chain, String assetSymbol, String txHash, int vout, String address,
                           BigDecimal amount, long blockHeight, int confirmations, boolean credited) {
        jdbcTemplate.update("""
                        insert into utxo_record(chain, asset_symbol, tx_hash, vout, address, amount, block_height,
                                                confirmations, state, credited, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?, ?)
                        on conflict (chain, tx_hash, vout) do update set
                            address = excluded.address,
                            amount = excluded.amount,
                            block_height = excluded.block_height,
                            confirmations = greatest(utxo_record.confirmations, excluded.confirmations),
                            state = case
                                when utxo_record.state in ('LOCKED', 'SPENT') then utxo_record.state
                                else excluded.state
                            end,
                            credited = utxo_record.credited or excluded.credited,
                            updated_at = excluded.updated_at
                        """,
                chain, assetSymbol, txHash, vout, address, amount, blockHeight, confirmations, credited,
                toTs(now()), toTs(now()));
    }

    public int markUtxoCredited(String chain, String txHash, int vout) {
        return jdbcTemplate.update("""
                        update utxo_record
                        set credited = true, updated_at = ?
                        where chain = ? and tx_hash = ? and vout = ? and credited = false
                        """,
                toTs(now()), chain, txHash, vout);
    }

    public int lockUtxo(String chain, String txHash, int vout, String lockRef) {
        return jdbcTemplate.update("""
                        update utxo_record
                        set state = 'LOCKED', lock_ref = ?, updated_at = ?
                        where chain = ? and tx_hash = ? and vout = ? and state = 'AVAILABLE'
                        """,
                lockRef, toTs(now()), chain, txHash, vout);
    }

    public int releaseUtxos(String chain, String lockRef) {
        return jdbcTemplate.update("""
                        update utxo_record
                        set state = 'AVAILABLE', lock_ref = null, updated_at = ?
                        where chain = ? and lock_ref = ? and state = 'LOCKED'
                        """,
                toTs(now()), chain, lockRef);
    }

    public int markUtxosSpent(String chain, String lockRef, String spentTxHash) {
        return jdbcTemplate.update("""
                        update utxo_record
                        set state = 'SPENT', spent_tx_hash = ?, updated_at = ?
                        where chain = ? and lock_ref = ? and state = 'LOCKED'
                        """,
                spentTxHash, toTs(now()), chain, lockRef);
    }

    public int updateUtxoConfirmations(String chain, String txHash, int vout, int confirmations) {
        return jdbcTemplate.update("""
                        update utxo_record
                        set confirmations = greatest(confirmations, ?), updated_at = ?
                        where chain = ? and tx_hash = ? and vout = ?
                        """,
                confirmations, toTs(now()), chain, txHash, vout);
    }

    public List<UtxoTransaction> listSpendableUtxos(String chain, String assetSymbol,
                                                    long requiredConfirmations,
                                                    int limit, int offset) {
        return jdbcTemplate.query("""
                        select ur.id, ur.tx_hash, ur.vout, ur.address, ur.amount, ur.block_height,
                               ur.confirmations, ur.credited, ur.created_at, ur.updated_at,
                               (
                                   select cp.runtime_currency_id
                                   from chain_profile cp
                                   where cp.chain = ur.chain
                                     and cp.native_symbol = ur.asset_symbol
                                     and cp.enabled = true
                                   order by case cp.network
                                       when 'regtest' then 0
                                       when 'testnet' then 1
                                       when 'testnet3' then 1
                                       else 2
                                   end
                                   limit 1
                               ) as runtime_currency_id
                        from utxo_record ur
                        where ur.chain = ?
                          and ur.asset_symbol = ?
                          and ur.state = 'AVAILABLE'
                          and ur.confirmations >= ?
                        order by ur.id
                        limit ? offset ?
                        """,
                (rs, rowNum) -> mapUtxoRecord(rs, chain),
                chain, assetSymbol, requiredConfirmations, limit, offset);
    }

    public List<UtxoTransaction> listAvailableUtxosBelowConfirmations(String chain, String assetSymbol,
                                                                      long maxConfirmations,
                                                                      int limit, int offset) {
        return jdbcTemplate.query("""
                        select ur.id, ur.tx_hash, ur.vout, ur.address, ur.amount, ur.block_height,
                               ur.confirmations, ur.credited, ur.created_at, ur.updated_at,
                               (
                                   select cp.runtime_currency_id
                                   from chain_profile cp
                                   where cp.chain = ur.chain
                                     and cp.native_symbol = ur.asset_symbol
                                     and cp.enabled = true
                                   order by case cp.network
                                       when 'regtest' then 0
                                       when 'testnet' then 1
                                       when 'testnet3' then 1
                                       else 2
                                   end
                                   limit 1
                               ) as runtime_currency_id
                        from utxo_record ur
                        where ur.chain = ?
                          and ur.asset_symbol = ?
                          and ur.state = 'AVAILABLE'
                          and ur.confirmations < ?
                        order by ur.id
                        limit ? offset ?
                        """,
                (rs, rowNum) -> mapUtxoRecord(rs, chain),
                chain, assetSymbol, maxConfirmations, limit, offset);
    }

    public BigDecimal sumAvailableUtxoAmount(String chain, String assetSymbol) {
        BigDecimal balance = jdbcTemplate.queryForObject("""
                        select coalesce(sum(amount), 0)
                        from utxo_record
                        where chain = ?
                          and asset_symbol = ?
                          and state = 'AVAILABLE'
                        """,
                BigDecimal.class, chain, assetSymbol);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    public List<UtxoTransaction> listUtxosByAddress(String chain, String address, int limit) {
        return jdbcTemplate.query("""
                        select ur.id, ur.tx_hash, ur.vout, ur.address, ur.amount, ur.block_height,
                               ur.confirmations, ur.credited, ur.created_at, ur.updated_at,
                               (
                                   select cp.runtime_currency_id
                                   from chain_profile cp
                                   where cp.chain = ur.chain
                                     and cp.native_symbol = ur.asset_symbol
                                     and cp.enabled = true
                                   order by case cp.network
                                       when 'regtest' then 0
                                       when 'testnet' then 1
                                       when 'testnet3' then 1
                                       else 2
                                   end
                                   limit 1
                               ) as runtime_currency_id
                        from utxo_record ur
                        where ur.chain = ?
                          and ur.address = ?
                        order by ur.id desc
                        limit ?
                        """,
                (rs, rowNum) -> mapUtxoRecord(rs, chain),
                chain, address, limit);
    }

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

    private UtxoTransaction mapUtxoRecord(java.sql.ResultSet rs, String chain) throws java.sql.SQLException {
        int runtimeCurrencyId = rs.getInt("runtime_currency_id");
        if (rs.wasNull()) {
            throw new IllegalStateException(
                    "missing enabled chain_profile.runtime_currency_id for unified UTXO chain " + chain);
        }
        return UtxoTransaction.builder()
                .id(rs.getLong("id"))
                .txId(rs.getString("tx_hash"))
                .seq((short) rs.getInt("vout"))
                .address(rs.getString("address"))
                .balance(rs.getBigDecimal("amount"))
                .blockHeight(rs.getLong("block_height"))
                .confirmNum(rs.getLong("confirmations"))
                .spent((byte) 0)
                .spentTxId(Constants.UNSPENT_TX_ID)
                .currency(runtimeCurrencyId)
                .status((byte) Constants.WAITING)
                .credited(rs.getBoolean("credited"))
                .createDate(rs.getTimestamp("created_at"))
                .updateDate(rs.getTimestamp("updated_at"))
                .build();
    }

    public int createWithdrawalOrder(String orderNo, long userId, String chain, String assetSymbol,
                                     String toAddress, BigDecimal amount, BigDecimal fee) {
        return jdbcTemplate.update("""
                        insert into withdrawal_order(order_no, user_id, chain, asset_symbol, to_address,
                                                     amount, fee, status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                        on conflict (chain, order_no) do nothing
                        """,
                orderNo, userId, chain, assetSymbol, toAddress, amount, fee, toTs(now()), toTs(now()));
    }

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

    public int markWithdrawalConfirmed(String chain, String orderNo, String txHash) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'CONFIRMED', tx_hash = ?, error_message = null, updated_at = ?
                        where chain = ? and order_no = ? and status <> 'CONFIRMED'
                        """,
                txHash, toTs(now()), chain, orderNo);
    }

    public Optional<String> findWithdrawalStatus(String chain, String orderNo) {
        List<String> results = jdbcTemplate.queryForList("""
                        select status from withdrawal_order where chain = ? and order_no = ?
                        """, String.class, chain, orderNo);
        return results.stream().findFirst();
    }

    public Optional<String> findWithdrawalTxHash(String chain, String orderNo) {
        List<String> results = jdbcTemplate.queryForList("""
                        select tx_hash from withdrawal_order
                        where chain = ? and order_no = ? and tx_hash is not null
                        """, String.class, chain, orderNo);
        return results.stream().findFirst();
    }

    public int createCollectionRecord(String collectionNo, String chain, String assetSymbol,
                                      String fromAddress, String toAddress, BigDecimal amount, BigDecimal fee,
                                      String rawPayload) {
        return jdbcTemplate.update("""
                        insert into collection_record(collection_no, chain, asset_symbol, from_address, to_address,
                                                      amount, fee, status, raw_payload, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?)
                        on conflict (chain, collection_no) do nothing
                        """,
                collectionNo, chain, assetSymbol, fromAddress, toAddress, amount, fee, rawPayload,
                toTs(now()), toTs(now()));
    }

    public int updateCollectionStatus(String chain, String collectionNo, String status, String txHash,
                                      String errorMessage, String rawPayload) {
        return jdbcTemplate.update("""
                        update collection_record
                        set status = ?,
                            tx_hash = coalesce(?, tx_hash),
                            error_message = ?,
                            raw_payload = coalesce(?, raw_payload),
                            updated_at = ?
                        where chain = ? and collection_no = ?
                        """,
                status, txHash, errorMessage, rawPayload, toTs(now()), chain, collectionNo);
    }

    /**
     * Atomically claims a new or explicitly retried collection before a signing
     * transaction is created. FAILED is intentionally terminal until an operator
     * or recovery workflow moves it to RETRYING.
     */
    public int claimCollectionSigning(String chain, String collectionNo, String rawPayload) {
        return jdbcTemplate.update("""
                        update collection_record
                        set status = 'SIGNING',
                            error_message = null,
                            raw_payload = coalesce(?, raw_payload),
                            updated_at = ?
                        where chain = ? and collection_no = ?
                          and status in ('CREATED', 'RETRYING')
                        """,
                rawPayload, toTs(now()), chain, collectionNo);
    }

    public int markCollectionConfirmed(String chain, String collectionNo, String txHash) {
        return jdbcTemplate.update("""
                        update collection_record
                        set status = 'CONFIRMED', tx_hash = ?, error_message = null, updated_at = ?
                        where chain = ? and collection_no = ? and status <> 'CONFIRMED'
                        """,
                txHash, toTs(now()), chain, collectionNo);
    }

    public Optional<String> findCollectionStatus(String chain, String collectionNo) {
        List<String> results = jdbcTemplate.queryForList("""
                        select status from collection_record where chain = ? and collection_no = ?
                        """, String.class, chain, collectionNo);
        return results.stream().findFirst();
    }

    public Optional<String> findCollectionTxHash(String chain, String collectionNo) {
        List<String> results = jdbcTemplate.queryForList("""
                        select tx_hash from collection_record
                        where chain = ? and collection_no = ? and tx_hash is not null
                        """, String.class, chain, collectionNo);
        return results.stream().findFirst();
    }

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

    public List<WithdrawTransaction> findStaleBitcoinLikeSigningTransactions(
            CurrencyEnum currency, long staleSeconds) {
        String table = signingTransactionTable(currency);
        return jdbcTemplate.query("""
                        select id, tx_id, balance, signature, currency, status, create_date, update_date
                        from %s
                        where status = 1
                          and update_date < now() - (? * interval '1 second')
                        order by id
                        limit 100
                        """.formatted(table),
                (rs, rowNum) -> WithdrawTransaction.builder()
                        .id(rs.getInt("id"))
                        .txId(rs.getString("tx_id"))
                        .balance(rs.getBigDecimal("balance"))
                        .signature(rs.getString("signature"))
                        .currency(rs.getInt("currency"))
                        .status(rs.getShort("status"))
                        .createDate(rs.getTimestamp("create_date"))
                        .updateDate(rs.getTimestamp("update_date"))
                        .build(),
                staleSeconds);
    }

    public boolean claimBitcoinLikeSigningRecovery(
            CurrencyEnum currency, int transactionId, long staleSeconds) {
        String table = signingTransactionTable(currency);
        return jdbcTemplate.update("""
                        update %s
                        set update_date = now()
                        where id = ? and status = 1
                          and update_date < now() - (? * interval '1 second')
                        """.formatted(table),
                transactionId, staleSeconds) == 1;
    }

    private String signingTransactionTable(CurrencyEnum currency) {
        return switch (currency) {
            case LTC -> "ltc_withdraw_transaction";
            case DOGE -> "doge_withdraw_transaction";
            case BCH -> "bch_withdraw_transaction";
            default -> throw new IllegalArgumentException(
                    "unsupported signing recovery currency " + currency);
        };
    }

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

    public void incrementLedgerBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
        jdbcTemplate.update("""
                        insert into ledger_balance(chain, asset_symbol, account_id, available_balance, locked_balance,
                                                   total_balance, created_at, updated_at)
                        values (?, ?, ?, ?, 0, ?, ?, ?)
                        on conflict (chain, asset_symbol, account_id) do update set
                            available_balance = ledger_balance.available_balance + excluded.available_balance,
                            total_balance = ledger_balance.total_balance + excluded.total_balance,
                            updated_at = excluded.updated_at
                        """,
                chain, assetSymbol, accountId, amount, amount, toTs(now()), toTs(now()));
    }

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
     * Freezes spendable balance before a withdrawal or collection transaction is signed.
     * The guarded update prevents over-spend and makes repeated freeze attempts visible.
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
     * Releases a previous freeze when signing or broadcasting fails before a confirmed debit.
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
     * Finalizes a frozen debit after the chain transaction is confirmed.
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

    public Optional<Long> findScanSafeHeight(String chain, String scannerName) {
        List<Long> results = jdbcTemplate.queryForList("""
                        select safe_height from chain_scan_height
                        where chain = ? and scanner_name = ?
                        """, Long.class, chain, scannerName);
        return results.stream().findFirst();
    }

    public Optional<TokenDefinition> findToken(String chain, String symbol) {
        List<TokenDefinition> results = queryTokens("""
                select id, chain, symbol,
                       coalesce(contract_address, contract_address_base58, contract_address_hex) as contract_address,
                       decimals, coalesce(token_standard, standard) as standard,
                       false as native_asset, enabled as active
                from token_config where chain = ? and symbol = ? and enabled = true
                """, chain, symbol);
        if (results.isEmpty()) {
            results = queryTokens("""
                    select id, chain, symbol, contract_address, decimals, standard, native_asset, active
                    from token_registry where chain = ? and symbol = ? and active = true
                    """, chain, symbol);
        }
        return results.stream().findFirst();
    }

    public Optional<TokenDefinition> findTokenByContract(String chain, String contractAddress) {
        List<TokenDefinition> results = queryTokens("""
                select id, chain, symbol,
                       coalesce(contract_address, contract_address_base58, contract_address_hex) as contract_address,
                       decimals, coalesce(token_standard, standard) as standard,
                       false as native_asset, enabled as active
                from token_config
                where chain = ? and enabled = true
                  and (lower(contract_address) = lower(?)
                       or lower(contract_address_base58) = lower(?)
                       or lower(contract_address_hex) = lower(?))
                """, chain, contractAddress, contractAddress, contractAddress);
        if (results.isEmpty()) {
            results = queryTokens("""
                    select id, chain, symbol, contract_address, decimals, standard, native_asset, active
                    from token_registry where chain = ? and lower(contract_address) = lower(?) and active = true
                    """, chain, contractAddress);
        }
        return results.stream().findFirst();
    }

    public List<TokenDefinition> listTokens(String chain) {
        List<TokenDefinition> results = queryTokens("""
                select id, chain, symbol,
                       coalesce(contract_address, contract_address_base58, contract_address_hex) as contract_address,
                       decimals, coalesce(token_standard, standard) as standard,
                       false as native_asset, enabled as active
                from token_config where chain = ? and enabled = true order by symbol
                """, chain);
        if (results.isEmpty()) {
            results = queryTokens("""
                    select id, chain, symbol, contract_address, decimals, standard, native_asset, active
                    from token_registry where chain = ? and active = true order by symbol
                    """, chain);
        }
        return results;
    }

    public Optional<ChainAsset> findAsset(String chain, String symbol) {
        List<ChainAsset> results = jdbcTemplate.query("""
                        select id, chain, symbol, asset_kind, contract_address, decimals, native_asset, active,
                               min_transfer, min_withdraw, created_at, updated_at
                        from chain_asset where chain = ? and symbol = ?
                        """,
                (rs, rowNum) -> ChainAsset.builder()
                        .id(rs.getLong("id"))
                        .chain(rs.getString("chain"))
                        .symbol(rs.getString("symbol"))
                        .assetKind(rs.getString("asset_kind"))
                        .contractAddress(rs.getString("contract_address"))
                        .decimals(rs.getObject("decimals", Integer.class))
                        .nativeAsset(rs.getBoolean("native_asset"))
                        .active(rs.getBoolean("active"))
                        .minTransfer(rs.getBigDecimal("min_transfer"))
                        .minWithdraw(rs.getBigDecimal("min_withdraw"))
                        .createdAt(toInstant(rs.getTimestamp("created_at")))
                        .updatedAt(toInstant(rs.getTimestamp("updated_at")))
                        .build(),
                chain, symbol);
        return results.stream().findFirst();
    }

    private static Timestamp toTs(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant nowOr(Instant instant) {
        return instant == null ? now() : instant;
    }

    private static Instant now() {
        return Instant.now();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private List<TokenDefinition> queryTokens(String sql, Object... args) {
        try {
            return jdbcTemplate.query(sql,
                    (rs, rowNum) -> TokenDefinition.builder()
                            .id(rs.getLong("id"))
                            .chain(rs.getString("chain"))
                            .symbol(rs.getString("symbol"))
                            .contractAddress(rs.getString("contract_address"))
                            .decimals(rs.getInt("decimals"))
                            .standard(rs.getString("standard"))
                            .nativeAsset(rs.getBoolean("native_asset"))
                            .active(rs.getBoolean("active"))
                            .build(),
                    args);
        } catch (DataAccessException ignored) {
            return List.of();
        }
    }

    private static ChainAddressRecord mapChainAddress(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ChainAddressRecord.builder()
                .id(rs.getLong("id"))
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
