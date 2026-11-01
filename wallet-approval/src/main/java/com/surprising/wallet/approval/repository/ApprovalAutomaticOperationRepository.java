package com.surprising.wallet.approval.repository;

import com.surprising.wallet.common.approval.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** approval.automatic_operation only. Quotas are permanent admission exposure, not unverified chain-settlement estimates. */
@Repository
public class ApprovalAutomaticOperationRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ApprovalAutomaticOperationRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<Stored> find(UUID tenant,String chain,String type,String reference) {
        return jdbc.query("select * from approval.automatic_operation where tenant_id=? and chain=? and business_type=? and business_reference=?",
                (r,n)->stored(r),tenant,chain,type,reference).stream().findFirst();
    }
    public Optional<Stored> find(UUID tenant,UUID id) {
        return jdbc.query("select * from approval.automatic_operation where tenant_id=? and id=?",
                (r,n)->stored(r),tenant,id).stream().findFirst();
    }
    public Optional<Stored> fundingParent(UUID tenant,String chain,AutomaticParentReference.Parts reference) {
        var rows=jdbc.query("""
                select * from approval.automatic_operation where tenant_id=? and chain=? and business_type='COLLECTION'
                and encode(sha256(convert_to(? || business_reference,'UTF8')),'hex')=? limit 2
                """,(r,n)->stored(r),
                tenant,chain,reference.hashPrefix()+"COLLECTION:",reference.parentHash());
        if(rows.size()>1)throw new IllegalStateException("ambiguous independently admitted funding parent");return rows.stream().findFirst();
    }
    public Budget budget(UUID tenant,ApprovalAssetSnapshot asset,String businessType,Instant now) {
        return jdbc.query("""
                select coalesce(sum(amount_atomic),0) lifetime_amount,
                    coalesce(sum(amount_atomic) filter(where created_at>=?),0) daily_amount,
                    count(*) filter(where created_at>=?) daily_count
                from approval.automatic_operation where tenant_id=? and chain=? and network=? and asset_symbol=? and business_type=?
                """,(r,n)->new Budget(r.getBigDecimal("lifetime_amount").toBigIntegerExact(),r.getBigDecimal("daily_amount").toBigIntegerExact(),r.getLong("daily_count")),
                Timestamp.from(now.minusSeconds(86400)),Timestamp.from(now.minusSeconds(86400)),tenant,asset.chain(),asset.network(),asset.symbol(),businessType).getFirst();
    }
    public BigInteger lifetimeFees(UUID tenant,ApprovalAssetSnapshot asset) {
        return jdbc.queryForObject("select coalesce(sum(maximum_fee_atomic),0) from approval.automatic_operation where tenant_id=? and chain=? and network=?",
                BigDecimal.class,tenant,asset.chain(),asset.network()).toBigIntegerExact();
    }
    public boolean differentDomain(UUID tenant,ApprovalAssetSnapshot asset) {
        return !jdbc.queryForList("""
                select id from approval.automatic_operation where tenant_id=? and chain=? and network=?
                and (network_identity<>? or native_symbol<>? or native_decimals<>?
                    or (asset_symbol=? and (asset_decimals<>? or asset_identifier is distinct from ?))) limit 1
                """,UUID.class,tenant,asset.chain(),asset.network(),asset.networkIdentity(),asset.nativeSymbol(),asset.nativeDecimals(),asset.symbol(),asset.decimals(),asset.tokenIdentifier()).isEmpty();
    }
    public Optional<Instant> latestSource(UUID tenant,ApprovalAssetSnapshot asset,UUID source) {
        return jdbc.query("""
                select created_at from approval.automatic_operation where tenant_id=? and chain=? and network=? and source_key_ids @> ?::jsonb
                order by created_at desc limit 1
                """,(r,n)->r.getTimestamp("created_at").toInstant(),tenant,asset.chain(),asset.network(),json.writeValueAsString(List.of(source))).stream().findFirst();
    }
    public int bind(UUID tenant,UUID operationId,UUID transactionId) {
        return jdbc.update("update approval.automatic_operation set transaction_id=? where tenant_id=? and id=? and transaction_id is null",transactionId,tenant,operationId);
    }
    public void insert(AutomaticOperation operation,UUID transactionId,Collection<UUID> sourceKeys,BigInteger fee,String parentScope) {
        insert(operation,transactionId,sourceKeys,fee,parentScope,null,null);
    }
    public void insert(AutomaticOperation operation,UUID transactionId,Collection<UUID> sourceKeys,BigInteger fee,String parentScope,AutomaticParentPlan plan,tools.jackson.databind.JsonNode evidence) {
        var asset=operation.payments().getFirst().assetSnapshot();var tenant=operation.payments().getFirst().intent().tenantId();
        BigInteger amount=operation.payments().stream().map(p->p.intent().amount().movePointRight(asset.decimals()).toBigIntegerExact()).reduce(BigInteger.ZERO,BigInteger::add);
        jdbc.update("""
                insert into approval.automatic_operation(id,tenant_id,transaction_id,rule_id,rule_fingerprint,chain,network,network_identity,
                    asset_symbol,asset_identifier,asset_decimals,native_symbol,native_decimals,business_type,business_reference,source_key_ids,
                    parent_operation_id,parent_scope,amount_atomic,maximum_fee_atomic,receipt,created_at,preparation_plan,preparation_evidence)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb)
                """,operation.id(),tenant,transactionId,operation.ruleId(),operation.ruleFingerprint(),asset.chain(),asset.network(),asset.networkIdentity(),
                asset.symbol(),asset.tokenIdentifier(),asset.decimals(),asset.nativeSymbol(),asset.nativeDecimals(),operation.businessType(),operation.businessReference(),
                json.writeValueAsString(sourceKeys.stream().sorted().toList()),operation.parentOperationId(),parentScope,new BigDecimal(amount),new BigDecimal(fee),json.writeValueAsString(operation),Timestamp.from(operation.admittedAt()),plan==null?null:json.writeValueAsString(plan),evidence==null?null:json.writeValueAsString(evidence));
    }
    private Stored stored(java.sql.ResultSet r) throws java.sql.SQLException {
        List<UUID> sources=json.readValue(r.getString("source_key_ids"),json.getTypeFactory().constructCollectionType(List.class,UUID.class));
        return new Stored(r.getObject("transaction_id",UUID.class),json.readValue(r.getString("receipt"),AutomaticOperation.class),r.getBigDecimal("maximum_fee_atomic").toBigIntegerExact(),
                List.copyOf(sources),r.getString("preparation_plan")==null?null:json.readValue(r.getString("preparation_plan"),AutomaticParentPlan.class),
                r.getString("preparation_evidence")==null?null:json.readTree(r.getString("preparation_evidence")));
    }
    public record Stored(UUID transactionId,AutomaticOperation receipt,BigInteger maximumFee,List<UUID> sourceKeyIds,AutomaticParentPlan plan,tools.jackson.databind.JsonNode evidence) { }
    public record Budget(BigInteger lifetimeAmount,BigInteger dailyAmount,long dailyCount) { }
}
