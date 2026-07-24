package com.surprising.wallet.devfaucet.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
public final class DevFaucetAmountGenerator {
    private final RandomGenerator random;    public DevFaucetAmountGenerator() {
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
