package com.surprising.wallet.chain.ton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code TonMessageEncodingTest} 覆盖的业务流程、边界条件和异常行为。
 */
class TonMessageEncodingTest {
    /**
     * 保存 {@code MASTER_SEED}，用于测试签名、认证或密钥相关逻辑。
     */
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    /**
     * 保存 {@code JETTON_TRANSFER_NOTIFICATION}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private static final long JETTON_TRANSFER_NOTIFICATION = 0x7362d09cL;
    /**
     * 保存 {@code JETTON_INTERNAL_TRANSFER}，记录测试开关、处理状态、确认结果或重试信息。
     */
    private static final long JETTON_INTERNAL_TRANSFER = 0x178d4519L;

    /**
     * 验证 {@code buildsNativeAndJettonWalletV4R2BocsWithoutBroadcasting} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void buildsNativeAndJettonWalletV4R2BocsWithoutBroadcasting() {
        TonKeyService keys = new TonKeyService(MASTER_SEED);
        FakeTonCenterClient rpc = new FakeTonCenterClient();
        FakeRepository repository = new FakeRepository();
        TonTransactionService service = new TonTransactionService(rpc, keys, repository);

        String user = keys.wallet(3).getAddress().toString(true, true, false, true);
        String destination = keys.wallet(4).getAddress().toString(true, true, false, true);

        TonTransactionService.PreparedTransfer nativeTransfer = service.prepareNative(
                3, destination, BigInteger.valueOf(1_000_000L), "withdraw");
        TonTransactionService.PreparedTransfer jettonTransfer = service.prepareJetton(
                3, user, destination, BigInteger.valueOf(5_000_000L), user, "jetton withdraw");

        assertEquals(12L, nativeTransfer.seqno());
        assertEquals(13L, jettonTransfer.seqno());
        assertTrue(nativeTransfer.boc().length > 0);
        assertTrue(jettonTransfer.boc().length > 0);
        assertEquals(64, nativeTransfer.messageHashHex().length());
        assertEquals(64, jettonTransfer.messageHashHex().length());
    }

    /**
     * 验证 {@code parsesTep74JettonTransferNotificationBody} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void parsesTep74JettonTransferNotificationBody() {
        TonKeyService keys = new TonKeyService(MASTER_SEED);
        String sender = keys.wallet(9).getAddress().toString(true, true, false, true);
        Cell body = CellBuilder.beginCell()
                .storeUint(JETTON_TRANSFER_NOTIFICATION, 32)
                .storeUint(99, 64)
                .storeCoins(BigInteger.valueOf(12_345_678L))
                .storeAddress(Address.of(sender))
                .storeBit(false)
                .endCell();

        TonDepositScanner scanner = new TonDepositScanner(null, null, null);
        TonDepositScanner.JettonNotification parsed = scanner.parseJettonNotification(body.toBase64(false));

        assertNotNull(parsed);
        assertEquals(BigInteger.valueOf(12_345_678L), parsed.amount());
        assertEquals(Address.of(sender).toRaw(), Address.of(parsed.sender()).toRaw());
    }

    /**
     * 验证 {@code parsesTep74JettonInternalTransferBodyWhenScanningJettonWallet} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void parsesTep74JettonInternalTransferBodyWhenScanningJettonWallet() {
        TonKeyService keys = new TonKeyService(MASTER_SEED);
        String sender = keys.wallet(10).getAddress().toString(true, true, false, true);
        String response = keys.wallet(11).getAddress().toString(true, true, false, true);
        Cell body = CellBuilder.beginCell()
                .storeUint(JETTON_INTERNAL_TRANSFER, 32)
                .storeUint(100, 64)
                .storeCoins(BigInteger.valueOf(22_000_000L))
                .storeAddress(Address.of(sender))
                .storeAddress(Address.of(response))
                .storeCoins(BigInteger.ONE)
                .storeRefMaybe(null)
                .endCell();

        TonDepositScanner scanner = new TonDepositScanner(null, null, null);
        TonDepositScanner.JettonNotification parsed = scanner.parseJettonDepositBody(body.toBase64(false));

        assertNotNull(parsed);
        assertEquals(BigInteger.valueOf(22_000_000L), parsed.amount());
        assertEquals(Address.of(sender).toRaw(), Address.of(parsed.sender()).toRaw());
    }

    /**
     * 验证 {@code duplicatePendingBocIsAStillPendingConfirmation} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void duplicatePendingBocIsAStillPendingConfirmation() {
        TonTransactionService service = new TonTransactionService(
                new DuplicateTonCenterClient(), new TonKeyService(MASTER_SEED), new PendingRepository());

        assertFalse(service.confirmSentMessage("message-hash", "sender"));
    }

    /**
     * 测试替身 {@code FakeTonCenterClient}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeTonCenterClient extends TonCenterClient {
        /**
         * 验证 {@code FakeTonCenterClient} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeTonCenterClient() {
            super(new ObjectMapper(), "http://127.0.0.1", "");
        }

        /**
         * 验证 {@code seqno} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public long seqno(String address) {
            return 12L;
        }
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends ChainJdbcRepository {
        /**
         * 保存 {@code next}，用于承载当前测试夹具的配置或运行数据。
         */
        private long next = 12L;

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        FakeRepository() {
            super(null);
        }

        /**
         * 验证 {@code reserveAccountSequence} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public long reserveAccountSequence(String chain, String address, long chainSequence) {
            long reserved = Math.max(chainSequence, next);
            next = reserved + 1;
            return reserved;
        }

        /**
         * 验证 {@code findAccountChainProfile} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<com.surprising.wallet.common.chain.AccountChainProfile> findAccountChainProfile(
                String chain, String network) {
            return Optional.empty();
        }

        /**
         * 验证 {@code findProfileByChain} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            return Optional.of(AccountChainProfile.builder()
                    .chain(chain)
                    .network("testnet")
                    .build());
        }
    }

    /**
     * 测试替身 {@code DuplicateTonCenterClient}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class DuplicateTonCenterClient extends TonCenterClient {
        /**
         * 验证 {@code DuplicateTonCenterClient} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private DuplicateTonCenterClient() {
            super(new ObjectMapper(), "http://127.0.0.1", "");
        }

        /**
         * 验证 {@code findExternalMessageTransaction} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<com.fasterxml.jackson.databind.JsonNode> findExternalMessageTransaction(
                String address, String messageHash, int limit) {
            return Optional.empty();
        }

        /**
         * 验证 {@code sendBoc} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public String sendBoc(byte[] boc) {
            throw new IllegalStateException("Duplicate msg_seqno 7");
        }
    }

    /**
     * 测试替身 {@code PendingRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class PendingRepository extends ChainJdbcRepository {
        /**
         * 验证 {@code PendingRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private PendingRepository() {
            super(null);
        }

        /**
         * 验证 {@code findTonTransactionRawPayload} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public Optional<String> findTonTransactionRawPayload(String chain, String txHash) {
            return Optional.of("AQ==");
        }
    }
}
