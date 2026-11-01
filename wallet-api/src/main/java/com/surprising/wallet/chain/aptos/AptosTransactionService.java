package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.model.mpc.AptosSigningPayload;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.AptosSubmissionService.Operation;
import com.surprising.wallet.service.AptosSubmissionService.Kind;
import java.math.BigInteger;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Public chain preparation and same-message recovery; all business writes are short transactions. */
@Service
@RequiredArgsConstructor
public class AptosTransactionService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final AptosRpcClient rpc;
    private final AptosSubmissionService submissions;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final WalletRuntimeConfigService runtime;
    public String send(Operation op,ChainAddressRecord source){runtime.requireTaskEnabled("APTOS",WalletRuntimeConfigService.TASK_WITHDRAW,"Aptos withdrawal");if(op.kind()!=Kind.WITHDRAWAL)throw new IllegalArgumentException("withdrawal operation required");return transfer(op,source);}
    public String collect(UUID tenant,String reference,ChainAddressRecord source) {
        runtime.requireTaskEnabled("APTOS",WalletRuntimeConfigService.TASK_COLLECTION,"Aptos collection");var previous=collections.findTxHash(tenant,"APTOS",reference);if(previous.isPresent())return previous.get();
        if(collections.claimSigningDurable(tenant,"APTOS",reference)!=1)throw new IllegalStateException("Aptos collection is claimed; recovery owns the intent");var op=new Operation(tenant,reference,Kind.COLLECTION);
        try{String hash=transfer(op,source);submissions.restoreSent(op,hash);return hash;}catch(RuntimeException error){submissions.unknown(op,error.getClass().getSimpleName());throw error;}
    }
    private String transfer(Operation op,ChainAddressRecord source) {
        AptosSigningPayload draft=null;
        if(submissions.intent(op).isEmpty()) {
            var payment=submissions.payment(op);var profile=profiles.findAccountByChain("APTOS").orElseThrow();int decimals=8;String metadata=null;
            if(!"APT".equals(payment.symbol())){var token=tokens.findDefinition("APTOS",profile.getNetwork(),payment.symbol()).orElseThrow();metadata=AptosFungibleAsset.requireMetadata(token);decimals=token.getDecimals();}
            long price=rpc.estimateGasPrice();if(price<=0)throw new IllegalStateException("Aptos gas price is invalid");
            long maxGas=Math.max(50_000L,BigInteger.valueOf(Math.max(1L,profile.getDefaultFee())).add(BigInteger.valueOf(price-1)).divide(BigInteger.valueOf(price)).longValueExact());
            var ledger=rpc.ledgerInfo();long seconds=ledger.path("ledger_timestamp").asLong()/1_000_000;if(seconds<=0)throw new IllegalStateException("Aptos ledger timestamp is absent");
            var unsigned=new MpcAptosTransactionCodec.Unsigned(source.getAddress(),rpc.sequenceNumber(source.getAddress()),payment.recipient(),payment.amount().movePointRight(decimals).longValueExact(),metadata,maxGas,price,Math.addExact(seconds,600),ledger.path("chain_id").asInt(),submissions.publicKey(source));
            draft=new AptosSigningPayload(payment.symbol(),payment.amount(),payment.customerFee(),decimals,unsigned);
        }
        var request=submissions.reserve(op,source,draft);submissions.sign(op,request.id());var signed=submissions.commit(op,request.id());return broadcast(op,signed,submissions.payload(request));
    }
    public boolean recover(Operation op){var request=submissions.intent(op);if(request.isEmpty())return false;submissions.sign(op,request.get().id());var signed=submissions.commit(op,request.get().id());String hash=broadcast(op,signed,submissions.payload(request.get()));submissions.restoreSent(op,hash);return true;}
    public int releaseUnsigned(Operation op){return submissions.releaseUnsigned(op);}
    private String broadcast(Operation op,MpcAptosTransactionCodec.Signed signed,AptosSigningPayload payload) {
        var known=rpc.transactionByHash(signed.txId());if(known==null||known.isNull()) {
            long seconds=rpc.ledgerInfo().path("ledger_timestamp").asLong()/1_000_000;
            if(seconds<=0||seconds>=payload.unsigned().expiration())throw new IllegalStateException("Aptos fixed transaction expired or ledger time unavailable; reconciliation required");
            String observed=rpc.submitTransaction(JSON.readValue(signed.json(),ObjectNode.class));if(!signed.txId().equals(observed))throw new IllegalStateException("Aptos node returned a different selected transaction hash");
        }
        submissions.observed(op,signed.txId());return signed.txId();
    }
    public boolean confirmWithdrawal(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.WITHDRAWAL),orders.findTxHash("APTOS",reference,tenant).orElseThrow());}
    public boolean confirmCollection(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.COLLECTION),collections.findTxHash(tenant,"APTOS",reference).orElseThrow());}
    private boolean confirm(Operation op,String hash){var receipt=rpc.transactionByHash(hash);if(receipt==null||!"user_transaction".equals(receipt.path("type").asText()))return false;return submissions.settle(op,receipt,rpc.ledgerVersion(),profiles.findAccountByChain("APTOS").orElseThrow().getWithdrawConfirmations());}
}
