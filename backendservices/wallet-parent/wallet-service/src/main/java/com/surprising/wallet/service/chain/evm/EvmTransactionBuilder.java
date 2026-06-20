package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.TokenDefinition;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Deterministic transaction payload builder for EVM transfers.
 */
@Component
public class EvmTransactionBuilder {
    public String buildNativePayload() {
        return "0x";
    }

    public String buildErc20TransferPayload(String toAddress, BigDecimal amount, TokenDefinition tokenDefinition) {
        if (toAddress == null || amount == null || tokenDefinition == null) {
            throw new IllegalArgumentException("invalid erc20 payload arguments");
        }
        int decimals = tokenDefinition.getDecimals() == null ? 18 : tokenDefinition.getDecimals();
        BigInteger rawAmount = amount.movePointRight(decimals).toBigIntegerExact();
        Function function = new Function("transfer",
                List.of(new Address(toAddress), new Uint256(rawAmount)),
                List.of());
        return FunctionEncoder.encode(function);
    }

    public String buildApprovalPayload(String spender, BigDecimal amount, TokenDefinition tokenDefinition) {
        if (spender == null || amount == null || tokenDefinition == null) {
            throw new IllegalArgumentException("invalid approval payload arguments");
        }
        int decimals = tokenDefinition.getDecimals() == null ? 18 : tokenDefinition.getDecimals();
        BigInteger rawAmount = amount.movePointRight(decimals).toBigIntegerExact();
        Function function = new Function("approve",
                List.of(new Address(spender), new Uint256(rawAmount)),
                List.of());
        return FunctionEncoder.encode(function);
    }
}
