package com.surprising.wallet.chain.tron;

import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Service
public class TronTrc20Service {
    /**
     * 为 {@code signTransfer} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public TronTransactionService.SignedTronTransaction signTransfer(TronTridentClient client,
                                                                    KeyPair keyPair,
                                                                    String contractAddress,
                                                                    String recipientAddress,
                                                                    BigDecimal amount,
                                                                    int decimals,
                                                                    long feeLimitSun) {
        String ownerAddress = keyPair.toBase58CheckAddress();
        List<Type> inputs = List.of(
                new Address(TronAddressCodec.toAbiAddress(recipientAddress)),
                new Uint256(Trc20AbiCodec.toRawAmount(amount, decimals))
        );
        Function transfer = new Function("transfer", inputs, List.of());
        Chain.Transaction unsigned = client.api()
                .triggerCall(ownerAddress, contractAddress, transfer)
                .setFeeLimit(feeLimitSun)
                .build();
        Chain.Transaction signed = client.api().signTransaction(unsigned, keyPair);
        return new TronTransactionService.SignedTronTransaction(TronTransactionService.txId(signed), signed);
    }
    /**
     * 发送或广播 {@code broadcast} 对应的链上请求，并返回节点处理结果。
     */
    public String broadcast(TronTridentClient client, TronTransactionService.SignedTronTransaction transaction) {
        return client.broadcast(transaction.transaction());
    }
}
