package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.signing.UtxoPartialSignature;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class BchFirstSignService implements ISignService {
    private final PubKeyConfig publicKeys;
    private final WalletKeyMaterialProvider keys;

    public BchFirstSignService(PubKeyConfig publicKeys,
                               @Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keys) {
        this.publicKeys = publicKeys;
        this.keys = keys;
    }

    @Override public String chain() { return "BCH"; }

    @Override
    public UtxoPartialSignature sign(UtxoSigningRequest request) {
        if (!chain().equals(request.asset().chain())) throw new IllegalArgumentException("signing chain mismatch");
        request.validateForSigning();
        var asset = request.asset();
        var builder = new BitcoinCashMultisigTransactionBuilder(
                (BitcoinCashNetworkParameters) asset.network().parameters(chain()));
        var signingKeys = new ArrayList<ECKey>();
        var scripts = new ArrayList<String>();
        for (var input : request.inputs()) {
            var key = input.key();
            String script = publicKeys.genRedeemScript(key, asset);
            scripts.add(script);
            signingKeys.add(keys.sig1Root().getChild(44).getChild(asset.coinType()).getChild(key.biz())
                    .getChild(Math.toIntExact(key.userId())).getChild(key.index()).getEcKey());
            builder.addInput(input.txId(), input.vout(), script, Coin.valueOf(asset.atomic(input.amount())));
        }
        long feeRate = request.feeRate() > 0 ? request.feeRate() : BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE;
        long dust = request.dustThreshold() > 0 ? request.dustThreshold() : BitcoinCashFeePolicy.DUST_THRESHOLD_SAT;
        var plan = BitcoinCashFeePolicy.calculateSpendPlan(request.inputAtomic(), request.paymentAtomic(),
                request.inputs().size(), request.payments().size(), feeRate, dust);
        for (var payment : request.payments()) {
            long amount = asset.atomic(payment.amount());
            if (amount < dust) throw new IllegalArgumentException("BCH output dust");
            builder.addOutput(payment.address(), Coin.valueOf(amount));
        }
        if (plan.change() > 0) {
            if (request.changeAddress() == null || request.changeAddress().isBlank()) {
                throw new IllegalArgumentException("BCH change address is required");
            }
            builder.addOutput(request.changeAddress(), Coin.valueOf(plan.change()));
        }
        return new UtxoPartialSignature(builder.buildFirstSign(signingKeys), UtxoPartialSignature.ScriptType.BCH_P2SH,
                scripts, plan.fee(), new UtxoPartialSignature.Estimates(plan.estimatedBytes(), 0, 0));
    }
}
