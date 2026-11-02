package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.model.ApprovalPolicy;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.approval.repository.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Independent admission and review. This service alone cannot grant an arbitrary signing digest. */
@Service
public class ApprovalWorkflowService {
    private final ApprovalTenantRepository tenants;
    private final ApprovalPolicyRepository policies;
    private final ApprovalMemberRepository members;
    private final ApprovalRequestRepository requests;
    private final ApprovalDecisionRepository decisions;
    private final ApprovalAuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final ApprovalAssetRegistry assets;
    public ApprovalWorkflowService(ApprovalTenantRepository tenants,ApprovalPolicyRepository policies,
            ApprovalMemberRepository members,ApprovalRequestRepository requests,ApprovalDecisionRepository decisions,
            ApprovalAuditRepository audit,ObjectMapper json,Clock clock,ApprovalAssetRegistry assets) {
        this.tenants=tenants;this.policies=policies;this.members=members;this.requests=requests;
        this.decisions=decisions;this.audit=audit;this.json=json;this.clock=clock;this.assets=assets;
    }

    @Transactional(rollbackFor=Throwable.class)
    public ApprovalRequest submit(WithdrawalIntent intent,String serviceIdentity) {
        if(!List.of("API","CONSOLE").contains(intent.source()))throw new IllegalArgumentException("human withdrawal admission requires an original API or Console source");
        requireTenant(intent.tenantId()); // Locks admission, including concurrent rolling-limit checks.
        var existing=requests.lock(intent.tenantId(),intent.id());
        if(existing.isPresent()) {
            require(existing.get().intent().hash(json).equals(intent.hash(json)),"existing approval intent differs");
            return expire(existing.get());
        }
        var policy=policies.find(intent.tenantId(),intent.chain(),intent.network(),intent.assetSymbol())
                .orElseThrow(()->new IllegalStateException("independent approval policy has not been configured"));
        var asset=assets.require(intent.chain(),intent.network(),intent.assetSymbol());
        String hash=asset.bind(intent,json);
        Instant now=clock.instant();
        var reasons=policy.reasons(intent,requests.rollingAmount(intent.tenantId(),intent.chain(),intent.network(),intent.assetSymbol(),now),
                requests.knownRecipient(intent.tenantId(),intent.chain(),intent.network(),intent.assetSymbol(),intent.recipient(),now));
        var request=new ApprovalRequest(intent,hash,asset,policy.id(),policy.version(),reasons,reasons.isEmpty()?0:policy.requiredReviewers(),
                reasons.isEmpty()?"APPROVED":"PENDING",false,now,now.plusSeconds(policy.expirySeconds()));
        requests.insert(request);
        audit.insert(intent.tenantId(),"SERVICE",serviceIdentity,"INTENT.SUBMIT",intent.id(),
                Map.of("intentHash",hash,"policyVersion",policy.version(),"reasons",reasons));
        return request;
    }

    @Transactional(rollbackFor=Throwable.class)
    public ApprovalRequest decide(UUID tenant,UUID requestId,UUID reviewer,String decision,String reason,String expectedIntentHash) {
        requireMember(tenant,reviewer,false);
        members.activeForDecision(tenant,reviewer).orElseThrow(()->new SecurityException("independent approval member disabled"));
        require(List.of("APPROVE","REJECT").contains(decision),"invalid decision");
        require(reason!=null && !reason.isBlank() && reason.length()<=1000
                && reason.chars().noneMatch(Character::isISOControl),"a decision reason is required");
        var current=requests.lock(tenant,requestId).orElseThrow(()->new IllegalArgumentException("approval request not found"));
        require(current.intentHash().equals(expectedIntentHash),"reviewed intent has changed");
        require(!current.intent().source().equals("CONSOLE") || !current.intent().initiatorId().equals(reviewer),"self approval is forbidden");
        var previous=decisions.find(tenant,requestId,reviewer);
        if(previous.isPresent()) {
            require(previous.get().decision().equals(decision) && previous.get().reason().equals(reason),"reviewer decision is immutable");
            return current;
        }
        current=expire(current);
        if(current.status().equals("EXPIRED")) return current; // Commit expiration; never throw it away by rollback.
        require(current.status().equals("PENDING") && !current.signingStarted(),"approval request is no longer pending");
        decisions.insert(tenant,requestId,reviewer,decision,reason,clock.instant());
        String result=decision.equals("REJECT")?"REJECTED":decisions.approvals(tenant,requestId)>=current.requiredReviewers()?"APPROVED":"PENDING";
        if(!result.equals("PENDING")) require(requests.decide(tenant,requestId,result)==1,"approval decision raced");
        audit.insert(tenant,"MEMBER",reviewer.toString(),"INTENT."+decision,requestId,
                Map.of("reason",reason,"intentHash",current.intentHash(),"status",result));
        return requests.find(tenant,requestId).orElseThrow();
    }

