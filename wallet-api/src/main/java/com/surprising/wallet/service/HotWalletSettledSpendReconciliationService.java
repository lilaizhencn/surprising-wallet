package com.surprising.wallet.service;

import com.surprising.wallet.common.approval.HotWalletSettledSpendObservation;
import com.surprising.wallet.common.approval.HotWalletSettledSpendRequest;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository;
import com.surprising.wallet.repository.CustodyHotWalletRepository;
import com.surprising.wallet.repository.CustodyHotWalletWithdrawalReservationRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Releases one confirmed principal hold only with a matching independent finalized receipt and balance. */
@Service
public class HotWalletSettledSpendReconciliationService {
    private static final Logger LOG=LoggerFactory.getLogger(HotWalletSettledSpendReconciliationService.class);
    private UUID pendingCursor;
    private final CustodyHotWalletRepository wallets;
    private final CustodyHotWalletWithdrawalReservationRepository reservations;
    private final CustodyHotWalletInventoryObservationRepository observations;
    private final WithdrawalOrderRepository orders;
    private final CustodyAddressRepository addresses;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokens;
    private final CustodyAuditRepository audits;
    private final WithdrawalApprovalClient approval;
    private final TransactionTemplate transaction;

    public HotWalletSettledSpendReconciliationService(CustodyHotWalletRepository wallets,
            CustodyHotWalletWithdrawalReservationRepository reservations,
            CustodyHotWalletInventoryObservationRepository observations,
            WithdrawalOrderRepository orders,CustodyAddressRepository addresses,
            ChainAssetRepository assets,TokenConfigRepository tokens,CustodyAuditRepository audits,
            WithdrawalApprovalClient approval,PlatformTransactionManager manager) {
        this.wallets=wallets;this.reservations=reservations;this.observations=observations;
        this.orders=orders;this.addresses=addresses;this.assets=assets;this.tokens=tokens;
        this.audits=audits;this.approval=approval;this.transaction=new TransactionTemplate(manager);
    }

    public record ReconciliationView(UUID walletId,String chain,String assetSymbol,String orderNo,
            String txHash,long receiptBlockHeight,String receiptBlockHash,long observedBlockHeight,
            String observedBlockHash,BigDecimal remainingCommittedExposure,boolean newlyAccounted) { }

    public ReconciliationView reconcile(CustodyPrincipal principal,UUID walletId,String orderNo,String sourceIp) {
        if(principal==null || principal.tenantId()==null || !"TENANT_ADMIN".equals(principal.role())
                || !principal.hasScope("chains:write") || !principal.hasScope("assets:read"))
            throw new CustodyForbiddenException("tenant administrator chains:write and assets:read scopes required");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("independent finalized spend proof requires a committed caller transaction");
        if(walletId==null || orderNo==null || !orderNo.matches("[A-Za-z0-9_-]{1,96}"))
            throw new IllegalArgumentException("valid original withdrawal order is required");
        return reconcileOriginal(principal.tenantId(),walletId,orderNo,
                principal.actorType().name(),principal.actorId().toString(),sourceIp);
    }

    /** Bounded background scan; an unprovable order remains charged and does not block newer orders. */
    public synchronized void reconcilePending() {
        var pending=reservations.listConfirmedUnaccountedAfter(pendingCursor,20);
        if(pending.isEmpty() && pendingCursor!=null) {
            pendingCursor=null;
            pending=reservations.listConfirmedUnaccountedAfter(null,20);
        }
        for(var hold:pending) {
            pendingCursor=hold.id();
            try {
                if(!ChainType.valueOf(hold.chain()).isEvm())continue;
                reconcileOriginal(hold.tenantId(),hold.walletId(),hold.orderNo(),
                        "SYSTEM","finalized-spend-reconciliation",null);
            } catch(RuntimeException failure) {
                LOG.warn("Confirmed hot wallet spend remains charged: tenant={}, wallet={}, order={}, reason={}",
                        hold.tenantId(),hold.walletId(),hold.orderNo(),failure.getMessage());
            }
        }
    }

