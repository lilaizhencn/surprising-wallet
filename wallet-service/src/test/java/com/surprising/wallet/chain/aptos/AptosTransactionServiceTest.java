package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AptosTransactionRecord;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AptosTransactionServiceTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void sendsFungibleAssetThroughPrimaryStoreTransfer() {
        AptosKeyService keys = new AptosKeyService(MASTER_SEED);
        CapturingRpc rpc = new CapturingRpc();
        String metadata = keys.address(22);
        FakeRepository repository = new FakeRepository(token("APTOS_FA", metadata));
        AptosTransactionService service = new AptosTransactionService(
                rpc, new AptosTransactionSigner(keys), repository);
        ChainAddressRecord from = address(keys, 21);
        String recipient = keys.address(23);

        String hash = service.sendToken(from, token("APTOS_FA", metadata), recipient, 1_500_000L);

        assertEquals("0xfungible", hash);
        ObjectNode payload = (ObjectNode) rpc.submitted.path("payload");
        assertEquals("0x1::primary_fungible_store::transfer", payload.path("function").asText());
        assertEquals("0x1::fungible_asset::Metadata", payload.path("type_arguments").get(0).asText());
        assertEquals(metadata, payload.path("arguments").get(0).asText());
        assertEquals(recipient, payload.path("arguments").get(1).asText());
        assertEquals("1500000", payload.path("arguments").get(2).asText());
        assertEquals("0xfungible", repository.recorded.getTxHash());
        assertEquals("USDC", repository.recorded.getAssetSymbol());
        assertEquals(metadata, repository.recorded.getCoinType());
        assertEquals("1.500000", repository.recorded.getAmount().toPlainString());
        assertEquals(0L, repository.recorded.getGasUsed());
        assertEquals(100L, repository.recorded.getGasUnitPrice());
        assertEquals("SENT", repository.recorded.getStatus());
        assertEquals(7L, repository.recorded.getSequenceNumber());
    }

    @Test
    void recordsNativeSubmissionBeforeReturningHash() {
        AptosKeyService keys = new AptosKeyService(MASTER_SEED);
        CapturingRpc rpc = new CapturingRpc();
        FakeRepository repository = new FakeRepository();
        AptosTransactionService service = new AptosTransactionService(
                rpc, new AptosTransactionSigner(keys), repository);
        ChainAddressRecord from = address(keys, 24);
        String recipient = keys.address(25);

        assertEquals("0xfungible", service.sendNative(from, recipient, 20_000_000L));
        assertEquals("0xfungible", repository.recorded.getTxHash());
        assertEquals("APT", repository.recorded.getAssetSymbol());
        assertEquals(from.getAddress(), repository.recorded.getSender());
        assertEquals(recipient, repository.recorded.getReceiver());
        assertEquals("0.20000000", repository.recorded.getAmount().toPlainString());
        assertEquals(0L, repository.recorded.getGasUsed());
        assertEquals(100L, repository.recorded.getGasUnitPrice());
        assertEquals("SENT", repository.recorded.getStatus());
        assertEquals(7L, repository.recorded.getSequenceNumber());
    }

    @Test
    void rejectsUnknownAptosTokenStandards() {
        AptosKeyService keys = new AptosKeyService(MASTER_SEED);
        AptosTransactionService service = new AptosTransactionService(
                new CapturingRpc(), new AptosTransactionSigner(keys), new FakeRepository());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.sendToken(address(keys, 27), token("UNKNOWN", keys.address(28)),
                        keys.address(29), 1L));

        assertEquals("unsupported Aptos token standard: UNKNOWN", error.getMessage());
    }

    private static ChainAddressRecord address(AptosKeyService keys, long index) {
        return ChainAddressRecord.builder()
                .chain("APTOS")
                .assetSymbol("APT")
                .accountId(keys.address(index))
                .userId(0L)
                .biz(0)
                .addressIndex(index)
                .address(keys.address(index))
                .ownerAddress(keys.address(index))
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    private static TokenDefinition token(String standard, String contractAddress) {
        return TokenDefinition.builder()
                .chain("APTOS")
                .symbol("USDC")
                .standard(standard)
                .contractAddress(contractAddress)
                .decimals(6)
                .active(true)
                .build();
    }

    private static final class CapturingRpc extends AptosRpcClient {
        private ObjectNode submitted;

        private CapturingRpc() {
            super(new ObjectMapper(), "http://aptos.invalid/v1", "");
        }

        @Override
        public long sequenceNumber(String address) {
            return 7L;
        }

        @Override
        public long estimateGasPrice() {
            return 100L;
        }

        @Override
        public int chainId() {
            return 4;
        }

        @Override
        public String submitTransaction(ObjectNode signedTransaction) {
            submitted = signedTransaction;
            return "0xfungible";
        }
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final TokenDefinition token;
        private AptosTransactionRecord recorded;

        private FakeRepository() {
            this(null);
        }

        private FakeRepository(TokenDefinition token) {
            super(null);
            this.token = token;
        }

        @Override
        public long reserveAccountSequence(String chain, String address, long chainSequence) {
            return chainSequence;
        }

        @Override
        public Optional<TokenDefinition> findTokenByContract(String chain, String contractAddress) {
            return token != null && token.getContractAddress().equals(contractAddress)
                    ? Optional.of(token)
                    : Optional.empty();
        }

        @Override
        public int recordAptosTransaction(AptosTransactionRecord transaction) {
            recorded = transaction;
            return 1;
        }

        @Override
        public Optional<AccountChainProfile> findProfileByChain(String chain) {
            return Optional.of(AccountChainProfile.builder()
                    .chain("APTOS")
                    .network("testnet")
                    .family("aptos")
                    .nativeSymbol("APT")
                    .defaultFee(5_000_000L)
                    .enabled(true)
                    .build());
        }
    }
}
