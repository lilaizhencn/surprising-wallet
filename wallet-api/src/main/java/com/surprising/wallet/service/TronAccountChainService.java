package com.surprising.wallet.service;

import com.surprising.wallet.chain.tron.TronClientFactory;
import com.surprising.wallet.chain.tron.TronTransactionService;
import com.surprising.wallet.chain.tron.TronTrc20Service;
import com.surprising.wallet.chain.tron.TronTridentClient;
import com.surprising.wallet.chain.tron.TronTridentKeyFactory;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.ChainCollectionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.chain.model.TronTransactionRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Response;

import java.math.BigDecimal;

/**
 * TRON 账户链提现、归集和确认服务。
 */
@Service
@RequiredArgsConstructor
class TronAccountChainService {
    /**
     * 定义 {@code TRX_SUN} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal TRX_SUN = new BigDecimal("1000000");

    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code secp256k1KeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final AccountSecp256k1KeyService secp256k1KeyService;
    /**
     * 保存 {@code assets}，表示链、网络、资产或代币配置。
     */
    private final AccountChainAssetService assets;
    /**
     * 保存 {@code tronClientFactory}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TronClientFactory tronClientFactory;
    /**
     * 保存 {@code tronTransactionService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TronTransactionService tronTransactionService;
    /**
     * 保存 {@code tronTrc20Service}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final TronTrc20Service tronTrc20Service;

    /**
     * 发送或广播 {@code broadcast} 对应的链上请求，并返回节点处理结果。
     */
    String broadcast(
            AccountChainProfile profile,
            WithdrawalOrderRecord order,
            ChainAddressRecord from) throws Exception {
        try (TronTridentClient client = tronClientFactory.create()) {
            KeyPair keyPair = tronKey(profile, from);
            if (assets.isNative(profile, order.getAssetSymbol())) {
                long amountSun = order.getAmount()
                        .multiply(TRX_SUN)
                        .longValueExact();
                TronTransactionService.SignedTronTransaction signed =
                        tronTransactionService.signTrxTransfer(
                                client, keyPair, order.getToAddress(), amountSun);
                tronTransactionService.broadcast(client, signed);
                recordSent(
                        order.getChain(), signed.txId(), from.getAddress(),
                        order.getToAddress(), "TRX", null, order.getAmount());
                return signed.txId();
            }
            TokenDefinition token = assets.requireToken(
                    order.getChain(), order.getAssetSymbol());
            TronTransactionService.SignedTronTransaction signed =
                    tronTrc20Service.signTransfer(
                            client, keyPair, token.getContractAddress(),
                            order.getToAddress(), order.getAmount(),
                            token.getDecimals(), feeLimitSun(profile));
            tronTrc20Service.broadcast(client, signed);
            recordSent(
                    order.getChain(), signed.txId(), from.getAddress(),
                    order.getToAddress(), token.getSymbol(),
                    token.getContractAddress(), order.getAmount());
            return signed.txId();
        }
    }

