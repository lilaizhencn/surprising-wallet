package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.common.signing.UtxoPartialSignature;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sig.second.ISignService;
import com.surprising.wallet.sig.second.Sig2KeyDerivation;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class BchSecondSignService implements ISignService {
    private final Sig2KeyDerivation keys;
    public BchSecondSignService(Sig2KeyDerivation keys) { this.keys = keys; }
    @Override public String chain() { return "BCH"; }

    @Override
    public String sign(UtxoSigningRequest request, UtxoPartialSignature partial) {
        if (!chain().equals(request.asset().chain())) throw new IllegalArgumentException("signing chain mismatch");
        request.validateForSigning();
        if (partial.scriptType() != UtxoPartialSignature.ScriptType.BCH_P2SH
                || partial.inputScripts().size() != request.inputs().size()) {
            throw new IllegalArgumentException("BCH signing metadata mismatch");
        }
        var builder = new BitcoinCashMultisigTransactionBuilder(
                (BitcoinCashNetworkParameters) request.asset().network().parameters(chain()));
        var signingKeys = new ArrayList<ECKey>();
        for (int index = 0; index < request.inputs().size(); index++) {
            var input = request.inputs().get(index);
            signingKeys.add(keys.derive(input.key(), request.asset()).getEcKey());
            builder.addInput(input.txId(), input.vout(), partial.inputScripts().get(index),
                    Coin.valueOf(request.asset().atomic(input.amount())));
        }
        return builder.buildSecondSign(partial.transactionHex(), signingKeys, partial.inputScripts());
    }
}