    private ReconciliationView reconcileOriginal(UUID tenant,UUID walletId,String orderNo,
            String actorType,String actorId,String sourceIp) {
        if(tenant==null || walletId==null || orderNo==null
                || !orderNo.matches("[A-Za-z0-9_-]{1,96}"))
            throw new IllegalArgumentException("valid original withdrawal order is required");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("independent finalized spend proof requires a committed caller transaction");
        var wallet=wallets.find(tenant,walletId).orElseThrow(()->new IllegalArgumentException("hot wallet not found"));
        if(!ChainType.valueOf(wallet.chain()).isEvm())
            throw new IllegalStateException("finalized direct spend reconciliation requires an EVM wallet");
        var original=orders.find(wallet.chain(),orderNo,tenant).orElseThrow();
        var hold=reservations.findByOrder(tenant,wallet.chain(),orderNo).orElseThrow();
        requireOriginal(wallet,original,hold);
        if(hold.accountedAt()!=null)return view(hold,false);
        if(!"ACTIVE".equals(wallet.status()))
            throw new IllegalStateException("only an active hot wallet may refresh spendable inventory");
        var current=observations.find(tenant,walletId,hold.assetSymbol()).orElseThrow();
        if(current.observedBlockHeight()==null || current.observedBlockHash()==null
                || !wallet.mpcKeyId().equals(current.keyId()))
            throw new IllegalStateException("hot wallet requires anchored independent inventory before reconciliation");
        BigInteger amountAtomic;
        try {amountAtomic=hold.amount().movePointRight(current.decimals()).toBigIntegerExact();}
        catch(ArithmeticException error) {throw new IllegalStateException("original withdrawal precision differs",error);}
        var request=new HotWalletSettledSpendRequest(tenant,wallet.chain(),wallet.network(),
                hold.assetSymbol(),wallet.mpcKeyId(),original.getTxHash(),original.getToAddress(),amountAtomic);
        var proof=approval.settledDirectSpend(request);
        requireProof(wallet,original,hold,current,proof,amountAtomic);
        return transaction.execute(status->{
            var lockedWallet=wallets.lock(tenant,walletId).orElseThrow();
            if(!"ACTIVE".equals(lockedWallet.status()) || !lockedWallet.mpcKeyId().equals(wallet.mpcKeyId())
                    || !lockedWallet.network().equals(wallet.network()))
                throw new IllegalStateException("hot wallet changed before finalized spend accounting");
            var lockedOrder=orders.lockForSettlement(tenant,wallet.chain(),orderNo).orElseThrow();
            var lockedHold=reservations.lockByOrder(tenant,wallet.chain(),orderNo).orElseThrow();
            requireOriginal(lockedWallet,lockedOrder,lockedHold);
            if(lockedHold.accountedAt()!=null)return view(lockedHold,false);
            var latest=observations.find(tenant,walletId,hold.assetSymbol()).orElseThrow();
            requireProof(lockedWallet,lockedOrder,lockedHold,latest,proof,amountAtomic);
            var observed=new CustodyHotWalletInventoryObservationRepository.Observation(tenant,walletId,
                    wallet.chain(),hold.assetSymbol(),wallet.mpcKeyId(),proof.sourceAddress(),
                    proof.networkIdentity(),proof.decimals(),proof.nativeDecimals(),
                    proof.availableAtomic(),proof.nativeAtomic(),proof.observedAt(),
                    proof.collectionRuleId(),proof.collectionRuleFingerprint(),
                    proof.observedBlockHeight(),proof.observedBlockHash());
            if(observations.record(observed)!=1)
                throw new IllegalStateException("finalized balance cannot replace newer or conflicting inventory evidence");
            if(reservations.markAccounted(tenant,wallet.chain(),orderNo,proof.txHash(),
                    proof.receiptBlockHeight(),proof.receiptBlockHash(),proof.observedBlockHeight(),
                    proof.observedBlockHash())!=1)
                throw new IllegalStateException("confirmed withdrawal accounting raced");
            audits.insert(UUID.randomUUID(),tenant,actorType,
                    actorId,"HOT_WALLET.CONFIRMED_SPEND_ACCOUNTED","HOT_WALLET",
                    walletId.toString(),sourceIp,"{\"orderNo\":\""+orderNo+"\",\"txHash\":\""
                            +proof.txHash()+"\",\"receiptBlockHeight\":"+proof.receiptBlockHeight()
                            +",\"observedBlockHeight\":"+proof.observedBlockHeight()+"}");
            return view(reservations.findByOrder(tenant,wallet.chain(),orderNo).orElseThrow(),true);
        });
    }

    private ReconciliationView view(CustodyHotWalletWithdrawalReservationRepository.Reservation hold,
                                    boolean newlyAccounted) {
        return new ReconciliationView(hold.walletId(),hold.chain(),hold.assetSymbol(),hold.orderNo(),
                hold.accountedTxHash(),hold.accountedReceiptBlockHeight(),hold.accountedReceiptBlockHash(),
                hold.accountedObservedBlockHeight(),hold.accountedObservedBlockHash(),
                reservations.committedExposure(hold.tenantId(),hold.walletId(),hold.assetSymbol()),newlyAccounted);
    }

