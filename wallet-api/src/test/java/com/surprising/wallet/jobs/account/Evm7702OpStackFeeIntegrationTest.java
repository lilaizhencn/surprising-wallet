package com.surprising.wallet.jobs.account;

import com.surprising.wallet.service.chain.evm.Evm7702BatchTransactionService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live read-only gate proving OP Stack receipts expose the L1 fee used for settlement. */
class Evm7702OpStackFeeIntegrationTest {
    private static final String GAS_PRICE_ORACLE =
            "0x420000000000000000000000000000000000000F";

    @Test
    void shouldReadExactL1FeeFromRecentStandardTransactionReceipt() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.7702.op-stack-fee.enabled"),
                "enable only for an OP Stack live receipt fee gate");
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
                    if ("0x7e".equalsIgnoreCase(transaction.getType())) continue;
                    Evm7702CollectionWorkflowService.EvmReceiptResponse response = new Request<>(
                            "eth_getTransactionReceipt", List.of(transaction.getHash()),
                            http, Evm7702CollectionWorkflowService.EvmReceiptResponse.class).send();
                    assertFalse(response.hasError(),
                            response.hasError() ? response.getError().getMessage() : "");
                    receipt = response.getResult();
                    if (receipt != null) break;
                }
            }
            assertNotNull(receipt, "no recent standard OP Stack transaction receipt found");
            assertNotNull(receipt.getL1Fee(), "OP Stack receipt must expose l1Fee");
            BigInteger l1Fee = Numeric.decodeQuantity(receipt.getL1Fee());
            assertTrue(l1Fee.signum() >= 0);
            assertTrue(receipt.getGasUsed().signum() > 0);
            assertTrue(Numeric.decodeQuantity(receipt.getEffectiveGasPrice()).signum() > 0);
            if (receipt.getOperatorFeeScalar() != null) {
                assertTrue(Numeric.decodeQuantity(receipt.getOperatorFeeScalar()).signum() >= 0);
            }
            if (receipt.getOperatorFeeConstant() != null) {
                assertTrue(Numeric.decodeQuantity(receipt.getOperatorFeeConstant()).signum() >= 0);
            }

            Credentials signer = Credentials.create("11".repeat(32));
            var signed = new Evm7702BatchTransactionService().signBatch(
                    expectedChainId.longValueExact(), BigInteger.ZERO,
                    BigInteger.valueOf(1_000_000L), BigInteger.valueOf(1_000_000_000L),
                    BigInteger.valueOf(100_000L), signer.getAddress(), "0x00", List.of(), signer);
            BigInteger estimatedL1Fee = oracleUint256(web3j, signer.getAddress(), new Function(
                    "getL1Fee",
                    List.of(new DynamicBytes(Numeric.hexStringToByteArray(signed.rawTransaction()))),
                    List.of(new TypeReference<Uint256>() { })));
            BigInteger estimatedOperatorFee = oracleUint256(web3j, signer.getAddress(), new Function(
                    "getOperatorFee", List.of(new Uint256(BigInteger.valueOf(100_000L))),
                    List.of(new TypeReference<Uint256>() { })));
            assertTrue(estimatedL1Fee.signum() > 0);
            assertTrue(estimatedOperatorFee.signum() >= 0);
        } finally {
            web3j.shutdown();
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing -D" + name);
        return value;
    }

    private static BigInteger oracleUint256(
            Web3j web3j, String from, Function function) throws Exception {
        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                        from, GAS_PRICE_ORACLE, FunctionEncoder.encode(function)),
                org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send();
        assertFalse(response.hasError(),
                response.hasError() ? response.getError().getMessage() : "");
        List<Type> values = FunctionReturnDecoder.decode(
                response.getValue(), function.getOutputParameters());
        assertEquals(1, values.size());
        return (BigInteger) values.getFirst().getValue();
    }
}
