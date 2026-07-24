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

class TonMessageEncodingTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    private static final long JETTON_TRANSFER_NOTIFICATION = 0x7362d09cL;
    private static final long JETTON_INTERNAL_TRANSFER = 0x178d4519L;

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

    @Test
    void duplicatePendingBocIsAStillPendingConfirmation() {
        TonTransactionService service = new TonTransactionService(
                new DuplicateTonCenterClient(), new TonKeyService(MASTER_SEED), new PendingRepository());

        assertFalse(service.confirmSentMessage("message-hash", "sender"));
    }

    private static final class FakeTonCenterClient extends TonCenterClient {
        FakeTonCenterClient() {
            super(new ObjectMapper(), "http://127.0.0.1", "");
        }

        @Override
        public long seqno(String address) {
            return 12L;
        }
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private long next = 12L;

        FakeRepository() {
            super(null);
        }

        @Override
        public long reserveAccountSequence(String chain, String address, long chainSequence) {
            long reserved = Math.max(chainSequence, next);
            next = reserved + 1;
            return reserved;
        }

        @Override
        public Optional<com.surprising.wallet.common.chain.AccountChainProfile> findAccountChainProfile(
                String chain, String network) {
            return Optional.empty();
        }

        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            return Optional.of(AccountChainProfile.builder()
                    .chain(chain)
                    .network("testnet")
                    .build());
        }
    }

    private static final class DuplicateTonCenterClient extends TonCenterClient {
        private DuplicateTonCenterClient() {
            super(new ObjectMapper(), "http://127.0.0.1", "");
        }

        @Override
        public Optional<com.fasterxml.jackson.databind.JsonNode> findExternalMessageTransaction(
                String address, String messageHash, int limit) {
            return Optional.empty();
        }

        @Override
        public String sendBoc(byte[] boc) {
            throw new IllegalStateException("Duplicate msg_seqno 7");
        }
    }

    private static final class PendingRepository extends ChainJdbcRepository {
        private PendingRepository() {
            super(null);
        }

        @Override
        public Optional<String> findTonTransactionRawPayload(String chain, String txHash) {
            return Optional.of("AQ==");
        }
    }
}
