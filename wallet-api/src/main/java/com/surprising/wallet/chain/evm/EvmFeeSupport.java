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
    /**
     * 定义 {@code OP_STACK_GAS_PRICE_ORACLE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String OP_STACK_GAS_PRICE_ORACLE =
            "0x420000000000000000000000000000000000000F";
    /**
     * 定义 {@code SCROLL_L1_GAS_PRICE_ORACLE} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String SCROLL_L1_GAS_PRICE_ORACLE =
            "0x5300000000000000000000000000000000000002";
    /**
     * 构造 {@code EvmFeeSupport}，初始化该组件运行所需的状态和依赖。
     */
    private EvmFeeSupport() {
    }

    /**
     * 从链配置读取并解析 Gas 交易信封策略。
     */
    public static EvmGasPolicy gasPolicy(AccountChainProfile profile) {
        return EvmGasPolicy.parse(profile.getGasPolicy());
    }

    /**
     * 从链配置读取并解析多链费用模型。
     */
    public static EvmFeeModel feeModel(AccountChainProfile profile) {
        return EvmFeeModel.parse(profile.getFeeModel());
    }

    /**
     * 根据节点当前 Gas 价格和最新区块基础费生成交易费用报价。
     * <p>EIP-1559 链返回优先费与最大费用；传统 GasPrice 链将两者设置为同一值。</p>
     */
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

    /**
     * 调用 OP Stack 或 Scroll 的 L1 费用预言机，估算交易需要支付的独立 L1 费用。
     * <p>本地网络和不含独立 L1 费用的模型返回零。</p>
     */
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

    /**
     * 调用 OP Stack 运营方费用预言机，按预计 Gas 上限估算运营方费用。
     */
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

    /**
     * 从交易回执读取并校验实际独立 L1 费用；生产链缺少回执字段时直接失败。
     */
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

    /**
     * 按交易实际使用的 Gas 和区块高度查询 OP Stack 实际运营方费用。
     */
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

    /**
     * 解析 Arbitrum Nitro 回执中的 L1 Gas，并确保它不超过交易总 Gas。
     */
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

    /**
     * 汇总执行费、L1 费用和运营方费用，形成交易最终费用组成。
     */
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

    /**
     * 校验待发送账户的 pending 原生余额足以覆盖指定的链上费用。
     */
    public static void requireBalance(
            Web3j web3j, String address, BigInteger requiredAtomic, String purpose) throws Exception {
        BigInteger balance = web3j.ethGetBalance(
                address, DefaultBlockParameterName.PENDING).send().getBalance();
        if (balance.compareTo(requiredAtomic) < 0) {
            throw new IllegalStateException(purpose + " native gas balance is insufficient: required="
                    + requiredAtomic + " available=" + balance);
        }
    }

    /**
     * 按资产精度将最小原生单位金额转换为展示和账务使用的原生币金额。
     */
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

    /**
     * 调用费用预言机合约，并严格解析单个 uint256 返回值。
     */
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

    /**
     * 判断 {@code isLocal} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean isLocal(AccountChainProfile profile) {
        return "local".equalsIgnoreCase(profile.getNetwork());
    }

    /**
     * 校验 {@code requirePositive} 对应的前置条件，不满足时抛出明确异常。
     */
    private static BigInteger requirePositive(BigInteger value, String source) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException(source + " returned a non-positive value");
        }
        return value;
    }

    public record FeeQuote(
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, boolean eip1559) {
        /**
         * 执行 {@code legacyGasPrice} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        public BigInteger legacyGasPrice() {
            if (eip1559) {
                throw new IllegalStateException("EIP-1559 quote has no legacy gas price");
            }
            return maxFeePerGas;
        }
    }

    public record FeeComponents(
            BigInteger executionFee, BigInteger l1Fee, BigInteger operatorFee) {
        /**
         * 编码 {@code total} 对应的数据，生成链上或接口所需的表示。
         */
        public BigInteger total() {
            return executionFee.add(l1Fee).add(operatorFee);
        }
    }
}
