package com.surprising.wallet.service;

import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.custody.WithdrawalApprovalRecord;
import com.surprising.wallet.repository.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Independent network admission is outside every Wallet transaction; applying its receipt owns a short transaction. */
@Slf4j @Service
public class WithdrawalApprovalService {
    private final WalletWithdrawalApprovalRepository approvals;
    private final WithdrawalOrderRepository orders;
    private final LedgerBalanceRepository balances;
    private final CustodyAuditRepository audit;
    private final CustodyWithdrawalReconciliationService projection;
    private final WithdrawalApprovalClient authority;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final UnsignedWithdrawalCancellationService cancellation;
    private final HotWalletReservationLifecycleService hotReservations;
    public WithdrawalApprovalService(WalletWithdrawalApprovalRepository approvals,WithdrawalOrderRepository orders,LedgerBalanceRepository balances,
            CustodyAuditRepository audit,CustodyWithdrawalReconciliationService projection,WithdrawalApprovalClient authority,ObjectMapper json,
            PlatformTransactionManager manager,UnsignedWithdrawalCancellationService cancellation,
            HotWalletReservationLifecycleService hotReservations) {
        this.approvals=approvals;this.orders=orders;this.balances=balances;this.audit=audit;this.projection=projection;this.authority=authority;
        this.json=json;this.transaction=new TransactionTemplate(manager);
        this.cancellation=cancellation;
        this.hotReservations=hotReservations;
    }
    public void reconcile() {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(30).toNanos();
        for(var row:approvals.awaiting(100)) {
            if(System.nanoTime()>=deadline)return;
            try { reconcile(row.tenantId(),row.withdrawalId()); }
            catch(RuntimeException failure) {
                if(Thread.currentThread().isInterrupted())throw failure;
                approvals.checked(row.tenantId(),row.withdrawalId());
                log.warn("independent withdrawal admission unavailable: withdrawalId={}",row.withdrawalId());
            }
        }
    }
    public WithdrawalApprovalRecord reconcile(UUID tenant,UUID withdrawal) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent admission requires a committed Wallet freeze");
        var original=approvals.find(tenant,withdrawal).orElseThrow();
        if(original.state().equals("AUTHORIZED") || Set.of("REJECTED","EXPIRED").contains(original.state()) && approvals.terminalCleanupComplete(tenant,withdrawal))return original;
        var receipt=original.receipt()==null?authority.submit(original.intent()):authority.status(tenant,original.intent().id());
        require(receipt.intent().equals(original.intent()) && receipt.intentHash().equals(receipt.assetSnapshot().bind(original.intent(),json)),
                "independent approval receipt differs from the original frozen intent");
        return transaction.execute(status->apply(original,receipt));
    }
    private WithdrawalApprovalRecord apply(WithdrawalApprovalRecord original,ApprovalRequest receipt) {
        UUID tenant=original.tenantId(),id=original.withdrawalId();
        var current=approvals.lock(tenant,id).orElseThrow();
        require(current.intent().equals(original.intent()),"withdrawal admission intent changed");
        if(current.state().equals("AUTHORIZED") || Set.of("REJECTED","EXPIRED").contains(current.state()) && approvals.terminalCleanupComplete(tenant,id))return current;
        if(current.state().equals("APPROVED") && receipt.status().equals("PENDING"))return current;
        if(current.receipt()!=null)require(current.receipt().intentHash().equals(receipt.intentHash()),"independent reviewed snapshot changed");
        String next=receipt.signingStarted()?"AUTHORIZED":receipt.status();
        require(Set.of("PENDING","APPROVED","AUTHORIZED","REJECTED","EXPIRED").contains(next),"invalid independent approval state");
        require(!receipt.signingStarted() || receipt.status().equals("APPROVED"),"independent signed intent has a terminal cancellation");
        if(Set.of("REJECTED","EXPIRED").contains(current.state()))require(current.state().equals(next),"independent terminal receipt cannot be replaced");
        var order=orders.lockForSettlement(tenant,original.intent().chain(),original.intent().businessReference()).orElseThrow();
        require(order.getId()==original.orderId(),"approval receipt belongs to another frozen order");
        boolean terminal=Set.of("REJECTED","EXPIRED").contains(next),cleaned=false;
        if(next.equals("APPROVED"))orders.releaseIndependentReview(tenant,original.orderId());
        else if(terminal && cancellation.cancel(current,receipt)) {
            if(orders.refuseIndependentlyCancelled(tenant,original.orderId())==1)
                require(balances.release(order.getChain(),order.getAssetSymbol(),order.getDebitAccountId(),order.getAmount().add(order.getFee()),tenant),
                        "independent refusal principal release is inconsistent");
            require(orders.independentRefundComplete(tenant,original.orderId()),"unsigned cancellation refund did not complete");
            hotReservations.close(tenant,order.getChain(),order.getOrderNo(),"CANCELLED");
            projection.project(tenant,id); // Input/nonce/batch cleanup, principal, Gas, event and receipt share one transaction.
            cleaned=true;
        }
        if(!Set.of("REJECTED","EXPIRED").contains(current.state()))require(approvals.record(tenant,id,next,receipt)==1,"withdrawal approval receipt changed concurrently");
        else approvals.checked(tenant,id);
        if(cleaned)approvals.completeTerminalCleanup(tenant,id);
        if(!current.state().equals(next))audit.insert(UUID.randomUUID(),tenant,"SYSTEM","independent-approval","WITHDRAWAL.APPROVAL_RECEIPT",
                "CUSTODY_WITHDRAWAL",id.toString(),null,json.writeValueAsString(Map.of("state",next,"intentHash",receipt.intentHash(),"orderNo",original.intent().businessReference(),"terminalCleanup",terminal?(cleaned?"COMPLETED":"HELD"):"NOT_TERMINAL")));
        return approvals.find(tenant,id).orElseThrow();
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalStateException(message); }
}
