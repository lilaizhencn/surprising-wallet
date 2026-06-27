package com.surprising.wallet.service.chain.tron;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.NodeType;
import org.tron.trident.proto.Response.Account;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;

/**
 * Thin lifecycle wrapper around Trident ApiWrapper.
 * Production code should create one client per configured TRON network and close
 * it on shutdown to release gRPC channels.
 */
public class TronTridentClient implements AutoCloseable {
    private final ApiWrapper apiWrapper;
    private final ChainRpcNode fullNode;
    private final ChainRpcNode solidityNode;
    private final ChainRpcNodeService rpcNodeService;

    public TronTridentClient(String fullNode, String solidityNode, String apiKey) {
        this.apiWrapper = new ApiWrapper(fullNode, solidityNode, "", apiKey == null ? "" : apiKey);
        this.fullNode = ChainRpcNode.builder()
                .nodeLabel("tron-full-node")
                .rpcUrl(fullNode)
                .build();
        this.solidityNode = ChainRpcNode.builder()
                .nodeLabel("tron-solidity-node")
                .rpcUrl(solidityNode)
                .build();
        this.rpcNodeService = null;
    }

    public TronTridentClient(
            ChainRpcNode fullNode,
            ChainRpcNode solidityNode,
            ChainRpcNodeService rpcNodeService) {
        String apiKey = firstNonBlank(fullNode.getApiKey(), solidityNode.getApiKey());
        this.apiWrapper = new ApiWrapper(fullNode.getRpcUrl(), solidityNode.getRpcUrl(),
                "", apiKey == null ? "" : apiKey);
        this.fullNode = fullNode;
        this.solidityNode = solidityNode;
        this.rpcNodeService = rpcNodeService;
    }

    public ApiWrapper api() {
        return apiWrapper;
    }

    public Chain.Block getNowBlock() throws Exception {
        return call(fullNode, apiWrapper::getNowBlock);
    }

    public Response.BlockExtention getBlockByNumber(long height) throws Exception {
        return call(fullNode, () -> apiWrapper.getBlockByNum(height));
    }

    public Response.TransactionInfo getTransactionInfo(String txId) throws Exception {
        return call(solidityNode, () -> apiWrapper.getTransactionInfoById(txId, NodeType.SOLIDITY_NODE));
    }

    public Response.TransactionInfo getTransactionInfo(String txId, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionInfoById(txId, nodeType));
    }

    public Chain.Transaction getTransactionById(String txId, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionById(txId, nodeType));
    }

    public Response.TransactionInfoList getTransactionInfoByBlockNum(long blockHeight, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionInfoByBlockNum(blockHeight, nodeType));
    }

    public Account getAccount(String base58Address, NodeType nodeType) {
        return callUnchecked(node(nodeType), () -> apiWrapper.getAccount(base58Address, nodeType));
    }

    public long getBalanceSun(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountBalance(base58Address));
    }

    public Response.AccountResourceMessage getResources(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountResource(base58Address));
    }

    public Response.AccountNetMessage getBandwidth(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountNet(base58Address));
    }

    public String broadcast(Chain.Transaction signedTransaction) {
        return callUnchecked(fullNode, () -> apiWrapper.broadcastTransaction(signedTransaction));
    }

    @Override
    public void close() {
        apiWrapper.close();
    }

    private <T> T call(ChainRpcNode node, ChainRpcNodeService.ProviderLimitedRequest<T> request) throws Exception {
        if (rpcNodeService == null) {
            return request.execute();
        }
        return rpcNodeService.withProviderLimit(node, request);
    }

    private <T> T callUnchecked(ChainRpcNode node, ChainRpcNodeService.ProviderLimitedRequest<T> request) {
        try {
            return call(node, request);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("TRON RPC request failed", e);
        }
    }

    private ChainRpcNode node(NodeType nodeType) {
        return NodeType.SOLIDITY_NODE.equals(nodeType) ? solidityNode : fullNode;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
