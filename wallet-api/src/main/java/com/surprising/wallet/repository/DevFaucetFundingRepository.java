package com.surprising.wallet.repository;

import com.surprising.wallet.devfaucet.DevFaucetFunding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** custody_dev_faucet_funding 单表仓储。 */
@Repository
public class DevFaucetFundingRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造水龙头资金单表仓储。 */
    public DevFaucetFundingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断同一托管地址、资产和用途是否已有资金记录。 */
    public boolean exists(UUID custodyAddressId, String assetSymbol, String purpose) {
        return !jdbc.queryForList("""
                select id from custody_dev_faucet_funding
                 where custody_address_id = ? and asset_symbol = ? and purpose = ?
                 limit 1
                """, custodyAddressId, assetSymbol, purpose).isEmpty();
    }

    /** 创建资金任务。 */
    public boolean create(UUID tenantId, UUID custodyAddressId, String chain, String network,
                          String assetSymbol, String purpose, String address,
                          String contractAddress, int decimals, BigDecimal amount) {
        return jdbc.update("""
                insert into custody_dev_faucet_funding(
                    id, tenant_id, custody_address_id, chain, network, asset_symbol,
                    purpose, address, contract_address, decimals, requested_amount)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (custody_address_id, asset_symbol, purpose) do nothing
                """, UUID.randomUUID(), tenantId, custodyAddressId, chain, network,
                assetSymbol, purpose, address, contractAddress, decimals, amount) == 1;
    }

    /** 查询待发送的资金任务。 */
    public List<DevFaucetFunding> due(int limit, int maxAttempts) {
        return jdbc.query("""
                select id, tenant_id, custody_address_id, chain, network, asset_symbol,
                       purpose, address, contract_address, decimals, requested_amount, attempts
                  from custody_dev_faucet_funding
                 where status in ('PENDING', 'FAILED')
                   and attempts < ? and next_attempt_at <= now()
                 order by created_at, id
                 limit ?
                """, (rs, rowNum) -> new DevFaucetFunding(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class), rs.getString("chain"),
                rs.getString("network"), rs.getString("asset_symbol"), rs.getString("purpose"),
                rs.getString("address"), rs.getString("contract_address"),
                rs.getInt("decimals"), rs.getBigDecimal("requested_amount"),
                rs.getInt("attempts")), maxAttempts, limit);
    }

    /** 查询已发送但尚未确认的资金任务。 */
    public List<SentFunding> sent() {
        return jdbc.query("""
                select id, tenant_id, custody_address_id, chain, asset_symbol, tx_hash
                  from custody_dev_faucet_funding
                 where status = 'SENT' and tx_hash is not null
                """, (rs, rowNum) -> new SentFunding(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class), rs.getString("chain"),
                rs.getString("asset_symbol"), rs.getString("tx_hash")));
    }

    /** 将已确认任务更新为 CONFIRMED。 */
    public int markConfirmed(UUID id, Timestamp creditedAt) {
        return jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'CONFIRMED', confirmed_at = ?, updated_at = now()
                 where id = ? and status = 'SENT'
                """, creditedAt, id);
    }

    /** 标记任务进入发送状态。 */
    public boolean markSending(UUID id) {
        return jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'SENDING', attempts = attempts + 1,
                       last_error = null, updated_at = now()
                 where id = ? and status in ('PENDING', 'FAILED')
                """, id) == 1;
    }

    /** 标记任务已发送。 */
    public void markSent(UUID id, String txHash) {
        jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'SENT', tx_hash = ?, sent_at = now(), updated_at = now()
                 where id = ? and status = 'SENDING'
                """, txHash, id);
    }

    /** 标记任务失败并设置重试时间。 */
    public void markFailed(UUID id, String error, Duration retryDelay) {
        jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'FAILED', last_error = ?, next_attempt_at = ?, updated_at = now()
                 where id = ? and status = 'SENDING'
                """, truncate(error), Timestamp.from(Instant.now().plus(retryDelay)), id);
    }

    /** 标记任务发送结果未知。 */
    public void markUnknown(UUID id, String error) {
        jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'UNKNOWN', last_error = ?, updated_at = now()
                 where id = ? and status = 'SENDING'
                """, truncate(error), id);
    }

    /** 恢复超时的发送任务。 */
    public int recoverStaleSending(Duration age) {
        return jdbc.update("""
                update custody_dev_faucet_funding
                   set status = 'UNKNOWN',
                       last_error = coalesce(last_error,
                           'worker stopped while RPC outcome was unknown'),
                       updated_at = now()
                 where status = 'SENDING' and updated_at < ?
                """, Timestamp.from(Instant.now().minus(age)));
    }

    /** 截断可持久化的错误文本。 */
    private static String truncate(String value) {
        String safe = value == null ? "unknown error" : value;
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

    /** 已发送资金任务。 */
    public record SentFunding(UUID id, UUID tenantId, UUID custodyAddressId,
                              String chain, String assetSymbol, String txHash) {
    }
}
