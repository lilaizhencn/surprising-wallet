package com.surprising.wallet.jobs.account;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live read-only gate proving Nitro receipts bake parent-chain data cost into gasUsed. */
class Evm7702ArbitrumFeeIntegrationTest {

    @Test
    void shouldSplitBakedInParentChainFeeFromRecentSequencerReceipt() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.7702.arbitrum-fee.enabled"),
                "enable only for an Arbitrum live receipt fee gate");
        String rpcUrl = requiredProperty("evm.7702.rpc-url");
        BigInteger expectedChainId = new BigInteger(requiredProperty("evm.7702.chain-id"));
        HttpService http = new HttpService(rpcUrl);
        Web3j web3j = Web3j.build(http);
        try {
            assertEquals(expectedChainId, web3j.ethChainId().send().getChainId());
            BigInteger height = web3j.ethBlockNumber().send().getBlockNumber();
            Evm7702CollectionWorkflowService.EvmTransactionReceipt receipt = null;
            for (int offset = 0; offset < 20 && receipt == null; offset++) {
                EthBlock block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(
                        height.subtract(BigInteger.valueOf(offset))), true).send();
                assertFalse(block.hasError(), block.hasError() ? block.getError().getMessage() : "");
                for (EthBlock.TransactionResult<?> result : block.getBlock().getTransactions()) {
                    org.web3j.protocol.core.methods.response.Transaction transaction =
                            (org.web3j.protocol.core.methods.response.Transaction) result.get();
                    Evm7702CollectionWorkflowService.EvmReceiptResponse response = new Request<>(
                            "eth_getTransactionReceipt", List.of(transaction.getHash()),
                            http, Evm7702CollectionWorkflowService.EvmReceiptResponse.class).send();
                    assertFalse(response.hasError(),
                            response.hasError() ? response.getError().getMessage() : "");
                    if (response.getResult() != null
                            && response.getResult().getGasUsedForL1() != null
                            && Numeric.decodeQuantity(
                                    response.getResult().getGasUsedForL1()).signum() > 0) {
                        receipt = response.getResult();
                        break;
                    }
                }
            }
            assertNotNull(receipt, "no recent Arbitrum sequencer receipt with gasUsedForL1 found");
            BigInteger gasUsedForL1 = Numeric.decodeQuantity(receipt.getGasUsedForL1());
            BigInteger gasUsed = receipt.getGasUsed();
            BigInteger gasPrice = Numeric.decodeQuantity(receipt.getEffectiveGasPrice());
            assertTrue(gasUsedForL1.signum() > 0);
            assertTrue(gasUsed.compareTo(gasUsedForL1) >= 0);
            BigInteger l2Fee = gasUsed.subtract(gasUsedForL1).multiply(gasPrice);
            BigInteger l1Fee = gasUsedForL1.multiply(gasPrice);
            assertEquals(gasUsed.multiply(gasPrice), l2Fee.add(l1Fee));
        } finally {
            web3j.shutdown();
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing -D" + name);
        return value;
    }
}
