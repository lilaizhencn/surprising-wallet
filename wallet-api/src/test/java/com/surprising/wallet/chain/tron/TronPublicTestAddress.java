package com.surprising.wallet.chain.tron;

import org.bitcoinj.crypto.ECKey;

/** Public-key conversion for ABI/address fixtures; no private-key import or signing support. */
final class TronPublicTestAddress {
    static String toBase58Address(ECKey key) { return org.tron.TronWalletApi.getAddress(key.getPubKey()); }
    static String toHexAddress(ECKey key) { return TronAddressCodec.base58ToHex(toBase58Address(key)); }
}
