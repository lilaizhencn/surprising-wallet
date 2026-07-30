package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.EvmFeeModel;
import com.surprising.wallet.common.chain.EvmGasPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvmFeeSupportTest {

    @Test
    void standardFeeUsesReceiptExecutionGasOnly() {
        EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                null, profile("standard"), "0x1",
                BigInteger.valueOf(21_000), BigInteger.valueOf(10), BigInteger.ONE,
                null, null, null, null);

        assertEquals(BigInteger.valueOf(210_000), fee.executionFee());
        assertEquals(BigInteger.ZERO, fee.l1Fee());
        assertEquals(BigInteger.ZERO, fee.operatorFee());
        assertEquals(BigInteger.valueOf(210_000), fee.total());
    }

    @Test
    void separateL1ModelAddsReceiptL1FeeWithoutGuessingFromGasPrice() {
        EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                null, profile("op-stack-l1"), "0x1",
                BigInteger.valueOf(30_000), BigInteger.valueOf(7), BigInteger.ONE,
                "0x64", null, "0x0", "0x0");

        assertEquals(BigInteger.valueOf(210_000), fee.executionFee());
        assertEquals(BigInteger.valueOf(100), fee.l1Fee());
        assertEquals(BigInteger.valueOf(210_100), fee.total());
    }

    @Test
    void arbitrumSplitsParentChainGasWithoutDoubleChargingIt() {
        EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                null, profile("arbitrum-nitro"), "0x1",
                BigInteger.valueOf(50_000), BigInteger.valueOf(3), BigInteger.ONE,
                null, "0x2710", null, null);

        assertEquals(BigInteger.valueOf(120_000), fee.executionFee());
        assertEquals(BigInteger.valueOf(30_000), fee.l1Fee());
        assertEquals(BigInteger.valueOf(150_000), fee.total());
    }

    @Test
    void separateL1ModelFailsClosedWhenReceiptOmitsL1Fee() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> EvmFeeSupport.actualFee(
                        null, profile("scroll"), "0x1",
                        BigInteger.ONE, BigInteger.ONE, BigInteger.ONE,
                        null, null, null, null));

        assertEquals("scroll receipt is missing l1Fee", error.getMessage());
    }

    @Test
    void nativeUnitConversionUsesConfiguredDecimals() {
        assertEquals("1.234567",
                EvmFeeSupport.atomicToNative(
                        BigInteger.valueOf(1_234_567), 6, RoundingMode.UP).toPlainString());
        assertEquals("1",
                EvmFeeSupport.atomicToNative(
                        new BigInteger("1000000000000000000"), 18, RoundingMode.UP)
                        .toPlainString());
    }

    @Test
    void policyParsersRejectAmbiguousLegacyL2Value() {
        assertEquals(EvmGasPolicy.EIP1559, EvmGasPolicy.parse("eip1559"));
        assertEquals(EvmFeeModel.OP_STACK, EvmFeeModel.parse("op-stack"));
        assertThrows(IllegalArgumentException.class, () -> EvmGasPolicy.parse("eip1559-l2"));
        assertThrows(IllegalArgumentException.class, () -> EvmFeeModel.parse("unknown"));
    }

    private AccountChainProfile profile(String feeModel) {
        return AccountChainProfile.builder()
                .chain("TEST")
                .network("testnet")
                .family("evm")
                .nativeSymbol("GAS")
                .chainId(123L)
                .gasPolicy("eip1559")
                .feeModel(feeModel)
                .build();
    }
}
