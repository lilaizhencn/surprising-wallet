package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.internal.CryptoUtils;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Generates sorted legacy P2SH n-of-m multisig addresses.
 */
public final class LegacyMultiSignAddressGenerator {
    private static final HexFormat HEX = HexFormat.of();
    private final List<ECKey> keys = new ArrayList<>();
    private Script redeemScript;

    public void addECKey(ECKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        keys.add(key);
        redeemScript = null;
    }

    public String generateAddress(NetworkParameters params, int requiredSignatures) {
        if (params == null || requiredSignatures <= 0 || requiredSignatures > keys.size()) {
            throw new IllegalArgumentException("invalid multisig parameters");
        }
        redeemScript = ScriptBuilder.createRedeemScript(requiredSignatures, keys);
        return LegacyAddress.fromScriptHash(
                params, CryptoUtils.sha256hash160(redeemScript.program())).toBase58();
    }

    public Script getRedeemScript() {
        return redeemScript;
    }

    public String getRedeemScriptHex() {
        if (redeemScript == null) {
            throw new IllegalStateException("address has not been generated");
        }
        return HEX.formatHex(redeemScript.program());
    }
}
