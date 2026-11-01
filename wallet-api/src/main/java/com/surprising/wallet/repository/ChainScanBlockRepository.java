package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.surprising.wallet.chain.model.ChainScanPoint;

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

    public List<CanonicalBlock> listObserved(String chain, String scannerName, long minimumHeight) {
        return jdbc.query("""
                select block_height, block_hash from chain_scan_block
                 where chain = ? and scanner_name = ? and block_height >= ? order by block_height
                """, (rs, rowNum) -> new CanonicalBlock(rs.getLong("block_height"), rs.getString("block_hash")),
                chain, scannerName, minimumHeight);
    }

    public record CanonicalBlock(long height, String hash) { }

    public void insertNative(String chain, String scannerName, ChainScanPoint point, String parentHash) {
        jdbc.update("""
                insert into chain_scan_block(chain,scanner_name,block_height,block_slot,block_hash,parent_hash)
                values (?,?,?,?,?,?)
                """, chain, scannerName, point.height(), point.slot(), point.hash(), parentHash);
    }

    public Optional<NativeBlock> nativePoint(String chain, String scannerName, long height) {
        return jdbc.query("""
                select block_height,block_slot,block_hash,parent_hash from chain_scan_block
                 where chain=? and scanner_name=? and block_height=? and block_slot is not null
                """, (row, index) -> new NativeBlock(new ChainScanPoint(row.getLong("block_height"),
                row.getLong("block_slot"), row.getString("block_hash")), row.getString("parent_hash")),
                chain, scannerName, height).stream().findFirst();
    }

    public List<NativeBlock> recentNative(String chain, String scannerName, int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("invalid native scan intersection count");
        return jdbc.query("""
                select block_height,block_slot,block_hash,parent_hash from chain_scan_block
                 where chain=? and scanner_name=? and block_slot is not null order by block_height desc limit ?
                """, (row, index) -> new NativeBlock(new ChainScanPoint(row.getLong("block_height"),
                row.getLong("block_slot"), row.getString("block_hash")), row.getString("parent_hash")), chain, scannerName, limit);
    }

    public int removeAbove(String chain, String scannerName, long height) {
        return jdbc.update("delete from chain_scan_block where chain=? and scanner_name=? and block_height>?", chain, scannerName, height);
    }

    public record NativeBlock(ChainScanPoint point, String parentHash) { }
}
