package com.surprising.wallet.service.dao;

import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.EvmNonceRecord;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TronTransactionRecord;
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

    /**
     * Records a deposit and credits ledger_balance once. The credited=false predicate is the
     * idempotency guard: repeated scans update confirmations but cannot credit the same tx twice.
     */
    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCreditDeposit(DepositEvent event, int requiredConfirmations) {
        return recordAndCreditDeposit(event, 0L, requiredConfirmations);
    }

    public boolean recordAndCreditDeposit(DepositEvent event, long logIndex, int requiredConfirmations) {
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
            incrementLedgerBalance(chain, event.assetSymbol(), event.toAddress().toLowerCase(), event.amount());
            return true;
        }
        return false;
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
                amount, amount, toTs(now()), chain, assetSymbol, accountId.toLowerCase(), amount);
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
                amount, amount, toTs(now()), chain, assetSymbol, accountId.toLowerCase(), amount);
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
                amount, amount, toTs(now()), chain, assetSymbol, accountId.toLowerCase(), amount);
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
                amount, amount, toTs(now()), chain, assetSymbol, accountId.toLowerCase(), amount);
        return updated == 1;
    }

    public void updateScanHeight(String chain, String scannerName, long bestHeight, long safeHeight) {
        jdbcTemplate.update("""
                        insert into chain_scan_height(chain, scanner_name, best_height, safe_height, status,
                                                      created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        on conflict (chain, scanner_name) do update set
                            best_height = greatest(chain_scan_height.best_height, excluded.best_height),
                            safe_height = greatest(chain_scan_height.safe_height, excluded.safe_height),
                            status = 'ACTIVE',
                            updated_at = excluded.updated_at
                        """,
                chain, scannerName, bestHeight, safeHeight, toTs(now()), toTs(now()));
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
}
