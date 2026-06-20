package com.surprising.wallet.service.chain.evm;

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
 * Normalizes ERC20 transfer logs into deposit events.
 */
@Component
public class EvmLogScanner {
    public List<DepositEvent> scanTransfers(ChainType chainType, TokenDefinition tokenDefinition,
                                           long blockHeight, int confirmations, List<Log> logs) {
        ArrayList<DepositEvent> events = new ArrayList<>();
        if (logs == null) {
            return events;
        }
        for (Log log : logs) {
            if (log == null || log.getTopics() == null || log.getTopics().size() < 3) {
                continue;
            }
            String txId = log.getTransactionHash();
            String from = topicToAddress(log.getTopics().get(1));
            String to = topicToAddress(log.getTopics().get(2));
            BigDecimal amount = new BigDecimal(new BigInteger(stripHex(log.getData())));
            int decimals = tokenDefinition.getDecimals() == null ? 18 : tokenDefinition.getDecimals();
            events.add(new DepositEvent(chainType, tokenDefinition.getSymbol(), txId, from, to,
                    amount.movePointLeft(decimals), blockHeight, confirmations, tokenDefinition.getContractAddress(),
                    log.toString()));
        }
        return events;
    }

    private static String topicToAddress(String topic) {
        if (topic == null) {
            return null;
        }
        String hex = stripHex(topic);
        return "0x" + hex.substring(Math.max(0, hex.length() - 40));
    }

    private static String stripHex(String value) {
        return value == null ? "" : value.startsWith("0x") ? value.substring(2) : value;
    }
}
