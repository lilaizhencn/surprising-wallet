package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import org.web3j.protocol.core.methods.response.Log;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责扫描链上区块、交易或事件，并转换为钱包领域事件。
 */
@Component
public class EvmLogScanner {
    /**
     * 扫描或观察 {@code scanTransfers} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanTransfers(ChainType chainType, TokenDefinition tokenDefinition,
                                           long blockHeight, int confirmations, List<Log> logs) {
        ArrayList<DepositEvent> events = new ArrayList<>();
        if (logs == null) {
            return events;
        }
        int decimals = requireTokenDecimals(tokenDefinition);
        for (Log log : logs) {
            if (log == null || log.getTopics() == null || log.getTopics().size() < 3) {
                continue;
            }
            String txId = log.getTransactionHash();
            String from = topicToAddress(log.getTopics().get(1));
            String to = topicToAddress(log.getTopics().get(2));
            BigDecimal amount = new BigDecimal(new BigInteger(stripHex(log.getData()), 16));
            events.add(new DepositEvent(chainType, tokenDefinition.getSymbol(), txId, from, to,
                    amount.movePointLeft(decimals), blockHeight, log.getBlockHash(), confirmations,
                    tokenDefinition.getContractAddress(), log.toString()));
        }
        return events;
    }
    /**
     * 编码 {@code topicToAddress} 对应的数据，生成链上或接口所需的表示。
     */
    private static String topicToAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String hex = stripHex(topic);
        return "0x" + hex.substring(Math.max(0, hex.length() - 40));
    }
    /**
     * 执行 {@code stripHex} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String stripHex(String value) {
        return value == null ? "" : value.startsWith("0x") ? value.substring(2) : value;
    }
    /**
     * 校验 {@code requireTokenDecimals} 对应的前置条件，不满足时抛出明确异常。
     */
    private static int requireTokenDecimals(TokenDefinition tokenDefinition) {
        if (tokenDefinition == null || tokenDefinition.getDecimals() == null) {
            String chain = tokenDefinition == null ? null : tokenDefinition.getChain();
            String symbol = tokenDefinition == null ? null : tokenDefinition.getSymbol();
            throw new IllegalStateException("missing token decimals in DB asset metadata for "
                    + chain + "/" + symbol);
        }
        return tokenDefinition.getDecimals();
    }
}
