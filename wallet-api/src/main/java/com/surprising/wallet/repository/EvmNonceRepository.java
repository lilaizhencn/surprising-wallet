package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.MpcPendingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** evm_nonce only. A relayer's first inner proof also admits the whole batch nonce. */
@Repository
public class EvmNonceRepository {
    private static final BigDecimal MAX_NONCE=new BigDecimal(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE));
    private final JdbcTemplate jdbc;
    public EvmNonceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lock7702Chain(String chain,String network) {
        transaction();jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",(r,n)->true,"wallet-eip7702:"+chain+":"+network);
    }
    /** Atomic UPSERT; another business waits until this owner's first durable independent proof or proven cancellation. */
    public BigInteger reserve(String chain,String address,BigInteger chainNonce,String owner) {
        if(!ChainType.valueOf(chain).isEvm() || chainNonce==null || chainNonce.signum()<0 || chainNonce.bitLength()>256
                || owner==null || owner.isBlank() || owner.length()>320)throw new IllegalArgumentException("fixed EVM nonce domain and owner required");
        BigDecimal nonce=new BigDecimal(chainNonce);
        // Serialize retries of one owner before speculative INSERT checks both unique indexes.
        var result=jdbc.query("""
                with admission as materialized (select pg_advisory_xact_lock(hashtextextended(?::text,0)))
                insert into evm_nonce(chain,address,chain_nonce,reserved_nonce,pending_owner,pending_nonce,status,created_at,updated_at)
                select ?,?,?,?+1,?,?,'ACTIVE',now(),now() from admission where true on conflict(chain,address) do update set
                    chain_nonce=greatest(evm_nonce.chain_nonce,excluded.chain_nonce),pending_owner=coalesce(evm_nonce.pending_owner,excluded.pending_owner),
                    pending_nonce=coalesce(evm_nonce.pending_nonce,greatest(evm_nonce.reserved_nonce,excluded.chain_nonce)),
                    reserved_nonce=case when evm_nonce.pending_owner is null then greatest(evm_nonce.reserved_nonce,excluded.chain_nonce)+1
                        else greatest(evm_nonce.reserved_nonce,excluded.chain_nonce) end,status='ACTIVE',updated_at=now()
                where (evm_nonce.pending_owner is null and greatest(evm_nonce.reserved_nonce,excluded.chain_nonce)<=?)
                    or (evm_nonce.pending_owner=excluded.pending_owner and evm_nonce.pending_nonce>=excluded.chain_nonce)
                returning pending_nonce
                """,(r,n)->r.getBigDecimal(1).toBigIntegerExact(),"evm-nonce-owner:"+chain+":"+owner,chain,address,nonce,nonce,owner,nonce,MAX_NONCE);
        if(!result.isEmpty())return result.getFirst();
        if(Boolean.TRUE.equals(jdbc.queryForObject("select pending_owner is null and reserved_nonce>? from evm_nonce where chain=? and address=?",Boolean.class,MAX_NONCE,chain,address)))
            throw new IllegalStateException("EVM nonce space exhausted");
        throw new MpcPendingException(UUID.nameUUIDFromBytes(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"NONCE_ADMISSION_PENDING");
    }
    public boolean admit(String chain,String address,BigInteger nonce,String owner) {
        transaction();return jdbc.update("update evm_nonce set pending_owner=null,pending_nonce=null,updated_at=now() where chain=? and address=? and pending_owner=? and pending_nonce=?",chain,address,owner,new BigDecimal(nonce))==1;
    }
    public boolean hasPendingOwner(String chain,String owner) {
        return !jdbc.queryForList("select address from evm_nonce where chain=? and pending_owner=?",String.class,chain,owner).isEmpty();
    }
    /** Only a fresh independent terminal/no-grant cancellation may return the exclusive unused allocation. */
    public List<CancelledNonce> releaseCancelled(String chain,String owner) {
        transaction();var rows=jdbc.query("select address,pending_nonce from evm_nonce where chain=? and pending_owner=? for update",(r,n)->new CancelledNonce(r.getString(1),r.getBigDecimal(2).toBigIntegerExact()),chain,owner);
        for(var row:rows)if(jdbc.update("""
                update evm_nonce set reserved_nonce=greatest(chain_nonce,pending_nonce),pending_owner=null,pending_nonce=null,updated_at=now()
                where chain=? and address=? and pending_owner=? and pending_nonce=?
                """,chain,row.address(),owner,new BigDecimal(row.nonce()))!=1)throw new IllegalStateException("cancelled EVM nonce owner changed");
        return rows;
    }
    public record CancelledNonce(String address,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger nonce) { }
    private static void transaction(){if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("nonce admission/cancellation must join its owning transaction");}
}
