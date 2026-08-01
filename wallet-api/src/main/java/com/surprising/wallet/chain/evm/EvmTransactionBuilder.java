package com.surprising.wallet.chain.evm;

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
 * 负责构建交易、脚本或请求对象，并执行必要的输入校验。
 */
@Component
public class EvmTransactionBuilder {
    /**
     * 构建或生成 {@code buildNativePayload} 对应的结果，并执行输入和状态校验。
     */
    public String buildNativePayload() {        return "0x";
    }
    /**
     * 构建或生成 {@code buildErc20TransferPayload} 对应的结果，并执行输入和状态校验。
     */
    public String buildErc20TransferPayload(String toAddress, BigDecimal amount, TokenDefinition tokenDefinition) {
        if (toAddress == null || amount == null || tokenDefinition == null) {
            throw new IllegalArgumentException("invalid erc20 payload arguments");
        }
        int decimals = requireTokenDecimals(tokenDefinition);
        BigInteger rawAmount = amount.movePointRight(decimals).toBigIntegerExact();
        Function function = new Function("transfer",
                List.of(new Address(toAddress), new Uint256(rawAmount)),
                List.of());
        return FunctionEncoder.encode(function);
    }
    /**
     * 构建或生成 {@code buildApprovalPayload} 对应的结果，并执行输入和状态校验。
     */
    public String buildApprovalPayload(String spender, BigDecimal amount, TokenDefinition tokenDefinition) {
        if (spender == null || amount == null || tokenDefinition == null) {
            throw new IllegalArgumentException("invalid approval payload arguments");
        }
        int decimals = requireTokenDecimals(tokenDefinition);
        BigInteger rawAmount = amount.movePointRight(decimals).toBigIntegerExact();
        Function function = new Function("approve",
                List.of(new Address(spender), new Uint256(rawAmount)),
                List.of());
        return FunctionEncoder.encode(function);
    }
    /**
     * 校验 {@code requireTokenDecimals} 对应的前置条件，不满足时抛出明确异常。
     */
    private int requireTokenDecimals(TokenDefinition tokenDefinition) {
        if (tokenDefinition.getDecimals() == null) {
            throw new IllegalStateException("missing token decimals in DB asset metadata for "
                    + tokenDefinition.getChain() + "/" + tokenDefinition.getSymbol());
        }
        return tokenDefinition.getDecimals();
    }
}
