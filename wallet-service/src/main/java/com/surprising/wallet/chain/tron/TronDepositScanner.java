package com.surprising.wallet.chain.tron;

import com.google.protobuf.InvalidProtocolBufferException;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.chain.model.TronTransactionRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.NodeType;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.tron.trident.proto.Response;
import org.tron.trident.utils.Numeric;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责扫描链上区块、交易或事件，并转换为钱包领域事件。
 */
@Service
@RequiredArgsConstructor
public class TronDepositScanner {
    /**
     * 定义 {@code SUN_PER_TRX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal SUN_PER_TRX = new BigDecimal("1000000");
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code tronScanner}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TronScanner tronScanner;

    /**
     * 扫描或观察 {@code scanAndCreditTrx} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCreditTrx(TronTridentClient client,
                                               long blockHeight,
                                               Set<String> platformAddresses,
                                               int requiredConfirmations) throws Exception {
        Response.BlockExtention block = client.getBlockByNumber(blockHeight);
        long bestHeight = client.getNowBlock().getBlockHeader().getRawData().getNumber();
        int confirmations = confirmations(bestHeight, blockHeight);
        List<DepositEvent> detected = new ArrayList<>();
        for (Response.TransactionExtention txExt : block.getTransactionsList()) {
            Chain.Transaction tx = txExt.getTransaction();
            String txId = txExt.getTxid().isEmpty()
                    ? ApiWrapper.toHex(ApiWrapper.calculateTransactionHash(tx))
                    : Numeric.toHexStringNoPrefix(txExt.getTxid().toByteArray()).toLowerCase(Locale.ROOT);
            for (Chain.Transaction.Contract contract : tx.getRawData().getContractList()) {
                if (contract.getType() != Chain.Transaction.Contract.ContractType.TransferContract) {
                    continue;
                }
                Contract.TransferContract transfer = unpackTransfer(contract);
                String from = TronAddressCodec.hexToBase58(Numeric.toHexStringNoPrefix(transfer.getOwnerAddress().toByteArray()));
                String to = TronAddressCodec.hexToBase58(Numeric.toHexStringNoPrefix(transfer.getToAddress().toByteArray()));
                if (!containsAddress(platformAddresses, to)) {
                    continue;
                }
                BigDecimal amount = new BigDecimal(transfer.getAmount()).divide(SUN_PER_TRX);
                DepositEvent event = new DepositEvent(ChainType.TRON, "TRX", txId, from, to, amount,
                        blockHeight, txId, confirmations, null, tx.toString());
                repository.recordTronTransaction(TronTransactionRecord.builder()
                        .chain(ChainType.TRON.name())
                        .txHash(txId)
                        .fromAddress(from)
                        .toAddress(to)
                        .assetSymbol("TRX")
                        .amount(amount)
                        .fee(transactionFee(client, txId))
                        .blockHeight(blockHeight)
                        .confirmations(confirmations)
                        .status(confirmations >= requiredConfirmations ? "CONFIRMED" : "CONFIRMING")
                        .rawPayload(tx.toString())
                        .build());
                repository.recordAndCreditDeposit(event, 0L, requiredConfirmations);
                detected.add(event);
            }
        }
        long safeHeight = Math.max(0L, blockHeight - requiredConfirmations + 1L);
        repository.updateScanHeight(ChainType.TRON.name(), "TRON_TRX", blockHeight, safeHeight);
        return detected;
    }

    /**
     * 扫描或观察 {@code scanAndCreditTrc20} 对应的链上状态，并转换为业务可用结果。
     */
    public List<DepositEvent> scanAndCreditTrc20(TronTridentClient client,
                                                 long blockHeight,
                                                 Map<String, TronScanner.TokenConfig> tokensByContractHex,
                                                 Set<String> platformAddresses,
                                                 int requiredConfirmations) throws Exception {
        Response.TransactionInfoList txInfos = client.getTransactionInfoByBlockNum(blockHeight, NodeType.FULL_NODE);
        long bestHeight = client.getNowBlock().getBlockHeader().getRawData().getNumber();
        int confirmations = confirmations(bestHeight, blockHeight);
        List<DepositEvent> detected = new ArrayList<>();
        for (Response.TransactionInfo txInfo : txInfos.getTransactionInfoList()) {
            String txId = Numeric.toHexStringNoPrefix(txInfo.getId().toByteArray()).toLowerCase(Locale.ROOT);
            for (TronScanner.TronTokenTransferEvent transfer : tronScanner.decodeTrc20Transfers(txInfo, tokensByContractHex)) {
                if (!containsAddress(platformAddresses, transfer.toAddress())) {
                    continue;
                }
                DepositEvent event = new DepositEvent(ChainType.TRON, transfer.symbol(), txId, transfer.fromAddress(),
                        transfer.toAddress(), transfer.amount(), transfer.blockHeight(), txId, confirmations,
                        transfer.contractAddress(), txInfo.toString());
                repository.recordTronTokenTransfer(event, transfer.logIndex(),
                        confirmations >= requiredConfirmations ? "CONFIRMED" : "CONFIRMING");
                repository.recordAndCreditDeposit(event, transfer.logIndex(), requiredConfirmations);
                detected.add(event);
            }
        }
        long safeHeight = Math.max(0L, blockHeight - requiredConfirmations + 1L);
        repository.updateScanHeight(ChainType.TRON.name(), "TRON_TRC20", blockHeight, safeHeight);
        return detected;
    }

    /**
     * 执行 {@code unpackTransfer} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static Contract.TransferContract unpackTransfer(Chain.Transaction.Contract contract)
            throws InvalidProtocolBufferException {
        return contract.getParameter().unpack(Contract.TransferContract.class);
    }
    /**
     * 校验 {@code containsAddress} 对应的输入或状态，失败时抛出明确异常。
     */
    private static boolean containsAddress(Set<String> addresses, String address) {
        return addresses.contains(address.toLowerCase(Locale.ROOT));
    }
    /**
     * 处理 {@code confirmations} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private static int confirmations(long bestHeight, long blockHeight) {
        return Math.toIntExact(Math.max(0, bestHeight - blockHeight + 1));
    }
    /**
     * 执行 {@code transactionFee} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static BigDecimal transactionFee(TronTridentClient client, String txId) {
        try {
            Response.TransactionInfo info = client.getTransactionInfo(txId, NodeType.FULL_NODE);
            if (info == null || info.getId().isEmpty()) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(info.getFee()).divide(SUN_PER_TRX);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }
}
