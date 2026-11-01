package com.surprising.wallet.chain.near;

import com.surprising.wallet.common.signing.NearPayment;

import com.surprising.wallet.common.signing.NearOperation;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.signing.NearSigningPayload;
import com.surprising.wallet.common.signing.NearRegistrationPayload;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;

import com.surprising.wallet.common.signing.NearOperation.Kind;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.base.Base58;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Public access-key/RPC selection. Recovery always retains the original nonce, block and signed Borsh. */
@Service
@RequiredArgsConstructor
public class NearTransactionService {
    private final com.surprising.wallet.service.MpcAuthorizationService authorizations;
    private static final long TOKEN_GAS=30_000_000_000_000L;
    private final NearRpcClient rpc;
    private final NearSubmissionService submissions;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final NearRegistrationService registrations;
    @NonNull private final WalletRuntimeConfigService runtime;

    public String send(NearOperation op,ChainAddressRecord source) {
        runtime.requireTaskEnabled("NEAR",WalletRuntimeConfigService.TASK_WITHDRAW,"NEAR withdrawal");
        if(op.kind()!=Kind.WITHDRAWAL)throw new IllegalArgumentException("withdrawal operation required");
        return transfer(op,source);
    }
    public String collect(UUID tenant,String reference,ChainAddressRecord source) {
        runtime.requireTaskEnabled("NEAR",WalletRuntimeConfigService.TASK_COLLECTION,"NEAR collection");
        var previous=collections.findTxHash(tenant,"NEAR",reference);if(previous.isPresent())return previous.get();
        if(collections.claimSigningDurable(tenant,"NEAR",reference)!=1)throw new IllegalStateException("NEAR collection is claimed; recovery owns the intent");
        var op=new NearOperation(tenant,reference,Kind.COLLECTION);
        try{String hash=transfer(op,source);submissions.restoreSent(op,hash);return hash;}
        catch(RuntimeException error){submissions.unknown(op,error.getClass().getSimpleName());throw error;}
    }
    private String transfer(NearOperation op,ChainAddressRecord source) {
        NearSigningPayload draft=null;
        if(submissions.intent(op).isEmpty()) {
            var payment=submissions.payment(op);var previousRegistration=registrations.intent(op);
            var original=previousRegistration.isPresent()?registrations.payload(op):null;
            var profile=(original==null?profiles.findAccountByChain("NEAR"):profiles.findByChainAndNetwork("NEAR",original.unsigned().network())).orElseThrow();
            var selectedRpc=rpc.forNetwork(profile.getNetwork());
            if(op.kind()==Kind.COLLECTION)authorizations.planCollection(new com.surprising.wallet.common.approval.AutomaticParentPlan(op.tenantId(),source.getMpcKeyId(),"NEAR",profile.getNetwork(),payment.symbol(),op.reference(),payment.recipient(),payment.amount(),null));
            var status=selectedRpc.status();requireNetwork(profile.getNetwork(),status.path("chain_id").asText());
            var genesis=selectedRpc.genesisConfig();
            require(status.path("chain_id").asText().equals(genesis.path("chain_id").asText()),"NEAR genesis configuration belongs to another network");
            long validity=genesis.path("transaction_validity_period").asLong();require(validity>0,"NEAR reference validity is unavailable");
            String contract=null;int decimals=24;
            if(!"NEAR".equals(payment.symbol())) {
                if(original!=null){contract=original.unsigned().contract();decimals=original.tokenDecimals();}
                else {var token=tokens.findDefinition("NEAR",profile.getNetwork(),payment.symbol()).orElseThrow();contract=token.getContractAddress();decimals=token.getDecimals();}
                ensureRegistration(op,source,payment,contract,decimals,profile.getNetwork(),status,validity,selectedRpc);
            }
            String publicKey=submissions.publicKey(source);
            var access=selectedRpc.accessKey(source.getAddress(),Base58.encode(HexFormat.of().parseHex(publicKey)));
            require("FullAccess".equals(access.path("permission").asText()),"NEAR joint access key is not FullAccess");
            String blockHash=access.path("block_hash").asText();long height=access.path("block_height").asLong(-1);
            var header=selectedRpc.block(blockHash).path("header");require(blockHash.equals(header.path("hash").asText()) && height==header.path("height").asLong(-2),"NEAR selected reference block identity differs");
            var unsigned=new MpcNearTransactionCodec.Unsigned(profile.getNetwork(),status.path("genesis_hash").asText(),source.getAddress(),Math.addExact(access.path("nonce").asLong(),1),payment.recipient(),payment.amount().movePointRight(decimals).toBigIntegerExact(),contract,contract==null?0:TOKEN_GAS,contract==null?null:op.reference(),height,Math.addExact(height,validity),blockHash,publicKey);
            draft=new NearSigningPayload(payment.symbol(),payment.amount(),payment.customerFee(),decimals,unsigned);
        }
        var request=submissions.reserve(op,source,draft);submissions.sign(op,request.id());
        return broadcast(op,submissions.commit(op,request.id()),submissions.payload(request));
    }
    public boolean recover(NearOperation op) {
        var request=submissions.intent(op);
        if(request.isEmpty()) {
            if(registrations.intent(op).isEmpty())return false;
            String hash=transfer(op,registrations.source(op));submissions.restoreSent(op,hash);return true;
        }
        submissions.sign(op,request.get().id());var signed=submissions.commit(op,request.get().id());
        String hash=broadcast(op,signed,submissions.payload(request.get()));submissions.restoreSent(op,hash);return true;
    }
    public int releaseUnsigned(NearOperation op){return submissions.releaseUnsigned(op);}
    private String broadcast(NearOperation op,MpcNearTransactionCodec.Signed signed,NearSigningPayload p) {
        var selectedRpc=rpc.forNetwork(p.unsigned().network());requireIdentity(p,selectedRpc);boolean found;
        try{var known=selectedRpc.knownTransaction(signed.txHash(),p.unsigned().sender());found=signed.txHash().equals(known.path("transaction").path("hash").asText());require(found,"NEAR lookup returned another transaction");}
        catch(IllegalStateException error){if(!NearRpcClient.isUnknownTransactionError(error.getMessage()))throw error;found=false;}
        if(!found) {
            require(selectedRpc.latestFinalBlockHeight()<=p.unsigned().validUntilHeight(),"NEAR reference expired; original payment remains held for reconciliation");
            var answer=selectedRpc.sendTransaction(signed.signedTransactionBase64());
            require(signed.txHash().equals(answer.path("transaction").path("hash").asText()),"NEAR node returned another selected transaction hash");
        }
        submissions.observed(op,signed.txHash());return signed.txHash();
    }
    public boolean confirmWithdrawal(UUID tenant,String reference){return confirm(new NearOperation(tenant,reference,Kind.WITHDRAWAL),orders.findTxHash("NEAR",reference,tenant).orElseThrow());}
    public boolean confirmCollection(UUID tenant,String reference){return confirm(new NearOperation(tenant,reference,Kind.COLLECTION),collections.findTxHash(tenant,"NEAR",reference).orElseThrow());}
    private boolean confirm(NearOperation op,String hash) {
        var request=submissions.intent(op).orElseThrow();var p=submissions.payload(request);var selectedRpc=rpc.forNetwork(p.unsigned().network());requireIdentity(p,selectedRpc);
        var proof=submissions.proof(op).orElseGet(()->{
            var signed=submissions.commit(op,request.id());var execution=selectedRpc.finalTransaction(hash,p.unsigned().sender());
            var verified=NearExecutionReceipt.verify(execution,p.unsigned(),signed);var headers=new HashMap<String,JsonNode>();
            for(String block:verified.blockHashes())headers.put(block,selectedRpc.block(block).path("header"));
            return new NearExecutionReceipt.Proof(execution,headers);
        });
        return submissions.settle(op,proof,selectedRpc.latestFinalBlockHeight(),profiles.findByChainAndNetwork("NEAR",p.unsigned().network()).orElseThrow().getWithdrawConfirmations());
    }
    private void requireIdentity(NearSigningPayload p,NearRpcClient selectedRpc) {
        var status=selectedRpc.status();requireNetwork(p.unsigned().network(),status.path("chain_id").asText());
        require(p.unsigned().genesisHash().equals(status.path("genesis_hash").asText()),"NEAR RPC genesis differs from fixed intent");
    }
    private void ensureRegistration(NearOperation op,ChainAddressRecord source,NearPayment payment,
            String contract,int decimals,String network,JsonNode status,long validity,NearRpcClient selectedRpc) {
        NearRegistrationPayload draft=null;
        if(registrations.intent(op).isEmpty()) {
            var storage=selectedRpc.viewFunctionJson(contract,"storage_balance_of",jsonArgs("account_id",payment.recipient()));
            if(!storage.isNull()) {require(storage.isObject() && positive(storage.path("total")).signum()>0,"NEP-145 storage balance is malformed");return;}
            var bounds=selectedRpc.viewFunctionJson(contract,"storage_balance_bounds",jsonArgs(null,null));BigInteger deposit=positive(bounds.path("min"));
            require(bounds.path("max").isNull() || positive(bounds.path("max")).compareTo(deposit)>=0,"NEP-145 storage bounds are inconsistent");
            var payer=registrations.gasSender(op.tenantId(),network);String publicKey=registrations.publicKey(payer);
            var access=selectedRpc.accessKey(payer.getAddress(),Base58.encode(HexFormat.of().parseHex(publicKey)));
            require("FullAccess".equals(access.path("permission").asText()),"NEP-145 Gas joint access key is not FullAccess");
            String block=access.path("block_hash").asText();long height=access.path("block_height").asLong(-1);
            var header=selectedRpc.block(block).path("header");require(block.equals(header.path("hash").asText()) && height==header.path("height").asLong(-2),"NEP-145 selected reference differs");
            BigInteger price=selectedRpc.gasPrice(block);require(price.signum()>0,"NEP-145 Gas quote is invalid");
            BigInteger fee=price.multiply(BigInteger.valueOf(100_000_000_000_000L));
            var unsigned=new MpcNearTransactionCodec.Registration(network,status.path("genesis_hash").asText(),payer.getAddress(),Math.addExact(access.path("nonce").asLong(),1),
                    payment.recipient(),contract,deposit,TOKEN_GAS,height,Math.addExact(height,validity),block,publicKey);
            draft=new NearRegistrationPayload(op,payment,source.getId(),source.getAddress(),decimals,fee,unsigned);
        }
        registrations.reserve(op,source,draft);registrations.sign(op);var selected=registrations.commit(op);var p=registrations.payload(op);
        var actual=selectedRpc.status();requireNetwork(p.unsigned().network(),actual.path("chain_id").asText());
        require(p.unsigned().genesisHash().equals(actual.path("genesis_hash").asText()),"NEP-145 RPC genesis differs from original intent");
        boolean known;
        try {var answer=selectedRpc.knownTransaction(selected.txHash(),p.unsigned().sender());known=selected.txHash().equals(answer.path("transaction").path("hash").asText());require(known,"NEP-145 lookup returned another transaction");}
        catch(IllegalStateException error){if(!NearRpcClient.isUnknownTransactionError(error.getMessage()))throw error;known=false;}
        if(!known) {
            require(selectedRpc.latestFinalBlockHeight()<=p.unsigned().validUntilHeight(),"NEP-145 original reference expired; retain original registration and parent");
            var sent=selectedRpc.sendTransaction(selected.signedTransactionBase64());require(selected.txHash().equals(sent.path("transaction").path("hash").asText()),"NEP-145 node returned another hash");
        }
        var proof=registrations.proof(op).orElseGet(()->{
            var execution=selectedRpc.finalTransaction(selected.txHash(),p.unsigned().sender());var receipts=new HashMap<String,JsonNode>();var headers=new HashMap<String,JsonNode>();
            var root=execution.path("transaction_outcome");String block=root.path("block_hash").asText();headers.put(block,selectedRpc.block(block).path("header"));
            for(var item:execution.path("receipts_outcome")) {String id=item.path("id").asText();receipts.put(id,selectedRpc.receipt(id));block=item.path("block_hash").asText();headers.put(block,selectedRpc.block(block).path("header"));}
            return new NearStorageReceipt.Proof(execution,headers,receipts);
        });
        var profile=profiles.findByChainAndNetwork("NEAR",p.unsigned().network()).orElseThrow();
        require(registrations.settle(op,proof,selectedRpc.latestFinalBlockHeight(),profile.getWithdrawConfirmations()),"NEP-145 registration did not finish with settled tenant Gas");
    }
    private static byte[] jsonArgs(String key,String value) {
        var json=new tools.jackson.databind.ObjectMapper();var args=json.createObjectNode();if(key!=null)args.put(key,value);return json.writeValueAsBytes(args);
    }
    private static BigInteger positive(JsonNode value) {
        String text=value.asText();require(text.length()<=39 && text.matches("[1-9][0-9]*"),"NEP-145 amount must be a positive u128");
        var amount=new BigInteger(text);require(amount.bitLength()<=128,"NEP-145 amount exceeds u128");return amount;
    }
    private static void requireNetwork(String network,String actual) {
        require(actual!=null && !actual.isBlank() && (Set.of("localnet","regtest").contains(network)?!Set.of("mainnet","testnet","betanet").contains(actual):network.equals(actual)),"NEAR RPC network differs from configured intent");
    }
    public static BigInteger toYocto(BigDecimal amount){return toAtomic(amount,24);}
    public static BigDecimal fromYocto(BigInteger amount){return new BigDecimal(Objects.requireNonNull(amount)).movePointLeft(24).stripTrailingZeros();}
    public static BigInteger toAtomic(BigDecimal amount,int decimals){return amount.movePointRight(decimals).toBigIntegerExact();}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
