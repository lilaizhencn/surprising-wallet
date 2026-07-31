package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import com.surprising.wallet.wallet.service.HotWalletAddressService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code PolkadotTransactionServiceTest} 覆盖的业务流程、边界条件和异常行为。
 */
class PolkadotTransactionServiceTest {
    /**
     * 保存 {@code MASTER_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * 验证 {@code assetCollectionTopsUpAssetHubGasAndAllowsAssetAccountDeath} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void assetCollectionTopsUpAssetHubGasAndAllowsAssetAccountDeath() {
        FakeRuntimeClient runtime = new FakeRuntimeClient();
        FakeRepository repository = new FakeRepository();
        ChainAddressRecord hot = address(0, 0, 0, "5HotAddress");
        PolkadotTransactionService service = new PolkadotTransactionService(
                runtime,
                new PolkadotKeyService(MASTER_SEED),
                repository,
                new FakeHotWalletAddressService(hot));
        ChainAddressRecord user = address(7, 0, 3, "5UserAddress");
        TokenDefinition token = TokenDefinition.builder()
                .chain("DOT")
                .symbol("USDC")
                .contractAddress("1984")
                .decimals(6)
                .active(true)
                .build();

        String txHash = service.collectAsset(UUID.randomUUID(), "COLL-DOT-USDC-test",
                user, token, hot.getAddress(),
                new BigDecimal("2.5"));

        assertEquals("asset-tx", txHash);
        assertEquals("5UserAddress", runtime.topUpTo);
        assertEquals(new BigInteger("100000000000"), runtime.topUpAmount);
        assertEquals("1984", runtime.assetId);
        assertEquals(new BigInteger("2500000"), runtime.assetAmount);
        assertFalse(runtime.assetKeepAlive);
        assertEquals("SENT", repository.status);
    }

    /**
     * 验证 {@code assetWithdrawalUsesKeepAliveWhenGasAlreadyExists} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void assetWithdrawalUsesKeepAliveWhenGasAlreadyExists() {
        FakeRuntimeClient runtime = new FakeRuntimeClient();
        runtime.senderGas = new BigInteger("30000000000");
        FakeRepository repository = new FakeRepository();
        ChainAddressRecord hot = address(0, 0, 0, "5HotAddress");
        PolkadotTransactionService service = new PolkadotTransactionService(
                runtime,
                new PolkadotKeyService(MASTER_SEED),
                repository,
                new FakeHotWalletAddressService(hot));
        ChainAddressRecord user = address(7, 0, 3, "5UserAddress");
        TokenDefinition token = TokenDefinition.builder()
                .chain("DOT")
                .symbol("USDC")
                .contractAddress("1984")
                .decimals(6)
                .active(true)
                .build();

        String txHash = service.sendAsset(user, token, "5RecipientAddress", new BigDecimal("1"));

        assertEquals("asset-tx", txHash);
        assertNull(runtime.topUpTo);
        assertTrue(runtime.assetKeepAlive);
    }

    /**
     * 验证 {@code address} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static ChainAddressRecord address(long userId, int biz, long index, String address) {
        return ChainAddressRecord.builder()
                .chain("DOT")
                .assetSymbol("DOT")
                .accountId(address)
                .userId(userId)
                .biz(biz)
                .addressIndex(index)
                .address(address)
                .ownerAddress(address)
                .derivationPath("m/44/354/" + biz + "/" + userId + "/" + index)
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    /**
     * 测试替身 {@code FakeRuntimeClient}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRuntimeClient extends PolkadotRuntimeClient {
        /**
         * 保存 {@code senderGas}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
         */
        private BigInteger senderGas = BigInteger.ZERO;
        /**
         * 保存 {@code topUpTo}，用于承载当前测试夹具的配置或运行数据。
         */
        private String topUpTo;
        /**
         * 保存 {@code topUpAmount}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
         */
        private BigInteger topUpAmount;
        /**
         * 保存 {@code assetId}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private String assetId;
        /**
         * 保存 {@code assetAmount}，表示测试使用的金额、余额、手续费、Gas 或精度参数。
         */
        private BigInteger assetAmount;
        /**
         * 保存 {@code assetKeepAlive}，表示测试所覆盖的链、网络、资产或代币配置。
         */
        private boolean assetKeepAlive;

        /**
         * 验证 {@code FakeRuntimeClient} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeRuntimeClient() {
            super(null, null);
        }

        /**
         * 验证 {@code assetHubNativeBalance} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public BigInteger assetHubNativeBalance(String address) {
            if (topUpTo != null && topUpTo.equals(address)) {
                return senderGas.add(topUpAmount);
            }
            return senderGas;
        }

        /**
         * 验证 {@code sendAssetHubNative} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public SubmittedTransaction sendAssetHubNative(String secretSeedHex, String expectedFrom,
                                                       String toAddress, BigInteger amountPlanck,
                                                       boolean keepAlive) {
            topUpTo = toAddress;
            topUpAmount = amountPlanck;
            return new SubmittedTransaction("topup-tx", 1, "FINALIZED", "{}");
        }

        /**
         * 验证 {@code sendAsset} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public SubmittedTransaction sendAsset(String secretSeedHex, String expectedFrom,
                                              String assetId, String toAddress, BigInteger amountAtomic,
                                              boolean keepAlive) {
            this.assetId = assetId;
            this.assetAmount = amountAtomic;
            this.assetKeepAlive = keepAlive;
            return new SubmittedTransaction("asset-tx", 2, "FINALIZED", "{}");
        }
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code status}，记录测试开关、处理状态、确认结果或重试信息。
         */
        private String status;

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeRepository() {
            super(null);
        }

        /**
         * 验证 {@code systemValue} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<String> systemValue(String configKey) {
            return Optional.empty();
        }

        /**
         * 验证 {@code findCollectionTxHash} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<String> findCollectionTxHash(UUID tenantId, String chain, String collectionNo) {
            return Optional.empty();
        }

        /**
         * 验证 {@code claimCollectionSigning} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int claimCollectionSigning(UUID tenantId, String chain,
                                          String collectionNo, String rawPayload) {
            return 1;
        }

        /**
         * 验证 {@code updateCollectionStatus} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public int updateCollectionStatus(UUID tenantId, String chain, String collectionNo,
                                          String status, String txHash,
                                          String errorMessage, String rawPayload) {
            this.status = status;
            return 1;
        }
    }

    /**
     * 测试替身 {@code FakeHotWalletAddressService}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeHotWalletAddressService extends HotWalletAddressService {
        /**
         * 保存 {@code hot}，用于承载当前测试夹具的配置或运行数据。
         */
        private final ChainAddressRecord hot;

        /**
         * 验证 {@code FakeHotWalletAddressService} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeHotWalletAddressService(ChainAddressRecord hot) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.hot = hot;
        }

        /**
         * 验证 {@code findDefaultHotAddress} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<ChainAddressRecord> findDefaultHotAddress(String chain, String assetSymbol) {
            return Optional.of(hot);
        }
    }
}
