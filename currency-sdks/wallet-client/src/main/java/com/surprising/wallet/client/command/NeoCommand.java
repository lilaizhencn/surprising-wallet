package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.annotation.RpcConfig;

import java.util.List;

/**
 * @author lilaizhen
 * @data 2018/7/13
 */

@RpcConfig(server = "${atomex.neo.server}")
public interface NeoCommand {
    /**
     * 获取区块链当前高度
     *
     * @return
     */
    @JsonRpcMethod(value = "getblockcount", required = true)
    long getBlockCount(String latest);


    /**
     * 根据hash获取区块详情
     *
     * @param height
     * @return
     */
    @JsonRpcMethod(value = "getblock", required = true)
    JSONObject getBlock(long height, int verbose);


    @JsonRpcMethod(value = "getrawtransaction", required = true)
    JSONObject getRawTransaction(String txid, int verbose);

    /**
     * 发送原始交易
     *
     * @param hex
     * @return
     */
    @JsonRpcMethod(value = "sendrawtransaction", required = true)
    boolean sendRawTransaction(String hex);

    @JsonRpcMethod(value = "invokefunction", required = true)
    JSONObject invoke(String contract, String method, List params);


    @JsonRpcMethod(value = "getapplicationlog", required = true)
    JSONObject getApplicationLog(String txId);

}
