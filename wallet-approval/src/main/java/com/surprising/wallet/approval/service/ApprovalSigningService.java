package com.surprising.wallet.approval.service;

import com.surprising.wallet.common.approval.SigningGrant;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.approval.chain.ApprovalWithdrawalVerifiers;
import com.surprising.wallet.approval.chain.UtxoWithdrawalVerifier;
import com.surprising.wallet.approval.chain.UtxoReplacementVerifier;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient;
import com.surprising.wallet.common.approval.ApprovalTransactionHash;
import com.surprising.wallet.common.signing.UtxoSigningPayload;
import com.surprising.wallet.approval.config.ApprovalNativeProperties;
import com.surprising.wallet.approval.model.*;
import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.common.approval.SigningCandidate;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.common.signing.MpcSignCommand;
import java.math.BigInteger;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Final-wire authorization. The proof is returned only after the independent database commits. */
@Service
public class ApprovalSigningService {
    private final ApprovalTenantRepository tenants;
    private final ApprovalRequestRepository requests;
    private final ApprovalKeyRepository keys;
    private final ApprovalSigningTransactionRepository transactions;
    private final ApprovalSigningVersionRepository versions;
    private final ApprovalSigningSlotRepository slots;
    private final ApprovalSigningGrantRepository grants;
    private final ApprovalFeeReservationRepository fees;
    private final ApprovalAuditRepository audit;
    private final ApprovalWorkflowService workflow;
    private final ApprovalAssetRegistry assets;
    private final ApprovalFeeBudgets budgets;
    private final ApprovalGrantSigner signer;
    private final ApprovalNativeProperties nativeNodes;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Clock clock;
    private final ApprovalWithdrawalVerifiers verifiers;
    private final ApprovalUtxoRpcClient utxoRpc;
    private final ApprovalSpendLockRepository spends;
    private final com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient nativeRegistry;
    private final ApprovalAutomationService automation;

