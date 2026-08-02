package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.DepositEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** tron_token_transfer 单表仓储。 */
@Repository
public class TronTokenTransferRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 TRON 代币转账仓储。 */
    public TronTokenTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入 TRON 代币转账。 */
    public int upsert(DepositEvent event, long logIndex, String status) {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("""
                insert into tron_token_transfer(chain, tx_hash, log_index, token_symbol, contract_address,
                                                 from_address, to_address, amount, block_height, status, raw_payload,
                                                 created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, tx_hash, log_index) do update set
                    token_symbol = excluded.token_symbol, contract_address = excluded.contract_address,
                    from_address = excluded.from_address, to_address = excluded.to_address, amount = excluded.amount,
                    block_height = excluded.block_height, status = excluded.status,
                    raw_payload = excluded.raw_payload, updated_at = excluded.updated_at
                """, event.chainType().name(), event.txId(), logIndex, event.assetSymbol(), event.tokenAddress(),
                event.fromAddress(), event.toAddress(), event.amount(), event.blockHeight(), status,
                event.rawPayload(), now, now);
    }
}
