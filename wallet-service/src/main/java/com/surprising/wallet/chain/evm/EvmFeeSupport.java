package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.EvmFeeModel;
import com.surprising.wallet.common.chain.EvmGasPolicy;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

/**
 * EVM 多链费用公共规则。
 *
 * <p>所有金额均以链的最小原生单位传入和返回；显示/账务单位转换必须显式传入
 * {@code chain_asset.decimals}，禁止假定所有 EVM 链都是 18 位。</p>
 */
public final class EvmFeeSupport {
    public static final String OP_STACK_GAS_PRICE_ORACLE =
            "0x420000000000000000000000000000000000000F";
    public static final String SCROLL_L1_GAS_PRICE_ORACLE =
            "0x5300000000000000000000000000000000000002";
    private EvmFeeSupport() {
    }

    public static EvmGasPolicy gasPolicy(AccountChainProfile profile) {
        return EvmGasPolicy.parse(profile.getGasPolicy());
    }

    public static EvmFeeModel feeModel(AccountChainProfile profile) {
        return EvmFeeModel.parse(profile.getFeeModel());
    }

    public static FeeQuote quote(Web3j web3j, AccountChainProfile profile) throws Exception {
        BigInteger gasPrice = requirePositive(
                web3j.ethGasPrice().send().getGasPrice(), "eth_gasPrice");
        if (!gasPolicy(profile).isEip1559()) {
            return new FeeQuote(gasPrice, gasPrice, false);
        }
        EthBlock.Block latest = web3j.ethGetBlockByNumber(
                DefaultBlockParameterName.LATEST, false).send().getBlock();
        BigInteger baseFee = latest == null ? null : latest.getBaseFeePerGas();
        boolean hasBlockBaseFee = baseFee != null && baseFee.signum() > 0;
        if (baseFee == null || baseFee.signum() <= 0) {
            baseFee = gasPrice;
        }
        BigInteger priority;
        try {
            priority = web3j.ethMaxPriorityFeePerGas().send().getMaxPriorityFeePerGas();
        } catch (Exception ignored) {
            priority = null;
        }
        if (priority == null || priority.signum() <= 0) {
            priority = hasBlockBaseFee
                    ? gasPrice.subtract(baseFee).max(BigInteger.ONE)
                    : gasPrice;
        }
        return new FeeQuote(priority, baseFee.multiply(BigInteger.TWO).add(priority), true);
    }

    public static BigInteger estimateSeparateL1Fee(
            Web3j web3j, AccountChainProfile profile, String from, String signedTransaction) {
        EvmFeeModel model = feeModel(profile);
        if (!model.hasSeparateL1Fee() || isLocal(profile)) {
            return BigInteger.ZERO;
        }
        String oracle = model == EvmFeeModel.SCROLL
                ? SCROLL_L1_GAS_PRICE_ORACLE : OP_STACK_GAS_PRICE_ORACLE;
        Function function = new Function(
                "getL1Fee",
                List.of(new DynamicBytes(Numeric.hexStringToByteArray(signedTransaction))),
                List.of(new TypeReference<Uint256>() { }));
        return oracleUint256(web3j, from, oracle, function, "getL1Fee",
                DefaultBlockParameterName.LATEST);
    }

    public static BigInteger estimateOperatorFee(
            Web3j web3j, AccountChainProfile profile, String from, BigInteger gasLimit) {
        if (!feeModel(profile).hasOperatorFee() || isLocal(profile)) {
            return BigInteger.ZERO;
        }
        Function function = new Function(
                "getOperatorFee", List.of(new Uint256(gasLimit)),
                List.of(new TypeReference<Uint256>() { }));
        return oracleUint256(web3j, from, OP_STACK_GAS_PRICE_ORACLE, function,
                "getOperatorFee", DefaultBlockParameterName.LATEST);
    }

    public static BigInteger actualSeparateL1Fee(
            AccountChainProfile profile, String receiptL1Fee) {
        if (!feeModel(profile).hasSeparateL1Fee()) {
            return BigInteger.ZERO;
        }
        if (receiptL1Fee == null || receiptL1Fee.isBlank()) {
            if (isLocal(profile)) {
                return BigInteger.ZERO;
            }
            throw new IllegalStateException(
                    feeModel(profile).configValue() + " receipt is missing l1Fee");
        }
        return Numeric.decodeQuantity(receiptL1Fee);
    }

