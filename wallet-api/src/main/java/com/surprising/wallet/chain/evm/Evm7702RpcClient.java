package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** RPC encoding and reads shared by EIP-7702 collection and payout workflows. */
@Component
@RequiredArgsConstructor
public class Evm7702RpcClient {
    private final ChainRpcNodeService nodes;
    private final EvmHttpServiceFactory httpServices;

    public HttpService http(AccountChainProfile profile) {
        List<ChainRpcNode> configured = nodes.enabledNodes(profile.getChain(), profile.getNetwork(), "rpc");
        if (configured.isEmpty()) {
            throw new IllegalStateException("no enabled RPC node for " + profile.getChain() + "/" + profile.getNetwork());
        }
        ChainRpcNode node = configured.getFirst();
        return httpServices.create(node.getRpcUrl(), nodes.authHeaders(node));
    }

    public Optional<EvmTransactionReceipt> transactionReceipt(HttpService http, String txHash) throws Exception {
        EvmTransactionReceipt.RpcResponse response = new Request<>(
                "eth_getTransactionReceipt", List.of(txHash), http, EvmTransactionReceipt.RpcResponse.class).send();
        if (response.hasError()) {
            throw new IllegalStateException("eth_getTransactionReceipt failed: " + response.getError().getMessage());
        }
        return Optional.ofNullable(response.getResult());
    }

    public boolean isTransactionKnown(Web3j web3j, String txHash) throws Exception {
        if (web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt().isPresent()) return true;
        return web3j.ethGetTransactionByHash(txHash).send().getTransaction().isPresent();
    }

    public BigInteger estimateGas(
            HttpService http, String from, String to, String data, BigInteger priority,
            BigInteger maxFee, List<AuthorizationTuple> authorizations) throws Exception {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("from", from);
        tx.put("to", to);
        tx.put("value", "0x0");
        tx.put("data", data);
        tx.put("maxPriorityFeePerGas", Numeric.encodeQuantity(priority));
        tx.put("maxFeePerGas", Numeric.encodeQuantity(maxFee));
        if (authorizations.isEmpty()) {
            tx.put("type", "0x2");
        } else {
            tx.put("type", "0x4");
            tx.put("authorizationList", authorizations.stream().map(this::authorizationJson).toList());
        }
        QuantityResponse response = new Request<>(
                "eth_estimateGas", List.of(tx), http, QuantityResponse.class).send();
        if (response.hasError()) {
            throw new IllegalStateException("eth_estimateGas failed: " + response.getError().getMessage());
        }
        return Numeric.decodeQuantity(response.getResult());
    }

    private Map<String, String> authorizationJson(AuthorizationTuple tuple) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("chainId", Numeric.encodeQuantity(tuple.getChainId()));
        result.put("address", tuple.getAddress());
        result.put("nonce", Numeric.encodeQuantity(tuple.getNonce()));
        result.put("yParity", Numeric.encodeQuantity(tuple.getYParity()));
        result.put("r", Numeric.encodeQuantity(tuple.getR()));
        result.put("s", Numeric.encodeQuantity(tuple.getS()));
        return result;
    }

    public BigInteger tokenBalance(Web3j web3j, String token, String owner) throws Exception {
        Function function = new Function("balanceOf", List.of(new Address(owner)),
                List.of(new TypeReference<Uint256>() { }));
        return uint256(web3j, owner, token, function, "token balanceOf");
    }

    public BigInteger operationNonce(Web3j web3j, String authority) throws Exception {
        Function function = new Function("operationNonce", List.of(), List.of(new TypeReference<Uint256>() { }));
        return uint256(web3j, authority, authority, function, "operationNonce");
    }

    private BigInteger uint256(Web3j web3j, String from, String target, Function function, String operation) throws Exception {
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(from, target, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        if (call.hasError()) throw new IllegalStateException(operation + " failed: " + call.getError().getMessage());
        List<Type> values = FunctionReturnDecoder.decode(call.getValue(), function.getOutputParameters());
        if (values.size() != 1) throw new IllegalStateException(operation + " returned malformed data");
        return (BigInteger) values.getFirst().getValue();
    }

    public void requireCodeHash(Web3j web3j, String address, String expected, String label) throws Exception {
        String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
        if (code == null || "0x".equalsIgnoreCase(code)) throw new IllegalStateException(label + " runtime code is missing");
        String actual = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(code)));
        if (!actual.equalsIgnoreCase(expected)) throw new IllegalStateException(label + " runtime code hash mismatch");
    }

    /** Deadlines use the same chain clock during preparation and validation. */
    public static Instant chainBlockTimestamp(EthBlock.Block block) {
        if (block == null || block.getTimestamp() == null) {
            throw new IllegalStateException("latest EVM block timestamp is unavailable");
        }
        return Instant.ofEpochSecond(block.getTimestamp().longValueExact());
    }

    public static String delegationCode(String delegate) {
        return "0xef0100" + Numeric.cleanHexPrefix(delegate).toLowerCase();
    }

    public static <T> T send(CheckedSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception error) {
            throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static class QuantityResponse extends Response<String> { }
}
