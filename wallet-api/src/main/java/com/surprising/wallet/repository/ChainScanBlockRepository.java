package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** chain_scan_block 单表仓储。 */
@Repository
public class ChainScanBlockRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造扫描区块仓储。 */
    public ChainScanBlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询指定扫描器和高度的区块哈希并加锁。 */
    public List<String> findHashForUpdate(String chain, String scannerName, long blockHeight) {
        return jdbc.queryForList("""
                select block_hash from chain_scan_block
                 where chain = ? and scanner_name = ? and block_height = ? for update
                """, String.class, chain, scannerName, blockHeight);
    }

    /** 插入扫描到的新区块。 */
    public void insert(String chain, String scannerName, long blockHeight, String blockHash, String parentHash) {
        jdbc.update("""
                insert into chain_scan_block(chain, scanner_name, block_height, block_hash, parent_hash, observed_at)
                values (?, ?, ?, ?, ?, ?)
                """, chain, scannerName, blockHeight, blockHash, parentHash, Timestamp.from(Instant.now()));
    }

    /** 更新扫描区块的父块和观测时间。 */
    public void updateObservation(String chain, String scannerName, long blockHeight,
                                  String blockHash, String parentHash) {
        jdbc.update("""
                update chain_scan_block set block_hash = ?, parent_hash = ?, observed_at = ?
                 where chain = ? and scanner_name = ? and block_height = ?
                """, blockHash, parentHash, Timestamp.from(Instant.now()), chain, scannerName, blockHeight);
    }
}
