package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.ChainCollectionRecord;
import com.surprising.wallet.chain.tron.TronClientFactory;
import com.surprising.wallet.chain.tron.TronTransactionService;
import com.surprising.wallet.chain.tron.TronTrc20Service;
import com.surprising.wallet.chain.tron.TronTridentClient;
import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import com.surprising.wallet.common.signing.TronSigningPayload;
import com.google.protobuf.ByteString;
import java.util.HexFormat;
import org.tron.trident.proto.Chain;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.CollectionRecordRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tron.trident.core.NodeType;
import org.tron.trident.proto.Response;

/**
 * TRON 账户链提现、归集和确认服务。
 */
@Service
@RequiredArgsConstructor
class TronAccountChainService {
    private static final BigDecimal TRX_SUN = new BigDecimal("1000000");

    private final CollectionRecordRepository collectionRecordRepository;

    private final TronSubmissionService submissions;
    private final AccountChainAssetService assets;
    private final TronClientFactory tronClientFactory;
    private final TronTransactionService tronTransactionService;
    private final TronTrc20Service tronTrc20Service;

    String broadcast(
            AccountChainProfile profile,
            WithdrawalOrderRecord order,
            ChainAddressRecord from) throws Exception {
        try (TronTridentClient client = tronClientFactory.create()) {
            return broadcastTransfer(client,profile,from,new TronSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),TronSubmissionService.Kind.WITHDRAWAL),
                    order.getAssetSymbol(),order.getToAddress(),order.getAmount(),order.getFee());
        }
    }

    void processCollection(
            AccountChainProfile profile,
            ChainCollectionRecord record,
            ChainAddressRecord from) throws Exception {
        if (collectionRecordRepository.claimSigningDurable(record.getTenantId(), record.getChain(), record.getCollectionNo()) != 1) {
            return;
        }
        try (TronTridentClient client = tronClientFactory.create()) {
            var operation=new TronSubmissionService.Operation(record.getTenantId(),record.getCollectionNo(),TronSubmissionService.Kind.COLLECTION);
            String txHash=broadcastTransfer(client,profile,from,operation,record.getAssetSymbol(),record.getToAddress(),record.getAmount(),BigDecimal.ZERO);
            submissions.restoreSent(operation,txHash);
        } catch (Exception error) {
            submissions.unknown(new TronSubmissionService.Operation(record.getTenantId(),record.getCollectionNo(),TronSubmissionService.Kind.COLLECTION),error.getClass().getSimpleName());
            throw error;
        }
    }

    /** No private-key construction: only a committed intent and selected MPC signature can reach RPC. */
    private String broadcastTransfer(TronTridentClient client,AccountChainProfile profile,ChainAddressRecord source,
                                     TronSubmissionService.Operation operation,String symbol,String recipient,BigDecimal amount,BigDecimal customerFee) throws Exception {
        var existing=submissions.intent(operation);
        TronSigningPayload draft=null;
        if(existing.isEmpty()) {
            boolean nativeAsset=assets.isNative(profile,symbol);
            TokenDefinition token=nativeAsset ? null:assets.requireToken(profile,symbol);
            int decimals=nativeAsset ? 6:token.getDecimals();
            long fee=nativeAsset ? 0:feeLimitSun(profile);
            Chain.Transaction unsigned=nativeAsset
                    ? tronTransactionService.prepareTrxTransfer(client,source.getAddress(),recipient,amount.multiply(TRX_SUN).longValueExact())
                    : tronTrc20Service.prepareTransfer(client,source.getAddress(),token.getContractAddress(),recipient,amount,decimals,fee);
            unsigned=unsigned.toBuilder().setRawData(unsigned.getRawData().toBuilder()
                    .setData(ByteString.copyFrom(TronSubmissionService.memo(operation)))).build();
            draft=new TronSigningPayload(symbol,amount,customerFee,decimals,MpcTronTransactionCodec.prepare(unsigned,source.getAddress(),recipient,
                    amount.movePointRight(decimals).toBigIntegerExact(),nativeAsset ? null:token.getContractAddress(),fee));
        }
        var request=submissions.reserve(operation,source,draft);
        submissions.sign(operation,request.id());
        var signed=submissions.commit(operation,request.id());
        return broadcastCommitted(client,operation,signed,submissions.payload(request));
    }

    boolean recover(TronSubmissionService.Operation operation) throws Exception {
        var intent=submissions.intent(operation);
        if(intent.isEmpty()) return false;
        submissions.sign(operation,intent.get().id());
        var signed=submissions.commit(operation,intent.get().id());
        try(var client=tronClientFactory.create()) {
            String hash=broadcastCommitted(client,operation,signed,submissions.payload(intent.get()));
            submissions.restoreSent(operation,hash);
            return true;
        }
    }

    int releaseUnsigned(TronSubmissionService.Operation operation) { return submissions.releaseUnsigned(operation); }

    private String broadcastCommitted(TronTridentClient client,TronSubmissionService.Operation operation,
                                       MpcTronTransactionCodec.Signed signed,TronSigningPayload payload) throws Exception {
        boolean known=false;
        try {
            var observed=client.getTransactionById(signed.txId(),NodeType.FULL_NODE);
            known=observed!=null && observed.hasRawData() && TronTransactionService.txId(observed).equals(signed.txId());
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw interrupted; }
        catch(Exception notObserved) { /* Same-byte broadcast remains safe after an uncertain lookup. */ }
        if(!known) {
            if(MpcTronTransactionCodec.restore(payload.unsigned()).getRawData().getExpiration()<=System.currentTimeMillis())
                throw new IllegalStateException("TRON accepted transaction expired; chain reconciliation is required");
            var transaction=Chain.Transaction.parseFrom(HexFormat.of().parseHex(signed.transactionHex()));
            String hash=client.broadcast(transaction);
            if(!signed.txId().equals(hash)) throw new IllegalStateException("TRON broadcast response differs from committed hash");
        }
        submissions.observed(operation,signed.txId());
        return signed.txId();
    }

    void confirmWithdrawal(
            AccountChainProfile profile,
            WithdrawalOrderRecord order) throws Exception {
        var receipt=confirmedTransaction(profile,order.getTxHash());
        if(receipt!=null) submissions.settle(new TronSubmissionService.Operation(order.getTenantId(),order.getOrderNo(),TronSubmissionService.Kind.WITHDRAWAL),receipt);
    }

    void confirmCollection(AccountChainProfile profile,ChainCollectionRecord record) throws Exception {
        var receipt=confirmedTransaction(profile,record.getTxHash());
        if(receipt!=null) submissions.settle(new TronSubmissionService.Operation(record.getTenantId(),record.getCollectionNo(),TronSubmissionService.Kind.COLLECTION),receipt);
    }

    private Response.TransactionInfo confirmedTransaction(
            AccountChainProfile profile, String txHash) throws Exception {
        if (txHash == null || txHash.isBlank()) {
            return null;
        }
        try (TronTridentClient client = tronClientFactory.create()) {
            Response.TransactionInfo txInfo =
                    client.getTransactionInfo(txHash, NodeType.SOLIDITY_NODE);
            if (txInfo == null || txInfo.getBlockNumber() <= 0) {
                return null;
            }
            long best = client.getNowBlock()
                    .getBlockHeader()
                    .getRawData()
                    .getNumber();
            long confirmations = Math.max(
                    0, best - txInfo.getBlockNumber() + 1);
            return confirmations >= Math.max(
                    1, profile.getWithdrawConfirmations())
                    ? txInfo
                    : null;
        }
    }

    private long feeLimitSun(AccountChainProfile profile) {
        Long configured = profile.getDefaultFee();
        return configured == null || configured <= 0
                ? 30_000_000L
                : Math.max(10_000_000L, configured);
    }
}
