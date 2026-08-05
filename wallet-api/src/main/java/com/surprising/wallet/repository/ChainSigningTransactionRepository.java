package com.surprising.wallet.repository;

import com.surprising.wallet.common.pojo.WithdrawTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** chain_signing_transaction 单表仓储。 */
@Repository
public class ChainSigningTransactionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造链签名交易仓储。 */
    public ChainSigningTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入或更新签名交易。 */
    public java.util.Optional<WithdrawTransaction> create(String chain, String assetSymbol, String businessType,
                                                           String businessNo, WithdrawTransaction tx,
                                                           short waitingStatus, short signingStatus) {
        return jdbc.query("""
                insert into chain_signing_transaction(
                    chain, asset_symbol, business_type, business_no, tx_id, balance, signature, currency,
                    status, create_date, update_date)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, business_type, business_no) do update set
                    tx_id = excluded.tx_id, balance = excluded.balance, signature = excluded.signature,
                    currency = excluded.currency, status = excluded.status, error_message = null,
                    update_date = excluded.update_date
                where chain_signing_transaction.status in (?, ?)
                returning id, tx_id, balance, signature, currency, status, create_date, update_date
                """, (rs, rowNum) -> map(rs), chain, assetSymbol, businessType, businessNo,
                tx.getTxId(), tx.getBalance(), tx.getSignature(), tx.getCurrency(), tx.getStatus(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), waitingStatus, signingStatus)
                .stream().findFirst();
    }

    /** 查询签名交易是否存在。 */
    public boolean exists(String chain, String txId) {
        return !jdbc.queryForList("""
                select id from chain_signing_transaction where chain = ? and tx_id = ? limit 1
                """, chain, txId).isEmpty();
    }

    /** 查询已发送的签名交易。 */
    public List<WithdrawTransaction> listSent(String chain, short sentStatus) {
        return jdbc.query("""
                select id, tx_id, balance, signature, currency, status, create_date, update_date
                  from chain_signing_transaction where chain = ? and status = ? order by id
                """, (rs, rowNum) -> map(rs), chain, sentStatus);
    }

    /** 按业务号查询签名交易。 */
    public Optional<WithdrawTransaction> findByBusiness(String chain, String businessType, String businessNo) {
        return jdbc.query("""
                select id, tx_id, balance, signature, currency, status, create_date, update_date
                  from chain_signing_transaction
                 where chain = ? and business_type = ? and business_no = ?
                """, (rs, rowNum) -> map(rs), chain, businessType, businessNo).stream().findFirst();
    }

    /** 按主键查询签名交易。 */
    public Optional<WithdrawTransaction> findById(String chain, int id) {
        return jdbc.query("""
                select id, tx_id, balance, signature, currency, status, create_date, update_date
                  from chain_signing_transaction where chain = ? and id = ?
                """, (rs, rowNum) -> map(rs), chain, id).stream().findFirst();
    }

    /** 按交易 ID 查询最近的签名交易。 */
    public Optional<WithdrawTransaction> findByTxId(String chain, String txId) {
        return jdbc.query("""
                select id, tx_id, balance, signature, currency, status, create_date, update_date
                  from chain_signing_transaction where chain = ? and tx_id = ? order by id desc limit 1
                """, (rs, rowNum) -> map(rs), chain, txId).stream().findFirst();
    }

    /** 查询超时的签名交易。 */
    public List<WithdrawTransaction> listStale(String chain, short signingStatus, long staleSeconds) {
        return jdbc.query("""
                select id, tx_id, balance, signature, currency, status, create_date, update_date
                  from chain_signing_transaction
                 where chain = ? and status = ? and error_message is null
                   and update_date < now() - (? * interval '1 second')
                 order by id limit 100
                """, (rs, rowNum) -> map(rs), chain, signingStatus, staleSeconds);
    }

    /** 原子领取广播处理权，避免多个广播工作者同时向链上发送同一笔交易。 */
    public boolean claimBroadcast(String chain, long id, String ownerId, long leaseSeconds, short sentStatus) {
        return jdbc.update("""
                update chain_signing_transaction
                   set broadcast_owner = ?, broadcast_lease_until = now() + (? * interval '1 second'),
                       update_date = now()
                 where chain = ? and id = ? and status < ? and error_message is null
                   and (broadcast_lease_until is null or broadcast_lease_until <= now()
                        or broadcast_owner = ?)
                """, ownerId, leaseSeconds, chain, id, sentStatus, ownerId) == 1;
    }

    /** 领取超时签名交易的恢复处理权。 */
    public boolean claimRecovery(String chain, int id, short signingStatus, long staleSeconds) {
        return jdbc.update("""
                update chain_signing_transaction set update_date = now()
                 where chain = ? and id = ? and status = ?
                   and update_date < now() - (? * interval '1 second')
                """, chain, id, signingStatus, staleSeconds) == 1;
    }

    /** 更新签名交易状态。 */
    public int updateStatus(String chain, long id, WithdrawTransaction transaction) {
        return jdbc.update("""
                update chain_signing_transaction set tx_id = ?, balance = ?, signature = ?, currency = ?,
                    status = ?, error_message = null, broadcast_owner = null, broadcast_lease_until = null,
                    update_date = ? where chain = ? and id = ?
                """, transaction.getTxId(), transaction.getBalance(), transaction.getSignature(),
                transaction.getCurrency(), transaction.getStatus(), Timestamp.from(Instant.now()), chain, id);
    }

    /** 将签名失败信息写入交易状态。 */
    public int markError(String chain, long id, String errorMessage) {
        return jdbc.update("""
                update chain_signing_transaction set error_message = ?, broadcast_owner = null,
                    broadcast_lease_until = null, update_date = ? where chain = ? and id = ?
                """, errorMessage, Timestamp.from(Instant.now()), chain, id);
    }

    /** 映射签名交易。 */
    private WithdrawTransaction map(ResultSet rs) throws SQLException {
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
}
