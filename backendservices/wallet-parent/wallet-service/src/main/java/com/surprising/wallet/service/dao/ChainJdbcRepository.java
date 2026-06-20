package com.surprising.wallet.service.dao;

import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.EvmNonceRecord;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TronTransactionRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    public Optional<TokenDefinition> findToken(String chain, String symbol) {
        List<TokenDefinition> results = jdbcTemplate.query("""
                        select id, chain, symbol, contract_address, decimals, standard, native_asset, active
                        from token_registry where chain = ? and symbol = ?
                        """,
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
                chain, symbol);
        return results.stream().findFirst();
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
}