    public static BigInteger actualOperatorFee(
            Web3j web3j, AccountChainProfile profile, String from, BigInteger gasUsed,
            BigInteger blockNumber, String operatorFeeScalar, String operatorFeeConstant) {
        if (!feeModel(profile).hasOperatorFee() || isLocal(profile)) {
            return BigInteger.ZERO;
        }
        Function function = new Function(
                "getOperatorFee", List.of(new Uint256(gasUsed)),
                List.of(new TypeReference<Uint256>() { }));
        return oracleUint256(web3j, from, OP_STACK_GAS_PRICE_ORACLE, function,
                "getOperatorFee", DefaultBlockParameter.valueOf(blockNumber));
    }

    public static BigInteger arbitrumL1Gas(
            AccountChainProfile profile, BigInteger totalGasUsed, String gasUsedForL1) {
        if (feeModel(profile) != EvmFeeModel.ARBITRUM_NITRO) {
            return BigInteger.ZERO;
        }
        if (gasUsedForL1 == null || gasUsedForL1.isBlank()) {
            if (isLocal(profile)) {
                return BigInteger.ZERO;
            }
            throw new IllegalStateException("Arbitrum Nitro receipt is missing gasUsedForL1");
        }
        BigInteger value = Numeric.decodeQuantity(gasUsedForL1);
        if (value.signum() < 0 || value.compareTo(totalGasUsed) > 0) {
            throw new IllegalStateException("Arbitrum gasUsedForL1 exceeds total gasUsed");
        }
        return value;
    }

    public static FeeComponents actualFee(
            Web3j web3j, AccountChainProfile profile, String from,
            BigInteger gasUsed, BigInteger effectiveGasPrice, BigInteger blockNumber,
            String receiptL1Fee, String gasUsedForL1,
            String operatorFeeScalar, String operatorFeeConstant) {
        BigInteger parentGas = arbitrumL1Gas(profile, gasUsed, gasUsedForL1);
        BigInteger executionFee = gasUsed.subtract(parentGas).multiply(effectiveGasPrice);
        BigInteger l1Fee = actualSeparateL1Fee(profile, receiptL1Fee)
                .add(parentGas.multiply(effectiveGasPrice));
        BigInteger operatorFee = actualOperatorFee(
                web3j, profile, from, gasUsed, blockNumber,
                operatorFeeScalar, operatorFeeConstant);
        return new FeeComponents(executionFee, l1Fee, operatorFee);
    }

    public static void requireBalance(
            Web3j web3j, String address, BigInteger requiredAtomic, String purpose) throws Exception {
        BigInteger balance = web3j.ethGetBalance(
                address, DefaultBlockParameterName.PENDING).send().getBalance();
        if (balance.compareTo(requiredAtomic) < 0) {
            throw new IllegalStateException(purpose + " native gas balance is insufficient: required="
                    + requiredAtomic + " available=" + balance);
        }
    }

    public static BigDecimal atomicToNative(
            BigInteger atomic, int decimals, RoundingMode roundingMode) {
        if (atomic == null || atomic.signum() < 0) {
            throw new IllegalArgumentException("atomic amount must be non-negative");
        }
        if (decimals < 0 || decimals > 18) {
            throw new IllegalArgumentException("native asset decimals must be between 0 and 18");
        }
        return new BigDecimal(atomic).movePointLeft(decimals)
                .setScale(decimals, roundingMode).stripTrailingZeros();
    }

    private static BigInteger oracleUint256(
            Web3j web3j, String from, String oracle, Function function,
            String operation, DefaultBlockParameter block) {
        try {
            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(
                            from, oracle, FunctionEncoder.encode(function)), block).send();
            if (response.hasError()) {
                throw new IllegalStateException(operation + " failed: "
                        + response.getError().getMessage());
            }
            List<Type> values = FunctionReturnDecoder.decode(
                    response.getValue(), function.getOutputParameters());
            if (values.size() != 1) {
                throw new IllegalStateException(operation + " returned malformed data");
            }
            return (BigInteger) values.getFirst().getValue();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(operation + " failed", e);
        }
    }

    private static boolean isLocal(AccountChainProfile profile) {
        return "local".equalsIgnoreCase(profile.getNetwork());
    }

    private static BigInteger requirePositive(BigInteger value, String source) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException(source + " returned a non-positive value");
        }
        return value;
    }

    public record FeeQuote(
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, boolean eip1559) {
        public BigInteger legacyGasPrice() {
            if (eip1559) {
                throw new IllegalStateException("EIP-1559 quote has no legacy gas price");
            }
            return maxFeePerGas;
        }
    }

    public record FeeComponents(
            BigInteger executionFee, BigInteger l1Fee, BigInteger operatorFee) {
        public BigInteger total() {
            return executionFee.add(l1Fee).add(operatorFee);
        }
    }
}
