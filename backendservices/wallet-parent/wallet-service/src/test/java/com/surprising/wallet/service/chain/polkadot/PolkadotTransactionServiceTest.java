package com.surprising.wallet.service.chain.polkadot;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
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

class PolkadotTransactionServiceTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

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

        String txHash = service.collectAsset(null, "COLL-DOT-USDC-test", user, token, hot.getAddress(),
                new BigDecimal("2.5"));

        assertEquals("asset-tx", txHash);
        assertEquals("5UserAddress", runtime.topUpTo);
        assertEquals(new BigInteger("100000000000"), runtime.topUpAmount);
        assertEquals("1984", runtime.assetId);
        assertEquals(new BigInteger("2500000"), runtime.assetAmount);
        assertFalse(runtime.assetKeepAlive);
        assertEquals("SENT", repository.status);
    }

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

    private static final class FakeRuntimeClient extends PolkadotRuntimeClient {
        private BigInteger senderGas = BigInteger.ZERO;
        private String topUpTo;
        private BigInteger topUpAmount;
        private String assetId;
        private BigInteger assetAmount;
        private boolean assetKeepAlive;

        FakeRuntimeClient() {
            super(null, null);
        }

        @Override
        public BigInteger assetHubNativeBalance(String address) {
            if (topUpTo != null && topUpTo.equals(address)) {
                return senderGas.add(topUpAmount);
            }
            return senderGas;
        }

        @Override
        public SubmittedTransaction sendAssetHubNative(String secretSeedHex, String expectedFrom,
                                                       String toAddress, BigInteger amountPlanck,
                                                       boolean keepAlive) {
            topUpTo = toAddress;
            topUpAmount = amountPlanck;
            return new SubmittedTransaction("topup-tx", 1, "FINALIZED", "{}");
        }

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

    private static final class FakeRepository extends ChainJdbcRepository {
        private String status;

        FakeRepository() {
            super(null);
        }

        @Override
        public Optional<String> systemValue(String configKey) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findCollectionTxHash(UUID tenantId, String chain, String collectionNo) {
            return Optional.empty();
        }

        @Override
        public int claimCollectionSigning(UUID tenantId, String chain,
                                          String collectionNo, String rawPayload) {
            return 1;
        }

        @Override
        public int updateCollectionStatus(UUID tenantId, String chain, String collectionNo,
                                          String status, String txHash,
                                          String errorMessage, String rawPayload) {
            this.status = status;
            return 1;
        }
    }

    private static final class FakeHotWalletAddressService extends HotWalletAddressService {
        private final ChainAddressRecord hot;

        FakeHotWalletAddressService(ChainAddressRecord hot) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.hot = hot;
        }

        @Override
        public Optional<ChainAddressRecord> findDefaultHotAddress(String chain, String assetSymbol) {
            return Optional.of(hot);
        }
    }
}
