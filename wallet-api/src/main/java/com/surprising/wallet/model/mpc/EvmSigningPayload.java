package com.surprising.wallet.model.mpc;

import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.transaction.type.Transaction1559;

/** Recoverable EVM envelope plus immutable business units; the signature covers unsignedHex. */
public record EvmSigningPayload(long chainId, String unsignedHex, String symbol, String contract,
                                BigDecimal amount, int assetDecimals, int nativeDecimals) {
    public EvmSigningPayload {
        if (chainId <= 0 || unsignedHex == null || unsignedHex.isEmpty() || unsignedHex.length() % 2 != 0
                || !unsignedHex.matches("[0-9a-f]+") || symbol == null || symbol.isBlank()
                || amount == null || amount.signum() <= 0 || assetDecimals < 0 || assetDecimals > 255
                || nativeDecimals < 0 || nativeDecimals > 255
                || (contract != null && !contract.matches("0x[0-9a-fA-F]{40}"))) {
            throw new IllegalArgumentException("invalid immutable EVM signing payload");
        }
    }

    public static EvmSigningPayload of(RawTransaction raw, long chainId, String symbol, String contract,
                                        BigDecimal amount, int assetDecimals, int nativeDecimals) {
        raw.getNonce().longValueExact();
        return new EvmSigningPayload(chainId, HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(raw, chainId)),
                symbol, contract, amount, assetDecimals, nativeDecimals);
    }

    public RawTransaction raw() { return MpcEvmTransactionCodec.restore(HexFormat.of().parseHex(unsignedHex), chainId); }
    public BigInteger maxFeePerGas() {
        var raw = raw();
        return raw.getTransaction() instanceof Transaction1559 dynamic ? dynamic.getMaxFeePerGas() : raw.getGasPrice();
    }
}
