package com.surprising.wallet.sig.first.service;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.signing.UtxoPartialSignature;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class DogeFirstSignService implements ISignService {
    private final PubKeyConfig publicKeys;
    private final WalletKeyMaterialProvider keys;

    public DogeFirstSignService(PubKeyConfig publicKeys,
                                @Qualifier("sig1KeyMaterial") WalletKeyMaterialProvider keys) {
        this.publicKeys = publicKeys;
        this.keys = keys;
    }

    @Override public String chain() { return "DOGE"; }

    @Override
    public UtxoPartialSignature sign(UtxoSigningRequest request) {
        if (!chain().equals(request.asset().chain())) throw new IllegalArgumentException("signing chain mismatch");
        request.validateForSigning();
        var asset = request.asset();
        var builder = new LegacyMultisigTransactionBuilder(asset.network().parameters(chain()));
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
        long feeRate = request.feeRate() > 0 ? request.feeRate() : DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE;
        long remaining = Math.subtractExact(request.inputAtomic(), request.paymentAtomic());
        long bytes = P2shMultisigFeeCalculator.estimateBytes(request.inputs().size(), request.payments().size(), 2, 3);
        long fee = DogecoinFeePolicy.feeForBytes(bytes, feeRate);
        long change = Math.subtractExact(remaining, fee);
        long dust = DogecoinFeePolicy.RECOMMENDED_DUST_THRESHOLD_KOINU;
        if (change >= dust) {
            long withChangeBytes = P2shMultisigFeeCalculator.estimateBytes(
                    request.inputs().size(), request.payments().size() + 1, 2, 3);
            long withChangeFee = DogecoinFeePolicy.feeForBytes(withChangeBytes, feeRate);
            long withChangeAmount = Math.subtractExact(remaining, withChangeFee);
            if (withChangeAmount >= dust) {
                bytes = withChangeBytes;
                fee = withChangeFee;
                change = withChangeAmount;
            } else {
                fee = remaining;
                change = 0;
            }
        } else if (change >= 0) {
            fee = remaining;
            change = 0;
        }
        if (change < 0) throw new IllegalArgumentException("insufficient DOGE input for network fee");
        for (var payment : request.payments()) {
            long amount = asset.atomic(payment.amount());
            if (amount < dust) throw new IllegalArgumentException("DOGE output below recommended dust");
            builder.addOutput(payment.address(), Coin.valueOf(amount));
        }
        if (change > 0) {
            if (request.changeAddress() == null || request.changeAddress().isBlank()) {
                throw new IllegalArgumentException("DOGE change address is required");
            }
            builder.addOutput(request.changeAddress(), Coin.valueOf(change));
        }
        return new UtxoPartialSignature(builder.buildFirstSign(signingKeys), UtxoPartialSignature.ScriptType.P2SH,
                scripts, fee, new UtxoPartialSignature.Estimates(bytes, 0, 0));
    }
}
