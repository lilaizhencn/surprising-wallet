package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.service.CustodyGasService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.service.WalletTaskLeaseService;
import com.surprising.wallet.service.XrpSubmissionService;
import com.surprising.wallet.service.XrpSubmissionService.Operation;
import com.surprising.wallet.service.XrpSubmissionService.Kind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.JsonNode;

/** Public-only XRPL transaction preparation, durable MPC submission and validated settlement. */
@Service
@RequiredArgsConstructor
public class XrpTransactionService {
    private static final String CHAIN="XRP";
    public static final String ACTIVATION_SYMBOL="XRP_ACTIVATION";
    private final XrpRpcClient rpc;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final ChainAddressRepository addresses;
    private final XrpSubmissionService submissions;
    private final CustodyGasService gas;
    private final WalletTaskLeaseService leases;
    private final WalletRuntimeConfigService runtime;

    public String sendNative(Operation operation,ChainAddressRecord source,String recipient,BigDecimal amount) {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_WITHDRAW,"xrp native withdrawal");
        return transfer(operation,source,"XRP",recipient,amount,null);
    }
    public String sendIssuedCurrency(Operation operation,ChainAddressRecord source,TokenDefinition token,String recipient,BigDecimal amount) {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_WITHDRAW,"xrp issued-currency withdrawal");
        return transfer(operation,source,token.getSymbol(),recipient,amount,token);
    }
    private String transfer(Operation operation,ChainAddressRecord source,String symbol,String recipient,BigDecimal amount,TokenDefinition token) {
        var previous=submissions.intent(operation);
        Transaction draft=null;
        if(previous.isEmpty()) {
            Address.of(recipient).validateAddress();
            ensureActivated(source.getAddress());
            CurrencyAmount paymentAmount;
            if(token==null)paymentAmount=XrpCurrencyAmount.of(UnsignedLong.valueOf(amount.movePointRight(6).longValueExact()));
            else {
                var issued=XrpIssuedCurrency.fromToken(token);
                ensureActivated(recipient);ensureTrustLine(source.getAddress(),issued);
                if(!recipient.equals(issued.issuer()))ensureTrustLine(recipient,issued);
                paymentAmount=issued.amount(amount);
            }
            draft=payment(source,recipient,paymentAmount);
        }
        var request=submissions.reserve(operation,source,symbol,amount,draft);
        submissions.sign(operation,request.id());
        var signed=submissions.commit(operation,request.id());
        return broadcast(operation,signed,submissions.payload(request).unsigned());
    }
    /** Same selected bytes are used after a lost reply, even if the current sequence advanced. */
    private String broadcast(Operation operation,MpcXrpTransactionCodec.Signed signed,MpcXrpTransactionCodec.Unsigned unsigned) {
        boolean known=false;
        try {
            var result=rpc.transaction(signed.txId());
            var tx=result.has("tx_json")?result.path("tx_json"):result;
            String hash=result.path("hash").asText(tx.path("hash").asText(""));
            known=signed.txId().equalsIgnoreCase(hash);
        } catch(XrpRpcClient.XrpRpcException absent) {
            if(!"txnNotFound".equals(absent.error()))throw absent;
        }
        if(!known) {
            long expiry=MpcXrpTransactionCodec.restore(unsigned).lastLedgerSequence().orElseThrow().longValue();
            if(rpc.latestLedgerIndex()>expiry)throw new IllegalStateException("XRPL accepted intent expired; ledger reconciliation is required");
            String observed=rpc.submit(signed.transactionHex());
            if(!signed.txId().equalsIgnoreCase(observed))throw new IllegalStateException("XRPL RPC hash differs from committed transaction");
        }
        submissions.observed(operation,signed.txId());
        return signed.txId();
    }
    public boolean recover(Operation operation) {
        var request=submissions.intent(operation);
        if(request.isEmpty())return false;
        submissions.sign(operation,request.get().id());
        var signed=submissions.commit(operation,request.get().id());
        broadcast(operation,signed,submissions.payload(request.get()).unsigned());
        if(operation.kind()!=Kind.ADDRESS_PREPARATION)submissions.restoreSent(operation,signed.txId());
        return true;
    }
    public int releaseUnsigned(Operation operation) {return submissions.releaseUnsigned(operation);}

    public String collectNative(UUID tenant,String reference,ChainAddressRecord source,String target,BigDecimal amount) {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_COLLECTION,"xrp native collection");
        return collect(tenant,reference,source,target,amount,null);
    }
    public String collectIssuedCurrency(UUID tenant,String reference,ChainAddressRecord source,TokenDefinition token,String target,BigDecimal amount) {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_COLLECTION,"xrp issued-currency collection");
        var operation=new Operation(tenant,reference,Kind.COLLECTION);
        if(submissions.intent(operation).isEmpty()) {
            var owned=addresses.findEnabledByTenantAndAddress(tenant,CHAIN,target).orElseThrow();
            ensureIssuedCurrencyDepositReady(owned,token.getSymbol());
        }
        return collect(tenant,reference,source,target,amount,token);
    }
    private String collect(UUID tenant,String reference,ChainAddressRecord source,String target,BigDecimal amount,TokenDefinition token) {
        var existing=collections.findTxHash(tenant,CHAIN,reference);
        if(existing.isPresent())return existing.get();
        if(collections.claimSigningDurable(tenant,CHAIN,reference)!=1)
            throw new IllegalStateException("XRPL collection is already claimed; recovery owns its intent");
        try {
            var operation=new Operation(tenant,reference,Kind.COLLECTION);
            String hash=transfer(operation,source,token==null?"XRP":token.getSymbol(),target,amount,token);
            submissions.restoreSent(operation,hash);return hash;
        } catch(RuntimeException error) {
            collections.markSigningUnknown(tenant,CHAIN,reference,error.getClass().getSimpleName());throw error;
        }
    }
    public boolean confirmWithdrawal(UUID tenant,AccountChainProfile profile,String reference) {
        String hash=orders.findTxHash(CHAIN,reference,tenant).orElseThrow();
        return confirm(new Operation(tenant,reference,Kind.WITHDRAWAL),profile,hash);
    }
    public boolean confirmCollection(UUID tenant,AccountChainProfile profile,String reference) {
        String hash=collections.findTxHash(tenant,CHAIN,reference).orElseThrow();
        return confirm(new Operation(tenant,reference,Kind.COLLECTION),profile,hash);
    }
    private boolean confirm(Operation operation,AccountChainProfile profile,String hash) {
        var receipt=rpc.transaction(hash);
        return submissions.settle(operation,receipt,profile.getWithdrawConfirmations(),rpc.latestLedgerIndex());
    }
    public BigDecimal collectableNativeAmount(String address,BigDecimal candidate) {
        if(candidate==null || candidate.signum()<=0)return BigDecimal.ZERO;
        var account=rpc.accountInfo(address);if(account.isEmpty())return BigDecimal.ZERO;
        var reserve=rpc.reserveInfo();
        int owners=account.get().ownerCount()+missingIssuedCurrencyTrustLines(address);
        BigDecimal spendable=account.get().balanceDrops().movePointLeft(6).subtract(reserve.baseXrp())
                .subtract(reserve.ownerXrp().multiply(BigDecimal.valueOf(owners))).subtract(BigDecimal.valueOf(feeDrops()).movePointLeft(6));
        return candidate.min(spendable.max(BigDecimal.ZERO)).stripTrailingZeros();
    }
    private int missingIssuedCurrencyTrustLines(String address) {
        var symbols=new HashSet<String>();int missing=0;
        for(var record:addresses.listEnabled(CHAIN)) {
            if(!address.equals(record.getAddress()) || "XRP".equalsIgnoreCase(record.getAssetSymbol())
                    || !symbols.add(record.getAssetSymbol().toUpperCase(Locale.ROOT)))continue;
            try {if(!hasTrustLine(address,XrpIssuedCurrency.fromToken(token(record.getAssetSymbol()))))missing++;}
            catch(IllegalArgumentException ignored) { }
        }
        return missing;
    }
    /** Existing preparation intents are recovered before checking current balances or opening another step. */
    public DepositPreparation ensureIssuedCurrencyDepositReady(ChainAddressRecord target,String symbol) {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_TRANSFER,"xrp issued-currency address preparation");
        String configuredSymbol=token(symbol).getSymbol();
        try {
            var result=leases.execute("xrp-preparation:"+target.getTenantId()+":"+target.getMpcKeyId(),()->prepareIssuedCurrency(target,configuredSymbol));
            if(result==null)throw new IllegalStateException("XRPL address preparation is already running");
            return (DepositPreparation)result;
        } catch(RuntimeException error) {throw error;}
        catch(Throwable error) {throw new IllegalStateException("XRPL preparation interrupted",error);}
    }
    private DepositPreparation prepareIssuedCurrency(ChainAddressRecord target,String symbol) {
        var owned=addresses.findOwned(target.getTenantId(),target.getId()).orElseThrow();
        if(!CHAIN.equals(owned.getChain()) || !owned.getAddress().equals(target.getAddress()) || owned.getMpcKeyId()==null)
            throw new IllegalArgumentException("XRPL preparation target is not an owned joint address");
        target=owned;
        var issued=XrpIssuedCurrency.fromToken(token(symbol));
        boolean activatedBefore=rpc.accountInfo(target.getAddress()).isPresent();
        boolean trustBefore=activatedBefore && hasTrustLine(target.getAddress(),issued);
        BigDecimal topUp=BigDecimal.ZERO;String activationHash=null,trustHash=null;
        var funding=new Operation(target.getTenantId(),XrpSubmissionService.preparationReference(target,symbol,false),Kind.ADDRESS_PREPARATION);
        var trust=new Operation(target.getTenantId(),XrpSubmissionService.preparationReference(target,symbol,true),Kind.ADDRESS_PREPARATION);
        if(submissions.intent(funding).isPresent())activationHash=finishPreparation(funding);
        if(submissions.intent(trust).isPresent())trustHash=finishPreparation(trust);
        if(!hasTrustLine(target.getAddress(),issued)) {
            var account=rpc.accountInfo(target.getAddress());
            var reserve=rpc.reserveInfo();
            topUp=reserve.baseXrp().add(reserve.ownerXrp().multiply(BigDecimal.valueOf(account.map(XrpRpcClient.AccountState::ownerCount).orElse(0)+1L)))
                    .add(BigDecimal.valueOf(feeDrops()).movePointLeft(6).multiply(BigDecimal.valueOf(3)))
                    .subtract(account.map(a->a.balanceDrops().movePointLeft(6)).orElse(BigDecimal.ZERO))
                    .max(BigDecimal.ZERO).setScale(6,RoundingMode.CEILING);
            if(topUp.signum()>0) {
                if(activationHash!=null)throw new IllegalStateException("XRPL fixed activation completed but reserve remains insufficient; reconcile the preparation");
                var gasAccount=gas.findGasAccount(target.getTenantId(),CHAIN).filter(a->"ACTIVE".equals(a.status())).orElseThrow();
                var source=addresses.findEnabledByTenantAndAddress(target.getTenantId(),CHAIN,gasAccount.address()).orElseThrow();
                if(collectableNativeAmount(source.getAddress(),topUp).compareTo(topUp)<0)throw new IllegalStateException("tenant XRP gas account has insufficient spendable reserve");
                var request=submissions.reserve(funding,source,symbol,topUp,payment(source,target.getAddress(),XrpCurrencyAmount.of(UnsignedLong.valueOf(topUp.movePointRight(6).longValueExact()))));
                activationHash=finishPreparation(funding);
            }
            if(!hasTrustLine(target.getAddress(),issued)) {
                if(trustHash!=null)throw new IllegalStateException("XRPL fixed TrustSet completed but trust line is unavailable; reconcile the preparation");
                var draft=TrustSet.builder().flags(org.xrpl.xrpl4j.model.flags.TrustSetFlags.builder().tfSetNoRipple().build()).account(Address.of(target.getAddress())).limitAmount(IssuedCurrencyAmount.builder()
                        .issuer(Address.of(issued.issuer())).currency(issued.currencyCode()).value("1000000000").build())
                        .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(feeDrops()))).sequence(UnsignedInteger.valueOf(rpc.accountSequence(target.getAddress())))
                        .lastLedgerSequence(UnsignedInteger.valueOf(rpc.latestLedgerIndex()+20)).build();
                submissions.reserve(trust,target,symbol,BigDecimal.ZERO,draft);trustHash=finishPreparation(trust);
            }
        }
        return new DepositPreparation(rpc.accountInfo(target.getAddress()).isPresent(),hasTrustLine(target.getAddress(),issued),
                activatedBefore,trustBefore,topUp,activationHash,trustHash);
    }
    private String finishPreparation(Operation operation) {
        recover(operation);
        var request=submissions.intent(operation).orElseThrow();
        String hash=submissions.commit(operation,request.id()).txId();
        for(int attempt=0;attempt<8;attempt++) {
            JsonNode receipt=rpc.transaction(hash);
            if(receipt.path("validated").asBoolean(false)) {
                if(!submissions.settle(operation,receipt,1,rpc.latestLedgerIndex()))throw new IllegalStateException("XRPL preparation failed in a validated ledger");
                return hash;
            }
            try {Thread.sleep(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        }
        throw new IllegalStateException("XRPL preparation is pending; retry will recover its fixed intent");
    }
    public boolean hasIssuedCurrencyTrustLine(String address,String symbol) {return hasTrustLine(address,XrpIssuedCurrency.fromToken(token(symbol)));}
    private Payment payment(ChainAddressRecord source,String recipient,CurrencyAmount amount) {
        return Payment.builder().account(Address.of(source.getAddress())).destination(Address.of(recipient)).amount(amount)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(feeDrops()))).sequence(UnsignedInteger.valueOf(rpc.accountSequence(source.getAddress())))
                .lastLedgerSequence(UnsignedInteger.valueOf(rpc.latestLedgerIndex()+20)).build();
    }
    private void ensureActivated(String address) {if(rpc.accountInfo(address).isEmpty())throw new IllegalStateException("XRPL account is not activated");}
    private void ensureTrustLine(String address,XrpIssuedCurrency issued) {if(!hasTrustLine(address,issued))throw new IllegalStateException("XRPL account has no configured issuer trust line");}
    private boolean hasTrustLine(String address,XrpIssuedCurrency issued) {
        return rpc.accountLines(address,issued.issuer()).findValues("currency").stream().anyMatch(c->issued.currencyCode().equalsIgnoreCase(c.asText()));
    }
    private TokenDefinition token(String symbol) {return tokens.findDefinition(CHAIN,profile().getNetwork(),symbol).orElseThrow();}
    private AccountChainProfile profile() {return profiles.findAccountByChain(CHAIN).orElseThrow();}
    private long feeDrops() {Long fee=profile().getDefaultFee();return fee==null||fee<=0?rpc.feeDrops():Math.max(10,fee);}
    public record DepositPreparation(boolean activated,boolean trustLineReady,boolean activatedBefore,boolean trustLineBefore,
                                     BigDecimal activationAmount,String activationTxHash,String trustSetTxHash) { }
}
