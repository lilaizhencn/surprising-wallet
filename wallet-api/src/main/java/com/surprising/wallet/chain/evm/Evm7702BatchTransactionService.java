package com.surprising.wallet.chain.evm;

import org.web3j.crypto.AccessListObject;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
public class Evm7702BatchTransactionService {
    /**
     * 构建并签名包含授权列表的 EIP-7702 type-4 交易。
     * <p>方法会校验交易费用、调用数据、授权列表和中继账户凭证，并确认编码结果的首字节为 type-4。</p>
     */
    public SignedType4Transaction sign(
            long chainId,
            BigInteger relayerNonce,
            BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas,
            BigInteger gasLimit,
            String collectorAddress,
            String data,
            List<AuthorizationTuple> authorizations,
            Credentials relayerCredentials) {
        if (chainId <= 0 || relayerNonce == null || relayerNonce.signum() < 0
                || maxPriorityFeePerGas == null || maxPriorityFeePerGas.signum() < 0
                || maxFeePerGas == null || maxFeePerGas.compareTo(maxPriorityFeePerGas) < 0
                || gasLimit == null || gasLimit.signum() <= 0) {
            throw new IllegalArgumentException("invalid EIP-1559 transaction parameters");
        }
        if (data == null || !data.matches("^0x[0-9a-fA-F]+$") || (data.length() & 1) != 0) {
            throw new IllegalArgumentException("collector calldata must be even-length hex");
        }
        if (authorizations == null || authorizations.isEmpty()) {
            throw new IllegalArgumentException("at least one authorization is required for a type-4 activation batch");
        }
        if (relayerCredentials == null) {
            throw new IllegalArgumentException("relayer credentials are required");
        }
        Evm7702CollectionRequest addressCheck = new Evm7702CollectionRequest(
                new byte[32], BigInteger.ZERO, relayerCredentials.getAddress(), collectorAddress,
                collectorAddress, collectorAddress, BigInteger.ONE, BigInteger.ZERO,
                BigInteger.ONE, BigInteger.ONE);
        RawTransaction raw = RawTransaction.createTransaction(
                chainId,
                relayerNonce,
                maxPriorityFeePerGas,
                maxFeePerGas,
                gasLimit,
                addressCheck.collector(),
                BigInteger.ZERO,
                data,
                List.<AccessListObject>of(),
                List.copyOf(authorizations));
        byte[] signed = TransactionEncoder.signMessage(raw, relayerCredentials);
        if (signed.length == 0 || signed[0] != 0x04) {
            throw new IllegalStateException("Web3j did not encode an EIP-7702 type-4 transaction");
        }
        return new SignedType4Transaction(
                Numeric.toHexString(signed), Numeric.toHexString(Hash.sha3(signed)), relayerNonce);
    }

    /**
     * 签名归集批次交易；存在授权列表时生成 type-4，否则生成普通 EIP-1559 type-2 交易。
     */
    public SignedBatchTransaction signBatch(
            long chainId, BigInteger relayerNonce, BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas, BigInteger gasLimit, String collectorAddress,
            String data, List<AuthorizationTuple> authorizations, Credentials relayerCredentials) {
        if (authorizations != null && !authorizations.isEmpty()) {
            SignedType4Transaction type4 = sign(
                    chainId, relayerNonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit,
                    collectorAddress, data, authorizations, relayerCredentials);
            return new SignedBatchTransaction(
                    type4.rawTransaction(), type4.transactionHash(), relayerNonce, 4);
        }
        if (chainId <= 0 || relayerNonce == null || relayerNonce.signum() < 0
                || maxPriorityFeePerGas == null || maxPriorityFeePerGas.signum() < 0
                || maxFeePerGas == null || maxFeePerGas.compareTo(maxPriorityFeePerGas) < 0
                || gasLimit == null || gasLimit.signum() <= 0 || relayerCredentials == null) {
            throw new IllegalArgumentException("invalid EIP-1559 transaction parameters");
        }
        Evm7702CollectionRequest addressCheck = new Evm7702CollectionRequest(
                new byte[32], BigInteger.ZERO, relayerCredentials.getAddress(), collectorAddress,
                collectorAddress, collectorAddress, BigInteger.ONE, BigInteger.ZERO,
                BigInteger.ONE, BigInteger.ONE);
        RawTransaction raw = RawTransaction.createTransaction(
                chainId, relayerNonce, gasLimit, addressCheck.collector(), BigInteger.ZERO,
                data, maxPriorityFeePerGas, maxFeePerGas);
        byte[] signed = TransactionEncoder.signMessage(raw, relayerCredentials);
        if (signed.length == 0 || signed[0] != 0x02) {
            throw new IllegalStateException("Web3j did not encode an EIP-1559 type-2 transaction");
        }
        return new SignedBatchTransaction(
                Numeric.toHexString(signed), Numeric.toHexString(Hash.sha3(signed)), relayerNonce, 2);
    }
    public record SignedType4Transaction(String rawTransaction, String transactionHash, BigInteger relayerNonce) {
    }

    public record SignedBatchTransaction(
            String rawTransaction, String transactionHash, BigInteger relayerNonce, int transactionType) {
    }
}
