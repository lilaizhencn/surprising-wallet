package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.CardanoOwnedOutput;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** cardano_owned_output only; retains wallet output references, not a full-chain index. */
@Repository
@RequiredArgsConstructor
public class CardanoOwnedOutputRepository {
    private final JdbcTemplate jdbc;

    public int insert(CardanoOwnedOutput output) {
        return jdbc.update("""
                insert into cardano_owned_output(tx_hash,output_index,tenant_id,chain_address_id,address,
                    block_height,block_hash,output_cbor) values (?,?,?,?,?,?,?,?)
                on conflict(tx_hash,output_index) do nothing
                """, output.txHash(), output.outputIndex(), output.tenantId(), output.addressId(), output.address(),
                output.blockHeight(), output.blockHash(), output.outputCbor());
    }

    public List<CardanoOwnedOutput> findByTransactions(List<String> hashes) {
        if (hashes.isEmpty()) return List.of();
        if (hashes.size() > 1_000) throw new IllegalArgumentException("Cardano owned-output lookup exceeds input bound");
        return jdbc.query("select * from cardano_owned_output where tx_hash=any(?) order by tx_hash,output_index",
                statement -> statement.setArray(1, statement.getConnection().createArrayOf("varchar", hashes.toArray(String[]::new))),
                (row, index) -> new CardanoOwnedOutput(row.getString("tx_hash"), row.getInt("output_index"),
                        row.getObject("tenant_id", UUID.class), row.getLong("chain_address_id"), row.getString("address"),
                        row.getLong("block_height"), row.getString("block_hash"), row.getString("output_cbor")));
    }

    public int removeAbove(long height) {
        return jdbc.update("delete from cardano_owned_output where block_height>?", height);
    }
}
