package com.surprising.wallet.devfaucet.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
/**
 * 开发水龙头金额生成器。
 *
 * <p>在配置的金额范围内随机生成补给金额，使用 {@link SecureRandom} 保证随机性。
 * 支持自定义的 {@link RandomGenerator} 注入以方便测试。
 */
public final class DevFaucetAmountGenerator {

    /** 随机数生成器 */
    private final RandomGenerator random;
    public DevFaucetAmountGenerator() {
        this(new SecureRandom());
    }
    public DevFaucetAmountGenerator(RandomGenerator random) {
        this.random = random;
    }
    public BigDecimal next(DevFaucetProperties.AmountRange range) {
        int scale = range.getScale();
        BigInteger min = range.getMin().movePointRight(scale).toBigIntegerExact();
        BigInteger max = range.getMax().movePointRight(scale).toBigIntegerExact();
        BigInteger width = max.subtract(min).add(BigInteger.ONE);
        if (width.bitLength() > 63) {
            throw new IllegalArgumentException("dev faucet amount range is too wide");
        }
        long offset = random.nextLong(width.longValueExact());
        return new BigDecimal(min.add(BigInteger.valueOf(offset)), scale);
    }
}
