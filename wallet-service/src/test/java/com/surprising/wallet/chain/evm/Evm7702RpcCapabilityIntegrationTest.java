package com.surprising.wallet.chain.evm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@code Evm7702RpcCapabilityIntegrationTest} 覆盖的业务流程、边界条件和异常行为。
 */
class Evm7702RpcCapabilityIntegrationTest {

    /**
     * 验证 {@code shouldAcceptType4AuthorizationListDuringGasEstimation} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
            String fundedSender = System.getProperty("evm.7702.funded-sender", "").trim();
            if (fundedSender.isEmpty()) {
                fundedSender = findFundedSender(web3j);
            }

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

    /**
     * 验证 {@code findFundedSender} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String findFundedSender(Web3j web3j) throws Exception {
        EthBlock.Block latest = web3j.ethGetBlockByNumber(
                DefaultBlockParameterName.LATEST, true).send().getBlock();
        BigInteger latestNumber = latest.getNumber();
        for (int offset = 0; offset < 20; offset++) {
            EthBlock.Block block = offset == 0
                    ? latest
                    : web3j.ethGetBlockByNumber(
                            DefaultBlockParameter.valueOf(latestNumber.subtract(BigInteger.valueOf(offset))),
                            true).send().getBlock();
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add(block.getMiner());
            for (EthBlock.TransactionResult<?> result : block.getTransactions()) {
                Object transaction = result.get();
                if (transaction instanceof EthBlock.TransactionObject tx) {
                    candidates.add(tx.getFrom());
                }
            }
            for (String candidate : candidates) {
                if (candidate != null
                        && candidate.matches("^0x[0-9a-fA-F]{40}$")
                        && web3j.ethGetBalance(candidate, DefaultBlockParameterName.LATEST)
                                .send().getBalance().signum() > 0) {
                    return candidate;
                }
            }
        }
        throw new AssertionError("no funded sender found in the latest 20 blocks");
    }

    /**
     * 验证 {@code authorizationJson} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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

    /**
     * 验证 {@code requiredProperty} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing -D" + name);
        return value;
    }

    /**
     * 测试辅助类 {@code QuantityResponse}，为相关测试提供隔离环境或共享数据。
     */
    public static class QuantityResponse extends Response<String> {
    }
}
