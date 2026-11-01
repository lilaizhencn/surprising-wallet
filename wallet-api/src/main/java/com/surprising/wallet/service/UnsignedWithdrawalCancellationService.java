package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.model.custody.WithdrawalApprovalRecord;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;

/** Cancel only under a freshly observed independent terminal/no-grant receipt. Unknown authorized payments remain frozen. */
@Service @RequiredArgsConstructor
public class UnsignedWithdrawalCancellationService {
    private final WalletMpcSignRequestRepository requests;private final WalletMpcSignAttemptRepository attempts;
    private final ChainSigningTransactionRepository utxoBatches;private final UtxoRepository utxos;
    private final EvmWithdrawalBatchRepository evmBatches;private final EvmWithdrawalBatchItemRepository evmItems;private final EvmWithdrawalBatchAttemptRepository evmAttempts;
    private final WithdrawalOrderRepository orders;private final AccountSequenceRepository sequences;private final EvmNonceRepository nonces;
    private final CustodyGasUsageRepository gasUsages;private final CustodyGasAccountingService gas;private final CustodyAuditRepository audit;private final ObjectMapper json;

    /** Joins the order/receipt/refund transaction. All checks finish before the first cancellation mutation. */
    public boolean cancel(WithdrawalApprovalRecord original,ApprovalRequest receipt) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),"unsigned cancellation requires the owning money transaction");
        require(receipt.intent().equals(original.intent()) && !receipt.signingStarted() && Set.of("REJECTED","EXPIRED").contains(receipt.status())
                && receipt.intentHash().equals(receipt.assetSnapshot().bind(original.intent(),json)),"definitive independent terminal no-grant receipt required");
        UUID tenant=original.tenantId();String chain=original.intent().chain(),reference=original.intent().businessReference();
        var nativeIds=new TreeSet<UUID>(requests.withdrawalMembers(tenant,chain,reference));
        var bitcoin=new ArrayList<UtxoSigningTransaction>();var evm=new ArrayList<EvmRoot>();var others=new TreeSet<String>();
        if(ChainType.valueOf(chain).isBitcoinLike())for(int id:utxoBatches.withdrawalMembers(tenant,chain,reference)) {
            var root=utxoBatches.lockById(chain,id).orElseThrow();
            require(root.request().tenantId().equals(tenant) && root.request().operation()==UtxoSigningRequest.Operation.WITHDRAWAL,"foreign UTXO cancellation root");
            if(root.signed()!=null || !root.attempts().isEmpty() || (root.status()!=Constants.SIGNING && root.status()!=Constants.DELETE))return false;
            nativeIds.addAll(requests.businessSlots(tenant,chain,MpcSignCommand.BusinessType.WITHDRAWAL,root.request().requestId().toString()));bitcoin.add(root);
            if(!root.terminal())for(var payment:root.request().payments())others.add(payment.reference());
        }
        if(ChainType.valueOf(chain).isEvm())for(var id:evmItems.withdrawalMembers(tenant,original.orderId())) {
            var root=evmBatches.lock(tenant,id).orElseThrow();
            require(root.chain().equals(chain),"foreign EIP-7702 cancellation root");
            if(root.canonicalTxHash()!=null || evmAttempts.countByBatch(tenant,id)!=0 || !Set.of("LOCKED","SIGNING","FAILED").contains(root.status()))return false;
            var members=evmItems.listByBatch(tenant,id);boolean active=!root.status().equals("FAILED");
            if(active && members.stream().anyMatch(item->!item.status().equals("CREATED")))return false;
            var fee=gasUsages.findForUpdate(tenant,"WITHDRAWAL_BATCH",id);
            if(fee.isPresent() && !Set.of("RESERVED","RELEASED").contains(fee.get().status()))return false;
            nativeIds.addAll(requests.businessSlots(tenant,chain,MpcSignCommand.BusinessType.WITHDRAWAL,"eip7702:"+tenant+":"+id));
            if(active)for(var member:members)others.add(orders.findById(tenant,member.withdrawalOrderId()).orElseThrow().getOrderNo());
            evm.add(new EvmRoot(root,members,fee.filter(value->value.status().equals("RESERVED")).isPresent()));
        }
        others.remove(reference);
        // An order still owned by a different live plan is not made available by cancelling this batch.
        for(var other:others) {
            var order=orders.lockForSettlement(tenant,chain,other).orElseThrow();
            if(!orders.canIndependentlyCancel(tenant,order.getId()))return false;
            for(var id:requests.withdrawalMembers(tenant,chain,other))if(!nativeIds.contains(id) && requests.lock(tenant,id).orElseThrow().state()!=MpcSignRequest.State.CANCELLED)return false;
        }
        var cancellable=new ArrayList<UUID>();var owners=new TreeSet<String>();
        owners.add(MpcSignCommand.reservationOwner(tenant,MpcSignCommand.BusinessType.WITHDRAWAL,reference));
        for(var root:evm)owners.add(MpcSignCommand.reservationOwner(tenant,MpcSignCommand.BusinessType.WITHDRAWAL,"eip7702:"+tenant+":"+root.batch().id()));
        var preparedBitcoin=new HashSet<Integer>();var preparedEvm=new HashSet<UUID>();
        for(var id:nativeIds) {
            var request=requests.lock(tenant,id).orElseThrow();var command=request.command();
            if(request.state()==MpcSignRequest.State.COMPLETED)return false;
            if(attempts.history(tenant,id).stream().anyMatch(a->a.authorization()!=null || a.state()==MpcSignAttempt.State.COMPLETED || a.signatureCiphertext()!=null))return false;
            if(request.state()==MpcSignRequest.State.CANCELLED)continue;
            // Every old command must become permanently inadmissible when THIS intent is terminal.
            require(command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL && command.businessReferences(json).contains(reference),"native batch does not bind the terminal parent");
            if(command.chain().isBitcoinLike()) {
                var payload=UtxoSigningPayload.decode(command,json);var root=bitcoin.stream().filter(row->row.id()==payload.task().transactionId()).findFirst().orElseThrow();
                require(root.request().equals(payload.task().request()),"UTXO cancellation payload differs from original root");preparedBitcoin.add(root.id());
            }
            if(command.payloadEncoding().equals(Evm7702SigningPayload.ENCODING)) {
                String prefix="eip7702:"+tenant+":";require(command.businessReference().startsWith(prefix),"EIP-7702 cancellation owner differs");
                UUID batch=UUID.fromString(command.businessReference().substring(prefix.length()));require(evm.stream().anyMatch(row->row.batch().id().equals(batch)),"missing EIP-7702 cancellation root");preparedEvm.add(batch);
            }
            owners.add(command.reservationOwner());cancellable.add(id);
        }
        for(var root:bitcoin)if(!root.terminal() && root.enqueuedRequestId()!=null && !preparedBitcoin.contains(root.id()))return false;
        for(var root:evm)if(root.batch().status().equals("SIGNING") && !preparedEvm.contains(root.batch().id()))return false;
        if(!orders.canIndependentlyCancel(tenant,original.orderId()) && !orders.independentRefundComplete(tenant,original.orderId()))return false;

        String terminal=json.writeValueAsString(receipt);
        for(var id:cancellable)require(requests.cancelWithoutGrant(tenant,id,terminal)==1,"unsigned native cancellation raced");
        var allocations=new ArrayList<Object>();
        for(var owner:owners) {
            if(ChainType.valueOf(chain).isEvm())allocations.addAll(nonces.releaseCancelled(chain,owner));
            else if(Set.of("APTOS","TON","XRP","NEAR","DOT").contains(chain))allocations.addAll(sequences.releaseCancelled(chain,owner));
        }
        int unlocked=0;
        for(var root:bitcoin) {
            if(!root.terminal())require(utxoBatches.update(root.failed("withdrawal could not be processed"))==1,"unsigned UTXO cancellation raced");
            unlocked+=utxos.release(chain,Integer.toString(root.id()));
        }
        for(var root:evm) {
            require(evmBatches.cancelUnsigned(tenant,root.batch().id())==1,"unsigned EIP-7702 cancellation raced");
            for(var item:root.items())evmItems.markPreBroadcastFailed(tenant,root.batch().id(),item.itemIndex(),"APPROVAL_TERMINAL");
            if(root.releaseGas())gas.release(tenant,"WITHDRAWAL_BATCH",root.batch().id(),"unsigned independent terminal cancellation");
        }
        for(var other:others)require(orders.requeueUnsignedBatchMember(tenant,chain,other)==1,"unsigned sibling order changed");
        audit.insert(UUID.randomUUID(),tenant,"SYSTEM","independent-approval","WITHDRAWAL.UNSIGNED_CANCEL","CUSTODY_WITHDRAWAL",original.withdrawalId().toString(),null,
                json.writeValueAsString(Map.of("approvalRequestId",receipt.intent().id(),"intentHash",receipt.intentHash(),"nativeRequests",cancellable,"releasedAllocations",allocations,
                        "utxoRoots",bitcoin.stream().map(UtxoSigningTransaction::id).toList(),"unlockedInputs",unlocked,"evmRoots",evm.stream().map(r->r.batch().id()).toList(),"requeuedReferences",others)));
        return true;
    }
    private record EvmRoot(EvmWithdrawalBatchRepository.BatchRow batch,List<EvmWithdrawalBatchItemRepository.Item> items,boolean releaseGas) { }
    private static void require(boolean allowed,String message){if(!allowed)throw new IllegalStateException(message);}
}
