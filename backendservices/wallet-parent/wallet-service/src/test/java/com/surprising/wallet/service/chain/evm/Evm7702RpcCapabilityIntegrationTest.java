package com.surprising.wallet.service.chain.evm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only live RPC gate proving authorizationList is accepted by eth_estimateGas. */
class Evm7702RpcCapabilityIntegrationTest {

    @Test
    void shouldAcceptType4AuthorizationListDuringGasEstimation() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.7702.rpc-capability.enabled"),
                "enable only for an official EIP-7702 testnet RPC capability check");
        String rpcUrl = requiredProperty("evm.7702.rpc-url");
        BigInteger expectedChainId = new BigInteger(requiredProperty("evm.7702.chain-id"));
        HttpService http = new HttpService(rpcUrl);
        Web3j web3j = Web3j.build(http);
        try {
            BigInteger actualChainId = web3j.ethChainId().send().getChainId();
            assertEquals(expectedChainId, actualChainId);
            String fundedSender = web3j.ethGetBlockByNumber(
                    DefaultBlockParameterName.LATEST, false).send().getBlock().getMiner();
            assertTrue(fundedSender != null && fundedSender.matches("^0x[0-9a-fA-F]{40}$"));

            Credentials authority = Credentials.create(Keys.createEcKeyPair());
            Credentials delegate = Credentials.create(Keys.createEcKeyPair());
            BigInteger authorityNonce = web3j.ethGetTransactionCount(
                    authority.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            AuthorizationTuple authorization = new Evm7702AuthorizationService().authorize(
                    actualChainId, delegate.getAddress(), authorityNonce, authority);

            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("from", fundedSender);
            tx.put("to", authority.getAddress());
            tx.put("value", "0x0");
            tx.put("data", "0x");
            tx.put("type", "0x4");
            tx.put("maxPriorityFeePerGas", Numeric.encodeQuantity(BigInteger.ONE));
            tx.put("maxFeePerGas", Numeric.encodeQuantity(gasPrice.multiply(BigInteger.TWO)));
            tx.put("authorizationList", List.of(authorizationJson(authorization)));

            QuantityResponse response = new Request<>(
                    "eth_estimateGas", List.of(tx), http, QuantityResponse.class).send();
            assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
            BigInteger estimate = Numeric.decodeQuantity(response.getResult());
            assertTrue(estimate.compareTo(BigInteger.valueOf(21_000L)) > 0,
                    "type-4 estimate must include authorization intrinsic gas, actual=" + estimate);
        } finally {
            web3j.shutdown();
        }
    }

    private static Map<String, String> authorizationJson(AuthorizationTuple tuple) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("chainId", Numeric.encodeQuantity(tuple.getChainId()));
        result.put("address", tuple.getAddress());
        result.put("nonce", Numeric.encodeQuantity(tuple.getNonce()));
        result.put("yParity", Numeric.encodeQuantity(tuple.getYParity()));
        result.put("r", Numeric.encodeQuantity(tuple.getR()));
        result.put("s", Numeric.encodeQuantity(tuple.getS()));
        return result;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing -D" + name);
        return value;
    }

    public static class QuantityResponse extends Response<String> {
    }
}