    public ApprovalSigningService(ApprovalTenantRepository tenants,ApprovalRequestRepository requests,ApprovalKeyRepository keys,
            ApprovalSigningTransactionRepository transactions,ApprovalSigningVersionRepository versions,ApprovalSigningSlotRepository slots,
            ApprovalSigningGrantRepository grants,ApprovalFeeReservationRepository fees,ApprovalAuditRepository audit,
            ApprovalWorkflowService workflow,ApprovalAssetRegistry assets,ApprovalFeeBudgets budgets,ApprovalGrantSigner signer,
            ApprovalNativeProperties nativeNodes,PlatformTransactionManager manager,ObjectMapper json,Clock clock,ApprovalWithdrawalVerifiers verifiers,
            ApprovalUtxoRpcClient utxoRpc,ApprovalSpendLockRepository spends,com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient nativeRegistry,ApprovalAutomationService automation) {
        this.tenants=tenants;this.requests=requests;this.keys=keys;this.transactions=transactions;this.versions=versions;
        this.slots=slots;this.grants=grants;this.fees=fees;this.audit=audit;this.workflow=workflow;this.assets=assets;
        this.budgets=budgets;this.signer=signer;this.nativeNodes=nativeNodes;this.transaction=new TransactionTemplate(manager);
        this.json=json;this.clock=clock;
        this.verifiers=verifiers;
        this.utxoRpc=utxoRpc;this.spends=spends;
        this.nativeRegistry=nativeRegistry;
        this.automation=automation;
    }
    public Outcome authorizeMoneroRelay(com.surprising.wallet.common.approval.MoneroRelayCandidate candidate,String serviceIdentity) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"relay authorization requires committed Wallet state");
        var execution=candidate.execution();var original=transaction.execute(status->{
            require(tenants.lockActive(execution.tenantId()),"independent tenant is disabled");
            var admitted=grants.find(execution.tenantId(),candidate.signingRequestId(),execution.sessionId());
            if(admitted.isPresent())require(admitted.get().execution().equals(execution),"original relay descriptor cannot change");
            return grants.find(execution.tenantId(),candidate.signingRequestId(),candidate.signSessionId()).orElseThrow(()->new IllegalArgumentException("original independently authorized native payment required"));
        });
        var previous=grants.find(execution.tenantId(),candidate.signingRequestId(),execution.sessionId());if(previous.isPresent())return new Outcome("AUTHORIZED",previous.get().grant());
        com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient.MoneroPayment observed;
        try { observed=nativeRegistry.moneroPayment(original.execution(),execution.online().getFirst()); }
        catch(MpcClient.CallException unavailable) {
            if(Thread.currentThread().isInterrupted())throw unavailable;
            return new Outcome("HOLDER_UNAVAILABLE",null);
        }
        return transaction.execute(status->{
            require(tenants.lockActive(execution.tenantId()),"independent tenant is disabled");
            var cached=grants.find(execution.tenantId(),candidate.signingRequestId(),execution.sessionId());
            if(cached.isPresent()){require(cached.get().execution().equals(execution),"original relay descriptor changed");return new Outcome("AUTHORIZED",cached.get().grant());}
            var slot=slots.find(execution.tenantId(),candidate.signingRequestId()).orElseThrow();var source=slot.candidate().command();
            var key=keys.lock(execution.tenantId(),execution.keyId()).orElseThrow();
            require(source.chain()==com.surprising.wallet.common.chain.ChainType.XMR && source.keyId().equals(execution.keyId()) && source.keyEpoch()==execution.epoch()
                    && key.state().equals("ACTIVE") && key.epoch()==execution.epoch() && key.keyGroup().equals("XMR")
                    && key.publicKeyHex().equals(observed.publicKeyHex()) && java.util.Objects.equals(key.publicAddress(),observed.publicAddress()),"original Monero relay key identity differs");
            var payment=json.readTree(HexFormat.of().parseHex(original.execution().messageHex()));var relay=json.readTree(HexFormat.of().parseHex(execution.messageHex()));
            require(relay.isObject() && relay.size()==7 && relay.path("kind").asText().equals("RELAY")
                    && relay.path("network").equals(payment.path("network")) && relay.path("genesisHash").equals(payment.path("genesisHash"))
                    && relay.path("sender").equals(payment.path("sender")) && relay.path("signSessionId").asText().equals(candidate.signSessionId().toString())
                    && relay.path("signIntentHash").asText().equals(observed.intentHash()) && relay.path("transactionHash").asText().equals(observed.transactionHash()),"Monero relay differs from the actual original native artifact");
            var originalClaims=SigningAuthorization.verifyOriginalProof(original.grant().proof(),SigningAuthorization.publicKey(signer.publicKeyHex()),original.execution());
            var now=clock.instant().truncatedTo(ChronoUnit.SECONDS);UUID id=UUID.randomUUID();
            var claims=new SigningAuthorization.Claims(id,candidate.signingRequestId(),execution.tenantId(),execution.keyId(),execution.epoch(),execution.sessionId(),execution.operation().name(),execution.algorithm().name(),execution.online(),
                    SigningAuthorization.hash(HexFormat.of().parseHex(execution.messageHex())),"XMR",originalClaims.network(),originalClaims.businessHash(),originalClaims.purpose(),now,now.plusSeconds(120));
            var grant=new SigningGrant(id,execution.tenantId(),candidate.signingRequestId(),execution.sessionId(),signer.sign(claims),now,claims.expiresAt());
            grants.insert(new StoredSigningGrant(grant,execution,null,null));
            audit.insert(execution.tenantId(),"SERVICE",serviceIdentity,"MONERO.RELAY_AUTHORIZE",id,Map.of("requestId",candidate.signingRequestId(),"signSessionId",candidate.signSessionId(),"relaySessionId",execution.sessionId(),"transactionHash",observed.transactionHash(),"holder",execution.online().getFirst()));
            return new Outcome("AUTHORIZED",grant);
        });
    }

    public Outcome authorize(SigningCandidate candidate,String serviceIdentity) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"authorization must be requested outside a caller transaction");
        var prepared=transaction.execute(status->prepare(candidate));
        if(prepared.outcome()!=null)return prepared.outcome();
        ApprovalUtxoRpcClient.Observation evidence=null;
        if(prepared.utxo()!=null && prepared.original()==null) {
            var points=prepared.utxo().unsigned().inputs().stream().map(i->new ApprovalUtxoRpcClient.Outpoint(i.txId(),i.outputIndex())).toList();
            evidence=utxoRpc.observe(prepared.reviewed().getFirst().assetSnapshot(),points);
        }
        var accountEvidence=prepared.utxo()==null && prepared.version()==null
                ?verifiers.observe(prepared.reviewed().getFirst(),candidate,clock.instant()):null;
        if(prepared.automatic()!=null && prepared.version()==null) {
            var started=clock.instant();var balances=automation.observeOperatingBalances(prepared.automatic(),candidate);
            if(balances!=null) {
                if(accountEvidence==null)accountEvidence=json.createObjectNode().put("chain",prepared.reviewed().getFirst().assetSnapshot().chain())
                        .put("networkIdentity",prepared.reviewed().getFirst().assetSnapshot().networkIdentity()).put("observedAt",started.toString());
                ((tools.jackson.databind.node.ObjectNode)accountEvidence).set("operatingBalances",balances);
            }
        }
        var observed=evidence;
        var accountFacts=accountEvidence;
        return transaction.execute(status->issue(candidate,serviceIdentity,observed,accountFacts));
    }

    /** No network call under this lock. Repeated after observation so expiry/configuration races cannot authorize stale work. */
    private Prepared prepare(SigningCandidate candidate) {
        var command=candidate.command();var execution=candidate.execution();UUID tenant=command.tenantId();
        require(tenants.lockActive(tenant),"independent tenant is not enrolled or disabled");
        var oldSlot=slots.find(tenant,candidate.signingRequestId());
        if(oldSlot.isPresent()) {
            var original=oldSlot.get().candidate();
            require(original.command().equals(command) && original.approvalRequestIds().equals(candidate.approvalRequestIds()),"original signing transaction or approval binding differs");
            var admitted=grants.find(tenant,candidate.signingRequestId(),execution.sessionId());
            if(admitted.isPresent()) {
                require(admitted.get().execution().equals(execution) && java.util.Objects.equals(admitted.get().replayActor(),candidate.replayActor())
                        && java.util.Objects.equals(admitted.get().replayReason(),candidate.replayReason()),"original native session or replay audit differs");
                return Prepared.finished(new Outcome("AUTHORIZED",admitted.get().grant(),command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL?null:automation.original(command)));
            }
            require(candidate.replayActor()!=null,"a fresh native session requires explicit operator replay audit");
        } else require(candidate.replayActor()==null,"initial authorization cannot replace a missing original signing slot");
        require(verifiers.supports(command),"this transaction family requires an independently implemented verifier");
        var automatic=command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL?null:automation.prepare(candidate);
        var reviewed=automatic==null?candidate.approvalRequestIds().stream().map(id->workflow.status(tenant,id)).toList():automatic.receipt().payments();
        for(String state:List.of("REJECTED","EXPIRED","PENDING"))
            if(reviewed.stream().anyMatch(r->r.status().equals(state)))return Prepared.finished(new Outcome(state,null));
        require(reviewed.stream().allMatch(r->r.status().equals("APPROVED")),"invalid reviewed batch state");
        var asset=reviewed.getFirst().assetSnapshot();
        for(var review:reviewed) {
            require(review.intent().chain().equals(command.chain().name()) && review.assetSnapshot().equals(asset),"reviewed batch owner, network or asset differs");
            assets.requireCurrent(review.assetSnapshot());
        }
        var payload=command.chain().isBitcoinLike()?UtxoSigningPayload.decode(command,json):null;
        boolean eip7702=command.payloadEncoding().equals(com.surprising.wallet.common.signing.Evm7702SigningPayload.ENCODING);
        if(payload==null && !eip7702)require(reviewed.size()==1 && reviewed.getFirst().intent().businessReference().equals(command.businessReference()),"reviewed business owner differs");
        String reference=payload==null?command.businessReference():"utxo:"+payload.task().transactionId();
        var existing=transactions.find(tenant,asset.chain(),asset.network(),command.businessType().name(),reference);
        ApprovalSigningVersion version=null;RbfContext replacement=null;
        if(existing.isPresent()) {
            require(oldSlot.isPresent() || payload!=null || eip7702 || command.chain()==com.surprising.wallet.common.chain.ChainType.SOLANA,"business transaction already has an immutable original candidate");
            version=versions.find(tenant,existing.get().id(),ApprovalTransactionHash.version(command)).orElse(null);
            if(payload!=null && !existing.get().businessHash().equals(ApprovalTransactionHash.business(reviewed,command))) {
                var history=versions.history(tenant,existing.get().id());var roots=history.stream().filter(v->existing.get().businessHash().equals(ApprovalTransactionHash.business(reviewed,command,v.versionHash()))).toList();
                require(roots.size()==1 && history.size()<=5,"original batch approval binding or bounded version history differs");var root=roots.getFirst();
                var initial=UtxoReplacementVerifier.payload(root,json);var previous=version==null?history.stream().map(v->UtxoReplacementVerifier.payload(v,json))
                        .max(java.util.Comparator.comparingLong(p->p.unsigned().feeAtomic())).orElseThrow():initial;
                var permission=utxoRpc.rbfPolicy(asset);UtxoReplacementVerifier.verify(initial,previous,payload,permission,history.size(),version==null);
                replacement=new RbfContext(root,initial,permission,version==null);
            } else require(existing.get().businessHash().equals(ApprovalTransactionHash.business(reviewed,command)) && version!=null,"original transaction version or approval set differs");
            require(oldSlot.isEmpty() || version!=null && oldSlot.get().versionId().equals(version.id()),"original slot belongs to another version");
            require(version==null || oldSlot.isPresent() || !slots.nameExists(tenant,version.id(),command.signingSlot()),"original input slot cannot be assigned a new Wallet request ID");
        } else require(oldSlot.isEmpty(),"original signing transaction is missing");
        for(var review:automatic==null?reviewed:List.<ApprovalRequest>of()) {
            var binding=requests.signingTransaction(tenant,review.intent().id());
            require(existing.isEmpty()?binding.isEmpty():binding.filter(existing.get().id()::equals).isPresent(),"approval already belongs to an original signing transaction");
        }
        return new Prepared(null,reviewed,existing.orElse(null),version,reference,payload,automatic,replacement);
    }

    private Outcome issue(SigningCandidate candidate,String serviceIdentity,ApprovalUtxoRpcClient.Observation fresh,tools.jackson.databind.JsonNode accountFresh) {
        var prepared=prepare(candidate);if(prepared.outcome()!=null)return prepared.outcome();
        var command=candidate.command();var execution=candidate.execution();UUID tenant=command.tenantId();
        var reviewed=prepared.reviewed();var asset=reviewed.getFirst().assetSnapshot();
        var required=new java.util.HashSet<UUID>();required.add(command.keyId());
        if(prepared.automatic()!=null)required.addAll(prepared.automatic().requiredKeyIds());
        required.addAll(verifiers.additionalKeyIds(candidate));
        if(prepared.utxo()!=null) { required.addAll(prepared.utxo().keyIds());required.add(prepared.utxo().changeKeyId()); }
        for(String publicKey:verifiers.additionalPublicKeys(candidate))required.add(keys.findByPublicKey(tenant,command.chain().name(),publicKey)
                .orElseThrow(()->new IllegalArgumentException("every required signer must be independently registered for this tenant")).keyId());
        var registered=new java.util.HashMap<UUID,RegisteredMpcKey>();
        for(var id:required.stream().sorted().toList()) {
            var key=keys.lock(tenant,id).orElseThrow(()->new IllegalArgumentException("independently registered MPC key required"));
            require(key.state().equals("ACTIVE") && key.keyGroup().equals(command.chain().isEvm()?"EVM":command.chain().name())
                    && key.algorithm().equals(execution.algorithm().name()),"independent key state, group or algorithm differs");
            registered.put(id,key);
        }
        var key=registered.get(command.keyId());require(key.epoch()==command.keyEpoch(),"independent key epoch differs");
        var roster=nativeNodes.nodes().stream().map(MpcClient.Node::partyId).toList();
        require(roster.size()==3 && execution.online().equals(roster.stream().filter(execution.online()::contains).toList()),"signing participants differ from the independently registered holder roster");
        var instant=clock.instant();var now=instant.truncatedTo(ChronoUnit.SECONDS);
        ApprovalUtxoRpcClient.Observation evidence=null;
        var accountEvidence=prepared.version()==null?accountFresh:prepared.version().chainEvidence();
        if(prepared.version()==null && accountEvidence!=null) {
            var observedAt=java.time.Instant.parse(accountEvidence.path("observedAt").asText());
            require(!observedAt.isAfter(instant) && !observedAt.isBefore(instant.minusSeconds(60)),"independent account observation expired before admission");
        }
        String spendDomain;BigInteger fee;boolean recover,preparationVerified;List<ApprovalUtxoRpcClient.Outpoint> points;
        if(prepared.utxo()!=null) {
            if(prepared.original()!=null) {
                var original=prepared.version()!=null?prepared.version():prepared.replacement().rootVersion();
                require(original.chainEvidence()!=null,"original independent chain evidence is missing");
                evidence=json.treeToValue(original.chainEvidence(),ApprovalUtxoRpcClient.Observation.class);
            } else {
                evidence=fresh;
                require(evidence!=null && !evidence.observedAt().isAfter(instant) && !evidence.observedAt().isBefore(instant.minusSeconds(30)),"independent UTXO observation expired before original admission");
            }
            var verified=UtxoWithdrawalVerifier.verify(reviewed,registered,candidate,evidence,json,prepared.replacement()==null?null:prepared.replacement().root());
            spendDomain=verified.spendDomain();fee=verified.maximumFeeAtomic();points=verified.inputs();recover=true;preparationVerified=false;
        } else {
            var verified=verifiers.verifyBatch(reviewed,key,registered,candidate,now,accountEvidence);
            spendDomain=verified.spendDomain();fee=verified.maximumFeeAtomic();recover=verified.supportsExactRecovery();points=verified.inputs();preparationVerified=verified.preparationVerified();
        }
        if(prepared.automatic()!=null)preparationVerified|=automation.verifyOperatingBalances(prepared.automatic(),candidate,
            accountEvidence==null || !accountEvidence.has("operatingBalances")?null:accountEvidence.path("operatingBalances"),fee,prepared.original()==null);
        var oldSlot=slots.find(tenant,candidate.signingRequestId());
        require(oldSlot.isEmpty() || recover,"this transaction family cannot safely authorize another native session");
        BigInteger limit=budgets.limit(tenant,asset);require(fee.compareTo(limit)<=0,"transaction maximum fee exceeds the independent native fee budget");
        String versionHash=ApprovalTransactionHash.version(command),businessHash=ApprovalTransactionHash.business(reviewed,command);
        BigInteger reserve=fee;
        if(prepared.utxo()!=null && utxoRpc.rbfPolicy(asset).enabled())reserve=reviewed.stream().map(r->r.intent().maximumNetworkFee().movePointRight(asset.nativeDecimals()).toBigIntegerExact())
                .reduce(BigInteger.ZERO,BigInteger::add).min(asset.networkFeeLimit().movePointRight(asset.nativeDecimals()).toBigIntegerExact());
        UUID transactionId,versionId;
        if(prepared.original()!=null) {
            var original=prepared.original();
            require(original.spendDomain().equals(spendDomain) && (prepared.replacement()!=null || original.businessHash().equals(businessHash)),"original transaction changed its spend domain");
            transactionId=original.id();require(fee.compareTo(fees.reserved(tenant,transactionId))<=0,"replacement exceeds its original permanent native fee reservation");
            if(prepared.version()==null) {
                require(prepared.replacement()!=null && prepared.replacement().newVersion(),"explicit bounded BTC replacement required");versionId=UUID.randomUUID();
                versions.insert(new ApprovalSigningVersion(versionId,tenant,transactionId,versionHash,command.payloadEncoding(),command.payloadHex(),json.valueToTree(evidence)));
                audit.insert(tenant,"SERVICE",serviceIdentity,"SIGNING.BTC_REPLACEMENT",versionId,Map.of("transactionId",transactionId,"versionHash",versionHash,"maximumFeeAtomic",fee.toString(),"permission",prepared.replacement().permission()));
            } else versionId=prepared.version().id();
            for(var point:points)require(spends.owner(tenant,asset.chain(),asset.network(),point.txId(),point.index()).filter(transactionId::equals).isPresent(),"original UTXO input ownership is missing");
        } else {
            require(!fees.differentDomain(tenant,asset,now),"independent native fee accounting domain changed within the active budget window");
            require(fees.rolling(tenant,asset,now).add(reserve).compareTo(limit)<=0,"independent rolling native fee budget exceeded");
            transactionId=UUID.randomUUID();versionId=UUID.randomUUID();
            transactions.insert(new ApprovalSigningTransaction(transactionId,tenant,asset.chain(),asset.network(),command.businessType().name(),prepared.reference(),spendDomain,businessHash,now));
            for(var point:points)require(spends.claim(tenant,transactionId,asset.chain(),asset.network(),point.txId(),point.index())==1,"UTXO input already belongs to an original signing transaction");
            versions.insert(new ApprovalSigningVersion(versionId,tenant,transactionId,versionHash,command.payloadEncoding(),command.payloadHex(),evidence==null?accountEvidence:json.valueToTree(evidence)));
            if(prepared.automatic()==null)for(var review:reviewed)require(requests.bindSigningTransaction(tenant,review.intent().id(),transactionId)==1,"approval transaction binding raced");
            else automation.admit(prepared.automatic(),transactionId,reserve,preparationVerified,serviceIdentity);
            fees.insert(tenant,transactionId,asset,reserve,now);
        }
        if(oldSlot.isEmpty())slots.insert(new ApprovalSigningSlot(candidate.signingRequestId(),tenant,versionId,SigningAuthorization.hash(json.writeValueAsBytes(candidate)),candidate));
        UUID grantId=UUID.randomUUID();
        var claims=new SigningAuthorization.Claims(grantId,candidate.signingRequestId(),tenant,key.keyId(),key.epoch(),execution.sessionId(),execution.operation().name(),execution.algorithm().name(),execution.online(),
                SigningAuthorization.hash(HexFormat.of().parseHex(execution.messageHex())),asset.chain(),asset.network(),businessHash,command.businessType().name(),now,now.plusSeconds(120));
        var grant=new SigningGrant(grantId,tenant,candidate.signingRequestId(),execution.sessionId(),signer.sign(claims),now,claims.expiresAt());
        grants.insert(new StoredSigningGrant(grant,execution,candidate.replayActor(),candidate.replayReason()));
        var detail=new java.util.LinkedHashMap<String,Object>(Map.of("requestIds",candidate.approvalRequestIds(),"transactionId",transactionId,
                "slotId",candidate.signingRequestId(),"sessionId",execution.sessionId(),"businessHash",businessHash,"maximumFeeAtomic",fee.toString()));
        if(oldSlot.isPresent()) { detail.put("replayActor",candidate.replayActor());detail.put("replayReason",candidate.replayReason()); }
        audit.insert(tenant,"SERVICE",serviceIdentity,oldSlot.isPresent()?"SIGNING.AUTHORIZE_REPLAY":"SIGNING.AUTHORIZE",grantId,detail);
        return new Outcome("AUTHORIZED",grant,prepared.automatic()==null?null:prepared.automatic().receipt());
    }
    private record RbfContext(ApprovalSigningVersion rootVersion,UtxoSigningPayload root,ApprovalUtxoRpcClient.RbfPolicy permission,boolean newVersion) { }
    private record Prepared(Outcome outcome,List<ApprovalRequest> reviewed,ApprovalSigningTransaction original,ApprovalSigningVersion version,String reference,UtxoSigningPayload utxo,ApprovalAutomationService.Admission automatic,RbfContext replacement) {
        static Prepared finished(Outcome result) { return new Prepared(result,List.of(),null,null,null,null,null,null); }
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
    public record Outcome(String status,SigningGrant grant,com.surprising.wallet.common.approval.AutomaticOperation automaticOperation) {
        public Outcome(String status,SigningGrant grant){this(status,grant,null);}
    }
}
