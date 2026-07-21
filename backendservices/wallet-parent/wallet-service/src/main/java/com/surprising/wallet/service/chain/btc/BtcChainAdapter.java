package com.surprising.wallet.service.chain.btc;

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
public class BtcChainAdapter implements BlockchainAdapter {
    private final BitcoinLikeChainRuntime runtime;

    public BtcChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ChainType chainType() {
        return ChainType.BTC;
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
        return "bitcoin";
    }

    @Override
    public String describe() {
        return "Isolated BTC UTXO adapter with SegWit fee estimation and multisig-safe planning.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null ? 10L : request.feeRateSatPerVByte();
        long amountSat = request.amount().movePointRight(8).setScale(0, RoundingMode.DOWN).longValueExact();
        long feeSat = P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate);
        long feeBtcSat = feeSat;
        BigDecimal fee = BigDecimal.valueOf(feeBtcSat).movePointLeft(8);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeBtcSat, 0L, null, true, "btc utxo fee estimate");
    }

    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.BTC, userId, biz);
    }

    @Override
    public Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        return runtime.generateDepositAddressAtIndex(ChainType.BTC, userId, biz, childIndex);
    }

    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.BTC, address);
    }

    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.BTC);
    }

    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.BTC);
    }

    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.BTC);
    }

    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.BTC, height);
    }

    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.BTC);
    }

    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.BTC);
    }

    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.BTC, transaction);
    }
}
