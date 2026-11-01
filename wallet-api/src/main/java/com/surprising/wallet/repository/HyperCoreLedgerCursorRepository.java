package com.surprising.wallet.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** hypercore_ledger_cursor: one tenant-owned asset address's inclusive ledger timestamp. */
@Repository
public class HyperCoreLedgerCursorRepository {
    private final JdbcTemplate jdbc;
    public HyperCoreLedgerCursorRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void ensure(UUID tenant,String network,long addressId,String symbol,String address) {
        jdbc.update("""
                insert into hypercore_ledger_cursor(tenant_id,network,chain_address_id,asset_symbol,address)
                values (?,?,?,?,?) on conflict(tenant_id,network,chain_address_id,asset_symbol) do nothing
                """,tenant,network,addressId,symbol,address);
    }
    public long position(UUID tenant,String network,long addressId,String symbol,String address,boolean lock) {
        return jdbc.query("""
                select last_time_ms from hypercore_ledger_cursor
                 where tenant_id=? and network=? and chain_address_id=? and asset_symbol=? and address=?
                """+(lock?" for update":""),(row,n)->row.getLong(1),tenant,network,addressId,symbol,address).stream().findFirst().orElseThrow();
    }
    public int advance(UUID tenant,String network,long addressId,String symbol,long expected,long next) {
        if(next<expected)throw new IllegalArgumentException("HyperCore ledger cursor cannot move backwards");
        return jdbc.update("""
                update hypercore_ledger_cursor set last_time_ms=?,updated_at=now()
                 where tenant_id=? and network=? and chain_address_id=? and asset_symbol=? and last_time_ms=?
                """,next,tenant,network,addressId,symbol,expected);
    }
}
