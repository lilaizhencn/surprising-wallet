package com.surprising.wallet.devfaucet.model;

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
    private static void requireRange(AmountRange range, String name) {
        requirePositive(range.min, name + ".min");
        requirePositive(range.max, name + ".max");
        if (range.max.compareTo(range.min) < 0 || range.scale < 0 || range.scale > 18
                || range.min.scale() > range.scale || range.max.scale() > range.scale) {
            throw new IllegalStateException(name + " range/scale is invalid");
        }
    }
    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException(name + " must be positive");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getDelay() { return delay; }
    public void setDelay(Duration delay) { this.delay = delay; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Bitcoin getBitcoin() { return bitcoin; }
    public Evm getEvm() { return evm; }
    public static class Bitcoin {
        private String rpcUrl = "http://127.0.0.1:18444";
        private String rpcUsername = "";
        private String rpcPassword = "";
        private String wallet = "regtest-funder";
        private int confirmationBlocks = 6;
        private BigDecimal gasAmount = new BigDecimal("0.10000000");
        private final AmountRange customer =
                new AmountRange("0.01000000", "0.10000000", 8);

        public String getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl == null ? "" : rpcUrl.trim(); }
        public String getRpcUsername() { return rpcUsername; }
        public void setRpcUsername(String rpcUsername) { this.rpcUsername = rpcUsername == null ? "" : rpcUsername; }
        public String getRpcPassword() { return rpcPassword; }
        public void setRpcPassword(String rpcPassword) { this.rpcPassword = rpcPassword == null ? "" : rpcPassword; }
        public String getWallet() { return wallet; }
        public void setWallet(String wallet) { this.wallet = wallet == null ? "" : wallet.trim(); }
        public int getConfirmationBlocks() { return confirmationBlocks; }
        public void setConfirmationBlocks(int confirmationBlocks) { this.confirmationBlocks = confirmationBlocks; }
        public BigDecimal getGasAmount() { return gasAmount; }
        public void setGasAmount(BigDecimal gasAmount) { this.gasAmount = gasAmount; }
        public AmountRange getCustomer() { return customer; }
    }
    public static class Evm {
        private String rpcUrl = "http://127.0.0.1:8545";
        private String fromAddress = "";
        private BigDecimal gasAmount = new BigDecimal("1.000000");
        private final AmountRange customer = new AmountRange("0.100000", "1.000000", 6);
        private final AmountRange usdt = new AmountRange("10.00", "100.00", 2);
        private final AmountRange usdc = new AmountRange("10.00", "100.00", 2);

        public String getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl == null ? "" : rpcUrl.trim(); }
        public String getFromAddress() { return fromAddress; }
        public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress == null ? "" : fromAddress.trim(); }
        public BigDecimal getGasAmount() { return gasAmount; }
        public void setGasAmount(BigDecimal gasAmount) { this.gasAmount = gasAmount; }
        public AmountRange getCustomer() { return customer; }
        public AmountRange getUsdt() { return usdt; }
        public AmountRange getUsdc() { return usdc; }
    }
    public static class AmountRange {
        private BigDecimal min;
        private BigDecimal max;
        private int scale;

        public AmountRange() {
        }

        AmountRange(String min, String max, int scale) {
            this.min = new BigDecimal(min);
            this.max = new BigDecimal(max);
            this.scale = scale;
        }

        public BigDecimal getMin() { return min; }
        public void setMin(BigDecimal min) { this.min = min; }
        public BigDecimal getMax() { return max; }
        public void setMax(BigDecimal max) { this.max = max; }
        public int getScale() { return scale; }
        public void setScale(int scale) { this.scale = scale; }
    }
}
