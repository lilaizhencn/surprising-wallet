package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.annotation.RpcConfig;
import com.surprising.wallet.common.pojo.rpc.EthRawTransaction;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
@RpcConfig(server = "${atomex.of.server}")
public interface OfCommand extends EthLikeCommand {

    @JsonRpcMethod("ofbank_getBlock")
    JSONObject getBlock(Long blockNum);

    @JsonRpcMethod("ofbank_lastBN")
    @Override
    String getBlockNumber();

    @JsonRpcMethod("ofbank_show")
    @Override
    String getBalance(String address, String blockNum);

    @Override
    @JsonRpcMethod("ofbank_checkTrans")
    EthRawTransaction getTransaction(String txId);

    @Override
    @JsonRpcMethod("eth_gasPrice")
    String getGasPrice();

    @JsonRpcMethod("eth_call")
    String getTokenBalance(JSONObject param, String latest);
}
