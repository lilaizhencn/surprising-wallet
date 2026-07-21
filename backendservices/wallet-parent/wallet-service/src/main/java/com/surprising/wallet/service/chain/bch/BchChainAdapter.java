package com.surprising.wallet.service.chain.bch;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.chain.utxo.BitcoinLikeChainRuntime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class BchChainAdapter implements BlockchainAdapter {
    private final BitcoinLikeChainRuntime runtime;

    public BchChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ChainType chainType() {
        return ChainType.BCH;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.EnumSet.of(
                Capability.NATIVE_QUOTE, Capability.ADDRESS_GENERATION,
                Capability.ADDRESS_VALIDATION, Capability.CONFIRMATION_POLICY,
                Capability.DUST_POLICY, Capability.BEST_HEIGHT,
                Capability.BLOCK_TRANSACTION_SCAN, Capability.CONFIRMATION_REFRESH,
                Capability.BALANCE_REFRESH, Capability.SIGNED_TRANSACTION_BROADCAST);
    }

    @Override
    public String family() {
        return "bitcoin-cash";
    }

    @Override
    public String describe() {
        return "Bitcoin Cash UTXO adapter with CashAddr normalization and RPC-backed scanning.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null ? 1L : request.feeRateSatPerVByte();
        long feeSat = Math.max(BitcoinCashFeePolicy.DUST_THRESHOLD_SAT,
                P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate));
        BigDecimal fee = BigDecimal.valueOf(feeSat).movePointLeft(8).setScale(8, RoundingMode.DOWN);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeSat, 0L, null, true, "bch p2sh fee estimate");
    }

    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.BCH, userId, biz);
    }

    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.BCH, address);
    }

    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.BCH);
    }

    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.BCH);
    }

    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.BCH);
    }

    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.BCH, height);
    }

    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.BCH);
    }

    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.BCH);
    }

    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.BCH, transaction);
    }
}
