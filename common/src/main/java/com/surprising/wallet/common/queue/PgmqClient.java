package com.surprising.wallet.common.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

/** Infrastructure adapter for PGMQ extension tables, never wallet business tables. */
public final class PgmqClient {
    private final JdbcTemplate jdbc;
    public PgmqClient(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Message(long id, int reads, String body, String headers) { }

    public long send(WalletQueue queue, String body, String headers) {
        return jdbc.queryForObject("select pgmq.send(?, ?::jsonb, ?::jsonb)", Long.class,
                queue.queueName(), body, headers);
    }
    public List<Message> read(WalletQueue queue, int visibilitySeconds, int count) {
        if (visibilitySeconds < 1 || count < 1 || count > 100) throw new IllegalArgumentException("invalid read limits");
        return jdbc.query("select msg_id, read_ct, message::text, headers::text from pgmq.read(?, ?, ?)",
                (rs, n) -> new Message(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4)),
                queue.queueName(), visibilitySeconds, count);
    }
    /** Must run inside the processing transaction. A stale reader cannot acknowledge a new lease. */
    public boolean lock(WalletQueue queue, Message message) {
        return !jdbc.queryForList("select msg_id from pgmq.q_" + queue.queueName()
                + " where msg_id = ? and read_ct = ? and vt > clock_timestamp() for update",
                message.id(), message.reads()).isEmpty();
    }
    public void archive(WalletQueue queue, Message message) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pgmq.archive(?, ?)", Boolean.class,
                queue.queueName(), message.id()))) throw new IllegalStateException("message missing");
    }
    public void retry(WalletQueue queue, Message message, String error, int delay) {
        jdbc.update("update pgmq.q_" + queue.queueName()
                + " set headers = coalesce(headers, '{}'::jsonb) || jsonb_build_object('last_error', ?::text) where msg_id = ?",
                error, message.id());
        jdbc.queryForList("select * from pgmq.set_vt(?, ?, ?)", queue.queueName(), message.id(), delay);
    }
    public void deadLetter(WalletQueue queue, Message message, String error) {
        jdbc.queryForObject("select pgmq.send(?, ?::jsonb, coalesce(?::jsonb, '{}'::jsonb)"
                        + " || jsonb_build_object('source_queue', ?::text, 'source_id', ?::bigint, 'last_error', ?::text))",
                Long.class, queue.queueName() + "_dead", message.body(), message.headers(),
                queue.queueName(), message.id(), error);
        archive(queue, message);
    }
}
