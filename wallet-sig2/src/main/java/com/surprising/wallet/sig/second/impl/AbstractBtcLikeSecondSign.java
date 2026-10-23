package com.surprising.wallet.sig.second.impl;

import com.surprising.wallet.common.signing.UtxoPartialSignature;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.core.WitnessSigner;
import com.surprising.wallet.sig.second.ISignService;
import com.surprising.wallet.sig.second.Sig2KeyDerivation;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;

/** BTC/LTC witness signing uses the immutable input amounts and key paths. */
public abstract class AbstractBtcLikeSecondSign implements ISignService {
    private final Sig2KeyDerivation keys;
    private final WitnessSigner witnessSigner = new WitnessSigner();

    protected AbstractBtcLikeSecondSign(Sig2KeyDerivation keys) {
        this.keys = Objects.requireNonNull(keys);
    }

    @Override
    public String sign(UtxoSigningRequest request, UtxoPartialSignature partial) {
        if (!chain().equals(request.asset().chain())) throw new IllegalArgumentException("signing chain mismatch");
        request.validateForSigning();
        if (partial.scriptType() != UtxoPartialSignature.ScriptType.P2WSH) {
            throw new IllegalArgumentException("not P2WSH");
        }
        var tx = Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(partial.transactionHex())));
        if (tx.getInputs().size() != request.inputs().size()
                || partial.inputScripts().size() != request.inputs().size()) {
            throw new IllegalArgumentException("signing input count mismatch");
        }
        for (int index = 0; index < tx.getInputs().size(); index++) {
            var input = tx.getInput(index);
            var witness = input.getWitness();
            if (witness == null || witness.getPushCount() < 3) throw new IllegalArgumentException("bad witness");
            byte[] scriptBytes = witness.getPush(witness.getPushCount() - 1);
            if (!HexFormat.of().formatHex(scriptBytes).equalsIgnoreCase(partial.inputScripts().get(index))) {
                throw new IllegalArgumentException("witness script mismatch");
            }
            var requested = request.inputs().get(index);
            var script = new Script(scriptBytes);
            var value = Coin.valueOf(request.asset().atomic(requested.amount()));
            var key = keys.derive(requested.key(), request.asset()).getEcKey();
            var signature = tx.calculateWitnessSignature(index, key, script, value, Transaction.SigHash.ALL, false);
            tx.replaceInput(index, input.withWitness(witnessSigner.mergeMultisigWitness(tx, index,
                    witness, signature, key, script, value, script.getNumberOfSignaturesRequiredToSpend())));
        }
        return HexFormat.of().formatHex(tx.bitcoinSerialize());
    }
}
