package com.surprising.wallet.service.chain.tron;

import com.google.protobuf.ByteString;
import org.tron.trident.proto.Response;
import org.tron.trident.utils.Numeric;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stateless TRON log decoder used by the scanner runtime.
 * It deliberately does not reuse EVM scanner logic because TRON log addresses
 * use 21-byte contract addresses and 20-byte indexed address topics.
 */
@Component
public class TronScanner {
    public List<TronTokenTransferEvent> decodeTrc20Transfers(Response.TransactionInfo txInfo,
                                                             Map<String, TokenConfig> tokenByContractHex) {
        List<TronTokenTransferEvent> transfers = new ArrayList<>();
        for (int logIndex = 0; logIndex < txInfo.getLogCount(); logIndex++) {
            Response.TransactionInfo.Log log = txInfo.getLog(logIndex);
            if (log.getTopicsCount() < 3) {
                continue;
            }
            String contractHex = normalizeLogContractAddress(log);
            TokenConfig token = tokenByContractHex.get(contractHex);
            if (token == null) {
                continue;
            }
            List<String> topics = log.getTopicsList().stream()
                    .map(ByteString::toByteArray)
                    .map(Numeric::toHexStringNoPrefix)
                    .toList();
            if (!Trc20AbiCodec.TRANSFER_TOPIC.equals(topics.get(0).toLowerCase(Locale.ROOT))) {
                continue;
            }
            Trc20AbiCodec.TransferLog decoded = Trc20AbiCodec.decodeTransferLog(contractHex, topics,
                    Numeric.toHexStringNoPrefix(log.getData().toByteArray()), token.decimals());
            transfers.add(new TronTokenTransferEvent(token.symbol(), decoded.contractAddress(), decoded.fromAddress(),
                    decoded.toAddress(), decoded.amount(), decoded.rawAmount(), txInfo.getBlockNumber(), logIndex));
        }
        return transfers;
    }
    public record TokenConfig(String symbol, String contractHex, int decimals) {
        public TokenConfig {
            contractHex = TronAddressCodec.normalizeHexAddress(contractHex);
        }
    }

    public record TronTokenTransferEvent(String symbol, String contractAddress, String fromAddress, String toAddress,
                                         java.math.BigDecimal amount, java.math.BigInteger rawAmount,
                                         long blockHeight, long logIndex) {
    }
    private static String normalizeLogContractAddress(Response.TransactionInfo.Log log) {
        String rawHex = Numeric.toHexStringNoPrefix(log.getAddress().toByteArray()).toLowerCase(Locale.ROOT);
        if (rawHex.length() == 40) {
            return TronAddressCodec.MAINNET_PREFIX_HEX + rawHex;
        }
        return TronAddressCodec.normalizeHexAddress(rawHex);
    }
}
