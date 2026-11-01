package com.surprising.wallet.service;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.evm.Evm7702SignedBatchTransaction;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.EvmCollectionBatchRepository;
import com.surprising.wallet.repository.EvmNonceRepository;
import com.surprising.wallet.repository.EvmWithdrawalBatchRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.repository.CustodyGasUsageRepository;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.utils.Numeric;
import tools.jackson.databind.ObjectMapper;

/** Original EIP-7702 inner and operator-owned outer intents. Native RPC is always outside transactions. */
@Service
public class Evm7702SigningService {
    private static final String PLAN_SLOT = "operation-0";
    private static final String RELAYER_SLOT = "relayer";
    private final WalletMpcSignRequestRepository requests;
    private final ChainAddressRepository addresses;
    private final EvmNonceRepository nonces;
    private final EvmCollectionBatchRepository collections;
    private final EvmWithdrawalBatchRepository payouts;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ObjectMapper json;
    private final CustodyGasUsageRepository gasUsages;

    public Evm7702SigningService(WalletMpcSignRequestRepository requests, ChainAddressRepository addresses,
            EvmNonceRepository nonces, EvmCollectionBatchRepository collections, EvmWithdrawalBatchRepository payouts,
            MpcSigningService signing, MpcNodes nodes, ObjectMapper json, CustodyGasUsageRepository gasUsages) {
        this.requests=requests;this.addresses=addresses;this.nonces=nonces;this.collections=collections;
        this.payouts=payouts;this.signing=signing;this.nodes=nodes;this.json=json;this.gasUsages=gasUsages;
    }

    public record Operation(UUID tenantId, UUID batchId, String chain, MpcSignCommand.BusinessType type) {
        public Operation {
            Objects.requireNonNull(tenantId);Objects.requireNonNull(batchId);
            require(ChainType.valueOf(chain).isEvm() && (type==MpcSignCommand.BusinessType.COLLECTION
                    || type==MpcSignCommand.BusinessType.WITHDRAWAL),"EIP-7702 batch operation required");
        }
        public String reference() { return "eip7702:"+tenantId+":"+batchId; }
    }
    public record Input(ChainAddressRecord owner, String slot, MpcSignCommand.Purpose purpose,
                         String payloadJson, String digestHex) { }
    public record Plan(long chainId, BigInteger relayerNonce, ChainAddressRecord relayer, String preparationJson) { }
    public record OuterPayload(long chainId, String unsignedHex, long estimatedGas, BigDecimal reservedFee) {
        public RawTransaction raw() { return MpcEvmTransactionCodec.restore(HexFormat.of().parseHex(unsignedHex),chainId); }
    }

    public Input input(ChainAddressRecord owner,String slot,MpcSignCommand.Purpose purpose,Object payload,byte[] digest) {
        require(digest!=null && digest.length==32,"EIP-7702 digest must contain32bytes");
        require(purpose==MpcSignCommand.Purpose.EIP7702_OPERATION || purpose==MpcSignCommand.Purpose.EIP7702_AUTHORIZATION,
                "inner EIP-7702 purpose required");
        return new Input(owner,slot,purpose,json.writeValueAsString(payload),HexFormat.of().formatHex(digest));
    }

    public Optional<Plan> plan(Operation operation) {
        return inner(operation,PLAN_SLOT).map(request->{
            require(request.command().payloadEncoding().equals("EIP7702_BATCH_PLAN_JSON")
                    && request.command().purpose()==MpcSignCommand.Purpose.EIP7702_OPERATION,"original batch plan encoding differs");
            return json.readValue(HexFormat.of().parseHex(request.command().payloadHex()),Plan.class);
        });
    }
    public <T> T preparation(Operation operation,Class<T> type) {
        return json.readValue(plan(operation).orElseThrow().preparationJson(),type);
    }

