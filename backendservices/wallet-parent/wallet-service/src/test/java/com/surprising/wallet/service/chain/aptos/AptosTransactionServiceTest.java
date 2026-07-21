package com.surprising.wallet.service.chain.aptos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
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
        AptosTransactionService service = new AptosTransactionService(
                rpc, new AptosTransactionSigner(keys), new FakeRepository());
        ChainAddressRecord from = address(keys, 21);
        String metadata = keys.address(22);
        String recipient = keys.address(23);

        String hash = service.sendToken(from, token("APTOS_FA", metadata), recipient, 1_500_000L);

        assertEquals("0xfungible", hash);
        ObjectNode payload = (ObjectNode) rpc.submitted.path("payload");
        assertEquals("0x1::primary_fungible_store::transfer", payload.path("function").asText());
        assertEquals("0x1::fungible_asset::Metadata", payload.path("type_arguments").get(0).asText());
        assertEquals(metadata, payload.path("arguments").get(0).asText());
        assertEquals(recipient, payload.path("arguments").get(1).asText());
        assertEquals("1500000", payload.path("arguments").get(2).asText());
    }

    @Test
    void keepsConfiguredCoinTokensOnCoinTransfer() {
        AptosKeyService keys = new AptosKeyService(MASTER_SEED);
        CapturingRpc rpc = new CapturingRpc();
        AptosTransactionService service = new AptosTransactionService(
                rpc, new AptosTransactionSigner(keys), new FakeRepository());
        ChainAddressRecord from = address(keys, 24);
        String coinType = keys.address(25) + "::mock_coin::MockCoin";

        service.sendToken(from, token("APTOS_COIN", coinType), keys.address(26), 99L);

        ObjectNode payload = (ObjectNode) rpc.submitted.path("payload");
        assertEquals("0x1::aptos_account::transfer_coins", payload.path("function").asText());
        assertEquals(coinType, payload.path("type_arguments").get(0).asText());
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
        private FakeRepository() {
            super(null);
        }

        @Override
        public long reserveAccountSequence(String chain, String address, long chainSequence) {
            return chainSequence;
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