    /**
     * 执行或处理 {@code processCollection} 对应的业务流程，并维护状态和异常边界。
     */
    void processCollection(
            AccountChainProfile profile,
            ChainCollectionRecord record,
            ChainAddressRecord from) throws Exception {
        if (repository.claimCollectionSigning(
                record.getTenantId(), record.getChain(),
                record.getCollectionNo(), null) != 1) {
            return;
        }
        try (TronTridentClient client = tronClientFactory.create()) {
            KeyPair keyPair = tronKey(profile, from);
            String txHash;
            if (assets.isNative(profile, record.getAssetSymbol())) {
                long amountSun = record.getAmount()
                        .multiply(TRX_SUN)
                        .longValueExact();
                TronTransactionService.SignedTronTransaction signed =
                        tronTransactionService.signTrxTransfer(
                                client, keyPair, record.getToAddress(), amountSun);
                tronTransactionService.broadcast(client, signed);
                txHash = signed.txId();
                recordSent(
                        record.getChain(), txHash, from.getAddress(),
                        record.getToAddress(), "TRX", null, record.getAmount());
            } else {
                TokenDefinition token = assets.requireToken(
                        record.getChain(), record.getAssetSymbol());
                TronTransactionService.SignedTronTransaction signed =
                        tronTrc20Service.signTransfer(
                                client, keyPair, token.getContractAddress(),
                                record.getToAddress(), record.getAmount(),
                                token.getDecimals(), feeLimitSun(profile));
                tronTrc20Service.broadcast(client, signed);
                txHash = signed.txId();
                recordSent(
                        record.getChain(), txHash, from.getAddress(),
                        record.getToAddress(), token.getSymbol(),
                        token.getContractAddress(), record.getAmount());
            }
            repository.updateCollectionStatus(
                    record.getTenantId(), record.getChain(),
                    record.getCollectionNo(), "SENT", txHash, null, null);
        } catch (Exception error) {
            repository.updateCollectionStatus(
                    record.getTenantId(), record.getChain(),
                    record.getCollectionNo(), "FAILED", null,
                    error.getMessage(), null);
            throw error;
        }
    }

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    void confirmWithdrawal(
            AccountChainProfile profile,
            WithdrawalOrderRecord order,
            ChainAddressRecord from) throws Exception {
        Response.TransactionInfo txInfo =
                confirmedTransaction(profile, order.getTxHash());
        if (txInfo == null) {
            return;
        }
        recordConfirmed(
                order.getChain(), order.getTxHash(), from.getAddress(),
                order.getToAddress(), order.getAssetSymbol(),
                order.getAmount(), txInfo);
        repository.confirmWithdrawalAndSettle(
                order.getTenantId(), order.getChain(), order.getOrderNo(),
                order.getTxHash(), order.getAssetSymbol(),
                assets.debitAccountId(order, from),
                assets.withdrawalDebitAmount(order));
    }

    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    void confirmCollection(
            AccountChainProfile profile,
            ChainCollectionRecord record) throws Exception {
        Response.TransactionInfo txInfo =
                confirmedTransaction(profile, record.getTxHash());
        if (txInfo == null) {
            return;
        }
        recordConfirmed(
                record.getChain(), record.getTxHash(), record.getFromAddress(),
                record.getToAddress(), record.getAssetSymbol(),
                record.getAmount(), txInfo);
        repository.markCollectionConfirmed(
                record.getTenantId(), record.getChain(),
                record.getCollectionNo(), record.getTxHash());
    }

    /**
     * 处理 {@code confirmedTransaction} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private Response.TransactionInfo confirmedTransaction(
            AccountChainProfile profile, String txHash) throws Exception {
        if (txHash == null || txHash.isBlank()) {
            return null;
        }
        try (TronTridentClient client = tronClientFactory.create()) {
            Response.TransactionInfo txInfo =
                    client.getTransactionInfo(txHash, NodeType.SOLIDITY_NODE);
            if (txInfo == null || txInfo.getBlockNumber() <= 0) {
                return null;
            }
            long best = client.getNowBlock()
                    .getBlockHeader()
                    .getRawData()
                    .getNumber();
            long confirmations = Math.max(
                    0, best - txInfo.getBlockNumber() + 1);
            return confirmations >= Math.max(
                    1, profile.getWithdrawConfirmations())
                    ? txInfo
                    : null;
        }
    }

    /**
     * 记录或保存 {@code recordConfirmed} 对应的数据，并遵守幂等和事务约束。
     */
    private void recordConfirmed(
            String chain,
            String txHash,
            String from,
            String to,
            String symbol,
            BigDecimal amount,
            Response.TransactionInfo txInfo) {
        repository.recordTronTransaction(TronTransactionRecord.builder()
                .chain(chain)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .amount(amount)
                .fee(BigDecimal.valueOf(txInfo.getFee()).movePointLeft(6))
                .blockHeight(txInfo.getBlockNumber())
                .confirmations(1)
                .status("CONFIRMED")
                .rawPayload(txInfo.toString())
                .build());
    }

    /**
     * 记录或保存 {@code recordSent} 对应的数据，并遵守幂等和事务约束。
     */
    private void recordSent(
            String chain,
            String txHash,
            String from,
            String to,
            String symbol,
            String contract,
            BigDecimal amount) {
        repository.recordTronTransaction(TronTransactionRecord.builder()
                .chain(chain)
                .txHash(txHash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .contractAddress(contract)
                .amount(amount)
                .fee(BigDecimal.ZERO)
                .confirmations(0)
                .status("SENT")
                .build());
    }

    /**
     * 执行 {@code tronKey} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private KeyPair tronKey(
            AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = secp256k1KeyService.key(profile, from);
        return TronTridentKeyFactory.fromBitcoinEcKey(ecKey);
    }

    /**
     * 转换或计算 {@code feeLimitSun} 对应的值，统一金额、格式和边界规则。
     */
    private long feeLimitSun(AccountChainProfile profile) {
        Long configured = profile.getDefaultFee();
        return configured == null || configured <= 0
                ? 30_000_000L
                : Math.max(10_000_000L, configured);
    }
}
