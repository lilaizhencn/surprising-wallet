package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.chain.*;
import com.surprising.wallet.approval.config.ApprovalAutomationProperties;
import com.surprising.wallet.approval.config.ApprovalAutomationProperties.Rule;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.*;
import java.math.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.surprising.wallet.approval.gateway.ApprovalOperatingBalanceReader;
import tools.jackson.databind.*;

/** Independent operating admission. This service cannot turn an ordinary customer withdrawal into an automatic payment. */
@Service
public class ApprovalAutomationService {
    private final ApprovalAutomationProperties policies;private final ApprovalAssetRegistry assets;private final ApprovalKeyRepository keys;
    private final ApprovalAutomaticOperationRepository operations;private final ApprovalRequestRepository humanRequests;private final ApprovalAuditRepository audit;
    private final ObjectMapper json;private final Clock clock;
    private final ApprovalTenantRepository tenants;private final ApprovalOperatingBalanceReader balanceReader;private final TransactionTemplate transaction;
    public ApprovalAutomationService(ApprovalAutomationProperties policies,ApprovalAssetRegistry assets,ApprovalKeyRepository keys,
            ApprovalAutomaticOperationRepository operations,ApprovalRequestRepository humanRequests,ApprovalAuditRepository audit,ObjectMapper json,Clock clock,
            ApprovalTenantRepository tenants,ApprovalOperatingBalanceReader balanceReader,PlatformTransactionManager manager) {
        this.policies=policies;this.assets=assets;this.keys=keys;this.operations=operations;this.humanRequests=humanRequests;this.audit=audit;this.json=json;this.clock=clock;
        this.tenants=tenants;this.balanceReader=balanceReader;this.transaction=new TransactionTemplate(manager);
        for(var rule:policies.rules()) {
            var asset=assets.require(rule.chain(),rule.network(),rule.symbol());
            for(var amount:List.of(rule.minimumAmount(),rule.maximumAmount(),rule.maximum24hAmount(),rule.maximumLifetimeAmount()))amount.movePointRight(asset.decimals()).toBigIntegerExact();
            rule.maximumNetworkFee().movePointRight(asset.nativeDecimals()).toBigIntegerExact();rule.maximumLifetimeNetworkFee().movePointRight(asset.nativeDecimals()).toBigIntegerExact();
            require(rule.maximumNetworkFee().compareTo(asset.networkFeeLimit())<=0,"operating fee ceiling exceeds independent asset policy");
        }
    }
    /** Reserve a fixed owned collection before short-lived wire preparation. It grants no native signing permission. */
    public AutomaticOperation plan(AutomaticParentPlan plan,String serviceIdentity) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"parent planning requires committed Wallet intent");
        var initial=transaction.execute(status->{require(tenants.lockActive(plan.tenantId()),"independent tenant is disabled");return preparePlan(plan);});
        if(initial.original()!=null)return initial.original();
        var balance=balanceReader.observe(initial.admission().receipt().payments().getFirst().assetSnapshot(),initial.source(),initial.sourceAddress(),plan.moneroObservation());
        return transaction.execute(status->{
            require(tenants.lockActive(plan.tenantId()),"independent tenant is disabled");var current=preparePlan(plan);if(current.original()!=null)return current.original();
            require(current.source().publicKeyHex().equals(initial.source().publicKeyHex()) && current.admission().receipt().ruleFingerprint().equals(initial.admission().receipt().ruleFingerprint()),"operating source/rule changed during independent balance read");
            var admission=current.admission();var asset=admission.receipt().payments().getFirst().assetSnapshot();
            verifyBalance(balance,asset,current.source().keyId(),current.sourceAddress(),plan.amount().movePointRight(asset.decimals()).toBigIntegerExact());
            BigInteger reserve=admission.rule().maximumNetworkFee().movePointRight(asset.nativeDecimals()).toBigIntegerExact();checkQuotas(admission,reserve);
            operations.insert(admission.receipt(),null,admission.sourceKeyIds(),reserve,null,plan,json.valueToTree(balance));
            audit.insert(plan.tenantId(),"SERVICE",serviceIdentity,"AUTOMATION.PLAN",admission.receipt().id(),Map.of("ruleId",admission.rule().id(),"ruleFingerprint",admission.receipt().ruleFingerprint(),"sourceKeyId",plan.sourceKeyId(),"businessReference",plan.businessReference(),"reservedFeeAtomic",reserve.toString()));
            return admission.receipt();
        });
    }
    private Planning preparePlan(AutomaticParentPlan plan) {
        var eligible=policies.rules().stream().filter(r->r.tenantId().equals(plan.tenantId()) && r.chain().equals(plan.chain()) && r.network().equals(plan.network())
                && r.symbol().equals(plan.assetSymbol()) && r.businessType().equals("COLLECTION")).toList();require(eligible.size()==1,"one independently installed collection rule required");var rule=eligible.getFirst();
        var asset=assets.require(plan.chain(),plan.network(),plan.assetSymbol());var required=List.of(plan.sourceKeyId(),rule.destinationKeyId()).stream().distinct().sorted().toList();var locked=new HashMap<UUID,RegisteredMpcKey>();
        for(var id:required)locked.put(id,keys.lock(plan.tenantId(),id).orElseThrow());var source=locked.get(plan.sourceKeyId());var treasury=locked.get(rule.destinationKeyId());
        require(source.state().equals("ACTIVE") && treasury.state().equals("ACTIVE") && !source.keyId().equals(treasury.keyId())
                && (rule.sourceKeyIds().isEmpty() || rule.sourceKeyIds().contains(source.keyId())) && RegisteredMpcAddresses.matches(treasury,asset,plan.recipient()),"planned source/treasury differs from installed independent roles");
        for(var permission:policies.rules())if(permission.tenantId().equals(plan.tenantId()) && permission.chain().equals(plan.chain()) && permission.network().equals(plan.network()))
            require(!source.keyId().equals(permission.destinationKeyId()) && (!List.of("FUNDING","DEV_FAUCET").contains(permission.businessType()) || !permission.sourceKeyIds().contains(source.keyId())),"privileged wallet roles cannot manufacture collection plans");
        require(plan.amount().compareTo(rule.minimumAmount())>=0 && plan.amount().compareTo(rule.maximumAmount())<=0,"planned collection amount exceeds independent bounds");plan.amount().movePointRight(asset.decimals()).toBigIntegerExact();
        String sourceAddress=RegisteredMpcAddresses.address(source,asset),fingerprint=fingerprint(rule);var original=operations.find(plan.tenantId(),plan.chain(),"COLLECTION",plan.businessReference());
        if(original.isPresent()) {
            var stored=original.get();require(stored.receipt().ruleFingerprint().equals(fingerprint) && stored.sourceKeyIds().equals(List.of(plan.sourceKeyId()))
                    && stored.receipt().payments().size()==1 && stored.receipt().payments().getFirst().assetSnapshot().equals(asset)
                    && stored.receipt().payments().getFirst().intent().recipient().equals(plan.recipient()) && stored.receipt().payments().getFirst().intent().amount().compareTo(plan.amount())==0,"original prepared collection cannot change source, payment, asset or rule");
            return new Planning(null,source,sourceAddress,stored.receipt());
        }
        Instant now=clock.instant();var intent=new WithdrawalIntent(identity("payment:"+plan.tenantId()+":"+plan.chain()+":COLLECTION:"+plan.businessReference()+":0"),plan.tenantId(),plan.businessReference(),plan.chain(),plan.network(),plan.assetSymbol(),plan.recipient(),plan.amount(),rule.maximumNetworkFee(),"SYSTEM",rule.id(),"AUTOMATION:"+fingerprint,"","ADDRESS");
        var payment=new ApprovalRequest(intent,asset.bind(intent,json),asset,rule.id(),1,List.of("INDEPENDENT_OPERATING_RULE"),0,"APPROVED",false,now,now.plusSeconds(86400));
        var receipt=new AutomaticOperation(identity("operation:"+plan.tenantId()+":"+plan.chain()+":COLLECTION:"+plan.businessReference()),rule.id(),fingerprint,"COLLECTION",plan.businessReference(),List.of(payment),null,now);
        return new Planning(new Admission(rule,receipt,Set.of(source.keyId()),Set.copyOf(required),null),source,sourceAddress,null);
    }
    public AutomaticOperation original(MpcSignCommand command) {
        return operations.find(command.tenantId(),command.chain().name(),command.businessType().name(),AutomaticOperation.reference(command,json)).orElseThrow().receipt();
    }
    /** The caller owns the independent tenant admission lock. No network calls or Wallet table access occur here. */
    public Admission prepare(SigningCandidate candidate) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),"operating admission requires the independent tenant transaction");
        var command=candidate.command();require(command.businessType()!=MpcSignCommand.BusinessType.WITHDRAWAL && candidate.approvalRequestIds().isEmpty(),"customer withdrawals cannot use an operating rule");
        var signer=keys.find(command.tenantId(),command.keyId()).orElseThrow();require(signer.state().equals("ACTIVE") && signer.epoch()==command.keyEpoch(),"operating signer is not independently active");
        var decoded=AutomaticPaymentDescriptor.decode(command,signer,json);
        boolean addressPreparation=command.businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION;
        var eligible=policies.rules().stream().filter(r->r.tenantId().equals(command.tenantId()) && r.chain().equals(command.chain().name()) && r.businessType().equals(command.businessType().name())
                && (decoded.symbol().equals("EIP7702_ATOMIC") || r.symbol().equals(decoded.symbol())) && (decoded.network()==null || r.network().equals(decoded.network()))).toList();
        eligible=eligible.stream().filter(r->networkMatches(command,assets.require(r.chain(),r.network(),r.symbol()))).toList();
        if(decoded.symbol().equals("EIP7702_ATOMIC"))eligible=eligible.stream().filter(r->eipAssetMatches(command,assets.require(r.chain(),r.network(),r.symbol()))).toList();
        require(eligible.size()==1,"one explicit independent operating rule/network/asset is required");var rule=eligible.getFirst();var asset=assets.require(rule.chain(),rule.network(),rule.symbol());
        require(!addressPreparation || asset.chain().equals("XRP") && asset.tokenIdentifier()!=null,"XRP address preparation requires an independently installed issued asset");
        var descriptor=decoded;
        if(command.chain()==ChainType.SOLANA) {
            var source=owned(command.tenantId(),asset,decoded.transfers().getFirst().sender());descriptor=descriptor.withSources(List.of(source.keyId()));
        }
        if(decoded.symbol().equals("EIP7702_ATOMIC"))descriptor=new AutomaticPaymentDescriptor(rule.symbol(),rule.network(),decoded.transfers().stream()
                .map(t->new AutomaticPaymentDescriptor.Transfer(t.reference(),t.sender(),t.recipient(),t.amount().movePointLeft(asset.decimals()))).toList(),decoded.sourceKeyIds());
        var sourceKeys=new HashSet<>(descriptor.sourceKeyIds());var requiredKeys=new HashSet<>(sourceKeys);requiredKeys.add(command.keyId());
        boolean storageRegistration=command.payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON");
        if(storageRegistration)requiredKeys.add(owned(command.tenantId(),asset,AutomaticPaymentDescriptor.payload(command,NearRegistrationPayload.class,json).parentSender()).keyId());
        var registered=new HashMap<UUID,RegisteredMpcKey>();for(var id:sourceKeys) {
            var key=keys.find(command.tenantId(),id).orElseThrow();require(key.state().equals("ACTIVE") && key.keyGroup().equals(command.chain().isEvm()?"EVM":command.chain().name()),"automatic source belongs to another independent key domain");registered.put(id,key);
        }
        if(!rule.sourceKeyIds().isEmpty())require(rule.sourceKeyIds().containsAll(sourceKeys),"automatic source differs from its independently installed role");
        if(command.businessType()==MpcSignCommand.BusinessType.COLLECTION) {
            var privileged=new HashSet<UUID>();for(var permission:policies.rules())if(permission.tenantId().equals(command.tenantId()) && permission.chain().equals(asset.chain()) && permission.network().equals(asset.network())) {
                if(permission.destinationKeyId()!=null)privileged.add(permission.destinationKeyId());
                if(List.of("FUNDING","DEV_FAUCET").contains(permission.businessType()))privileged.addAll(permission.sourceKeyIds());
            }
            require(Collections.disjoint(sourceKeys,privileged),"treasury/Gas roles cannot manufacture automatic collection loops");
        }
        RegisteredMpcKey destination=null;if(rule.destinationKeyId()!=null) {
            destination=keys.find(command.tenantId(),rule.destinationKeyId()).orElseThrow();requiredKeys.add(destination.keyId());
        }
        BigDecimal total=BigDecimal.ZERO;
        for(var transfer:descriptor.transfers()) {
            require(registered.values().stream().anyMatch(k->RegisteredMpcAddresses.matches(k,asset,transfer.sender())),"operating source address differs from independent registered keys");
            require(addressPreparation || transfer.amount().signum()>0,"automatic payments require positive principal");
            require(transfer.amount().compareTo(rule.minimumAmount())>=0 && transfer.amount().compareTo(rule.maximumAmount())<=0,"automatic principal is outside installed per-operation bounds");
            transfer.amount().movePointRight(asset.decimals()).toBigIntegerExact();total=total.add(transfer.amount());
            if(destination!=null)require(RegisteredMpcAddresses.matches(destination,asset,transfer.recipient()),"automatic payment target is not the independently installed treasury");
            else if(command.businessType()!=MpcSignCommand.BusinessType.DEV_FAUCET && !storageRegistration) {
                var recipient=owned(command.tenantId(),asset,transfer.recipient());requiredKeys.add(recipient.keyId());require(addressPreparation || !sourceKeys.contains(recipient.keyId()),"automatic internal transfer cannot target its source");
            }
            if(destination!=null)require(!sourceKeys.contains(destination.keyId()),"automatic collection/recovery cannot target itself");
        }
        require(total.compareTo(rule.maximumAmount())<=0,"automatic whole-batch principal exceeds installed operation maximum");
        String operationReference=AutomaticOperation.reference(command,json);
        UUID parent=null;String parentScope=null;
        if(command.businessType()==MpcSignCommand.BusinessType.FUNDING) {
            var reference=AutomaticParentReference.decode(command.tenantId(),asset.chain(),command.businessReference());parentScope=reference.scope();
            var human=humanRequests.fundingParent(command.tenantId(),asset.chain(),reference);
            if(human.isPresent()) {
                var request=human.get();require(request.status().equals("APPROVED") && !request.signingStarted() && request.expiresAt().isAfter(clock.instant())
                        && request.intent().network().equals(asset.network()),"Gas preparation requires an unspent independently approved parent");parent=request.intent().id();
                assets.requireCurrent(request.assetSnapshot());
                if(storageRegistration) {
                    var registration=AutomaticPaymentDescriptor.payload(command,NearRegistrationPayload.class,json);
                    require(registration.parent().tenantId().equals(command.tenantId()) && registration.parent().kind()==NearOperation.Kind.WITHDRAWAL
                            && registration.parent().reference().equals(request.intent().businessReference()) && registration.payment().symbol().equals(request.intent().assetSymbol())
                            && registration.payment().recipient().equals(request.intent().recipient()) && registration.payment().amount().compareTo(request.intent().amount())==0
                            && asset.equals(request.assetSnapshot()),"NEP-145 parent payment differs from independent human admission");
                }
            } else {
                var automatic=operations.fundingParent(command.tenantId(),asset.chain(),reference).orElseThrow(()->new IllegalArgumentException("Gas preparation requires an independently admitted original parent"));
                require(automatic.transactionId()==null && automatic.plan()!=null && automatic.receipt().payments().getFirst().assetSnapshot().network().equals(asset.network()),"Gas parent must be independently prepared and still unsigned on this network");parent=automatic.receipt().id();
                if(storageRegistration) {
                    var registration=AutomaticPaymentDescriptor.payload(command,NearRegistrationPayload.class,json);var payments=automatic.receipt().payments();
                    require(registration.parent().tenantId().equals(command.tenantId()) && registration.parent().kind()==NearOperation.Kind.COLLECTION
                            && registration.parent().reference().equals(automatic.receipt().businessReference()) && payments.size()==1 && payments.getFirst().assetSnapshot().equals(asset)
                            && registration.payment().recipient().equals(payments.getFirst().intent().recipient()) && registration.payment().amount().compareTo(payments.getFirst().intent().amount())==0,
                            "NEP-145 parent payment differs from independent collection admission");
                    require(RegisteredMpcAddresses.matches(keys.find(command.tenantId(),automatic.plan().sourceKeyId()).orElseThrow(),asset,registration.parentSender()),"NEP-145 source differs from the original prepared collection source");
                }
            }
        }
        String fingerprint=fingerprint(rule);var existing=operations.find(command.tenantId(),asset.chain(),command.businessType().name(),operationReference);
        if(existing.isPresent()) {
            var receipt=existing.get().receipt();require(receipt.ruleId().equals(rule.id()) && receipt.ruleFingerprint().equals(fingerprint),"independent operating rule changed after original admission");
            require(new HashSet<>(existing.get().sourceKeyIds()).equals(sourceKeys),"original operating source keys cannot be replaced");
            return new Admission(rule,receipt,Set.copyOf(sourceKeys),Set.copyOf(requiredKeys),parentScope);
        }
        Instant now=clock.instant();var payments=new ArrayList<ApprovalRequest>();int index=0;
        for(var transfer:descriptor.transfers()) {
            var intent=new WithdrawalIntent(identity("payment:"+command.tenantId()+":"+command.chain()+":"+command.businessType()+":"+operationReference+":"+index++),command.tenantId(),transfer.reference(),asset.chain(),asset.network(),asset.symbol(),
                    transfer.recipient(),transfer.amount(),rule.maximumNetworkFee(),"SYSTEM",rule.id(),"AUTOMATION:"+fingerprint,parent==null?"":parent.toString(),"ADDRESS");
            payments.add(new ApprovalRequest(intent,asset.bind(intent,json),asset,rule.id(),1,List.of("INDEPENDENT_OPERATING_RULE"),0,"APPROVED",false,now,now.plusSeconds(86400)));
        }
        var receipt=new AutomaticOperation(identity("operation:"+command.tenantId()+":"+command.chain()+":"+command.businessType()+":"+operationReference),rule.id(),fingerprint,command.businessType().name(),operationReference,payments,parent,now);
        return new Admission(rule,receipt,Set.copyOf(sourceKeys),Set.copyOf(requiredKeys),parentScope);
    }
    /** Called only between committed transactions. Typed native input verifiers already cover the skipped families. */
    public JsonNode observeOperatingBalances(Admission admission,SigningCandidate candidate) {
        var command=candidate.command();if(command.businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION)return null;boolean funding=command.businessType()==MpcSignCommand.BusinessType.FUNDING;
        if(!funding && (command.chain().isBitcoinLike() || Set.of(ChainType.ADA,ChainType.SUI,ChainType.XMR).contains(command.chain()) || command.payloadEncoding().equals(Evm7702SigningPayload.ENCODING)))return null;
        var asset=admission.receipt().payments().getFirst().assetSnapshot();boolean storage=command.payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON");
        var sourceAsset=storage?assets.require(asset.chain(),asset.network(),asset.nativeSymbol()):asset;var signer=keys.find(command.tenantId(),command.keyId()).orElseThrow();
        var descriptor=AutomaticPaymentDescriptor.decode(command,signer,json);require(descriptor.transfers().size()==1,"bounded single-source operating balance observation required");var transfer=descriptor.transfers().getFirst();
        var source=admission.sourceKeyIds().stream().map(id->keys.find(command.tenantId(),id).orElseThrow()).filter(k->RegisteredMpcAddresses.matches(k,sourceAsset,transfer.sender())).findFirst().orElseThrow();
        var original=moneroObservation(command,source.keyId());var sourceBalance=balanceReader.observe(sourceAsset,source,transfer.sender(),original);ApprovalOperatingBalanceReader.Balance targetBalance=null,payerBalance=null;
        if(funding && !storage) {
            var parent=parentPayment(candidate,admission);var target=owned(command.tenantId(),parent.assetSnapshot(),transfer.recipient());
            targetBalance=balanceReader.observe(parent.assetSnapshot(),target,transfer.recipient(),moneroObservation(command,target.keyId()));
        }
        if(command.chain()==ChainType.SOLANA) {
            var payment=AutomaticPaymentDescriptor.payload(command,SolanaSigningPayload.class,json);if(!payment.feePayer().equals(payment.sender())) {
                var nativeAsset=assets.require(asset.chain(),asset.network(),asset.nativeSymbol());var payer=owned(command.tenantId(),nativeAsset,payment.feePayer());payerBalance=balanceReader.observe(nativeAsset,payer,payment.feePayer(),null);
            }
        }
        return json.valueToTree(new OperatingEvidence(sourceAsset,sourceBalance,targetBalance,payerBalance));
    }
    /** The complete wire was already verified. This additional gate proves automatic source funds or a real linked Gas deficit. */
    public boolean verifyOperatingBalances(Admission admission,SigningCandidate candidate,JsonNode evidence,BigInteger expenditure,boolean firstVersion) {
        var command=candidate.command();if(command.businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION){require(evidence==null,"XRP preparation uses its fixed validated-ledger evidence");return false;}boolean funding=command.businessType()==MpcSignCommand.BusinessType.FUNDING;
        if(evidence==null) {require(!funding || command.payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON"),"independent funding evidence required");return false;}
        var observed=json.treeToValue(evidence,OperatingEvidence.class);var asset=admission.receipt().payments().getFirst().assetSnapshot();boolean storage=command.payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON");
        var sourceAsset=storage?assets.require(asset.chain(),asset.network(),asset.nativeSymbol()):asset;require(observed.sourceAsset().equals(sourceAsset),"operating source balance asset differs");
        var descriptor=AutomaticPaymentDescriptor.decode(command,keys.find(command.tenantId(),command.keyId()).orElseThrow(),json);var transfer=descriptor.transfers().getFirst();
        var source=admission.sourceKeyIds().stream().map(id->keys.find(command.tenantId(),id).orElseThrow()).filter(k->RegisteredMpcAddresses.matches(k,sourceAsset,transfer.sender())).findFirst().orElseThrow();
        BigInteger principal=storage?BigInteger.ZERO:admission.receipt().payments().getFirst().intent().amount().movePointRight(asset.decimals()).toBigIntegerExact();
        checkBalance(observed.source(),sourceAsset,source.keyId(),transfer.sender(),principal,firstVersion);
        if(observed.feePayer()!=null) {
            require(command.chain()==ChainType.SOLANA,"unexpected separate automatic fee payer");var p=AutomaticPaymentDescriptor.payload(command,SolanaSigningPayload.class,json);
            var nativeAsset=assets.require(asset.chain(),asset.network(),asset.nativeSymbol());var payer=owned(command.tenantId(),nativeAsset,p.feePayer());checkBalance(observed.feePayer(),nativeAsset,payer.keyId(),p.feePayer(),expenditure,firstVersion);
        } else require(observed.source().nativeAtomic().compareTo(expenditure.add(sourceAsset.tokenIdentifier()==null?principal:BigInteger.ZERO))>=0,"automatic source cannot cover principal plus independently reserved native expenditure");
        if(!funding || storage)return false; // NEP-145 supplies its own exact minimum-deposit verifier.
        require(asset.tokenIdentifier()==null && descriptor.transfers().size()==1,"Gas top-up must transfer the native asset to one original owned source");
        var parent=parentPayment(candidate,admission);var target=owned(command.tenantId(),parent.assetSnapshot(),transfer.recipient());
        BigInteger parentPrincipal=parent.intent().amount().movePointRight(parent.assetSnapshot().decimals()).toBigIntegerExact();
        checkBalance(observed.parentSource(),parent.assetSnapshot(),target.keyId(),transfer.recipient(),parentPrincipal,firstVersion);
        require(parent.assetSnapshot().networkIdentity().equals(asset.networkIdentity()) && parent.assetSnapshot().nativeSymbol().equals(asset.nativeSymbol()) && parent.assetSnapshot().nativeDecimals()==asset.nativeDecimals(),"Gas parent native accounting domain differs");
        BigInteger reserve=admission.rule().maximumAmount().movePointRight(asset.nativeDecimals()).toBigIntegerExact();
        BigInteger required=reserve.add(parent.assetSnapshot().tokenIdentifier()==null?parentPrincipal:BigInteger.ZERO);
        BigInteger deficit=required.subtract(observed.parentSource().nativeAtomic());require(deficit.signum()>0 && principal.compareTo(deficit)<=0,"Gas amount exceeds the independently observed parent reserve deficit");
        return true;
    }
    private ApprovalRequest parentPayment(SigningCandidate candidate,Admission admission) {
        var command=candidate.command();var reference=AutomaticParentReference.decode(command.tenantId(),command.chain().name(),command.businessReference());var manual=humanRequests.fundingParent(command.tenantId(),command.chain().name(),reference);
        if(manual.isPresent()) {require(admission.receipt().parentOperationId().equals(manual.get().intent().id()),"funding parent identity changed");return manual.get();}
        var automatic=operations.fundingParent(command.tenantId(),command.chain().name(),reference).orElseThrow();require(automatic.receipt().id().equals(admission.receipt().parentOperationId()) && automatic.plan()!=null && automatic.receipt().payments().size()==1,"one independently prepared parent payment required");
        var descriptor=AutomaticPaymentDescriptor.decode(command,keys.find(command.tenantId(),command.keyId()).orElseThrow(),json);String recipient=descriptor.transfers().getFirst().recipient();
        var key=keys.find(command.tenantId(),automatic.plan().sourceKeyId()).orElseThrow();require(RegisteredMpcAddresses.matches(key,automatic.receipt().payments().getFirst().assetSnapshot(),recipient),"Gas recipient differs from the original prepared collection source");return automatic.receipt().payments().getFirst();
    }
    private MpcClient.Request moneroObservation(MpcSignCommand command,UUID keyId) {
        if(command.chain()!=ChainType.XMR)return null;var payload=AutomaticPaymentDescriptor.payload(command,MoneroSigningPayload.class,json);
        var observed=payload.observations().stream().filter(request->request.tenantId().equals(command.tenantId()) && request.keyId().equals(keyId)).toList();require(observed.size()==1,"one original Monero balance observation is required per Gas source/recipient");return observed.getFirst();
    }
    /** All locks, fee reservation, operation receipt and outer authorization audit share this transaction. */
    public void admit(Admission admission,UUID transactionId,BigInteger maximumFee,boolean preparationVerified,String serviceIdentity) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),"automatic receipt must join original signing transaction");
        var receipt=admission.receipt();var asset=receipt.payments().getFirst().assetSnapshot();var tenant=receipt.payments().getFirst().intent().tenantId();var rule=admission.rule();Instant now=clock.instant();
        var previous=operations.find(tenant,asset.chain(),receipt.businessType(),receipt.businessReference());
        if(previous.isPresent()) {
            var original=previous.get();require(original.transactionId()==null && original.plan()!=null && original.receipt().equals(receipt)
                    && new HashSet<>(original.sourceKeyIds()).equals(admission.sourceKeyIds()) && maximumFee.compareTo(original.maximumFee())<=0,"original parent reservation cannot be replaced or exceed its reserved native exposure");
            require(operations.bind(tenant,receipt.id(),transactionId)==1,"original parent signing binding raced");
            audit.insert(tenant,"SERVICE",serviceIdentity,"AUTOMATION.BIND",receipt.id(),Map.of("transactionId",transactionId,"ruleFingerprint",receipt.ruleFingerprint()));return;
        }
        checkQuotas(admission,maximumFee);
        require(!List.of("FUNDING","ADDRESS_PREPARATION").contains(receipt.businessType()) || preparationVerified,"independent funding balance/deficit verifier is required");
        operations.insert(receipt,transactionId,admission.sourceKeyIds(),maximumFee,admission.parentScope());
        audit.insert(tenant,"SERVICE",serviceIdentity,"AUTOMATION.ADMIT",receipt.id(),Map.of("ruleId",rule.id(),"ruleFingerprint",receipt.ruleFingerprint(),"transactionId",transactionId,"businessType",receipt.businessType(),"maximumFeeAtomic",maximumFee.toString()));
    }
    private void checkQuotas(Admission admission,BigInteger maximumFee) {
        var receipt=admission.receipt();var asset=receipt.payments().getFirst().assetSnapshot();var tenant=receipt.payments().getFirst().intent().tenantId();var rule=admission.rule();Instant now=clock.instant();
        BigInteger principal=receipt.payments().stream().map(p->p.intent().amount().movePointRight(asset.decimals()).toBigIntegerExact()).reduce(BigInteger.ZERO,BigInteger::add);
        require(!operations.differentDomain(tenant,asset),"automatic principal/native accounting domain changed; historical exposure must not be discarded");
        var budget=operations.budget(tenant,asset,receipt.businessType(),now);
        require(budget.lifetimeAmount().add(principal).compareTo(rule.maximumLifetimeAmount().movePointRight(asset.decimals()).toBigIntegerExact())<=0
                && budget.dailyAmount().add(principal).compareTo(rule.maximum24hAmount().movePointRight(asset.decimals()).toBigIntegerExact())<=0
                && budget.dailyCount()<rule.maximum24hOperations(),"independent automatic principal/count quota exceeded");
        require(maximumFee.signum()>=0 && maximumFee.compareTo(rule.maximumNetworkFee().movePointRight(asset.nativeDecimals()).toBigIntegerExact())<=0
                && operations.lifetimeFees(tenant,asset).add(maximumFee).compareTo(rule.maximumLifetimeNetworkFee().movePointRight(asset.nativeDecimals()).toBigIntegerExact())<=0,"independent lifetime automatic native-fee exposure exceeded");
        if(asset.tokenIdentifier()==null)require(maximumFee.multiply(BigInteger.valueOf(10000)).compareTo(principal.multiply(BigInteger.valueOf(rule.maximumNativeFeeShareBps())))<=0,"automatic native transfer would spend too much principal on network fees");
        for(var source:admission.sourceKeyIds())require(operations.latestSource(tenant,asset,source).filter(last->last.isAfter(now.minusSeconds(rule.minimumIntervalSeconds()))).isEmpty(),"automatic source has not completed its minimum admission interval");
    }
    private void verifyBalance(ApprovalOperatingBalanceReader.Balance balance,ApprovalAssetSnapshot asset,UUID keyId,String source,BigInteger principal) {
        checkBalance(balance,asset,keyId,source,principal,true);
    }
    private void checkBalance(ApprovalOperatingBalanceReader.Balance balance,ApprovalAssetSnapshot asset,UUID keyId,String source,BigInteger principal,boolean firstVersion) {
        Instant now=clock.instant();require(balance!=null && balance.chain().equals(asset.chain()) && balance.networkIdentity().equals(asset.networkIdentity()) && balance.symbol().equals(asset.symbol())
                && balance.decimals()==asset.decimals() && balance.nativeDecimals()==asset.nativeDecimals() && balance.keyId().equals(keyId) && balance.owner().equals(source)
                && (!firstVersion || !balance.observedAt().isAfter(now) && !balance.observedAt().isBefore(now.minusSeconds(60))) && balance.availableAtomic().compareTo(principal)>=0,"independent parent principal balance is missing, stale or insufficient");
    }
    private RegisteredMpcKey owned(UUID tenant,ApprovalAssetSnapshot asset,String address) {
        var active=keys.active(tenant,ChainType.valueOf(asset.chain()).isEvm()?"EVM":asset.chain());require(active.size()<=10000,"independent public key ownership query exceeds bound");
        var matches=active.stream().filter(k->RegisteredMpcAddresses.matches(k,asset,address)).toList();require(matches.size()==1,"one independently registered tenant owner is required");return matches.getFirst();
    }
    private boolean eipAssetMatches(MpcSignCommand command,ApprovalAssetSnapshot asset) {
        var p=Evm7702SigningPayload.decode(command,json);var preparation=json.readValue(p.plan().preparationJson(),Evm7702CollectionPayload.class);
        String token=asset.tokenIdentifier()==null?"0x"+"0".repeat(40):asset.tokenIdentifier();
        return asset.networkIdentity().equals("eip155:"+p.plan().chainId()) && preparation.items().stream().allMatch(i->i.request().token().equalsIgnoreCase(token));
    }
    private boolean networkMatches(MpcSignCommand command,ApprovalAssetSnapshot asset) {
        if(command.payloadEncoding().equals(Evm7702SigningPayload.ENCODING))return asset.networkIdentity().equals("eip155:"+Evm7702SigningPayload.decode(command,json).plan().chainId());
        if(command.chain().isEvm())return asset.networkIdentity().equals("eip155:"+AutomaticPaymentDescriptor.payload(command,EvmSigningPayload.class,json).chainId());
        if(command.chain()==ChainType.APTOS)return asset.networkIdentity().equals("aptos:"+AutomaticPaymentDescriptor.payload(command,AptosSigningPayload.class,json).unsigned().chainId());
        if(command.chain()==ChainType.ADA) {var p=AutomaticPaymentDescriptor.payload(command,CardanoSigningPayload.class,json).unsigned();return asset.networkIdentity().equals("cardano:"+p.genesisHash()+":"+Long.toUnsignedString(p.networkMagic()));}
        return true; // Families without a signed network identifier must resolve to exactly one installed profile.
    }
    private String fingerprint(Rule rule) {
        var fields=new TreeMap<String,Object>();json.valueToTree(rule).properties().forEach(p->fields.put(p.getKey(),p.getValue()));
        fields.put("sourceKeyIds",rule.sourceKeyIds().stream().sorted().map(UUID::toString).toList());return SigningAuthorization.hash(json.writeValueAsBytes(fields));
    }
    private static UUID identity(String text) {
        byte[] bytes=HexFormat.of().parseHex(SigningAuthorization.hash(text.getBytes(StandardCharsets.UTF_8)));var buffer=ByteBuffer.wrap(bytes);
        long high=(buffer.getLong()&0xffffffffffff0fffL)|0x0000000000004000L,low=(buffer.getLong()&0x3fffffffffffffffL)|0x8000000000000000L;return new UUID(high,low);
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
    public record Admission(Rule rule,AutomaticOperation receipt,Set<UUID> sourceKeyIds,Set<UUID> requiredKeyIds,String parentScope) { }
    private record Planning(Admission admission,RegisteredMpcKey source,String sourceAddress,AutomaticOperation original) { }
    private record OperatingEvidence(ApprovalAssetSnapshot sourceAsset,ApprovalOperatingBalanceReader.Balance source,ApprovalOperatingBalanceReader.Balance parentSource,ApprovalOperatingBalanceReader.Balance feePayer) { }
}
