package com.surprising.wallet.service.chain.tron;

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

    public TronTridentClient(String fullNode, String solidityNode, String apiKey) {
        this.apiWrapper = new ApiWrapper(fullNode, solidityNode, apiKey == null ? "" : apiKey);
    }

    public ApiWrapper api() {
        return apiWrapper;
    }

    public Chain.Block getNowBlock() throws Exception {
        return apiWrapper.getNowBlock();
    }

    public Response.BlockExtention getBlockByNumber(long height) throws Exception {
        return apiWrapper.getBlockByNum(height);
    }

    public Response.TransactionInfo getTransactionInfo(String txId) throws Exception {
        return apiWrapper.getTransactionInfoById(txId, NodeType.SOLIDITY_NODE);
    }

    public Response.TransactionInfo getTransactionInfo(String txId, NodeType nodeType) throws Exception {
        return apiWrapper.getTransactionInfoById(txId, nodeType);
    }

    public Chain.Transaction getTransactionById(String txId, NodeType nodeType) throws Exception {
        return apiWrapper.getTransactionById(txId, nodeType);
    }

    public Response.TransactionInfoList getTransactionInfoByBlockNum(long blockHeight, NodeType nodeType) throws Exception {
        return apiWrapper.getTransactionInfoByBlockNum(blockHeight, nodeType);
    }

    public Account getAccount(String base58Address, NodeType nodeType) {
        return apiWrapper.getAccount(base58Address, nodeType);
    }

    public long getBalanceSun(String base58Address) {
        return apiWrapper.getAccountBalance(base58Address);
    }

    public Response.AccountResourceMessage getResources(String base58Address) {
        return apiWrapper.getAccountResource(base58Address);
    }

    public Response.AccountNetMessage getBandwidth(String base58Address) {
        return apiWrapper.getAccountNet(base58Address);
    }

    public String broadcast(Chain.Transaction signedTransaction) {
        return apiWrapper.broadcastTransaction(signedTransaction);
    }

    @Override
    public void close() {
        apiWrapper.close();
    }
}
