package com.surprising.wallet.devfaucet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.surprising.wallet.devfaucet.model.DevFaucetFunding;
import com.surprising.wallet.devfaucet.model.DevFaucetProperties;

@Component
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
public final class JsonRpcDevFaucetClient implements DevFaucetRpcClient {
    private static final String ERC20_TRANSFER_SELECTOR = "a9059cbb";    private final DevFaucetProperties properties;    private final ObjectMapper objectMapper;    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();
    public JsonRpcDevFaucetClient(DevFaucetProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String send(DevFaucetFunding funding) {
        return switch (funding.chain().toUpperCase(Locale.ROOT)) {
            case "BTC" -> sendBitcoin(funding);
            case "ETH" -> sendEvm(funding);
            default -> throw new RejectedException("unsupported dev faucet chain " + funding.chain());
        };
    }
    private String sendBitcoin(DevFaucetFunding funding) {
        DevFaucetProperties.Bitcoin bitcoin = properties.getBitcoin();
        URI walletUri = URI.create(trimSlash(bitcoin.getRpcUrl()) + "/wallet/"
                + URLEncoder.encode(bitcoin.getWallet(), StandardCharsets.UTF_8));
        JsonNode sent = call(walletUri, bitcoinAuth(), "sendtoaddress",
                List.of(funding.address(), funding.requestedAmount()));
        String txHash = requiredText(sent, "bitcoin sendtoaddress");
        try {
            String miningAddress = requiredText(call(walletUri, bitcoinAuth(),
                    "getnewaddress", List.of()), "bitcoin getnewaddress");
            call(URI.create(bitcoin.getRpcUrl()), bitcoinAuth(), "generatetoaddress",
                    List.of(bitcoin.getConfirmationBlocks(), miningAddress));
            return txHash;
        } catch (RuntimeException error) {
            throw new AmbiguousException(
                    "bitcoin transaction was sent but confirmation block mining failed", error);
        }
    }
    private String sendEvm(DevFaucetFunding funding) {
        DevFaucetProperties.Evm evm = properties.getEvm();
        String from = evm.getFromAddress().toLowerCase(Locale.ROOT);
        JsonNode accounts = call(URI.create(evm.getRpcUrl()), null, "eth_accounts", List.of());
        boolean unlocked = accounts.isArray() && accounts.valueStream()
                .map(JsonNode::asText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(from::equals);
        if (!unlocked) {
            throw new RejectedException("configured EVM faucet account is not unlocked by Hardhat");
        }

        Map<String, String> transaction;
        if (funding.contractAddress() == null) {
            BigInteger wei = funding.requestedAmount().movePointRight(funding.decimals())
                    .toBigIntegerExact();
            transaction = Map.of(
                    "from", evm.getFromAddress(),
                    "to", funding.address(),
                    "value", hexQuantity(wei));
        } else {
            BigInteger atomic = funding.requestedAmount().movePointRight(funding.decimals())
                    .toBigIntegerExact();
            transaction = Map.of(
                    "from", evm.getFromAddress(),
                    "to", funding.contractAddress(),
                    "data", encodeTransfer(funding.address(), atomic));
        }
        return requiredText(call(URI.create(evm.getRpcUrl()), null,
                "eth_sendTransaction", List.of(transaction)), "eth_sendTransaction");
    }
    private JsonNode call(URI uri, String authorization, String method, List<?> params) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", requestIds.incrementAndGet(),
                    "method", method,
                    "params", params));
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(properties.getRequestTimeout())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (authorization != null) {
                request.header("authorization", authorization);
            }
            HttpResponse<String> response = httpClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AmbiguousException(
                        "dev faucet RPC returned HTTP " + response.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                throw new RejectedException("dev faucet RPC rejected " + method + ": " + error);
            }
            JsonNode result = root.get("result");
            if (result == null || result.isNull()) {
                throw new AmbiguousException("dev faucet RPC omitted result for " + method, null);
            }
            return result;
        } catch (DevFaucetRpcClient.RejectedException | DevFaucetRpcClient.AmbiguousException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AmbiguousException("dev faucet RPC was interrupted", error);
        } catch (IOException error) {
            if (error instanceof ConnectException) {
                throw new RejectedException("dev faucet RPC is not reachable", error);
            }
            throw new AmbiguousException("dev faucet RPC outcome is unknown", error);
        }
    }
    private String bitcoinAuth() {
        DevFaucetProperties.Bitcoin bitcoin = properties.getBitcoin();
        String raw = bitcoin.getRpcUsername() + ":" + bitcoin.getRpcPassword();
        return "Basic " + Base64.getEncoder().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
    }
    public static String encodeTransfer(String address, BigInteger atomicAmount) {
        if (address == null || !address.matches("(?i)^0x[0-9a-f]{40}$")) {
            throw new RejectedException("invalid EVM deposit address");
        }
        if (atomicAmount.signum() <= 0) {
            throw new RejectedException("token amount must be positive");
        }
        return "0x" + ERC20_TRANSFER_SELECTOR
                + leftPad(address.substring(2).toLowerCase(Locale.ROOT), 64)
                + leftPad(atomicAmount.toString(16), 64);
    }
    private static String hexQuantity(BigInteger value) {
        if (value.signum() < 0) {
            throw new RejectedException("EVM value must not be negative");
        }
        return "0x" + value.toString(16);
    }
    private static String leftPad(String value, int length) {
        if (value.length() > length) {
            throw new RejectedException("EVM ABI value exceeds 32 bytes");
        }
        return "0".repeat(length - value.length()) + value;
    }
    private static String requiredText(JsonNode node, String operation) {
        String value = node == null ? "" : node.asText("").trim();
        if (value.isEmpty()) {
            throw new AmbiguousException(operation + " returned an empty result", null);
        }
        return value;
    }
    private static String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
