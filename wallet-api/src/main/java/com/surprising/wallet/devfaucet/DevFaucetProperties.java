package com.surprising.wallet.devfaucet;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 开发环境水龙头配置属性，从 {@code sw.wallet.dev-faucet} 前缀绑定。
 *
 * <p>仅在允许的环境（dev/test/test2/local）中启用，用于向测试地址自动补币
 * （BTC/ETH/USDT/USDC 等）。包含 BTC 和 EVM 链各自的 RPC 端点、阈值、私钥配置。
 */
@Component
@ConfigurationProperties(prefix = "sw.wallet.dev-faucet")
public class DevFaucetProperties {

    /** 允许启用水龙头的环境列表 */
    private static final Set<String> ALLOWED_ENVIRONMENTS =
            Set.of("dev", "test", "test2", "local");
    /** 是否启用 */
    private boolean enabled;
    /** 轮询间隔，默认 10 秒 */
    private Duration delay = Duration.ofSeconds(10);
    /** 失败重试延迟，默认 30 秒 */
    private Duration retryDelay = Duration.ofSeconds(30);
    /** RPC 请求超时，默认 10 秒 */
    private Duration requestTimeout = Duration.ofSeconds(10);
    /** 每次批处理的最大候选地址数 */
    private int batchSize = 20;
    /** 每笔补币的最大重试次数 */
    private int maxAttempts = 3;
    /** BTC 水龙头配置 */
    private final Bitcoin bitcoin = new Bitcoin();
    /** EVM 水龙头配置 */
    private final Evm evm = new Evm();

