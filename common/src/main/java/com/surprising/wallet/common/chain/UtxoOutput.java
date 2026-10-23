package com.surprising.wallet.common.chain;

import java.math.BigDecimal;
import java.util.Objects;

/** 链上输出事实；交易哈希和输出序号始终独立传递。 */
public record UtxoOutput(String txHash, int vout, String address, BigDecimal amount,
                         long blockHeight, String blockHash, int confirmations) {
    public UtxoOutput {
        Objects.requireNonNull(txHash, "txHash");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(blockHash, "blockHash");
        if (vout < 0 || blockHeight < 0 || confirmations < 0 || amount.signum() < 0)
            throw new IllegalArgumentException("invalid observed UTXO");
    }

    public UtxoOutput withConfirmations(int value) {
        return new UtxoOutput(txHash, vout, address, amount, blockHeight, blockHash, value);
    }
}
