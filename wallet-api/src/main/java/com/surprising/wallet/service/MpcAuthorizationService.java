package com.surprising.wallet.service;

import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.common.approval.SigningCandidate;
import com.surprising.wallet.common.approval.SigningGrant;
import com.surprising.wallet.common.approval.ApprovalTransactionHash;
import com.surprising.wallet.common.signing.UtxoSigningPayload;
import com.surprising.wallet.common.signing.Evm7702SigningPayload;
import com.surprising.wallet.common.signing.MpcSignCommand;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.custody.WithdrawalApprovalRecord;
import com.surprising.wallet.model.mpc.MpcPendingException;
import com.surprising.wallet.model.mpc.MpcSignAttempt;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.repository.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Obtain an independent final-wire grant and commit its exact original proof before any native SIGN dispatch. */
@Service
public class MpcAuthorizationService {
    private final WalletMpcSignRequestRepository requests;
    private final WalletMpcSignAttemptRepository attempts;
    private final WalletWithdrawalApprovalRepository approvals;
    private final CustodyAuditRepository audit;
    private final WithdrawalApprovalClient authority;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final WalletMpcKeyRepository keys;
    private final WalletMpcKeyEpochRepository epochs;
    private final WalletMpcRelayRepository relays;
    private final CustodyTenantUserRepository users;
    private final MpcNonceAdmissionService nonceAdmission;
    public MpcAuthorizationService(WalletMpcSignRequestRepository requests,WalletMpcSignAttemptRepository attempts,WalletWithdrawalApprovalRepository approvals,
            CustodyAuditRepository audit,WithdrawalApprovalClient authority,ObjectMapper json,PlatformTransactionManager manager,
            WalletMpcKeyRepository keys,WalletMpcKeyEpochRepository epochs,WalletMpcRelayRepository relays,CustodyTenantUserRepository users,MpcNonceAdmissionService nonceAdmission) {
        this.requests=requests;this.attempts=attempts;this.approvals=approvals;this.audit=audit;this.authority=authority;this.json=json;
        this.transaction=new TransactionTemplate(manager);
        this.keys=keys;this.epochs=epochs;
        this.relays=relays;
        this.users=users;
        this.nonceAdmission=nonceAdmission;
    }
    public SigningAuthorization.Proof authorizeMoneroRelay(UUID requestId,UUID signSessionId,com.surprising.wallet.common.signing.MpcClient.Request relay) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"Monero relay authorization requires committed original business state");
        var tenant=relay.tenantId();var request=requests.find(tenant,requestId).orElseThrow();var original=attempts.find(tenant,signSessionId).orElseThrow();
        require(request.state()==MpcSignRequest.State.COMPLETED && request.currentAttemptId().equals(signSessionId) && original.requestId().equals(requestId)
                && original.state()==MpcSignAttempt.State.COMPLETED && original.authorization()!=null && original.request().online().containsAll(relay.online()),"Monero relay requires its original independently authorized completed payment");
        var originalClaims=request.command().businessType()==MpcSignCommand.BusinessType.WITHDRAWAL
                ?verify(request,original,reviewed(request.command(),requestId),original.authorization()):verifyAutomatic(request,original,original.automaticOperation(),original.authorization());
        var stored=transaction.execute(status->relays.reserve(requestId,signSessionId,relay));
        if(stored.proof()!=null){verifyRelay(requestId,relay,originalClaims,stored.proof());return stored.proof();}
        var response=authority.authorizeMoneroRelay(new com.surprising.wallet.common.approval.MoneroRelayCandidate(requestId,signSessionId,relay));
        if(response.status().equals("HOLDER_UNAVAILABLE") && response.grant()==null)return null;
        require(response.status().equals("AUTHORIZED") && response.grant()!=null,"original native Monero relay is not independently authorized");
        var grant=response.grant();var claims=verifyRelay(requestId,relay,originalClaims,grant.proof());
        require(grant.id().equals(claims.authorizationId()) && grant.tenantId().equals(tenant) && grant.slotId().equals(requestId) && grant.sessionId().equals(relay.sessionId())
                && grant.issuedAt().equals(claims.issuedAt()) && grant.expiresAt().equals(claims.expiresAt()),"Monero relay receipt differs from its proof");
        return transaction.execute(status->{
            requests.lock(tenant,requestId).orElseThrow();var current=relays.find(tenant,requestId,relay.online().getFirst());
            if(current.proof()==null) {
                require(relays.authorize(tenant,requestId,relay.online().getFirst(),grant.proof())==1,"relay proof persistence raced");
                audit.insert(UUID.randomUUID(),tenant,"SYSTEM","independent-approval","MPC.RELAY_AUTHORIZE","MPC_SIGN_REQUEST",requestId.toString(),null,
                        json.writeValueAsString(Map.of("authorizationId",grant.id(),"signSessionId",signSessionId,"relaySessionId",relay.sessionId(),"holder",relay.online().getFirst())));
            } else require(current.proof().equals(grant.proof()),"original relay proof cannot be replaced");
            return relays.find(tenant,requestId,relay.online().getFirst()).proof();
        });
    }
    /** The original collection is durably claimed by its caller before this independent network admission. */
    public void planCollection(com.surprising.wallet.common.approval.AutomaticParentPlan plan) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"collection planning requires committed original business state");
        var receipt=authority.plan(plan);require(receipt.businessType().equals("COLLECTION") && receipt.businessReference().equals(plan.businessReference())
                && receipt.parentOperationId()==null && receipt.payments().size()==1,"independent collection planning receipt differs");
        var payment=receipt.payments().getFirst();require(payment.intent().tenantId().equals(plan.tenantId()) && payment.intent().chain().equals(plan.chain())
                && payment.intent().network().equals(plan.network()) && payment.intent().assetSymbol().equals(plan.assetSymbol()) && payment.intent().recipient().equals(plan.recipient())
                && payment.intent().amount().compareTo(plan.amount())==0 && payment.intentHash().equals(payment.assetSnapshot().bind(payment.intent(),json)),"independent collection plan changed the original payment");
        transaction.executeWithoutResult(status->audit.insert(UUID.randomUUID(),plan.tenantId(),"SYSTEM","independent-approval","MPC.COLLECTION_PLAN","AUTOMATIC_OPERATION",receipt.id().toString(),null,
                json.writeValueAsString(Map.of("sourceKeyId",plan.sourceKeyId(),"ruleId",receipt.ruleId(),"ruleFingerprint",receipt.ruleFingerprint(),"businessReference",plan.businessReference()))));
    }
    private SigningAuthorization.Claims verifyRelay(UUID requestId,com.surprising.wallet.common.signing.MpcClient.Request relay,SigningAuthorization.Claims original,SigningAuthorization.Proof proof) {
        var claims=SigningAuthorization.verifyOriginalProof(proof,SigningAuthorization.publicKey(authority.verificationKeyHex()),relay);
        require(claims.requestId().equals(requestId) && claims.chain().equals("XMR") && claims.network().equals(original.network())
                && claims.purpose().equals(original.purpose()) && claims.businessHash().equals(original.businessHash()),"Monero relay proof differs from original approved payment");return claims;
    }

    public MpcKey registerKey(UUID tenant,UUID keyId) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent key observation requires committed native ceremony state");
        var key=keys.find(tenant,keyId).orElseThrow();require(key.state()==MpcKey.State.ACTIVE,"native key is not active");
        var epoch=epochs.find(tenant,keyId,key.activeEpoch()).orElseThrow();
        var cached=epochs.registration(tenant,keyId,key.activeEpoch());
        if(cached.isPresent()) { requireRegistration(key,cached.get());return key; }
        require(key.activeEpoch()==1 || epochs.registration(tenant,keyId,key.activeEpoch()-1).isPresent(),"source epoch must be independently registered before refresh");
        var receipt=authority.register(key.keyGroup(),epoch.request(),key.publicKeyHex());
        requireRegistration(key,receipt);
        transaction.executeWithoutResult(status->{
            var current=keys.lock(tenant,keyId).orElseThrow();
            require(current.publicKeyHex().equals(key.publicKeyHex()) && current.algorithm().equals(key.algorithm()) && current.keyGroup().equals(key.keyGroup())
                    && current.activeEpoch()>=key.activeEpoch(),"native key changed during independent registration");
            var previous=epochs.registration(tenant,keyId,key.activeEpoch());
            if(previous.isEmpty()) {
                require(epochs.register(tenant,keyId,key.activeEpoch(),receipt)==1,"independent key registration persistence raced");
                audit.insert(UUID.randomUUID(),tenant,"SYSTEM","independent-approval","MPC.KEY_REGISTER","MPC_KEY",keyId.toString(),null,
                        json.writeValueAsString(Map.of("epoch",key.activeEpoch(),"sessionId",epoch.request().sessionId(),"publicKeyHex",key.publicKeyHex())));
            } else require(previous.get().equals(receipt),"original key registration cannot be replaced");
        });
        return keys.find(tenant,keyId).orElseThrow();
    }
    private static void requireRegistration(MpcKey key,tools.jackson.databind.JsonNode receipt) {
        require(receipt.path("tenantId").asText().equals(key.tenantId().toString()) && receipt.path("keyId").asText().equals(key.id().toString())
                && receipt.path("epoch").asInt()==key.activeEpoch() && receipt.path("publicKeyHex").asText().equals(key.publicKeyHex())
                && receipt.path("keyGroup").asText().equals(key.keyGroup()) && receipt.path("algorithm").asText().equals(key.algorithm())
                && receipt.path("state").asText().equals("ACTIVE"),"independent key registration differs from the original local identity");
    }
    public SigningAuthorization.Proof authorize(UUID tenant,UUID requestId,UUID attemptId) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent final authorization requires committed signing intent");
        var request=requests.find(tenant,requestId).orElseThrow();var attempt=attempts.find(tenant,attemptId).orElseThrow();
        if(request.state()==MpcSignRequest.State.CANCELLED)throw new MpcPendingException(requestId,"CANCELLED");
        require(attempt.requestId().equals(requestId),"independent grant attempt belongs to another request");
        var command=request.command();
        boolean manual=command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL;
        if(command.purpose()!=MpcSignCommand.Purpose.TRANSACTION && !command.payloadEncoding().equals(Evm7702SigningPayload.ENCODING))
            throw new MpcPendingException(requestId,"AUTHORIZATION_VERIFIER_REQUIRED");
        var reviewed=manual?reviewed(command,requestId):List.<WithdrawalApprovalRecord>of();
        var candidate=new SigningCandidate(requestId,reviewed.stream().map(r->r.intent().id()).toList(),command,attempt.request(),attempt.replayActor(),attempt.replayReason());
        if(attempt.authorization()!=null) {
            if(manual){require(attempt.automaticOperation()==null,"human withdrawal cannot use an operating receipt");verify(request,attempt,reviewed,attempt.authorization());}
            else verifyAutomatic(request,attempt,attempt.automaticOperation(),attempt.authorization());return attempt.authorization();
        }
        if(users.pendingApprovalRevocation(tenant))throw new MpcPendingException(requestId,"APPROVAL_REVOCATION_PENDING");
        var response=authority.authorize(candidate);
        if(!response.status().equals("AUTHORIZED") || response.grant()==null) throw new MpcPendingException(requestId,"AUTHORIZATION_"+response.status());
        var grant=response.grant();
        require(!manual || response.automaticOperation()==null,"human withdrawal cannot be admitted by an operating rule");
        var claims=manual?verify(request,attempt,reviewed,grant.proof()):verifyAutomatic(request,attempt,response.automaticOperation(),grant.proof());
        require(grant.id().equals(claims.authorizationId()) && grant.tenantId().equals(tenant) && grant.slotId().equals(requestId)
                && grant.sessionId().equals(attemptId) && grant.issuedAt().equals(claims.issuedAt()) && grant.expiresAt().equals(claims.expiresAt()),
                "independent grant receipt differs from its signed claims");
        return transaction.execute(status->{
            var current=requests.lock(tenant,requestId).orElseThrow();
            require(current.command().equals(command),"original signing command changed");
            require(current.state()!=MpcSignRequest.State.CANCELLED,"independently cancelled signing request cannot accept a grant");
            var existing=attempts.find(tenant,attemptId).orElseThrow();
            if(existing.authorization()==null) {
                nonceAdmission.admit(command,claims,authority.verificationKeyHex());
                require(attempts.authorize(tenant,attemptId,grant.proof(),response.automaticOperation())==1,"independent grant persistence raced");
                audit.insert(UUID.randomUUID(),tenant,"SYSTEM","independent-approval","MPC.AUTHORIZE","MPC_SIGN_REQUEST",requestId.toString(),null,
                        json.writeValueAsString(Map.of("authorizationId",grant.id(),"sessionId",attemptId,"businessHash",claims.businessHash())));
            } else require(existing.authorization().equals(grant.proof()) && java.util.Objects.equals(existing.automaticOperation(),response.automaticOperation()),"original independent grant/operating receipt cannot be replaced");
            return attempts.find(tenant,attemptId).orElseThrow().authorization();
        });
    }
    private List<WithdrawalApprovalRecord> reviewed(MpcSignCommand command,UUID requestId) {
        var references=command.businessReferences(json);
        require(references.stream().distinct().count()==references.size(),"each UTXO payment requires a distinct original order");
        var result=references.stream().map(reference->approvals.findByBusiness(command.tenantId(),command.chain().name(),reference)
                .orElseThrow(()->new MpcPendingException(requestId,"ADMISSION_REQUIRED"))).toList();
        for(var approval:result)require(approval.receipt()!=null && List.of("APPROVED","AUTHORIZED").contains(approval.state()),"original independent approval receipt is not ready");
        return result;
    }
    private SigningAuthorization.Claims verify(MpcSignRequest request,MpcSignAttempt attempt,List<WithdrawalApprovalRecord> reviewed,SigningAuthorization.Proof proof) {
        var command=request.command();
        for(var approval:reviewed)require(approval.receipt().intent().equals(approval.intent())
                && approval.receipt().intentHash().equals(approval.receipt().assetSnapshot().bind(approval.intent(),json)),"original reviewed asset snapshot differs");
        var claims=SigningAuthorization.verifyOriginalProof(proof,SigningAuthorization.publicKey(authority.verificationKeyHex()),attempt.request());
        String businessHash=ApprovalTransactionHash.business(reviewed.stream().map(WithdrawalApprovalRecord::receipt).toList(),command);
        require(claims.requestId().equals(request.id()) && claims.chain().equals(command.chain().name())
                && claims.network().equals(reviewed.getFirst().intent().network()) && claims.purpose().equals(command.businessType().name())
                && claims.businessHash().equals(businessHash),"independent proof differs from the original reviewed transaction");
        return claims;
    }
    private SigningAuthorization.Claims verifyAutomatic(MpcSignRequest request,MpcSignAttempt attempt,com.surprising.wallet.common.approval.AutomaticOperation operation,SigningAuthorization.Proof proof) {
        require(request.command().businessType()!=MpcSignCommand.BusinessType.WITHDRAWAL && operation!=null,"independent operating receipt is required");
        var claims=SigningAuthorization.verifyOriginalProof(proof,SigningAuthorization.publicKey(authority.verificationKeyHex()),attempt.request());
        require(claims.requestId().equals(request.id()) && claims.chain().equals(request.command().chain().name()) && claims.purpose().equals(request.command().businessType().name())
                && claims.network().equals(operation.payments().getFirst().assetSnapshot().network()) && claims.businessHash().equals(operation.businessHash(request.command(),json)),"automatic signing proof differs from original independently admitted operation");
        return claims;
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalStateException(message); }
}
