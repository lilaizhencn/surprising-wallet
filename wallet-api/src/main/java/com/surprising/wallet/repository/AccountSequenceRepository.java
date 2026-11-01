package com.surprising.wallet.repository;

import com.surprising.wallet.model.mpc.MpcPendingException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** account_sequence only. One unadmitted business per account prevents cancellation-created nonce holes. */
@Repository
public class AccountSequenceRepository {
    private final JdbcTemplate jdbc;
    public AccountSequenceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    /** Atomic even without a forwarding transaction; a caller's original owner is idempotent until admission. */
    public long reserve(String chain,String address,long chainSequence,String owner) {
        domain(chain);owner(owner);
        if(chainSequence<0 || chainSequence==Long.MAX_VALUE)throw new IllegalArgumentException("account sequence is outside reservable range");
        Timestamp now=Timestamp.from(Instant.now());
        // Serialize retries of one owner before speculative INSERT checks both unique indexes.
        var result=jdbc.query("""
                with admission as materialized (select pg_advisory_xact_lock(hashtextextended(?::text,0)))
                insert into account_sequence(chain,address,chain_sequence,next_sequence,pending_owner,pending_sequence,status,created_at,updated_at)
                select ?,?,?,?,?,?,'ACTIVE',?,? from admission where true on conflict(chain,address) do update set
                    chain_sequence=greatest(account_sequence.chain_sequence,excluded.chain_sequence),
                    pending_owner=coalesce(account_sequence.pending_owner,excluded.pending_owner),
                    pending_sequence=coalesce(account_sequence.pending_sequence,greatest(account_sequence.next_sequence,excluded.chain_sequence)),
                    next_sequence=case when account_sequence.pending_owner is null then greatest(account_sequence.next_sequence,excluded.chain_sequence)+1
                        else greatest(account_sequence.next_sequence,excluded.chain_sequence) end,
                    status='ACTIVE',updated_at=excluded.updated_at
                where (account_sequence.pending_owner is null and greatest(account_sequence.next_sequence,excluded.chain_sequence)<9223372036854775807)
                    or (account_sequence.pending_owner=excluded.pending_owner and account_sequence.pending_sequence>=excluded.chain_sequence)
                returning pending_sequence
                """,(r,n)->r.getLong(1),"account-sequence-owner:"+chain+":"+owner,chain,address,chainSequence,chainSequence+1,owner,chainSequence,now,now);
        if(!result.isEmpty())return result.getFirst();
        if(Boolean.TRUE.equals(jdbc.queryForObject("select pending_owner is null and next_sequence=9223372036854775807 from account_sequence where chain=? and address=?",Boolean.class,chain,address)))
            throw new IllegalStateException("account sequence space exhausted");
        throw new MpcPendingException(UUID.nameUUIDFromBytes(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"NONCE_ADMISSION_PENDING");
    }
    /** HyperCore time is a replay window, not a contiguous account sequence. */
    public long reserveTimestamp(String address,long timestamp) {
        if(timestamp<1 || timestamp==Long.MAX_VALUE)throw new IllegalArgumentException("invalid HyperCore timestamp");
        return jdbc.queryForObject("""
                insert into account_sequence(chain,address,chain_sequence,next_sequence,status,created_at,updated_at)
                values ('HYPERCORE',?,?,?,'ACTIVE',now(),now()) on conflict(chain,address) do update set
                    chain_sequence=greatest(account_sequence.chain_sequence,excluded.chain_sequence),
                    next_sequence=greatest(account_sequence.next_sequence,excluded.chain_sequence)+1,status='ACTIVE',updated_at=now()
                where account_sequence.pending_owner is null returning next_sequence-1
                """,Long.class,address,timestamp,timestamp+1);
    }
    public void synchronize(String chain,String address,long sequence) {
        domain(chain);if(sequence<0)throw new IllegalArgumentException("invalid observed sequence");
        jdbc.update("""
                insert into account_sequence(chain,address,chain_sequence,next_sequence,status,created_at,updated_at)
                values (?,?,?,?,'ACTIVE',now(),now()) on conflict(chain,address) do update set
                    chain_sequence=excluded.chain_sequence,next_sequence=greatest(account_sequence.next_sequence,excluded.next_sequence),
                    status='ACTIVE',updated_at=now()
                """,chain,address,sequence,sequence);
    }
    /** Joins proof persistence. A different or later business is never cleared. */
    public boolean admit(String chain,String address,long sequence,String owner) {
        transaction();return jdbc.update("update account_sequence set pending_owner=null,pending_sequence=null,updated_at=now() where chain=? and address=? and pending_owner=? and pending_sequence=?",chain,address,owner,sequence)==1;
    }
    public boolean hasPendingOwner(String chain,String owner) {
        return !jdbc.queryForList("select address from account_sequence where chain=? and pending_owner=?",String.class,chain,owner).isEmpty();
    }
    /** The exclusive unadmitted owner proves no later allocation exists. Return only that owner's unused suffix. */
    public List<CancelledSequence> releaseCancelled(String chain,String owner) {
        transaction();var rows=jdbc.query("select address,pending_sequence from account_sequence where chain=? and pending_owner=? for update",(r,n)->new CancelledSequence(r.getString(1),r.getLong(2)),chain,owner);
        for(var row:rows)if(jdbc.update("""
                update account_sequence set next_sequence=greatest(chain_sequence,pending_sequence),pending_owner=null,pending_sequence=null,updated_at=now()
                where chain=? and address=? and pending_owner=? and pending_sequence=?
                """,chain,row.address(),owner,row.sequence())!=1)throw new IllegalStateException("cancelled sequence owner changed");
        return rows;
    }
    public record CancelledSequence(String address,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long sequence) { }
    private static void domain(String chain){if(!Set.of("APTOS","TON","XRP","NEAR","DOT").contains(chain))throw new IllegalArgumentException("explicit contiguous account sequence domain required");}
    private static void owner(String owner){if(owner==null || owner.isBlank() || owner.length()>320)throw new IllegalArgumentException("fixed nonce owner required");}
    private static void transaction(){if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("nonce admission/cancellation must join its owning transaction");}
}
