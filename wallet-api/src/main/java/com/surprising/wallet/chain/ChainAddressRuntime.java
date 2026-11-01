package com.surprising.wallet.chain;

import com.surprising.wallet.chain.monero.MoneroAddressValidator;
import com.surprising.wallet.chain.tron.TronAddressCodec;
import com.surprising.wallet.chain.near.MpcNearTransactionCodec;
import com.surprising.wallet.chain.polkadot.MpcPolkadotTransactionCodec;
import com.surprising.wallet.common.chain.ChainType;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.Bech32;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Public address validation only; CustodyAddressService owns tenant MPC address publication. */
@Service
public class ChainAddressRuntime {
    private static final String EVM_ADDRESS_REGEX = "^0x[a-fA-F0-9]{40}$";
    private static final String HEX_32_BYTE_ADDRESS_REGEX = "^0x[0-9a-fA-F]{64}$";
    public boolean checkAddress(ChainType chainType, String address) {
        if (!StringUtils.hasText(address)) {
            return false;
        }
        String value = address.trim();
        return switch (chainType) {
            case ETH, BNB, POLYGON, ARBITRUM, OPTIMISM, BASE, AVAX_C, HYPEREVM,
                    LINEA, SCROLL, UNICHAIN,
                    X_LAYER, ROBINHOOD_CHAIN, HYPERCORE ->
                    value.matches(EVM_ADDRESS_REGEX);
            case TRON -> TronAddressCodec.isValidBase58(value);
            case XRP -> isValidXrpAddress(value);
            case SOLANA -> isValidSolanaAddress(value);
            case TON -> isValidTonAddress(value);
            case APTOS, SUI -> value.matches(HEX_32_BYTE_ADDRESS_REGEX);
            case ADA -> isValidCardanoAddress(value);
            case DOT -> isValidPolkadotAddress(value);
            case NEAR -> isValidNearAccount(value);
            case XMR -> MoneroAddressValidator.isValid(value);
            default -> throw new IllegalStateException(
                    "address validation is not implemented by generic runtime for " + chainType);
        };
    }
    private boolean isValidXrpAddress(String address) {
        try {
            return org.xrpl.xrpl4j.codec.addresses.AddressCodec.getInstance()
                    .isValidClassicAddress(org.xrpl.xrpl4j.model.transactions.Address.of(address));
        } catch (RuntimeException e) {
            return false;
        }
    }
    private boolean isValidSolanaAddress(String address) {
        try {
            return Base58.decode(address).length == 32;
        } catch (RuntimeException e) {
            return false;
        }
    }
    private boolean isValidTonAddress(String address) {
        return org.ton.ton4j.address.Address.isValid(address);
    }

    private static boolean isValidCardanoAddress(String address) {
        String value = address == null ? "" : address.trim();
        if (!value.startsWith("addr1") && !value.startsWith("addr_test1")) {
            return false;
        }
        boolean mainnet = value.startsWith("addr1");
        String hrp = mainnet ? "addr" : "addr_test";
        byte[] payload;
        try {
            payload = Bech32.decodeBytes(value, hrp, Bech32.Encoding.BECH32);
        } catch (RuntimeException e) {
            return false;
        }
        if (payload.length != 1 + 28) {
            return false;
        }
        int header = payload[0] & 0xff;
        return (header & 0xf0) == 0x60
                && (header & 0x0f) == (mainnet ? 1 : 0);
    }
    private static boolean isValidPolkadotAddress(String value) {
        try { return MpcPolkadotTransactionCodec.account(value).length == 32; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static boolean isValidNearAccount(String value) {
        try { MpcNearTransactionCodec.account(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