    @Transactional(rollbackFor=Throwable.class)
    public ApprovalPolicy configure(UUID tenant,UUID administrator,PolicySettings settings) {
        requireTenant(tenant);
        requireMember(tenant,administrator,true);
        assets.require(settings.chain(),settings.network(),settings.assetSymbol());
        var old=policies.find(tenant,settings.chain(),settings.network(),settings.assetSymbol());
        long version=old.map(ApprovalPolicy::version).orElse(0L);
        require(version==settings.expectedVersion(),"approval policy changed; reload before saving");
        var policy=new ApprovalPolicy(old.map(ApprovalPolicy::id).orElseGet(UUID::randomUUID),tenant,settings.chain(),settings.network(),settings.assetSymbol(),version+1,
                settings.singleAmount(),settings.rolling24hAmount(),settings.allWithdrawals(),settings.newRecipient(),settings.consoleWithdrawal(),settings.requiredReviewers(),settings.expirySeconds());
        if(old.isEmpty()) policies.insert(policy);
        else require(policies.update(policy,version)==1,"approval policy update raced");
        audit.insert(tenant,"MEMBER",administrator.toString(),"POLICY.UPDATE",policy.id(),policy);
        return policy;
    }

    @Transactional(rollbackFor=Throwable.class)
    public ApprovalRequest status(UUID tenant,UUID request) {
        return expire(requests.lock(tenant,request).orElseThrow(()->new IllegalArgumentException("approval request not found")));
    }

    @Transactional(readOnly=true)
    public List<ApprovalPolicy> policies(UUID tenant,UUID member) { requireMember(tenant,member,false);return policies.list(tenant); }

    @Transactional(readOnly=true)
    public RequestPage list(UUID tenant,UUID member,String state,int limit,int offset) {
        requireMember(tenant,member,false);
        require(List.of("","PENDING","APPROVED","REJECTED","EXPIRED").contains(state),"invalid approval state filter");
        int size=Math.min(Math.max(limit,1),100),start=Math.max(offset,0);
        return new RequestPage(requests.list(tenant,state,size,start),requests.count(tenant,state),size,start);
    }

    @Transactional(readOnly=true)
    public List<com.surprising.wallet.approval.model.ApprovalDecisionView> history(UUID tenant,UUID member,UUID request) {
        requireMember(tenant,member,false);
        requests.find(tenant,request).orElseThrow(()->new IllegalArgumentException("approval request not found"));
        return decisions.history(tenant,request);
    }

    private ApprovalRequest expire(ApprovalRequest current) {
        if(!current.signingStarted() && !clock.instant().isBefore(current.expiresAt())
                && List.of("PENDING","APPROVED").contains(current.status())) {
            int changed=current.status().equals("PENDING")?requests.decide(current.intent().tenantId(),current.intent().id(),"EXPIRED")
                    :requests.expireApproved(current.intent().tenantId(),current.intent().id());
            require(changed==1,"approval expiry raced");
            audit.insert(current.intent().tenantId(),"SYSTEM","expiry","INTENT.EXPIRE",current.intent().id(),Map.of("intentHash",current.intentHash()));
            return requests.find(current.intent().tenantId(),current.intent().id()).orElseThrow();
        }
        return current;
    }
    private void requireTenant(UUID tenant) { require(tenants.lockActive(tenant),"independent tenant is not enrolled or disabled"); }
    private void requireMember(UUID tenant,UUID member,boolean admin) {
        if(!tenants.active(tenant)) throw new SecurityException("independent approval tenant is disabled");
        var actor=members.active(tenant,member).orElseThrow(()->new SecurityException("independent approval member required"));
        if(admin && !actor.role().equals("TENANT_ADMIN")) throw new SecurityException("independent approval administrator required");
    }
    private static void require(boolean value,String message) { if(!value) throw new IllegalArgumentException(message); }
    public record PolicySettings(String chain,String network,String assetSymbol,long expectedVersion,BigDecimal singleAmount,
            BigDecimal rolling24hAmount,Boolean allWithdrawals,boolean newRecipient,boolean consoleWithdrawal,int requiredReviewers,int expirySeconds) {
        public PolicySettings { if(allWithdrawals==null) throw new IllegalArgumentException("allWithdrawals must be explicitly configured"); }
    }
    public record RequestPage(List<ApprovalRequest> items,long total,int limit,int offset) { }
}
