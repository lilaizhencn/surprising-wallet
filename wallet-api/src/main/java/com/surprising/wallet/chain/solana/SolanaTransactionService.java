package com.surprising.wallet.chain.solana;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.model.mpc.SolanaSigningPayload;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.service.CustodyGasService;
import com.surprising.wallet.service.SolanaSubmissionService;
import com.surprising.wallet.service.SolanaSubmissionService.Operation;
import com.surprising.wallet.service.SolanaSubmissionService.Kind;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Public-key preparation, fixed multi-signer MPC, identical-wire recovery and receipt settlement. */
@Service
@RequiredArgsConstructor
public class SolanaTransactionService {
    private final SolanaRpcClient rpc;
    private final SolanaSubmissionService submissions;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final CustodyGasService gas;
    private final WalletRuntimeConfigService runtime;

    public String send(Operation operation,ChainAddressRecord source) {
        runtime.requireTaskEnabled("SOLANA",WalletRuntimeConfigService.TASK_WITHDRAW,"Solana withdrawal");
        if(operation.kind()!=Kind.WITHDRAWAL)throw new IllegalArgumentException("withdrawal operation required");
        return transfer(operation,source);
    }
    public String collect(UUID tenant,String reference,ChainAddressRecord source) {
        runtime.requireTaskEnabled("SOLANA",WalletRuntimeConfigService.TASK_COLLECTION,"Solana collection");
        var previous=collections.findTxHash(tenant,"SOLANA",reference);if(previous.isPresent())return previous.get();
        if(collections.claimSigningDurable(tenant,"SOLANA",reference)!=1)throw new IllegalStateException("Solana collection is already claimed; recovery owns its intent");
        var operation=new Operation(tenant,reference,Kind.COLLECTION);
        try {String signature=transfer(operation,source);submissions.restoreSent(operation,signature);return signature;}
        catch(RuntimeException error){submissions.unknown(operation,error.getClass().getSimpleName());throw error;}
    }
    private String transfer(Operation operation,ChainAddressRecord source) {
        SolanaSigningPayload draft=null;
        if(submissions.intent(operation).isEmpty()) {
            var payment=submissions.payment(operation);var profile=profiles.findAccountByChain("SOLANA").orElseThrow();
            boolean nativeAsset="SOL".equals(payment.symbol());String mint=null,payer=source.getAddress();int decimals=9;
            if(!nativeAsset) {
                var token=tokens.findDefinition("SOLANA",profile.getNetwork(),payment.symbol()).orElseThrow();mint=token.getContractAddress();decimals=token.getDecimals();
                payer=gas.findGasAccount(operation.tenantId(),"SOLANA").filter(account->"ACTIVE".equals(account.status())).orElseThrow().address();
            }
            var latest=rpc.call("getLatestBlockhash",List.of(Map.of("commitment","confirmed"))).path("value");
            String blockhash=latest.path("blockhash").asText();long lastValid=latest.path("lastValidBlockHeight").asLong(0);
            var unsigned=MpcSolanaTransactionCodec.payment(source.getAddress(),payer,payment.recipient(),mint,
                    payment.amount().movePointRight(decimals).longValueExact(),decimals,blockhash,SolanaSubmissionService.memo(operation));
            draft=new SolanaSigningPayload(payment.symbol(),payment.amount(),payment.customerFee(),decimals,mint,source.getAddress(),payment.recipient(),payer,lastValid,unsigned);
        }
        var request=submissions.reserve(operation,source,draft);submissions.sign(operation,request.id());
        var signed=submissions.commit(operation,request.id());return broadcast(operation,signed,submissions.payload(request));
    }
    public boolean recover(Operation operation) {
        var request=submissions.intent(operation);if(request.isEmpty())return false;
        submissions.sign(operation,request.get().id());var signed=submissions.commit(operation,request.get().id());
        String signature=broadcast(operation,signed,submissions.payload(request.get()));submissions.restoreSent(operation,signature);return true;
    }
    public int releaseUnsigned(Operation operation){return submissions.releaseUnsigned(operation);}
    private String broadcast(Operation operation,MpcSolanaTransactionCodec.Signed signed,SolanaSigningPayload payload) {
        var status=rpc.getSignatureStatus(signed.signature());
        if(status==null || status.isNull() || status.isMissingNode()) {
            long height=rpc.call("getBlockHeight",List.of(Map.of("commitment","confirmed"))).asLong();
            if(height>payload.lastValidBlockHeight())throw new IllegalStateException("Solana accepted message expired; chain reconciliation is required");
            String observed=rpc.sendTransaction(Base64.getDecoder().decode(signed.transactionBase64()));
            if(!signed.signature().equals(observed))throw new IllegalStateException("Solana RPC returned another transaction signature");
        }
        submissions.observed(operation,signed.signature());return signed.signature();
    }
    public boolean confirmWithdrawal(UUID tenant,String reference) {return confirm(new Operation(tenant,reference,Kind.WITHDRAWAL),orders.findTxHash("SOLANA",reference,tenant).orElseThrow());}
    public boolean confirmCollection(UUID tenant,String reference) {return confirm(new Operation(tenant,reference,Kind.COLLECTION),collections.findTxHash(tenant,"SOLANA",reference).orElseThrow());}
    private boolean confirm(Operation operation,String signature) {
        var status=rpc.getSignatureStatus(signature);
        if(status==null || status.isNull() || !Set.of("confirmed","finalized").contains(status.path("confirmationStatus").asText()))return false;
        var receipt=rpc.getRawTransaction(signature);if(receipt==null || receipt.isNull())return false;
        return submissions.settle(operation,receipt,status,rpc.getSlot(),profiles.findAccountByChain("SOLANA").orElseThrow().getWithdrawConfirmations());
    }
}
