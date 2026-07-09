package com.surprising.wallet.sdk.bitcoinj.core;

import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.HexFormat;

public class SegwitMultiSignAddressGenerator {
    private static final HexFormat HEX = HexFormat.of();

    private List<ECKey> ecKeyList = new ArrayList<>();
    private Script witnessScript;
    private int minSignNum;

    public void addECKey(ECKey key) {
        if (key == null || ecKeyList.size() >= 16) {
            throw new IllegalArgumentException("max 16 non-null public keys");
        }
        if (!key.isCompressed()) {
            throw new IllegalArgumentException("native SegWit multisig requires compressed public keys");
        }
        ecKeyList.add(key);
        witnessScript = null;
        minSignNum = 0;
    }

    public boolean setECKey(int index, ECKey key) {
        if (index < 0 || index >= ecKeyList.size() || key == null || !key.isCompressed()) {
            return false;
        }
        ecKeyList.set(index, key);
        witnessScript = null;
        minSignNum = 0;
        return true;
    }

    public String generateAddress(NetworkParameters params, int minSignNum) {
        if (params == null) {
            throw new IllegalArgumentException("network must not be null");
        }
        int size = ecKeyList.size();
        if (size < 2) {
            throw new IllegalArgumentException("at least two public keys are required");
        }
        if (minSignNum < 1) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        if (minSignNum > size) {
            minSignNum = size;
        }
        witnessScript = ScriptBuilder.createMultiSigOutputScript(minSignNum, ecKeyList);
        this.minSignNum = minSignNum;
        return SegwitAddress.fromHash(params, Sha256Hash.hash(witnessScript.program())).toBech32();
    }

    public Script getWitnessScript() {
        return witnessScript;
    }

    public String getWitnessScriptStr() {
        return witnessScript == null ? null : HEX.formatHex(witnessScript.program());
    }

    public String getScriptStr() {
        return getWitnessScriptStr();
    }

    public byte[] getWitnessProgram() {
        return witnessScript == null ? null : Sha256Hash.hash(witnessScript.program());
    }

    public void setEcKeyList(List<ECKey> ecKeyList) {
        if (ecKeyList == null) {
            return;
        }
        List<ECKey> sanitized = new ArrayList<>(ecKeyList);
        Iterator<ECKey> iter = sanitized.iterator();
        while (iter.hasNext()) {
            ECKey key = iter.next();
            if (key == null) {
                iter.remove();
            } else if (!key.isCompressed()) {
                throw new IllegalArgumentException("native SegWit multisig requires compressed public keys");
            }
        }
        this.ecKeyList = sanitized;
        witnessScript = null;
        minSignNum = 0;
    }

    public List<ECKey> getEcKeyList() {
        return new ArrayList<>(ecKeyList);
    }

    public int getMinSignNum() {
        return minSignNum;
    }

    public int getMaxSignNum() {
        return ecKeyList.size();
    }
}
