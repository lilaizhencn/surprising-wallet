package com.surprising.wallet.devfaucet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
/**
 * 开发水龙头金额生成器。
 *
 * <p>在配置的金额范围内随机生成补给金额，使用 {@link SecureRandom} 保证随机性。
 */
@Component
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
public final class DevFaucetAmountGenerator {

    private final SecureRandom random = new SecureRandom();

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
