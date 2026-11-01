package com.surprising.wallet.chain.hypercore;

import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.HyperCoreActionRepository;
import com.surprising.wallet.service.HyperCoreSubmissionService;
import com.surprising.wallet.service.HyperCoreSubmissionService.Operation;
import com.surprising.wallet.service.HyperCoreSubmissionService.Kind;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Remote MPC user-signed transfers. Unknown responses retain the original body and nonce. */
@Service
@RequiredArgsConstructor
public class HyperCoreTransactionService {
    private final HyperCoreApiClient api;
    private final HyperCoreSubmissionService submissions;
    private final HyperCoreActionRepository actions;
    private final CollectionRecordRepository collections;
    private final WalletRuntimeConfigService runtime;
    private final ObjectMapper json;

    public String send(Operation operation,ChainAddressRecord source) {
        runtime.requireTaskEnabled("HYPERCORE",WalletRuntimeConfigService.TASK_WITHDRAW,"hypercore withdrawal");
        if(operation.kind()!=Kind.WITHDRAWAL)throw new IllegalArgumentException("withdrawal operation required");
        var request=reserve(operation,source);submissions.sign(operation,request.id());
        return broadcast(operation,submissions.commit(operation,request.id()));
    }
    public void collect(UUID tenant,String reference,ChainAddressRecord source) {
        runtime.requireTaskEnabled("HYPERCORE",WalletRuntimeConfigService.TASK_COLLECTION,"hypercore collection");
        if(collections.claimSigningDurable(tenant,"HYPERCORE",reference)!=1)return;
        var operation=new Operation(tenant,reference,Kind.COLLECTION);
        try {
            var request=reserve(operation,source);submissions.sign(operation,request.id());
            String id=broadcast(operation,submissions.commit(operation,request.id()));submissions.restoreSent(operation,id);
        } catch(RuntimeException error){submissions.unknown(operation,error.getClass().getSimpleName());throw error;}
    }
    public boolean recover(Operation operation) {
        var request=submissions.intent(operation);if(request.isEmpty())return false;
        submissions.sign(operation,request.get().id());
        String id=broadcast(operation,submissions.commit(operation,request.get().id()));submissions.restoreSent(operation,id);return true;
    }
    public int releaseUnsigned(Operation operation){return submissions.releaseUnsigned(operation);}
    private com.surprising.wallet.model.mpc.MpcSignRequest reserve(Operation operation,ChainAddressRecord source) {
        String recipient=null;
        if(submissions.intent(operation).isEmpty()) {
            recipient=submissions.recipient(operation);
            var role=api.postInfo(json.createObjectNode().put("type","userRole").put("user",recipient));
            if(role==null || !("user".equals(role.path("role").asText()) || "subAccount".equals(role.path("role").asText())))
                throw new IllegalStateException("HyperCore recipient must be activated before payout; activation fees are not included in this payment");
        }
        return submissions.reserve(operation,source,recipient);
    }
    private String broadcast(Operation operation,HyperCoreSubmissionService.Prepared prepared) {
        if(!actions.accepted(prepared.actionId())) {
            if(reconcile(operation,prepared.actionId()))return prepared.actionId();
            try {
                var response=api.postExchange(json.readValue(prepared.body(),ObjectNode.class));
                submissions.accepted(operation,prepared.actionId(),response);
            } catch(RuntimeException unknown) {
                try {if(reconcile(operation,prepared.actionId()))return prepared.actionId();}
                catch(RuntimeException proofUnavailable){unknown.addSuppressed(proofUnavailable);}
                submissions.unknown(operation,unknown.getClass().getSimpleName());throw unknown;
            }
        }
        return prepared.actionId();
    }
    private boolean reconcile(Operation operation,String id) {
        var request=submissions.intent(operation).orElseThrow();var payload=submissions.payload(request);
        var unsigned=payload.unsigned();var action=MpcHyperCoreActionCodec.restore(unsigned);
        var records=api.postInfo(json.createObjectNode().put("type","userNonFundingLedgerUpdates").put("user",unsigned.owner())
                .put("startTime",Math.max(0,action.path("time").asLong()-60_000)));
        if(records==null || !records.isArray())throw new IllegalStateException("HyperCore ledger query returned no event list");
        tools.jackson.databind.JsonNode found=null;
        for(var record:records) {
            var delta=record.path("delta");
            if(!delta.path("nonce").isIntegralNumber() || !delta.path("nonce").canConvertToLong()
                    || delta.path("nonce").asLong()!=action.path("time").asLong()
                    || !unsigned.owner().equalsIgnoreCase(delta.path("user").asText()))continue;
            HyperCoreExecutionReceipt.verify(unsigned,record);
            if(found!=null && !found.equals(record))throw new IllegalStateException("HyperCore nonce has conflicting execution records");
            found=record;
        }
        if(found!=null){submissions.proven(operation,id,found);return true;}
        if(records.size()>=HyperCoreDepositService.LEDGER_PAGE_THRESHOLD)throw new IllegalStateException("HyperCore ledger query is truncated; original action remains pending");
        return false;
    }
    private boolean confirm(Operation operation,String id) {
        var action=actions.find(id).orElseThrow();
        if(action.receipt()==null && !reconcile(operation,id))return false;
        return submissions.settle(operation,id);
    }
    public boolean confirmWithdrawal(UUID tenant,String reference,String id) {return confirm(new Operation(tenant,reference,Kind.WITHDRAWAL),id);}
    public boolean confirmCollection(UUID tenant,String reference,String id) {return confirm(new Operation(tenant,reference,Kind.COLLECTION),id);}
}
