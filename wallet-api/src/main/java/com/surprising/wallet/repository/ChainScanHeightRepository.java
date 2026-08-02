package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.ChainScanHeightRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** chain_scan_height 单表仓储。 */
@Repository
public class ChainScanHeightRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造扫描高度仓储。 */
    public ChainScanHeightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入或更新扫描高度。 */
    public void upsert(String chain, String scannerName, long bestHeight, long safeHeight) {
        jdbc.update("""
                insert into chain_scan_height(chain, scanner_name, best_height, safe_height, status, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?)
                on conflict (chain, scanner_name) do update set
                    best_height = greatest(chain_scan_height.best_height, excluded.best_height),
                    safe_height = case when excluded.best_height >= chain_scan_height.best_height
                        then excluded.safe_height else chain_scan_height.safe_height end,
                    status = 'ACTIVE', updated_at = excluded.updated_at
                """, chain, scannerName, bestHeight, safeHeight,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    /** 查询扫描安全高度。 */
    public Optional<Long> findSafeHeight(String chain, String scannerName) {
        List<Long> rows = jdbc.queryForList("""
                select safe_height from chain_scan_height where chain = ? and scanner_name = ?
                """, Long.class, chain, scannerName);
        return rows.stream().findFirst();
    }

    /** 查询所有启用的扫描高度。 */
    public List<ChainScanHeightRecord> listActive() {
        return jdbc.query("""
                select chain, scanner_name, best_height, safe_height, status, updated_at
                  from chain_scan_height where status = 'ACTIVE' order by chain, scanner_name
                """, (rs, rowNum) -> ChainScanHeightRecord.builder()
                .chain(rs.getString("chain")).scannerName(rs.getString("scanner_name"))
                .bestHeight(rs.getLong("best_height")).safeHeight(rs.getLong("safe_height"))
                .status(rs.getString("status"))
                .updatedAt(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant())
                .build());
    }
}
