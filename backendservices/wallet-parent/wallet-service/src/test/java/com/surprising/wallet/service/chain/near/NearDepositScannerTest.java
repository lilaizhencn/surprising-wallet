package com.surprising.wallet.service.chain.near;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.TokenDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NearDepositScannerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void extractsNativeTransfersFromChunkTransactions() throws Exception {
        JsonNode chunk = json("""
                {
                  "transactions": [
                    {
                      "hash": "near-tx-1",
                      "signer_id": "alice.testnet",
                      "receiver_id": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "actions": [
                        {"Transfer": {"deposit": "1000000000000000000000000"}},
                        {"FunctionCall": {"method_name": "noop"}}
                      ]
                    },
                    {
                      "hash": "near-tx-2",
                      "signer_id": "bob.testnet",
                      "receiver_id": "carol.testnet",
                      "actions": [
                        {"Transfer": {"deposit": "0"}}
                      ]
                    }
                  ]
                }
                """);

        List<NearDepositScanner.NativeTransfer> transfers =
                NearDepositScanner.nativeTransfers(chunk, 20, 24);

        assertEquals(1, transfers.size());
        NearDepositScanner.NativeTransfer transfer = transfers.getFirst();
        assertEquals("near-tx-1", transfer.txHash());
        assertEquals("alice.testnet", transfer.sender());
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                transfer.receiver());
        assertEquals(new BigDecimal("1"), transfer.amount());
        assertEquals(20L, transfer.blockHeight());
        assertEquals(5, transfer.confirmations());
        assertEquals(0, transfer.actionIndex());
    }

    @Test
    void extractsNep141TransfersFromFunctionCallActions() throws Exception {
        String args = Base64.getEncoder().encodeToString("""
                {"receiver_id":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","amount":"2500000"}
                """.getBytes(StandardCharsets.UTF_8));
        JsonNode chunk = json("""
                {
                  "transactions": [
                    {
                      "hash": "near-token-tx-1",
                      "signer_id": "alice.testnet",
                      "receiver_id": "usdc.fakes.testnet",
                      "actions": [
                        {"FunctionCall": {"method_name": "storage_deposit", "args": ""}},
                        {"FunctionCall": {"method_name": "ft_transfer", "args": "__ARGS__"}}
                      ]
                    }
                  ]
                }
                """.replace("__ARGS__", args));
        TokenDefinition token = TokenDefinition.builder()
                .chain("NEAR")
                .symbol("USDC")
                .contractAddress("usdc.fakes.testnet")
                .decimals(6)
                .active(true)
                .build();

        List<NearDepositScanner.TokenTransfer> transfers = NearDepositScanner.tokenTransfers(
                chunk, Map.of("usdc.fakes.testnet", token), 30, 32);

        assertEquals(1, transfers.size());
        NearDepositScanner.TokenTransfer transfer = transfers.getFirst();
        assertEquals("near-token-tx-1", transfer.txHash());
        assertEquals("alice.testnet", transfer.sender());
        assertEquals("USDC", transfer.symbol());
        assertEquals("usdc.fakes.testnet", transfer.contractId());
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                transfer.receiver());
        assertEquals(new BigDecimal("2.5"), transfer.amount());
        assertEquals(3, transfer.confirmations());
        assertEquals(1, transfer.actionIndex());
    }

    @Test
    void extractsNep141TransferCallDeposits() throws Exception {
        String args = Base64.getEncoder().encodeToString("""
                {"receiver_id":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","amount":"7100000","msg":"deposit"}
                """.getBytes(StandardCharsets.UTF_8));
        JsonNode chunk = json("""
                {
                  "transactions": [
                    {
                      "hash": "near-token-call-tx-1",
                      "signer_id": "alice.testnet",
                      "receiver_id": "usdc.fakes.testnet",
                      "actions": [
                        {"FunctionCall": {"method_name": "ft_transfer_call", "args": "__ARGS__"}}
                      ]
                    }
                  ]
                }
                """.replace("__ARGS__", args));
        TokenDefinition token = TokenDefinition.builder()
                .chain("NEAR")
                .symbol("USDC")
                .contractAddress("usdc.fakes.testnet")
                .decimals(6)
                .active(true)
                .build();

        List<NearDepositScanner.TokenTransfer> transfers = NearDepositScanner.tokenTransfers(
                chunk, Map.of("usdc.fakes.testnet", token), 30, 32);

        assertEquals(1, transfers.size());
        NearDepositScanner.TokenTransfer transfer = transfers.getFirst();
        assertEquals("near-token-call-tx-1", transfer.txHash());
        assertEquals(new BigDecimal("7.1"), transfer.amount());
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
