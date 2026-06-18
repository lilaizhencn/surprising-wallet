package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.annotation.RpcConfig;
import com.surprising.wallet.common.pojo.rpc.OntBalance;

/**
 * @author lilaizhen
 */
@RpcConfig(server = "${atomex.ont.server}")
public interface OntCommand {

    @JsonRpcMethod("getblock")
    JSONObject getBlock(Long blockNum, int verbose);

    @JsonRpcMethod("getsmartcodeevent")
    JSONArray getSmartcodeevent(Long blockNum, int verbose);

    @JsonRpcMethod("getblockcount")
    Integer getBlockCount(String latest);

    @JsonRpcMethod("getbalance")
    OntBalance getBalance(String address);

    @JsonRpcMethod("getgasprice")
    String getGasPrice();

    @JsonRpcMethod("sendrawtransaction")
    String sendRawTransaction(String params, int PreExec);

    @JsonRpcMethod("getblockheightbytxhash")
    int getblockheightbytxhash(String txId);

    @JsonRpcMethod("getunboundong")
    String getUnboundong(String address);

}
