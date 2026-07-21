package com.surprising.wallet.service.chain.doge;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.chain.utxo.BitcoinLikeChainRuntime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class DogeChainAdapter implements BlockchainAdapter {
    private static final long DEFAULT_FEE_RATE_KOINU_PER_VBYTE = 1_000L;

    private final BitcoinLikeChainRuntime runtime;

    public DogeChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ChainType chainType() {
        return ChainType.DOGE;
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
        return "dogecoin";
    }

    @Override
    public String describe() {
        return "Dogecoin UTXO adapter with legacy P2SH multisig address derivation and RPC-backed scanning.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null
                ? DEFAULT_FEE_RATE_KOINU_PER_VBYTE
                : request.feeRateSatPerVByte();
        long feeKoinu = P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate);
        BigDecimal fee = BigDecimal.valueOf(feeKoinu).movePointLeft(8).setScale(8, RoundingMode.DOWN);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeKoinu, 0L, null, true, "doge p2sh fee estimate");
    }

    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.DOGE, userId, biz);
    }

    @Override
    public Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        return runtime.generateDepositAddressAtIndex(ChainType.DOGE, userId, biz, childIndex);
    }

    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.DOGE, address);
    }

    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.DOGE);
    }

    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.DOGE);
    }

    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.DOGE);
    }

    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.DOGE, height);
    }

    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.DOGE);
    }

    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.DOGE);
    }

    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.DOGE, transaction);
    }
}
