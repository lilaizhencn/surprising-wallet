package com.surprising.wallet.client.command;

import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.pojo.rpc.EthGasUsedDto;
import com.surprising.wallet.common.pojo.rpc.EthLikeBlock;
import com.surprising.wallet.common.pojo.rpc.EthRawTransaction;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
public interface EthLikeCommand extends IRpcCommand {
    @JsonRpcMethod(value = "eth_getBalance")
    String getBalance(String addStr, String str);

    @JsonRpcMethod(value = "eth_blockNumber")
    String getBlockNumber();

    @JsonRpcMethod("eth_getBlockByNumber")
    EthLikeBlock getBlockByHeight(String str, boolean flag);

    @JsonRpcMethod("eth_getBlockByHash")
    EthLikeBlock getBlockByHash(String str, boolean flag);

    @JsonRpcMethod("eth_sendRawTransaction")
    String sendRawTransaction(String params);

    @JsonRpcMethod("eth_gasPrice")
    String getGasPrice();

    @JsonRpcMethod("eth_getTransactionByHash")
    EthRawTransaction getTransaction(String txId);

    @JsonRpcMethod("eth_getTransactionReceipt")
    EthGasUsedDto getGasUsed(String txId);

    @JsonRpcMethod("eth_getTransactionCount")
    String getAddressNonce(String address, String latest);
}
