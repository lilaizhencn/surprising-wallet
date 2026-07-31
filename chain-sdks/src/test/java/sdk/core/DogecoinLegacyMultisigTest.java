package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultiSignAddressGenerator;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code DogecoinLegacyMultisigTest} 覆盖的业务流程、边界条件和异常行为。
 */
class DogecoinLegacyMultisigTest {
    /**
     * 验证 {@code shouldGenerateAndSignDogecoinTestnetP2shTwoOfThree} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void shouldGenerateAndSignDogecoinTestnetP2shTwoOfThree() {
        var params = DogecoinNetworkParameters.testnet();
        ECKey key1 = ECKey.fromPrivate(BigInteger.TWO);
        ECKey key2 = ECKey.fromPrivate(BigInteger.valueOf(3L));
        ECKey key3 = ECKey.fromPrivate(BigInteger.valueOf(4L));

        LegacyMultiSignAddressGenerator generator = new LegacyMultiSignAddressGenerator();
        generator.addECKey(key1);
        generator.addECKey(key2);
        generator.addECKey(key3);
        String source = generator.generateAddress(params, 2);

        assertEquals(params.getP2SHHeader(), LegacyAddress.fromBase58(params, source).getVersion());
        String destination = LegacyAddress.fromKey(params, ECKey.fromPrivate(BigInteger.valueOf(5L))).toBase58();

        LegacyMultisigTransactionBuilder builder = new LegacyMultisigTransactionBuilder(params);
        builder.addInput("11".repeat(32), 0, generator.getRedeemScriptHex(), Coin.COIN.multiply(10));
        builder.addOutput(destination, Coin.COIN.multiply(9));
        String firstSigned = builder.buildFirstSign(List.of(key1));
        String completed = builder.buildSecondSign(
                firstSigned, List.of(key2), List.of(generator.getRedeemScriptHex()));

        assertNotNull(completed);
        assertFalse(completed.isBlank());
        assertFalse(builder.getTransaction().hasWitnesses());
        assertTrue(builder.getTransaction().messageSize()
                <= P2shMultisigFeeCalculator.estimateBytes(1, 1, 2, 3));
    }
}
