package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** hypercore_token_metadata 单表仓储。 */
@Repository
public class HyperCoreTokenMetadataRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 HyperCore 代币元数据仓储。 */
    public HyperCoreTokenMetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存代币元数据。 */
    public void upsert(String network, String name, Integer tokenIndex, String tokenId,
                       Integer szDecimals, Integer weiDecimals, Boolean canonical,
                       String evmContract, String fullName, java.sql.Timestamp now) {
        jdbc.update("""
                insert into hypercore_token_metadata(network, token_index, token_id, name,
                                                     sz_decimals, wei_decimals, is_canonical,
                                                     evm_contract, full_name, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (network, token_index) do update set
                    token_id = excluded.token_id,
                    name = excluded.name,
                    sz_decimals = excluded.sz_decimals,
                    wei_decimals = excluded.wei_decimals,
                    is_canonical = excluded.is_canonical,
                    evm_contract = excluded.evm_contract,
                    full_name = excluded.full_name,
                    updated_at = excluded.updated_at
                """, network, tokenIndex, tokenId, name, szDecimals, weiDecimals,
                Boolean.TRUE.equals(canonical), evmContract, fullName, now, now);
    }

    /** 按网络和名称查询代币名称。 */
    public Optional<String> findName(String network, String symbol) {
        List<String> values = jdbc.queryForList("""
                select name
                  from hypercore_token_metadata
                 where network = ? and upper(name) = upper(?)
                 order by is_canonical desc, token_index
                 limit 1
                """, String.class, network, symbol);
        return values.stream().findFirst();
    }
}
