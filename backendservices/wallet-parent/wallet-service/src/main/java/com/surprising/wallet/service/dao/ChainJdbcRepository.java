package com.surprising.wallet.service.dao;

import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AptosTransactionRecord;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainCollectionRecord;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.EvmNonceRecord;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.common.chain.ChainScanHeightRecord;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TronTransactionRecord;
import com.surprising.wallet.common.chain.SolanaTransactionRecord;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.common.chain.SuiTransactionRecord;
import com.surprising.wallet.common.chain.WalletPublicKey;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.utils.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        where chain = ? and network = ? and enabled = true
                        """,
                (rs, rowNum) -> mapBitcoinLikeProfile(rs),
                chain, network);
        return results.stream().findFirst();
    }

    public Optional<AccountChainProfile> findAccountChainProfile(String chain, String network) {
        List<AccountChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        where chain = ? and network = ? and enabled = true
                        """,
                (rs, rowNum) -> mapAccountProfile(rs),
                chain, network);
        return results.stream().findFirst();
    }

    public Optional<AccountChainProfile> findProfileByRuntimeCurrencyId(int runtimeCurrencyId) {
        List<AccountChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, scan_batch_size, scan_enabled, withdraw_enabled,
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

    public Optional<AccountChainProfile> findProfileByChain(String chain) {
        List<AccountChainProfile> results = jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, scan_batch_size, scan_enabled, withdraw_enabled,
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

    @Transactional(rollbackFor = Throwable.class)
    public long reserveEvmNonce(String chain, String address, long chainNonce) {
        jdbcTemplate.update("""
                        insert into evm_nonce(chain, address, chain_nonce, reserved_nonce, status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        on conflict (chain, address) do nothing
                        """,
                chain, address, chainNonce, chainNonce, toTs(now()), toTs(now()));
        Long next = jdbcTemplate.queryForObject("""
                        select reserved_nonce from evm_nonce
                        where chain = ? and address = ?
                        for update
                        """, Long.class, chain, address);
        long reserved = Math.max(chainNonce, next == null ? chainNonce : next);
        jdbcTemplate.update("""
                        update evm_nonce
                        set chain_nonce = greatest(chain_nonce, ?),
                            reserved_nonce = ?,
                            status = 'ACTIVE',
                            updated_at = ?
                        where chain = ? and address = ?
                        """,
                chainNonce, reserved + 1, toTs(now()), chain, address);
        return reserved;
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
        return listEnabledChainScanAddresses(chain);
    }

    public Set<String> listEnabledChainScanAddresses(String chain) {
        return jdbcTemplate.queryForList("""
                        select lower(address) from chain_address
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

    public List<ChainAddressRecord> listDefaultHotAddressCandidates(String chain, String assetSymbol) {
        return jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
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

    public List<ChainAddressRecord> listReservedHotNamespaceAddresses(String chain) {
        return jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
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

    public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String assetSymbol, String address) {
        List<ChainAddressRecord> results = jdbcTemplate.query("""
                        select id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                               owner_address, derivation_path, wallet_role, enabled
                        from chain_address
                        where chain = ? and asset_symbol = ? and address = ? and enabled = true
                        """,
                (rs, rowNum) -> mapChainAddress(rs), chain, assetSymbol, address);
        return results.stream().findFirst();
    }

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
                        where chain = ? and tx_hash = ? and vout = ?
                          and (state = 'AVAILABLE' or (state = 'LOCKED' and lock_ref = ?))
                        """,
                lockRef, toTs(now()), chain, txHash, vout, lockRef);
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
        return createWithdrawalOrder(orderNo, userId, chain, assetSymbol, null, null, toAddress, amount, fee);
    }

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

    public List<WithdrawalOrderRecord> listWithdrawalsForSigning(String chain, String assetSymbol, int limit) {
        return jdbcTemplate.query("""
                        select id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where chain = ? and asset_symbol = ? and status in ('FROZEN', 'RETRYING')
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, assetSymbol, limit);
    }

    public List<WithdrawalOrderRecord> listWithdrawalsForSigning(String chain, int limit) {
        return jdbcTemplate.query("""
                        select id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where chain = ? and status in ('FROZEN', 'RETRYING')
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, limit);
    }

    public List<WithdrawalOrderRecord> listWithdrawalsByStatus(String chain, String status, int limit) {
        return jdbcTemplate.query("""
                        select id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where chain = ? and status = ?
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, status, limit);
    }

    public int claimWithdrawalSigning(String chain, String orderNo, String fromAddress) {
        return jdbcTemplate.update("""
                        update withdrawal_order
                        set status = 'SIGNING',
                            from_address = coalesce(?, from_address),
                            error_message = null,
                            updated_at = ?
                        where chain = ? and order_no = ? and status in ('FROZEN', 'RETRYING')
                        """,
                fromAddress, toTs(now()), chain, orderNo);
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

    public Optional<WithdrawalOrderRecord> findWithdrawalOrder(String chain, String orderNo) {
        List<WithdrawalOrderRecord> results = jdbcTemplate.query("""
                        select id, order_no, user_id, chain, asset_symbol, from_address, debit_account_id, to_address,
                               amount, fee, tx_hash, status, error_message, created_at, updated_at
                        from withdrawal_order
                        where chain = ? and order_no = ?
                        """,
                (rs, rowNum) -> mapWithdrawalOrder(rs),
                chain, orderNo);
        return results.stream().findFirst();
    }

    private WithdrawalOrderRecord mapWithdrawalOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return WithdrawalOrderRecord.builder()
                .id(rs.getLong("id"))
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

    public List<ChainCollectionRecord> listCollectionsForSigning(String chain, int limit) {
        return jdbcTemplate.query("""
                        select id, collection_no, chain, asset_symbol, from_address, to_address,
                               amount, fee, tx_hash, status, error_message, raw_payload, created_at, updated_at
                        from collection_record
                        where chain = ? and status in ('CREATED', 'RETRYING')
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapCollectionRecord(rs),
                chain, limit);
    }

    public List<ChainCollectionRecord> listCollectionsByStatus(String chain, String status, int limit) {
        return jdbcTemplate.query("""
                        select id, collection_no, chain, asset_symbol, from_address, to_address,
                               amount, fee, tx_hash, status, error_message, raw_payload, created_at, updated_at
                        from collection_record
                        where chain = ? and status = ?
                        order by id
                        limit ?
                        """,
                (rs, rowNum) -> mapCollectionRecord(rs),
                chain, status, limit);
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

    private ChainCollectionRecord mapCollectionRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ChainCollectionRecord.builder()
                .id(rs.getLong("id"))
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

    public WithdrawTransaction createBitcoinLikeSigningTransaction(
            RuntimeAsset currency,
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

    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransaction(
            RuntimeAsset currency, String businessType, String businessNo) {
        return findBitcoinLikeSigningTransaction(
                currency.getName().toUpperCase(java.util.Locale.ROOT), businessType, businessNo);
    }

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

    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransactionById(
            RuntimeAsset currency, int transactionId) {
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

    public Optional<WithdrawTransaction> findBitcoinLikeSigningTransactionByTxId(
            RuntimeAsset currency, String txId) {
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

    public boolean bitcoinLikeSigningTransactionExists(RuntimeAsset currency, String txId) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from chain_signing_transaction
                            where chain = ? and tx_id = ?
                        )
                        """, Boolean.class, chain, txId);
        return Boolean.TRUE.equals(exists);
    }

    public int updateBitcoinLikeSigningTransaction(RuntimeAsset currency, WithdrawTransaction transaction) {
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

    public int markBitcoinLikeSigningError(RuntimeAsset currency, int transactionId, String errorMessage) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.update("""
                        update chain_signing_transaction
                        set error_message = ?,
                            update_date = ?
                        where chain = ? and id = ?
                        """,
                errorMessage, toTs(now()), chain, transactionId);
    }

    public List<WithdrawTransaction> findSentBitcoinLikeSigningTransactions(RuntimeAsset currency) {
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

    public BigDecimal sumLedgerTotalBalance(String chain, String assetSymbol) {
        BigDecimal balance = jdbcTemplate.queryForObject("""
                        select coalesce(sum(total_balance), 0)
                        from ledger_balance
                        where chain = ? and asset_symbol = ?
                        """,
                BigDecimal.class, chain, assetSymbol);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    public BigDecimal sumLedgerAvailableBalance(String chain, String assetSymbol) {
        BigDecimal balance = jdbcTemplate.queryForObject("""
                        select coalesce(sum(available_balance), 0)
                        from ledger_balance
                        where chain = ? and asset_symbol = ?
                        """,
                BigDecimal.class, chain, assetSymbol);
        return balance == null ? BigDecimal.ZERO : balance;
    }

    public List<WithdrawTransaction> findStaleBitcoinLikeSigningTransactions(
            RuntimeAsset currency, long staleSeconds) {
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

    public boolean claimBitcoinLikeSigningRecovery(
            RuntimeAsset currency, int transactionId, long staleSeconds) {
        String chain = currency.getName().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.update("""
                        update chain_signing_transaction
                        set update_date = now()
                        where chain = ? and id = ? and status = ?
                          and update_date < now() - (? * interval '1 second')
                        """,
                chain, transactionId, Constants.SIGNING, staleSeconds) == 1;
    }

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

    public List<CollectionCandidateRecord> listCollectableLedgerBalances(String chain,
                                                                         BigDecimal minimumAmount,
                                                                         int limit) {
        return jdbcTemplate.query("""
                        with collected as (
                            select chain, asset_symbol, lower(from_address) as from_address, coalesce(sum(amount), 0) amount
                            from collection_record
                            where chain = ?
                              and status <> 'FAILED'
                            group by chain, asset_symbol, lower(from_address)
                        ),
                        deposited as (
                            select chain, asset_symbol, lower(to_address) as to_address, coalesce(sum(amount), 0) amount
                            from deposit_record
                            where chain = ?
                              and credited = true
                            group by chain, asset_symbol, lower(to_address)
                        ),
                        candidates as (
                            select ca.chain, ca.asset_symbol, ca.account_id, ca.address, ca.owner_address,
                                   ca.user_id, ca.biz, ca.address_index, ca.wallet_role,
                                   greatest(deposited.amount - coalesce(collected.amount, 0), 0) as amount,
                                   greatest(coalesce(a.min_transfer, 0), ?) as minimum_amount
                            from deposited
                            join chain_address ca
                              on ca.chain = deposited.chain
                             and ca.asset_symbol = deposited.asset_symbol
                             and lower(ca.address) = deposited.to_address
                             and ca.enabled = true
                             and ca.wallet_role = 'DEPOSIT'
                             and ca.user_id <> ?
                            left join ledger_balance lb
                              on lb.chain = ca.chain
                             and lb.asset_symbol = ca.asset_symbol
                             and lower(lb.account_id) = lower(ca.account_id)
                            join chain_asset a
                              on a.chain = ca.chain
                             and a.symbol = ca.asset_symbol
                             and a.active = true
                            left join collected
                              on collected.chain = ca.chain
                             and collected.asset_symbol = ca.asset_symbol
                             and collected.from_address = lower(ca.address)
                            where deposited.chain = ?
                        ),
                        positive_candidates as (
                            select chain, asset_symbol, account_id, address, owner_address,
                                   user_id, biz, address_index, wallet_role, amount, minimum_amount
                            from candidates
                            where amount > 0
                              and amount >= minimum_amount
                        )
                        select chain, asset_symbol, account_id, address, owner_address,
                               user_id, biz, address_index, wallet_role, amount
                        from positive_candidates
                        order by amount desc, address_index
                        limit ?
                        """,
                (rs, rowNum) -> CollectionCandidateRecord.builder()
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
                chain, chain, minimumAmount, HotWalletRules.DEFAULT_HOT_USER_ID, chain, limit);
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

    public Optional<TokenDefinition> findToken(String chain, String symbol) {
        List<TokenDefinition> results = queryTokens("""
                select id, chain, symbol,
                       coalesce(contract_address, contract_address_base58, contract_address_hex) as contract_address,
                       decimals, coalesce(token_standard, standard) as standard,
                       false as native_asset, enabled as active
                from token_config where chain = ? and symbol = ? and enabled = true
                """, chain, symbol);
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
        return results.stream().findFirst();
    }

    public List<TokenDefinition> listTokens(String chain) {
        return queryTokens("""
                select id, chain, symbol,
                       coalesce(contract_address, contract_address_base58, contract_address_hex) as contract_address,
                       decimals, coalesce(token_standard, standard) as standard,
                       false as native_asset, enabled as active
                from token_config where chain = ? and enabled = true order by symbol
                """, chain);
    }

    public Optional<ChainAsset> findAsset(String chain, String symbol) {
        List<ChainAsset> results = jdbcTemplate.query("""
                        select id, chain, symbol, asset_kind, contract_address, decimals, native_asset, active,
                               min_transfer, min_withdraw, created_at, updated_at
                        from chain_asset where chain = ? and symbol = ? and active = true
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

    public List<AccountChainProfile> listEnabledChainProfiles() {
        return jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        where enabled = true
                        order by chain, network
                        """,
                (rs, rowNum) -> mapAccountProfile(rs));
    }

    public List<AccountChainProfile> listAllChainProfiles() {
        return jdbcTemplate.query("""
                        select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                               rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                               default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, scan_batch_size, scan_enabled, withdraw_enabled,
                               collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                        from chain_profile
                        order by chain, network
                        """,
                (rs, rowNum) -> mapAccountProfile(rs));
    }

    public boolean systemBoolean(String configKey, boolean defaultValue) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        select config_value, enabled
                        from wallet_system_config
                        where config_key = ?
                        limit 1
                        """, configKey);
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

    public Optional<String> systemValue(String configKey) {
        List<String> values = jdbcTemplate.queryForList("""
                        select config_value
                        from wallet_system_config
                        where config_key = ? and enabled = true
                        limit 1
                        """, String.class, configKey);
        return values.stream().findFirst();
    }

    public List<WalletPublicKey> listEnabledWalletPublicKeys() {
        return jdbcTemplate.query("""
                        select key_slot, key_role, key_type, network, public_key, enabled, remark
                        from wallet_public_key
                        where enabled = true
                        order by key_slot
                        """,
                (rs, rowNum) -> WalletPublicKey.builder()
                        .keySlot(rs.getInt("key_slot"))
                        .keyRole(rs.getString("key_role"))
                        .keyType(rs.getString("key_type"))
                        .network(rs.getString("network"))
                        .publicKey(rs.getString("public_key"))
                        .enabled(rs.getBoolean("enabled"))
                        .remark(rs.getString("remark"))
                        .build());
    }

    public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network, String environment, String purpose) {
        String env = environment == null ? "" : environment;
        String nodePurpose = purpose == null ? "rpc" : purpose;
        return jdbcTemplate.query("""
                        select id, chain, network, environment, node_label, purpose, connection_type, rpc_url,
                               auth_type, auth_header_name, api_key, api_key_ref, username, username_ref,
                               password, password_ref,
                               priority, min_request_interval_ms, enabled, renewal_due_at, remark
                        from chain_rpc_node
                        where upper(chain) = upper(?)
                          and lower(network) = lower(?)
                          and enabled = true
                          and lower(environment) = lower(?)
                          and (lower(purpose) = lower(?) or lower(purpose) = 'all')
                        order by priority asc, id asc
                        """,
                (rs, rowNum) -> mapRpcNode(rs), chain, network, env, nodePurpose);
    }

    public List<ChainRpcNode> listEnabledRpcNodes(String chain, String network, String environment) {
        return listEnabledRpcNodes(chain, network, environment, "rpc");
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
                .scanBatchSize(rs.getObject("scan_batch_size", Integer.class))
                .scanEnabled(rs.getBoolean("scan_enabled"))
                .withdrawEnabled(rs.getBoolean("withdraw_enabled"))
                .collectionEnabled(rs.getBoolean("collection_enabled"))
                .transferEnabled(rs.getBoolean("transfer_enabled"))
                .scanStartHeight(rs.getObject("scan_start_height", Long.class))
                .scanMaxBlocksPerRun(rs.getObject("scan_max_blocks_per_run", Long.class))
                .build();
    }

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
