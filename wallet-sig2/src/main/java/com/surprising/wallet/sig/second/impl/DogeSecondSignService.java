package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.common.signing.UtxoPartialSignature;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultisigTransactionBuilder;
import com.surprising.wallet.sig.second.ISignService;
import com.surprising.wallet.sig.second.Sig2KeyDerivation;
import org.springframework.stereotype.Component;

@Component
public class DogeSecondSignService implements ISignService {
    private final Sig2KeyDerivation keys;
    public DogeSecondSignService(Sig2KeyDerivation keys) { this.keys = keys; }
    @Override public String chain() { return "DOGE"; }

    @Override
    public String sign(UtxoSigningRequest request, UtxoPartialSignature partial) {
        if (!chain().equals(request.asset().chain())) throw new IllegalArgumentException("signing chain mismatch");
        request.validateForSigning();
        if (partial.scriptType() != UtxoPartialSignature.ScriptType.P2SH
                || partial.inputScripts().size() != request.inputs().size()) {
            throw new IllegalArgumentException("DOGE signing metadata mismatch");
        }
        var signingKeys = request.inputs().stream()
                .map(input -> keys.derive(input.key(), request.asset()).getEcKey()).toList();
        var builder = new LegacyMultisigTransactionBuilder(request.asset().network().parameters(chain()));
        return builder.buildSecondSign(partial.transactionHex(), signingKeys, partial.inputScripts());
    }
}