    private void requireOriginal(CustodyHotWalletRepository.Wallet wallet,
            com.surprising.wallet.common.chain.WithdrawalOrderRecord order,
            CustodyHotWalletWithdrawalReservationRepository.Reservation hold) {
        if(!wallet.id().equals(hold.walletId()) || !wallet.tenantId().equals(hold.tenantId())
                || !wallet.chain().equals(hold.chain()) || !"CONFIRMED".equals(hold.status())
                || !"CONFIRMED".equals(order.getStatus()) || !hold.orderNo().equals(order.getOrderNo())
                || !hold.assetSymbol().equals(order.getAssetSymbol())
                || order.getAmount()==null || hold.amount().compareTo(order.getAmount())!=0
                || order.getFromAddress()==null || !order.getFromAddress().equalsIgnoreCase(hold.sourceAddress())
                || order.getTxHash()==null || !order.getTxHash().matches("0x[0-9a-fA-F]{64}")
                || order.getToAddress()==null || !order.getToAddress().matches("0x[0-9a-fA-F]{40}")
                || !addresses.require(wallet.tenantId(),wallet.custodyAddressId()).address()
                        .equalsIgnoreCase(hold.sourceAddress()))
            throw new IllegalStateException("confirmed withdrawal differs from its original hot wallet reservation");
    }

    private void requireProof(CustodyHotWalletRepository.Wallet wallet,
            com.surprising.wallet.common.chain.WithdrawalOrderRecord order,
            CustodyHotWalletWithdrawalReservationRepository.Reservation hold,
            CustodyHotWalletInventoryObservationRepository.Observation current,
            HotWalletSettledSpendObservation proof,BigInteger amountAtomic) {
        Instant now=Instant.now();
        if(proof==null || !wallet.tenantId().equals(proof.tenantId())
                || !wallet.mpcKeyId().equals(proof.keyId()) || !wallet.chain().equals(proof.chain())
                || !wallet.network().equals(proof.network()) || !hold.assetSymbol().equals(proof.assetSymbol())
                || !hold.sourceAddress().equalsIgnoreCase(proof.sourceAddress())
                || !order.getToAddress().equalsIgnoreCase(proof.recipient())
                || !order.getTxHash().equalsIgnoreCase(proof.txHash())
                || !amountAtomic.equals(proof.amountAtomic())
                || !current.networkIdentity().equals(proof.networkIdentity())
                || current.decimals()!=proof.decimals() || current.nativeDecimals()!=proof.nativeDecimals()
                || !current.keyId().equals(proof.keyId())
                || !current.address().equalsIgnoreCase(proof.sourceAddress())
                || proof.availableAtomic()==null || proof.availableAtomic().signum()<0
                || proof.nativeAtomic()==null || proof.nativeAtomic().signum()<0
                || proof.receiptBlockHeight()<0 || proof.observedBlockHeight()<proof.receiptBlockHeight()
                || proof.receiptBlockHash()==null || !proof.receiptBlockHash().matches("0x[0-9a-f]{64}")
                || proof.observedBlockHash()==null || !proof.observedBlockHash().matches("0x[0-9a-f]{64}")
                || proof.observedAt()==null || proof.observedAt().isBefore(now.minusSeconds(60))
                || proof.observedAt().isAfter(now.plusSeconds(5))
                || proof.collectionRuleId()==null || proof.collectionRuleFingerprint()==null
                || !proof.collectionRuleFingerprint().matches("[0-9a-f]{64}"))
            throw new IllegalStateException("independent finalized spend proof differs from original wallet, order or inventory");
        var definition=assets.find(wallet.chain(),hold.assetSymbol()).orElse(null);
        boolean nativeAsset=definition!=null && Boolean.TRUE.equals(definition.getNativeAsset());
        if(nativeAsset ? proof.tokenIdentifier()!=null
                : tokens.listByChainAndNetwork(wallet.chain(),wallet.network()).stream()
                        .noneMatch(token->token.symbol().equals(hold.assetSymbol())
                            && token.contractAddress().equalsIgnoreCase(proof.tokenIdentifier()==null?"":proof.tokenIdentifier())
                            && token.decimals()==proof.decimals()))
            throw new IllegalStateException("independent finalized spend token identity differs from local network");
    }
}