    /** 校验当前环境是否允许启用水龙头 */
    public void validate(String environment) {
        if (!enabled) {
            return;
        }
        String normalized = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ENVIRONMENTS.contains(normalized)) {
            throw new IllegalStateException(
                    "dev faucet is only allowed in dev/test/test2/local environments");
        }
        requireLoopback(bitcoin.rpcUrl, "bitcoin.rpc-url");
        requireLoopback(evm.rpcUrl, "evm.rpc-url");
        requireRange(bitcoin.customer, "bitcoin.customer");
        requirePositive(bitcoin.gasAmount, "bitcoin.gas-amount");
        requireRange(evm.customer, "evm.customer");
        requireRange(evm.usdt, "evm.usdt");
        requireRange(evm.usdc, "evm.usdc");
        requirePositive(evm.gasAmount, "evm.gas-amount");
        // BTC RPC credentials are optional (regtest node may not require auth)
        if (!bitcoin.wallet.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("dev faucet bitcoin wallet name is invalid");
        }
        if (!evm.fromAddress.matches("(?i)^0x[0-9a-f]{40}$")) {
            throw new IllegalStateException("dev faucet EVM from address is invalid");
        }
        if (batchSize < 1 || batchSize > 500 || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalStateException("dev faucet batch/attempt limits are invalid");
        }
        if (bitcoin.confirmationBlocks < 1 || bitcoin.confirmationBlocks > 100) {
            throw new IllegalStateException("dev faucet bitcoin confirmation blocks are invalid");
        }
    }
    /**
     * 校验 {@code requireLoopback} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireLoopback(String value, String name) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1") || host.equals("::1"));
            if (!loopback || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalStateException(name + " must use a loopback HTTP endpoint");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(name + " is invalid", error);
        }
    }
    /**
     * 校验 {@code requireRange} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireRange(AmountRange range, String name) {
        requirePositive(range.min, name + ".min");
        requirePositive(range.max, name + ".max");
        if (range.max.compareTo(range.min) < 0 || range.scale < 0 || range.scale > 18
                || range.min.scale() > range.scale || range.max.scale() > range.scale) {
            throw new IllegalStateException(name + " range/scale is invalid");
        }
    }
    /**
     * 校验 {@code requirePositive} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    /**
     * 判断 {@code isEnabled} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isEnabled() { return enabled; }
    /**
     * 设置或更新 {@code setEnabled} 对应的状态，并保持相关业务字段一致。
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /**
     * 获取或查询 {@code getDelay} 对应的数据，供调用方读取当前状态。
     */
    public Duration getDelay() { return delay; }
    /**
     * 设置或更新 {@code setDelay} 对应的状态，并保持相关业务字段一致。
     */
    public void setDelay(Duration delay) { this.delay = delay; }
    /**
     * 获取或查询 {@code getRetryDelay} 对应的数据，供调用方读取当前状态。
     */
    public Duration getRetryDelay() { return retryDelay; }
    /**
     * 设置或更新 {@code setRetryDelay} 对应的状态，并保持相关业务字段一致。
     */
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    /**
     * 获取或查询 {@code getRequestTimeout} 对应的数据，供调用方读取当前状态。
     */
    public Duration getRequestTimeout() { return requestTimeout; }
    /**
     * 设置或更新 {@code setRequestTimeout} 对应的状态，并保持相关业务字段一致。
     */
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    /**
     * 获取或查询 {@code getBatchSize} 对应的数据，供调用方读取当前状态。
     */
    public int getBatchSize() { return batchSize; }
    /**
     * 设置或更新 {@code setBatchSize} 对应的状态，并保持相关业务字段一致。
     */
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    /**
     * 获取或查询 {@code getMaxAttempts} 对应的数据，供调用方读取当前状态。
     */
    public int getMaxAttempts() { return maxAttempts; }
    /**
     * 设置或更新 {@code setMaxAttempts} 对应的状态，并保持相关业务字段一致。
     */
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    /**
     * 获取或查询 {@code getBitcoin} 对应的数据，供调用方读取当前状态。
     */
    public Bitcoin getBitcoin() { return bitcoin; }
    /**
     * 获取或查询 {@code getEvm} 对应的数据，供调用方读取当前状态。
     */
    public Evm getEvm() { return evm; }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    public static class Bitcoin {
        /**
         * 保存 {@code rpcUrl}，用于访问当前业务所依赖的仓储、客户端或服务。
         */
        private String rpcUrl = "http://127.0.0.1:18444";
        /**
         * 保存 {@code rpcUsername}，用于访问当前业务所依赖的仓储、客户端或服务。
         */
        private String rpcUsername = "";
        /**
         * 保存 {@code rpcPassword}，用于访问当前业务所依赖的仓储、客户端或服务。
         */
        private String rpcPassword = "";
        /**
         * 保存 {@code wallet}，用于承载当前对象的运行配置或业务数据。
         */
        private String wallet = "regtest-funder";
        /**
         * 保存 {@code confirmationBlocks}，记录开关、处理状态、确认结果或重试信息。
         */
        private int confirmationBlocks = 6;
        /**
         * 保存 {@code gasAmount}，用于保存金额、费用或链上执行状态。
         */
        private BigDecimal gasAmount = new BigDecimal("0.10000000");
        /**
         * 保存 {@code customer}，用于承载当前对象的运行配置或业务数据。
         */
        private final AmountRange customer =
                new AmountRange("0.01000000", "0.10000000", 8);

        /**
         * 获取或查询 {@code getRpcUrl} 对应的数据，供调用方读取当前状态。
         */
        public String getRpcUrl() { return rpcUrl; }
        /**
         * 设置或更新 {@code setRpcUrl} 对应的状态，并保持相关业务字段一致。
         */
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl == null ? "" : rpcUrl.trim(); }
        /**
         * 获取或查询 {@code getRpcUsername} 对应的数据，供调用方读取当前状态。
         */
        public String getRpcUsername() { return rpcUsername; }
        /**
         * 设置或更新 {@code setRpcUsername} 对应的状态，并保持相关业务字段一致。
         */
        public void setRpcUsername(String rpcUsername) { this.rpcUsername = rpcUsername == null ? "" : rpcUsername; }
        /**
         * 获取或查询 {@code getRpcPassword} 对应的数据，供调用方读取当前状态。
         */
        public String getRpcPassword() { return rpcPassword; }
        /**
         * 设置或更新 {@code setRpcPassword} 对应的状态，并保持相关业务字段一致。
         */
        public void setRpcPassword(String rpcPassword) { this.rpcPassword = rpcPassword == null ? "" : rpcPassword; }
        /**
         * 获取或查询 {@code getWallet} 对应的数据，供调用方读取当前状态。
         */
        public String getWallet() { return wallet; }
        /**
         * 设置或更新 {@code setWallet} 对应的状态，并保持相关业务字段一致。
         */
        public void setWallet(String wallet) { this.wallet = wallet == null ? "" : wallet.trim(); }
        /**
         * 获取或查询 {@code getConfirmationBlocks} 对应的数据，供调用方读取当前状态。
         */
        public int getConfirmationBlocks() { return confirmationBlocks; }
        /**
         * 设置或更新 {@code setConfirmationBlocks} 对应的状态，并保持相关业务字段一致。
         */
        public void setConfirmationBlocks(int confirmationBlocks) { this.confirmationBlocks = confirmationBlocks; }
        /**
         * 获取或查询 {@code getGasAmount} 对应的数据，供调用方读取当前状态。
         */
        public BigDecimal getGasAmount() { return gasAmount; }
        /**
         * 设置或更新 {@code setGasAmount} 对应的状态，并保持相关业务字段一致。
         */
        public void setGasAmount(BigDecimal gasAmount) { this.gasAmount = gasAmount; }
        /**
         * 获取或查询 {@code getCustomer} 对应的数据，供调用方读取当前状态。
         */
        public AmountRange getCustomer() { return customer; }
    }
    /**
     * 负责 EVM 链交易、费用、扫描或 EIP-7702 相关处理。
     */
    public static class Evm {
        /**
         * 保存 {@code rpcUrl}，用于访问当前业务所依赖的仓储、客户端或服务。
         */
        private String rpcUrl = "http://127.0.0.1:8545";
        /**
         * 保存 {@code fromAddress}，表示链、网络、资产或代币配置。
         */
        private String fromAddress = "";
        /**
         * 保存 {@code gasAmount}，用于保存金额、费用或链上执行状态。
         */
        private BigDecimal gasAmount = new BigDecimal("1.000000");
        /**
         * 保存 {@code customer}，用于承载当前对象的运行配置或业务数据。
         */
        private final AmountRange customer = new AmountRange("0.100000", "1.000000", 6);
        /**
         * 保存 {@code usdt}，用于承载当前对象的运行配置或业务数据。
         */
        private final AmountRange usdt = new AmountRange("10.00", "100.00", 2);
        /**
         * 保存 {@code usdc}，用于承载当前对象的运行配置或业务数据。
         */
        private final AmountRange usdc = new AmountRange("10.00", "100.00", 2);

        /**
         * 获取或查询 {@code getRpcUrl} 对应的数据，供调用方读取当前状态。
         */
        public String getRpcUrl() { return rpcUrl; }
        /**
         * 设置或更新 {@code setRpcUrl} 对应的状态，并保持相关业务字段一致。
         */
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl == null ? "" : rpcUrl.trim(); }
        /**
         * 获取或查询 {@code getFromAddress} 对应的数据，供调用方读取当前状态。
         */
        public String getFromAddress() { return fromAddress; }
        /**
         * 设置或更新 {@code setFromAddress} 对应的状态，并保持相关业务字段一致。
         */
        public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress == null ? "" : fromAddress.trim(); }
        /**
         * 获取或查询 {@code getGasAmount} 对应的数据，供调用方读取当前状态。
         */
        public BigDecimal getGasAmount() { return gasAmount; }
        /**
         * 设置或更新 {@code setGasAmount} 对应的状态，并保持相关业务字段一致。
         */
        public void setGasAmount(BigDecimal gasAmount) { this.gasAmount = gasAmount; }
        /**
         * 获取或查询 {@code getCustomer} 对应的数据，供调用方读取当前状态。
         */
        public AmountRange getCustomer() { return customer; }
        /**
         * 获取或查询 {@code getUsdt} 对应的数据，供调用方读取当前状态。
         */
        public AmountRange getUsdt() { return usdt; }
        /**
         * 获取或查询 {@code getUsdc} 对应的数据，供调用方读取当前状态。
         */
        public AmountRange getUsdc() { return usdc; }
    }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    public static class AmountRange {
        /**
         * 保存 {@code min}，用于承载当前对象的运行配置或业务数据。
         */
        private BigDecimal min;
        /**
         * 保存 {@code max}，用于承载当前对象的运行配置或业务数据。
         */
        private BigDecimal max;
        /**
         * 保存 {@code scale}，用于承载当前对象的运行配置或业务数据。
         */
        private int scale;

        /**
         * 构造 {@code AmountRange}，初始化该组件运行所需的状态和依赖。
         */
        public AmountRange() {
        }

        /**
         * 构造 {@code AmountRange}，初始化该组件运行所需的状态和依赖。
         */
        AmountRange(String min, String max, int scale) {
            this.min = new BigDecimal(min);
            this.max = new BigDecimal(max);
            this.scale = scale;
        }

        /**
         * 获取或查询 {@code getMin} 对应的数据，供调用方读取当前状态。
         */
        public BigDecimal getMin() { return min; }
        /**
         * 设置或更新 {@code setMin} 对应的状态，并保持相关业务字段一致。
         */
        public void setMin(BigDecimal min) { this.min = min; }
        /**
         * 获取或查询 {@code getMax} 对应的数据，供调用方读取当前状态。
         */
        public BigDecimal getMax() { return max; }
        /**
         * 设置或更新 {@code setMax} 对应的状态，并保持相关业务字段一致。
         */
        public void setMax(BigDecimal max) { this.max = max; }
        /**
         * 获取或查询 {@code getScale} 对应的数据，供调用方读取当前状态。
         */
        public int getScale() { return scale; }
        /**
         * 设置或更新 {@code setScale} 对应的状态，并保持相关业务字段一致。
         */
        public void setScale(int scale) { this.scale = scale; }
    }
}
