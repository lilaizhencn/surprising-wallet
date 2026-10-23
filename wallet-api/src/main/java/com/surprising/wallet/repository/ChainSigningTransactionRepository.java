package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.common.utils.Constants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** chain_signing_transaction: typed intent and API-owned signed attempts. */
@Repository
public class ChainSigningTransactionRepository {
    private static final String COLUMNS = """
            id, request::text, signed_transaction::text, attempts::text, status,
            enqueued_signing_request_id, error_message, create_date, update_date
            """;
    private static final String HASH_MATCH = """
            (tx_id = ? or jsonb_path_exists(attempts,
                '$[*].transaction ? (@.txId == $hash)', jsonb_build_object('hash', ?::text)))
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ChainSigningTransactionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UtxoSigningTransaction create(UtxoSigningRequest request, String businessNo) {
        return jdbc.query("""
                insert into chain_signing_transaction(request, business_no, status)
                values (?::jsonb, ?, ?)
                on conflict (chain, business_type, business_no) do update set
                    request = excluded.request, error_message = null, update_date = now()
                where chain_signing_transaction.tenant_id = excluded.tenant_id
                  and chain_signing_transaction.status in (0, 1)
                  and chain_signing_transaction.enqueued_signing_request_id is null
                  and chain_signing_transaction.signed_transaction is null
                returning
                """ + COLUMNS, (rs, rowNum) -> map(rs), JacksonJson.writeValue(json, request),
                businessNo, Constants.SIGNING).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("signing business already submitted: " + businessNo));
    }

    public Optional<UtxoSigningTransaction> findByBusiness(String chain, String type, String businessNo) {
        return jdbc.query("select " + COLUMNS + " from chain_signing_transaction where chain = ? and business_type = ? and business_no = ?",
                (rs, rowNum) -> map(rs), chain, type, businessNo).stream().findFirst();
    }

    public Optional<UtxoSigningTransaction> findById(String chain, int id) {
        return jdbc.query("select " + COLUMNS + " from chain_signing_transaction where chain = ? and id = ?",
                (rs, rowNum) -> map(rs), chain, id).stream().findFirst();
    }

    /** RBF, signing submission, broadcast and settlement all lock this same row. */
    public Optional<UtxoSigningTransaction> lockById(String chain, int id) {
        return jdbc.query("select " + COLUMNS + " from chain_signing_transaction where chain = ? and id = ? for update",
                (rs, rowNum) -> map(rs), chain, id).stream().findFirst();
    }

    public int recordEnqueued(String chain, int id, UUID requestId) {
        return jdbc.update("""
                update chain_signing_transaction set enqueued_signing_request_id = ?
                 where chain = ? and id = ? and signing_request_id = ?
                """, requestId, chain, id, requestId);
    }

    public Optional<UtxoSigningTransaction> findByTxId(String chain, String txId) {
        return jdbc.query("select " + COLUMNS + " from chain_signing_transaction where chain = ? and "
                + HASH_MATCH + " order by id desc limit 1", (rs, rowNum) -> map(rs), chain, txId, txId)
                .stream().findFirst();
    }

    public boolean exists(String chain, String txId) {
        return !jdbc.queryForList("select id from chain_signing_transaction where chain = ? and " + HASH_MATCH
                + " limit 1", chain, txId, txId).isEmpty();
    }

    public Optional<String> findCollectionBusinessNo(String chain, UUID tenantId, String txId) {
        return jdbc.queryForList("""
                select business_no from chain_signing_transaction
                 where chain = ? and tenant_id = ? and business_type = 'COLLECTION' and
                """ + HASH_MATCH + " limit 1", String.class, chain, tenantId, txId, txId).stream().findFirst();
    }

    public List<UtxoSigningTransaction> listPendingConfirmation(String chain) {
        return jdbc.query("select " + COLUMNS + """
                 from chain_signing_transaction where chain = ? and
                    (status = 2 or (status = 1 and (signed_transaction is not null or jsonb_array_length(attempts) > 0)))
                 order by id
                """, (rs, rowNum) -> map(rs), chain);
    }

    public boolean claimBroadcast(String chain, int id, String ownerId) {
        return jdbc.update("""
                update chain_signing_transaction
                   set broadcast_owner = ?, broadcast_lease_until = now() + interval '5 minutes', update_date = now()
                 where chain = ? and id = ? and status = 1 and error_message is null
                   and (broadcast_lease_until is null or broadcast_lease_until <= now() or broadcast_owner = ?)
                """, ownerId, chain, id, ownerId) == 1;
    }

    public int update(UtxoSigningTransaction transaction) {
        return jdbc.update("""
                update chain_signing_transaction set request = ?::jsonb, signed_transaction = ?::jsonb,
                    attempts = ?::jsonb, status = ?, error_message = ?, broadcast_owner = null,
                    broadcast_lease_until = null, update_date = now()
                 where id = ? and tenant_id = ? and status not in (-1, 3)
                """, JacksonJson.writeValue(json, transaction.request()),
                transaction.signed() == null ? null : JacksonJson.writeValue(json, transaction.signed()),
                JacksonJson.writeValue(json, transaction.attempts()), transaction.status(), transaction.error(),
                transaction.id(), transaction.request().tenantId());
    }

    public int markError(String chain, int id, String error) {
        return jdbc.update("""
                update chain_signing_transaction set error_message = ?, broadcast_owner = null,
                    broadcast_lease_until = null, update_date = now()
                 where chain = ? and id = ? and status not in (-1, 3)
                """, error, chain, id);
    }

    private UtxoSigningTransaction map(ResultSet rs) throws SQLException {
        String signed = rs.getString("signed_transaction");
        return new UtxoSigningTransaction(rs.getInt("id"),
                JacksonJson.readValue(json, rs.getString("request"), UtxoSigningRequest.class),
                signed == null ? null : JacksonJson.readValue(json, signed, UtxoSignedTransaction.class),
                json.readValue(rs.getString("attempts"), json.getTypeFactory()
                        .constructCollectionType(List.class, UtxoSigningTransaction.Attempt.class)),
                rs.getShort("status"), rs.getObject("enqueued_signing_request_id", UUID.class),
                rs.getString("error_message"), rs.getTimestamp("create_date").toInstant(),
                rs.getTimestamp("update_date").toInstant());
    }
}
