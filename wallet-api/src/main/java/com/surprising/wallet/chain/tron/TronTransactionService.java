package com.surprising.wallet.chain.tron;

import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.exceptions.IllegalException;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;
import org.springframework.stereotype.Service;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Service
public class TronTransactionService {
    /**
     * 为 {@code signTrxTransfer} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public SignedTronTransaction signTrxTransfer(TronTridentClient client, KeyPair keyPair,
                                                 String toAddress, long amountSun) throws IllegalException {
        String fromAddress = keyPair.toBase58CheckAddress();
        Response.TransactionExtention tx = client.api().transfer(fromAddress, toAddress, amountSun);
        Chain.Transaction signed = client.api().signTransaction(tx, keyPair);
        return new SignedTronTransaction(txId(signed), signed);
    }
    /**
     * 发送或广播 {@code broadcast} 对应的链上请求，并返回节点处理结果。
     */
    public String broadcast(TronTridentClient client, SignedTronTransaction transaction) {
        return client.broadcast(transaction.transaction());
    }
    /**
     * 执行 {@code txId} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public static String txId(Chain.Transaction transaction) {
        return ApiWrapper.toHex(ApiWrapper.calculateTransactionHash(transaction));
    }
    public record SignedTronTransaction(String txId, Chain.Transaction transaction) {
    }
}
