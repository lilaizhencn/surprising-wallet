package org.dogecoinj;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.libdohj.params.DogecoinMainNetParams;

import static org.bitcoinj.script.ScriptOpCodes.*;

@Slf4j
public class DogeSdk {

    public static String getNewAddress(ECKey ecKey) {
        NetworkParameters params = DogecoinMainNetParams.get();
        return ecKey.getPrivateKeyEncoded(params).toBase58();
    }

    public static Boolean checkAddress(String addressStr) {
        boolean valid = false;
        if (!Strings.isNullOrEmpty(addressStr)) {
            try {
                org.bitcoinj.core.Address address = org.bitcoinj.core.Address.fromString(DogecoinMainNetParams.get(), addressStr);
                if (address != null) {
                    valid = true;
                }
            } catch (AddressFormatException e) {
                log.error("{} is not valid", addressStr, e);
            }
        }
        return valid;
    }

    public static Script createSignleSignScript(byte[] publicKeyHash) {
        ScriptBuilder scriptBuilder = new ScriptBuilder();
        scriptBuilder.op(OP_DUP).op(OP_HASH160).data(publicKeyHash).op(OP_EQUALVERIFY).op(OP_CHECKSIG);
        return scriptBuilder.build();
    }
}
