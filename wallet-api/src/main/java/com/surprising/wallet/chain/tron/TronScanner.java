package com.surprising.wallet.chain.tron;

import com.google.protobuf.ByteString;
import org.tron.trident.proto.Response;
import org.tron.trident.utils.Numeric;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 负责扫描链上区块、交易或事件，并转换为钱包领域事件。
 */
@Component
public class TronScanner {
    /**
     * 解析或转换 {@code decodeTrc20Transfers} 对应的数据，并校验其格式和边界。
     */
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
    /**
     * 转换或计算 {@code normalizeLogContractAddress} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeLogContractAddress(Response.TransactionInfo.Log log) {
        String rawHex = Numeric.toHexStringNoPrefix(log.getAddress().toByteArray()).toLowerCase(Locale.ROOT);
        if (rawHex.length() == 40) {
            return TronAddressCodec.MAINNET_PREFIX_HEX + rawHex;
        }
        return TronAddressCodec.normalizeHexAddress(rawHex);
    }
}
