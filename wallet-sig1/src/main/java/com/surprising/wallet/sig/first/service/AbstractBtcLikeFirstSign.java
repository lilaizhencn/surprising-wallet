package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.signing.UtxoPartialSignature;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessTransactionBuilder;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;

import java.util.ArrayList;
import java.util.Objects;

/** BTC and LTC share the P2WSH algorithm; fee and dust policies remain chain-specific. */
public abstract class AbstractBtcLikeFirstSign implements ISignService {
    private final PubKeyConfig publicKeys;
    private final WalletKeyMaterialProvider keys;

    protected AbstractBtcLikeFirstSign(PubKeyConfig publicKeys, WalletKeyMaterialProvider keys) {
        this.publicKeys = Objects.requireNonNull(publicKeys);
        this.keys = Objects.requireNonNull(keys);
    }

    @Override
    public UtxoPartialSignature sign(UtxoSigningRequest request) {
        if (!chain().equals(request.asset().chain())) throw new IllegalArgumentException("signing chain mismatch");
        request.validateForSigning();
        var asset = request.asset();
        var builder = new WitnessTransactionBuilder(asset.network().parameters(chain()));
        var scripts = new ArrayList<String>();
        var signingKeys = new ArrayList<ECKey>();
        for (var input : request.inputs()) {
            var key = input.key();
            String script = publicKeys.genWitnessScript(key, asset);
            scripts.add(script);
            signingKeys.add(keys.sig1Root().getChild(44).getChild(asset.coinType()).getChild(key.biz())
                    .getChild(Math.toIntExact(key.userId())).getChild(key.index()).getEcKey());
            builder.addInput(input.txId(), input.vout(), script, Coin.valueOf(asset.atomic(input.amount())));
        }
        long feeRate = request.feeRate() > 0 ? request.feeRate() : defaultFeeRate();
        long remaining = Math.subtractExact(request.inputAtomic(), request.paymentAtomic());
        long vBytes = WitnessTransactionBuilder.estimateVBytes(request.inputs().size(), request.payments().size());
        long fee = Math.multiplyExact(vBytes, feeRate);
        long change = Math.subtractExact(remaining, fee);
        long dust = dustThreshold();
        if (change >= dust) {
            long withChangeVBytes = WitnessTransactionBuilder.estimateVBytes(
                    request.inputs().size(), request.payments().size() + 1);
            long withChangeFee = Math.multiplyExact(withChangeVBytes, feeRate);
            long withChangeAmount = Math.subtractExact(remaining, withChangeFee);
            if (withChangeAmount >= dust) {
                vBytes = withChangeVBytes;
                fee = withChangeFee;
                change = withChangeAmount;
            } else {
                fee = remaining;
                change = 0;
            }
        } else if (change > 0) {
            fee = remaining;
            change = 0;
        }
        if (change < 0) throw new IllegalArgumentException("insufficient input for SegWit fee");
        for (var payment : request.payments()) {
            long amount = asset.atomic(payment.amount());
            if (amount < dust) throw new IllegalArgumentException("withdraw output dust: " + amount);
            builder.addOutput(payment.address(), Coin.valueOf(amount));
        }
        if (change > 0) {
            if (request.changeAddress() == null || request.changeAddress().isBlank()) {
                throw new IllegalArgumentException("change address is required");
            }
            builder.addOutput(request.changeAddress(), Coin.valueOf(change));
        }
        long estimatedWeight = P2wshFeeCalculator.estimateWeight(
                request.inputs().size(), request.payments().size() + (change > 0 ? 1 : 0), 2, 3);
        return new UtxoPartialSignature(builder.buildFirstSign(signingKeys), UtxoPartialSignature.ScriptType.P2WSH,
                scripts, fee, new UtxoPartialSignature.Estimates(0, vBytes, estimatedWeight));
    }

    protected long defaultFeeRate() { return 10L; }
    protected long dustThreshold() { return 546L; }
}