    /** Joins the batch owner transaction; all inner slots and the reserved nonce commit before a native call. */
    public Plan reserve(Operation operation,long chainId,ChainAddressRecord relayer,BigInteger pendingNonce,
                        Object preparation) {
        requireTransaction();lock(operation);
        var existing=plan(operation);if(existing.isPresent())return existing.get();
        require(status(operation).equals("LOCKED"),"batch cannot admit another signing plan");
        List<Input> inputs=validatedInputs(operation,chainId,relayer,preparation);
        require(chainId>0 && inputs.stream().filter(input->PLAN_SLOT.equals(input.slot())).count()==1,
                "batch has no unique original operation slot");
        require(inputs.stream().map(Input::slot).distinct().count()==inputs.size() && inputs.size()<=200,"duplicate or excessive inner signing slots");
        require(inputs.stream().filter(input->PLAN_SLOT.equals(input.slot())).allMatch(input->input.purpose()==MpcSignCommand.Purpose.EIP7702_OPERATION),"original plan requires an operation signature");
        require(inputs.stream().allMatch(input->input.digestHex()!=null && input.digestHex().matches("[0-9a-f]{64}")
                && input.slot().matches("(?:authorization|operation)-[0-9]+")
                && !input.owner().getAddress().equalsIgnoreCase(relayer.getAddress())),"invalid digest, slot or self-sponsored authority");
        var operations=inputs.stream().filter(input->input.purpose()==MpcSignCommand.Purpose.EIP7702_OPERATION).toList();
        require(operations.stream().map(input->input.owner().getMpcKeyId()).distinct().count()==operations.size(),"an authority cannot consume one operation nonce twice in a batch");
        requirePublicOwner(relayer,operation.chain(),true);
        signing.lockActiveKey(relayer.getTenantId(),relayer.getMpcKeyId(),ChainType.valueOf(operation.chain()));
        BigInteger nonce=nonces.reserve(operation.chain(),relayer.getAddress().toLowerCase(Locale.ROOT),pendingNonce);
        require(nonce.signum()>=0 && nonce.compareTo(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))<0,"operator nonce exhausted");
        Plan plan=new Plan(chainId,nonce,relayer,json.writeValueAsString(preparation));
        for(var input:inputs) {
            require(operation.tenantId().equals(input.owner().getTenantId()),"inner authority belongs to another tenant");
            var key=activePublicOwner(input.owner(),operation.chain());
            String payload=PLAN_SLOT.equals(input.slot())?json.writeValueAsString(plan):input.payloadJson();
            var command=new MpcSignCommand(operation.tenantId(),key.id(),key.activeEpoch(),ChainType.valueOf(operation.chain()),
                    operation.type(),operation.reference(),input.slot(),input.purpose(),
                    PLAN_SLOT.equals(input.slot())?"EIP7702_BATCH_PLAN_JSON":"EIP7702_INNER_JSON",
                    HexFormat.of().formatHex(json.writeValueAsBytes(json.readTree(payload))),input.digestHex());
            signing.reserveSignature(command,nodes.online());
        }
        require(beginSigning(operation)==1,"batch signing admission raced");
        return plan;
    }

    /** Only an original committed slot can be dispatched; reads of completed results are independently verified. */
    public byte[] signature(Operation operation,Input input) {
        requireOutsideTransaction();
        var request=inner(operation,input.slot()).orElseThrow();
        require(request.command().keyId().equals(input.owner().getMpcKeyId())
                && input.owner().getTenantId().equals(operation.tenantId())
                && request.command().purpose()==input.purpose()
                && request.command().preimageHex().equals(input.digestHex()),"original EIP-7702 slot differs");
        if(!PLAN_SLOT.equals(input.slot()))require(request.command().payloadHex().equals(
                HexFormat.of().formatHex(json.writeValueAsBytes(json.readTree(input.payloadJson())))),"inner original payload differs");
        requirePublicOwner(input.owner(),operation.chain(),false);
        return signing.awaitSignature(operation.tenantId(),request.id(),nodes);
    }

    public byte[] publicKey(ChainAddressRecord owner,String chain) {
        requirePublicOwner(owner,chain,false);
        return HexFormat.of().parseHex(signing.requireKey(owner.getTenantId(),owner.getMpcKeyId()).publicKeyHex());
    }

    public Optional<MpcSignRequest> outer(Operation operation) {
        var plan=plan(operation);if(plan.isEmpty())return Optional.empty();
        return requests.findBusiness(plan.get().relayer().getTenantId(),ChainType.valueOf(operation.chain()),
                operation.type(),operation.reference(),RELAYER_SLOT);
    }
    public OuterPayload outerPayload(Operation operation) {
        var request=outer(operation).orElseThrow();
        var payload=json.readValue(HexFormat.of().parseHex(request.command().payloadHex()),OuterPayload.class);
        var plan=plan(operation).orElseThrow();
        require(plan.chainId()==payload.chainId() && plan.relayerNonce().equals(payload.raw().getNonce())
                && request.command().keyId().equals(plan.relayer().getMpcKeyId())
                && request.command().preimageHex().equals(HexFormat.of().formatHex(MpcEvmTransactionCodec.digest(payload.raw(),payload.chainId()))),
                "outer intent differs from original batch plan");
        return payload;
    }

    /** Coordinator reserves the same payload's Gas in this transaction before any operator SIGN can leave Java. */
    public OuterPayload reserveOuter(Operation operation,RawTransaction raw,long estimatedGas,BigDecimal fee) {
        requireTransaction();lock(operation);
        require(status(operation).equals("SIGNING"),"batch is not signing");
        if(outer(operation).isPresent())return outerPayload(operation);
        Plan plan=plan(operation).orElseThrow();
        require(raw.getNonce().equals(plan.relayerNonce()) && estimatedGas>0 && fee!=null && fee.signum()>0,
                "outer nonce, gas or fee differs from original plan");
        var expected=expectedOuter(operation,raw.getGasLimit());
        require(java.util.Arrays.equals(MpcEvmTransactionCodec.unsignedPayload(raw,plan.chainId()),
                MpcEvmTransactionCodec.unsignedPayload(expected,plan.chainId())),"outer transaction differs from original approved batch");
        requirePublicOwner(plan.relayer(),operation.chain(),false);
        var key=signing.lockActiveKey(plan.relayer().getTenantId(),plan.relayer().getMpcKeyId(),ChainType.valueOf(operation.chain()));
        var payload=new OuterPayload(plan.chainId(),HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(raw,plan.chainId())),estimatedGas,fee);
        var command=new MpcSignCommand(plan.relayer().getTenantId(),key.id(),key.activeEpoch(),ChainType.valueOf(operation.chain()),
                operation.type(),operation.reference(),RELAYER_SLOT,MpcSignCommand.Purpose.TRANSACTION,"EIP7702_OUTER_JSON",
                HexFormat.of().formatHex(json.writeValueAsBytes(payload)),HexFormat.of().formatHex(MpcEvmTransactionCodec.digest(raw,plan.chainId())));
        signing.reserveSignature(command,nodes.online());
        return payload;
    }

    public void signOuter(Operation operation) {
        requireOutsideTransaction();var request=outer(operation).orElseThrow();
        var payload=outerPayload(operation);
        var gas=gasUsages.find(operation.tenantId(),operation.type()==MpcSignCommand.BusinessType.COLLECTION?
                "COLLECTION_BATCH":"WITHDRAWAL_BATCH",operation.batchId()).orElseThrow();
        require(gas.status().equals("RESERVED") && gas.reservedAmount().compareTo(payload.reservedFee())==0,
                "original batch Gas reservation is not committed");
        signing.awaitSignature(request.command().tenantId(),request.id(),nodes);
    }

    /** Local verification/assembly only; safe inside the atomic raw/hash commit transaction. */
    public Evm7702SignedBatchTransaction selected(Operation operation) {
        var request=outer(operation).orElseThrow();
        require(request.state()==MpcSignRequest.State.COMPLETED,"original operator signature is incomplete");
        var plan=plan(operation).orElseThrow();var payload=outerPayload(operation);
        byte[] signature=signing.signature(request.command().tenantId(),request.id()).orElseThrow();
        String raw=Numeric.toHexString(MpcEvmTransactionCodec.assemble(HexFormat.of().parseHex(payload.unsignedHex()),
                payload.chainId(),publicKey(plan.relayer(),operation.chain()),signature));
        return new Evm7702SignedBatchTransaction(raw,Hash.sha3(raw),plan.relayerNonce(),payload.raw().getType().isEip7702()?4:2);
    }

    /** A copied raw/ciphertext and matching database hash are not an authenticated original business selection. */
    public void verifySelectedRaw(Operation operation,String raw,String hash) {
        var selected=selected(operation);
        require(selected.rawTransaction().equalsIgnoreCase(raw) && selected.transactionHash().equalsIgnoreCase(hash)
                && selected.transactionHash().equalsIgnoreCase(Hash.sha3(raw)),"recovery bytes differ from the original authenticated MPC selection");
    }

    private List<Input> validatedInputs(Operation operation,long chainId,ChainAddressRecord relayer,Object preparation) {
        var result=new java.util.ArrayList<Input>();
        if(operation.type()==MpcSignCommand.BusinessType.COLLECTION) {
            var batch=json.readValue(collections.claimPayload(operation.tenantId(),operation.batchId()).orElseThrow(),
                    com.surprising.wallet.coordinator.Evm7702CollectionCoordinator.Batch.class);
            var payload=json.convertValue(preparation,com.surprising.wallet.model.mpc.Evm7702CollectionPayload.class);
            require(batch.config().chainId().longValueExact()==chainId && payload.chainId().equals(batch.config().chainId())
                    && payload.items().size()==batch.items().size(),"collection plan context differs");
            requireSameRelayer(batch.relayerChainAddress(),relayer);
            for(int index=0;index<payload.items().size();index++) {
                var item=payload.items().get(index);var claimed=batch.items().get(index);var request=item.request();
                require(item.authority().getMpcKeyId().equals(claimed.authorityChainAddress().getMpcKeyId())
                        && java.util.Arrays.equals(request.batchId(),Numeric.hexStringToByteArray(batch.batchHash()))
                        && request.itemIndex().equals(BigInteger.valueOf(index)) && request.authority().equalsIgnoreCase(claimed.fromAddress())
                        && request.collector().equalsIgnoreCase(batch.config().collectorAddress()) && request.token().equalsIgnoreCase(batch.tokenContract())
                        && request.recipient().equalsIgnoreCase(batch.hotWallet()) && request.amount().equals(claimed.amountAtomic())
                        && request.deadline().equals(BigInteger.valueOf(batch.signatureDeadline().getEpochSecond()))
                        && request.callGasLimit().compareTo(BigInteger.valueOf(500_000))<=0,"collection payment differs from original claim");
                if(item.authorization()!=null) {
                    requireAuthorization(item.authorization(),chainId,request.authority(),batch.config().delegateAddress());
                    result.add(input(item.authority(),"authorization-"+index,MpcSignCommand.Purpose.EIP7702_AUTHORIZATION,item.authorization(),com.surprising.wallet.chain.evm.MpcEvm7702Codec.digest(item.authorization())));
                }
                result.add(input(item.authority(),"operation-"+index,MpcSignCommand.Purpose.EIP7702_OPERATION,request,
                        new com.surprising.wallet.chain.evm.Evm7702OperationSigner().digest(payload.chainId(),request)));
            }
        } else {
            var batch=json.readValue(payouts.claimPayload(operation.tenantId(),operation.batchId()).orElseThrow(),
                    com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator.Batch.class);
            var payload=json.convertValue(preparation,com.surprising.wallet.model.mpc.Evm7702PayoutPayload.class);var request=payload.request();
            require(batch.config().chainId().longValueExact()==chainId && payload.chainId().equals(batch.config().chainId())
                    && request.items().size()==batch.items().size()
                    && payload.authority().getMpcKeyId().equals(batch.hotChainAddress().getMpcKeyId())
                    && java.util.Arrays.equals(request.batchId(),Numeric.hexStringToByteArray(batch.batchHash()))
                    && request.authority().equalsIgnoreCase(batch.hotWallet()) && request.executor().equalsIgnoreCase(batch.config().relayerAddress())
                    && request.deadline().equals(BigInteger.valueOf(batch.signatureDeadline().getEpochSecond())),"payout plan context differs");
            requireSameRelayer(batch.relayerChainAddress(),relayer);
            for(int index=0;index<request.items().size();index++) {
                var item=request.items().get(index);var claimed=batch.items().get(index);
                require(java.util.Arrays.equals(item.withdrawalId(),claimed.withdrawalId()) && item.token().equalsIgnoreCase(batch.tokenContract())
                        && item.recipient().equalsIgnoreCase(claimed.recipient()) && item.amount().equals(claimed.amountAtomic())
                        && item.callGasLimit().compareTo(BigInteger.valueOf(500_000))<=0,"payout item differs from original claim");
            }
            if(payload.authorization()!=null) {
                requireAuthorization(payload.authorization(),chainId,request.authority(),batch.config().payoutDelegateAddress());
                result.add(input(payload.authority(),"authorization-0",MpcSignCommand.Purpose.EIP7702_AUTHORIZATION,payload.authorization(),com.surprising.wallet.chain.evm.MpcEvm7702Codec.digest(payload.authorization())));
            }
            result.add(input(payload.authority(),PLAN_SLOT,MpcSignCommand.Purpose.EIP7702_OPERATION,request,
                    new com.surprising.wallet.chain.evm.Evm7702PayoutSigner().digest(payload.chainId(),request)));
        }
        return result;
    }

    private RawTransaction expectedOuter(Operation operation,BigInteger gasLimit) {
        Plan plan=plan(operation).orElseThrow();
        var authorizations=new java.util.ArrayList<org.web3j.crypto.AuthorizationTuple>();
        String destination,data;BigInteger priority,maxFee,blockGas;
        com.surprising.wallet.repository.Evm7702ConfigRepository.Configuration config;
        if(operation.type()==MpcSignCommand.BusinessType.COLLECTION) {
            var payload=preparation(operation,com.surprising.wallet.model.mpc.Evm7702CollectionPayload.class);
            var batch=json.readValue(collections.claimPayload(operation.tenantId(),operation.batchId()).orElseThrow(),com.surprising.wallet.coordinator.Evm7702CollectionCoordinator.Batch.class);
            var signed=new java.util.ArrayList<byte[]>();var items=new java.util.ArrayList<com.surprising.wallet.chain.evm.Evm7702CollectionRequest>();
            for(int index=0;index<payload.items().size();index++) {
                var item=payload.items().get(index);byte[] publicKey=publicKey(item.authority(),operation.chain());
                if(item.authorization()!=null)authorizations.add(com.surprising.wallet.chain.evm.MpcEvm7702Codec.authorization(item.authorization(),publicKey,
                        completedInner(operation,item.authority(),"authorization-"+index,com.surprising.wallet.chain.evm.MpcEvm7702Codec.digest(item.authorization()))));
                byte[] digest=new com.surprising.wallet.chain.evm.Evm7702OperationSigner().digest(payload.chainId(),item.request());
                signed.add(com.surprising.wallet.chain.evm.MpcEvm7702Codec.collectionSignature(payload.chainId(),item.request(),publicKey,completedInner(operation,item.authority(),"operation-"+index,digest)));
                items.add(item.request());
            }
            data=new com.surprising.wallet.chain.evm.Evm7702ContractCodec().encodeCollectBatch(items,signed);
            destination=batch.config().collectorAddress();priority=payload.maxPriorityFeePerGas();maxFee=payload.maxFeePerGas();blockGas=payload.blockGasLimit();config=batch.config();
        } else {
            var payload=preparation(operation,com.surprising.wallet.model.mpc.Evm7702PayoutPayload.class);
            var batch=json.readValue(payouts.claimPayload(operation.tenantId(),operation.batchId()).orElseThrow(),com.surprising.wallet.coordinator.Evm7702WithdrawalCoordinator.Batch.class);
            byte[] publicKey=publicKey(payload.authority(),operation.chain());
            if(payload.authorization()!=null)authorizations.add(com.surprising.wallet.chain.evm.MpcEvm7702Codec.authorization(payload.authorization(),publicKey,
                    completedInner(operation,payload.authority(),"authorization-0",com.surprising.wallet.chain.evm.MpcEvm7702Codec.digest(payload.authorization()))));
            byte[] digest=new com.surprising.wallet.chain.evm.Evm7702PayoutSigner().digest(payload.chainId(),payload.request());
            byte[] signature=com.surprising.wallet.chain.evm.MpcEvm7702Codec.payoutSignature(payload.chainId(),payload.request(),publicKey,completedInner(operation,payload.authority(),PLAN_SLOT,digest));
            data=new com.surprising.wallet.chain.evm.Evm7702PayoutCodec().encode(payload.request(),signature);
            destination=batch.hotWallet();priority=payload.maxPriorityFeePerGas();maxFee=payload.maxFeePerGas();blockGas=payload.blockGasLimit();config=batch.config();
        }
        BigInteger cap=BigInteger.valueOf(config.maxBatchGas()).min(new BigDecimal(blockGas).multiply(config.blockGasRatio()).toBigInteger());
        require(gasLimit.signum()>0 && gasLimit.compareTo(cap)<=0,"outer gas exceeds original bounds");
        return com.surprising.wallet.chain.evm.MpcEvm7702Codec.batch(plan.chainId(),plan.relayerNonce(),priority,maxFee,gasLimit,destination,data,authorizations);
    }

    private byte[] completedInner(Operation operation,ChainAddressRecord owner,String slot,byte[] digest) {
        var request=inner(operation,slot).orElseThrow();
        require(request.state()==MpcSignRequest.State.COMPLETED && request.command().keyId().equals(owner.getMpcKeyId())
                && request.command().preimageHex().equals(HexFormat.of().formatHex(digest)),"original inner signature is incomplete or differs");
        return signing.signature(operation.tenantId(),request.id()).orElseThrow();
    }
    private static void requireSameRelayer(ChainAddressRecord original,ChainAddressRecord supplied) {
        require(Objects.equals(original.getTenantId(),supplied.getTenantId()) && Objects.equals(original.getId(),supplied.getId())
                && Objects.equals(original.getMpcKeyId(),supplied.getMpcKeyId()) && original.getAddress().equalsIgnoreCase(supplied.getAddress()),"configured operator differs from original claim");
    }
    private static void requireAuthorization(com.surprising.wallet.chain.evm.MpcEvm7702Codec.Authorization authorization,long chainId,String owner,String delegate) {
        require(authorization.chainId().equals(BigInteger.valueOf(chainId)) && authorization.authority().equalsIgnoreCase(owner)
                && authorization.delegate().equalsIgnoreCase(delegate),"authorization differs from original approved delegate");
    }

    private Optional<MpcSignRequest> inner(Operation operation,String slot) {
        return requests.findBusiness(operation.tenantId(),ChainType.valueOf(operation.chain()),operation.type(),operation.reference(),slot);
    }
    private com.surprising.wallet.model.mpc.MpcKey activePublicOwner(ChainAddressRecord owner,String chain) {
        requirePublicOwner(owner,chain,true);
        return signing.lockActiveKey(owner.getTenantId(),owner.getMpcKeyId(),ChainType.valueOf(chain));
    }
    private void requirePublicOwner(ChainAddressRecord owner,String chain,boolean enabled) {
        require(owner!=null && owner.getTenantId()!=null && owner.getMpcKeyId()!=null && owner.getId()!=null
                && chain.equals(owner.getChain()),"owned MPC chain address required");
        var saved=addresses.findOwned(owner.getTenantId(),owner.getId()).orElseThrow();
        require(Objects.equals(saved.getMpcKeyId(),owner.getMpcKeyId()) && saved.getAddress().equalsIgnoreCase(owner.getAddress())
                && (!enabled || Boolean.TRUE.equals(saved.getEnabled())),"registered batch address identity differs");
        var key=signing.requireKey(owner.getTenantId(),owner.getMpcKeyId());
        String address=MpcPublicAddressCodec.encode(AccountChainProfile.builder().chain(chain).network("unused").build(),HexFormat.of().parseHex(key.publicKeyHex()));
        require(address.equalsIgnoreCase(owner.getAddress()),"joint public key differs from batch address");
    }
    private String status(Operation operation) {
        var states=operation.type()==MpcSignCommand.BusinessType.COLLECTION?collections.findStatuses(operation.tenantId(),operation.batchId())
                :payouts.findStatuses(operation.tenantId(),operation.batchId());
        require(states.size()==1,"owned batch does not exist");return states.getFirst();
    }
    private void lock(Operation operation) {
        var owner=operation.type()==MpcSignCommand.BusinessType.COLLECTION?collections.lockGasOperation(operation.tenantId(),operation.batchId())
                :payouts.lockGasOperation(operation.tenantId(),operation.batchId());
        require(owner.isPresent() && owner.get().chain().equals(operation.chain()),"batch tenant or chain differs");
    }
    private int beginSigning(Operation operation) {
        return operation.type()==MpcSignCommand.BusinessType.COLLECTION?collections.beginSigning(operation.tenantId(),operation.batchId())
                :payouts.beginSigning(operation.tenantId(),operation.batchId());
    }
    private static void requireTransaction() { require(TransactionSynchronizationManager.isActualTransactionActive(),"batch reservation requires owner transaction"); }
    private static void requireOutsideTransaction() { require(!TransactionSynchronizationManager.isActualTransactionActive(),"batch native calls require no database transaction"); }
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalStateException(message); }
}
